param(
    [switch] $SkipBuild,
    [switch] $RunBlockedEgress,
    [string] $Output
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalReleaseRunnerSupport.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gradle = Join-Path $root 'gradlew.bat'
$started = (Get-Date).ToUniversalTime()
$checks = [Collections.Generic.List[object]]::new()
$script:hostGateFailed = $false
$initialSourceState = Get-RepositorySourceState -Root $root

function Invoke-HostCheck {
    param(
        [string] $Name,
        [scriptblock] $Body
    )
    Write-Host "[tracebox-host] START $Name"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        & $Body | Out-Host
        $timer.Stop()
        $checks.Add([pscustomobject]@{
            name = $Name
            result = 'PASS'
            duration_ms = $timer.ElapsedMilliseconds
        })
        Write-Host "[tracebox-host] PASS  $Name ($($timer.ElapsedMilliseconds) ms)"
    } catch {
        $timer.Stop()
        $script:hostGateFailed = $true
        $checks.Add([pscustomobject]@{
            name = $Name
            result = 'FAIL'
            duration_ms = $timer.ElapsedMilliseconds
            detail = $_.Exception.Message
        })
        Write-Host "[tracebox-host] FAIL  $Name`: $($_.Exception.Message)"
    }
}

function Assert-LastExitCode {
    param([string] $Label)
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Get-PinnedCMakeTool {
    param([string] $Name)
    $executableName = if ($env:OS -eq 'Windows_NT') { "$Name.exe" } else { $Name }
    $candidate = Join-Path $env:ANDROID_HOME "cmake\4.1.2\bin\$executableName"
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Pinned CMake tool is unavailable: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Get-WindowsVcVars64 {
    $programFilesX86 = [Environment]::GetFolderPath('ProgramFilesX86')
    $vswhere = Join-Path $programFilesX86 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
        throw "Visual Studio discovery tool is unavailable: $vswhere"
    }
    $installations = & $vswhere `
        -latest `
        -products * `
        -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
        -property installationPath
    $vswhereExitCode = $LASTEXITCODE
    if ($vswhereExitCode -ne 0) {
        throw "Visual Studio toolchain discovery failed with exit code $vswhereExitCode"
    }
    $installation = $installations | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($installation)) {
        throw 'No Visual Studio x64 C/C++ toolchain installation was found'
    }
    $vcvars = Join-Path $installation.Trim() 'VC\Auxiliary\Build\vcvars64.bat'
    if (-not (Test-Path -LiteralPath $vcvars -PathType Leaf)) {
        throw "Visual Studio x64 environment script is unavailable: $vcvars"
    }
    return (Resolve-Path -LiteralPath $vcvars).Path
}

Invoke-HostCheck 'foundation_contracts' {
    & (Join-Path $PSScriptRoot 'Verify-FoundationSpecs.ps1')
}
Invoke-HostCheck 'pinned_toolchains_and_locks' {
    & (Join-Path $PSScriptRoot 'Verify-Toolchains.ps1')
}
Invoke-HostCheck 'generated_artifact_drift' {
    & (Join-Path $PSScriptRoot 'Verify-GeneratedArtifacts.ps1')
}
Invoke-HostCheck 'personal_release_lab_topology' {
    & (Join-Path $PSScriptRoot 'Verify-PersonalReleaseLab.ps1')
}
Invoke-HostCheck 'malicious_corpora' {
    & (Join-Path $PSScriptRoot 'Verify-MaliciousCorpora.ps1')
}

Push-Location $root
try {
    Invoke-HostCheck 'schema_compiler_and_goldens' {
        & python -m unittest discover `
            -s tooling/schema-compiler/tests `
            -p 'test_*.py'
        Assert-LastExitCode 'Schema compiler unit tests'
    }
    Invoke-HostCheck 'gradle_plugin_contracts' {
        & $gradle `
            :test-apps:phase0-fixture:minifyNoInternetReleaseWithR8 `
            --offline `
            --no-daemon `
            --dependency-verification strict
        Assert-LastExitCode 'Real no-INTERNET R8 mapping prerequisite'
        & $gradle `
            -p tooling/tracebox-gradle-plugin `
            test `
            identityCaptureTest `
            --offline `
            --no-daemon
        Assert-LastExitCode 'Gradle plugin tests'
    }
    Invoke-HostCheck 'rust_format' {
        & cargo fmt --all -- --check
        Assert-LastExitCode 'cargo fmt'
    }
    Invoke-HostCheck 'rust_strict_clippy' {
        & cargo clippy --workspace --all-targets --locked --offline -- -D warnings
        Assert-LastExitCode 'cargo clippy'
    }
    Invoke-HostCheck 'rust_workspace_tests' {
        & cargo test --workspace --locked --offline
        Assert-LastExitCode 'Rust workspace tests'
    }
    Invoke-HostCheck 'native_host_build_and_ctest' {
        $cmake = Get-PinnedCMakeTool 'cmake'
        $ctest = Get-PinnedCMakeTool 'ctest'
        $ninja = Get-PinnedCMakeTool 'ninja'
        $nativeBuild = Join-Path $root 'native\build-personal-host-ninja'
        $nativeSource = Join-Path $root 'native'
        if ($env:OS -eq 'Windows_NT') {
            $vcvars = Get-WindowsVcVars64
            $nativeCommands = @(
                "call `"$vcvars`" >nul",
                "`"$cmake`" --fresh -S `"$nativeSource`" -B `"$nativeBuild`" -G Ninja -DCMAKE_MAKE_PROGRAM=`"$ninja`" -DCMAKE_BUILD_TYPE=Release",
                "`"$cmake`" --build `"$nativeBuild`" --config Release",
                "`"$ctest`" --test-dir `"$nativeBuild`" -C Release --output-on-failure"
            ) -join ' && '
            & $env:ComSpec /d /s /c $nativeCommands
            Assert-LastExitCode 'Native CMake build and CTest'
        } else {
            & $cmake --fresh `
                -S $nativeSource `
                -B $nativeBuild `
                -G Ninja `
                "-DCMAKE_MAKE_PROGRAM=$ninja" `
                -DCMAKE_BUILD_TYPE=Release
            Assert-LastExitCode 'Native CMake configure'
            & $cmake --build $nativeBuild --config Release
            Assert-LastExitCode 'Native CMake build'
            & $ctest --test-dir $nativeBuild -C Release --output-on-failure
            Assert-LastExitCode 'Native CTest'
        }
    }
    Invoke-HostCheck 'android_jvm_fixture_and_release_conformance' {
        $tasks = @(
            'phase0Check',
            'phase1Check',
            'phase2Check',
            'phase4CoreCheck',
            ':android:tracebox-export-ui:testDebugUnitTest',
            ':android:tracebox:testDebugUnitTest',
            ':test-apps:phase0-fixture:testNoInternetDebugUnitTest',
            ':test-apps:phase0-fixture:testHostNetworkDebugUnitTest',
            ':test-apps:phase0-fixture:verifyFixtureRustPanicProbeIsolation',
            ':test-apps:phase0-fixture:verifyTraceboxReleaseConformanceNoInternetQualificationRelease'
        )
        & $gradle @tasks `
            --offline `
            --no-daemon `
            --dependency-verification strict
        Assert-LastExitCode 'Android JVM/fixture/release-conformance checks'
    }
    Invoke-HostCheck 'android_release_lint' {
        $lintTasks = @(
            ':android:tracebox-anr-exit:lintRelease',
            ':android:tracebox-native:lintRelease',
            ':android:tracebox-api:lintRelease',
            ':android:tracebox-core:lintRelease',
            ':android:tracebox-storage:lintRelease',
            ':android:tracebox-export:lintRelease',
            ':android:tracebox-export-ui:lintRelease',
            ':android:tracebox-directboot:lintRelease',
            ':android:tracebox:lintRelease',
            ':test-apps:phase0-fixture:lintNoInternetRelease',
            ':test-apps:phase0-fixture:lintHostNetworkRelease',
            ':benchmarks:phase0-benchmark:lintRelease'
        )
        & $gradle @lintTasks `
            --offline `
            --no-daemon `
            --dependency-verification strict
        Assert-LastExitCode 'Android release lint'
    }
} finally {
    Pop-Location
}

Invoke-HostCheck 'release_artifacts_and_static_no_network' {
    & (Join-Path $PSScriptRoot 'Verify-Phase5NoNetworkStatic.ps1') -SkipBuild:$SkipBuild
}

if ($RunBlockedEgress) {
    Invoke-HostCheck 'tbdiag_blocked_egress' {
        & (Join-Path $PSScriptRoot 'Invoke-TbdiagBlockedEgress.ps1') `
            -AllowFirewallMutation:($env:OS -eq 'Windows_NT')
    }
}

$ended = (Get-Date).ToUniversalTime()
$head = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    $script:hostGateFailed = $true
    $head = 'UNAVAILABLE'
}
$currentSourceState = try {
    Get-RepositorySourceState -Root $root
} catch {
    $script:hostGateFailed = $true
    [pscustomobject]@{
        sha256 = "UNAVAILABLE: $($_.Exception.Message)"
        entries = @()
    }
}
$sourceStateStable =
    $initialSourceState.sha256 -eq $currentSourceState.sha256
if (-not $sourceStateStable) {
    $script:hostGateFailed = $true
}
$passed = -not $script:hostGateFailed
$report = [ordered]@{
    schema = 'tracebox-personal-release-host-readiness-v2'
    scope = 'All host-runnable implementation, unit, native, parser, lint, fixture, release-conformance, and static no-network gates for the personal release. No Android endpoint is contacted.'
    started_utc = $started.ToString('o')
    ended_utc = $ended.ToString('o')
    provenance = [ordered]@{
        base_commit = $head
        working_tree_patch_sha256 = $initialSourceState.sha256
        source_state_recheck_sha256 = $currentSourceState.sha256
        source_state_stable = $sourceStateStable
        source_state_entries = @($initialSourceState.entries)
    }
    checks = @($checks)
    blocked_egress = if ($RunBlockedEgress) {
        'HOST_PROCESS_ISOLATION_RUN'
    } else {
        'OPTIONAL_NOT_RUN; ANDROID_UID_EGRESS_REMAINS_IN_EMULATOR_GATE'
    }
    emulator_validation = 'SEPARATE_INVOKE_PERSONAL_RELEASE_EMULATOR'
    result = if ($passed) { 'PASS' } else { 'FAIL' }
}
$json = $report | ConvertTo-Json -Depth 6
if ($Output) {
    $outputDirectory = Split-Path -Parent $Output
    if ($outputDirectory -and -not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
    Set-Content -LiteralPath $Output -Value $json -Encoding utf8
}
Write-Output $json
if (-not $passed) {
    exit 2
}
