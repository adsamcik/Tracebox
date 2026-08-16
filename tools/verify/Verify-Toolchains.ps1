$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'AndroidSdkSupport.ps1')
$androidSdkRoot = Get-TraceboxAndroidSdkRoot -RepositoryRoot $root
Set-Location $root
$toolchainLockText = Get-Content 'gradle\toolchains.lock.toml' -Raw
$catalogText = Get-Content 'gradle\libs.versions.toml' -Raw

function Get-LockedVersion {
    param([string] $Section)
    $escaped = [regex]::Escape($Section)
    $match = [regex]::Match(
        $toolchainLockText,
        "(?ms)^\[$escaped\]\s+.*?^version\s*=\s*`"([^`"]+)`""
    )
    if (-not $match.Success) { throw "Missing locked $Section version" }
    return $match.Groups[1].Value
}

function Assert-CatalogVersion {
    param([string] $Name, [string] $Expected)
    $escapedName = [regex]::Escape($Name)
    $escapedVersion = [regex]::Escape($Expected)
    if ($catalogText -notmatch "(?m)^$escapedName\s*=\s*`"$escapedVersion`"\s*$") {
        throw "Version catalog $Name does not match the locked version $Expected"
    }
}

Assert-CatalogVersion 'agp' (Get-LockedVersion 'agp')
Assert-CatalogVersion 'kotlin' (Get-LockedVersion 'kotlin')
Assert-CatalogVersion 'coroutines' (Get-LockedVersion 'coroutines')

function Assert-FileContains {
    param(
        [string] $Path,
        [string[]] $Required,
        [string[]] $Forbidden = @()
    )
    $text = Get-Content $Path -Raw
    foreach ($pattern in $Required) {
        if ($text -notmatch $pattern) {
            throw "$Path does not satisfy required contract: $pattern"
        }
    }
    foreach ($pattern in $Forbidden) {
        if ($text -match $pattern) {
            throw "$Path violates forbidden contract: $pattern"
        }
    }
}

Assert-FileContains '.github\workflows\ci.yml' @(
    '(?m)^\s*host-readiness:',
    '(?m)^\s*release-readiness:',
    'Invoke-Phase5HostReadiness\.ps1',
    'required release readiness',
    'ubuntu-24\.04',
    'verifyReleaseMetadata check createReleaseChecksums',
    'java-version:\s*"21"',
    'build-tools;37\.0\.0'
) @('tools\\ci\\presubmit\.ps1')
Assert-FileContains '.github\workflows\native-qualification.yml' @(
    '(?m)^\s*workflow_dispatch:',
    '(?m)^\s*schedule:',
    'timeout-minutes:\s*90',
    'tools\\ci\\presubmit\.ps1',
    'identityCaptureTest',
    'verifyFixtureRustPanicProbeIsolation'
)
Assert-FileContains '.github\workflows\emulator-qualification.yml' @(
    '(?m)^\s*workflow_dispatch:',
    'timeout-minutes:\s*90',
    'self-hosted',
    'Invoke-PersonalReleaseEmulator\.ps1'
) @('(?m)^\s*schedule:')

$allPublishedArtifacts = @(
    'tracebox-api',
    'tracebox-core',
    'tracebox-storage',
    'tracebox-directboot',
    'tracebox-anr-exit',
    'tracebox-native',
    'tracebox-export',
    'tracebox-export-ui',
    'tracebox-ui-compose',
    'tracebox'
)
foreach ($workflow in @('.github\workflows\release.yml', '.github\workflows\recover-alpha-release.yml')) {
    Assert-FileContains $workflow @(
        'java-version:\s*"21"',
        'build-tools;37\.0\.0',
        'cmdline-tools/latest/bin/sdkmanager',
        'traceboxExpectedArtifactRoot'
    )
    $workflowText = Get-Content $workflow -Raw
    foreach ($artifact in $allPublishedArtifacts) {
        $escapedArtifact = [regex]::Escape($artifact)
        if ($workflowText -notmatch "(?<![A-Za-z0-9-])$escapedArtifact(?![A-Za-z0-9-])") {
            throw "$workflow does not verify published artifact $artifact"
        }
        $releaseAssetPath = "android/$artifact/build/outputs/aar/$artifact-release.aar"
        if ($workflowText -notmatch [regex]::Escape($releaseAssetPath)) {
            throw "$workflow does not attach published artifact $artifact to the GitHub release"
        }
    }
}
Assert-FileContains '.github\workflows\release.yml' @(
    '(?m)^\s*workflow_dispatch:',
    'inputs\.tag \|\| github\.ref',
    'inputs\.tag \|\| github\.sha',
    'inputs\.tag \|\| github\.ref_name',
    'TRACEBOX_EVENT_NAME'
)

$savedErrorActionPreference = $ErrorActionPreference
try {
    # Windows PowerShell 5 surfaces native stderr as ErrorRecord objects and would
    # terminate here under Stop even when the process exits successfully. Java
    # intentionally writes its version banner to stderr, so capture it under
    # Continue and validate the native exit status explicitly.
    $ErrorActionPreference = 'Continue'
    $javaOutput = @(& java -version 2>&1)
    $javaExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
if ($javaExitCode -ne 0) { throw "java -version failed with exit code $javaExitCode" }
$java = ($javaOutput | Select-Object -First 1).ToString()
$rust = (& rustc --version) -join ''
$cargo = (& cargo --version) -join ''
$cmakeExecutable = if ($env:OS -eq 'Windows_NT') { 'cmake.exe' } else { 'cmake' }
$cmakePath = Join-Path $androidSdkRoot "cmake\4.1.2\bin\$cmakeExecutable"
$cmake = (& $cmakePath --version | Select-Object -First 1) -join ''
$wrapper = (Get-FileHash 'gradle\wrapper\gradle-wrapper.jar' -Algorithm SHA256).Hash.ToLowerInvariant()

if ($java -notmatch 'version "21(?:\.|\")') { throw "Unexpected Java: $java" }
if ($rust -notmatch '^rustc 1\.93\.1 ') { throw "Unexpected Rust: $rust" }
if ($cargo -notmatch '^cargo 1\.93\.1 ') { throw "Unexpected Cargo: $cargo" }
if ($cmake -ne 'cmake version 4.1.2') { throw "Unexpected CMake: $cmake" }
if ($wrapper -ne '497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7') {
    throw 'Unexpected Gradle wrapper JAR'
}

$requiredSdkPaths = @(
    (Join-Path $androidSdkRoot 'platforms\android-37.0'),
    (Join-Path $androidSdkRoot 'build-tools\37.0.0'),
    (Join-Path $androidSdkRoot 'ndk\28.2.13676358'),
    (Join-Path $androidSdkRoot 'cmake\4.1.2')
)
foreach ($path in $requiredSdkPaths) {
    if (-not (Test-Path $path)) {
        throw "Missing Android toolchain path: $path"
    }
}

$metadataText = Get-Content 'gradle\verification-metadata.xml' -Raw
$dependencyComponents = [regex]::Matches($metadataText, '<component ').Count
if ($dependencyComponents -lt 100) {
    throw 'Dependency verification metadata is unexpectedly small'
}

$lockFiles = Get-ChildItem -Recurse -File -Filter 'gradle.lockfile'
if ($lockFiles.Count -lt 4) {
    throw 'Expected Gradle dependency lock files'
}

[ordered]@{
    java = $java
    rustc = $rust
    cargo = $cargo
    cmake = $cmake
    gradle_wrapper_sha256 = $wrapper
    dependency_components = $dependencyComponents
    gradle_lock_files = $lockFiles.Count
    result = 'PASS'
} | ConvertTo-Json
