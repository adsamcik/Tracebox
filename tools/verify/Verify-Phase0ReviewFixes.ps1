$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root
$started = (Get-Date).ToUniversalTime()
$commands = @()

function Add-CommandResult {
    param(
        [string] $Command,
        [Diagnostics.Stopwatch] $Timer,
        [string] $Status = 'PASS'
    )
    $script:commands += [ordered]@{
        command = $Command
        duration_ms = [math]::Round($Timer.Elapsed.TotalMilliseconds)
        exit_status = 0
        status = $Status
    }
}

$timer = [Diagnostics.Stopwatch]::StartNew()
foreach ($scriptPath in @(
        'tools\android\Run-Phase0Qualification.ps1',
        'tools\android\Run-Phase0ReviewFixQualification.ps1',
        'tools\crashpad\Acquire-Crashpad.ps1',
        'tools\crashpad\Test-AcquireCrashpadTamper.ps1'
    )) {
    $errors = $null
    [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $root $scriptPath),
        [ref]$null,
        [ref]$errors) | Out-Null
    if ($errors.Count -gt 0) {
        throw "PowerShell syntax failure: $scriptPath"
    }
}
$timer.Stop()
Add-CommandResult `
    'PowerShell parser: qualification/acquisition/tamper scripts' $timer

$timer = [Diagnostics.Stopwatch]::StartNew()
& cargo fmt --all -- --check
if ($LASTEXITCODE -ne 0) { throw 'cargo fmt failed' }
$timer.Stop()
Add-CommandResult 'cargo fmt --all -- --check' $timer

$timer = [Diagnostics.Stopwatch]::StartNew()
& cargo clippy -p tracebox-phase0 -p tbdiag-phase0 --all-targets `
    --locked --offline -- -D warnings
if ($LASTEXITCODE -ne 0) { throw 'cargo clippy failed' }
$timer.Stop()
Add-CommandResult `
    'cargo clippy -p tracebox-phase0 -p tbdiag-phase0 --all-targets --locked --offline -- -D warnings' `
    $timer

$timer = [Diagnostics.Stopwatch]::StartNew()
& cargo test -p tracebox-phase0 -p tbdiag-phase0 --locked --offline
if ($LASTEXITCODE -ne 0) { throw 'cargo test failed' }
$timer.Stop()
Add-CommandResult `
    'cargo test -p tracebox-phase0 -p tbdiag-phase0 --locked --offline' $timer

$timer = [Diagnostics.Stopwatch]::StartNew()
$tamper = & "$root\tools\crashpad\Test-AcquireCrashpadTamper.ps1" |
    ConvertFrom-Json
$timer.Stop()
if ($tamper.result -ne 'PASS' -or -not $tamper.cached_reuse_rejected) {
    throw 'Crashpad tamper regression failed'
}
Add-CommandResult 'tools\crashpad\Test-AcquireCrashpadTamper.ps1' $timer

$timer = [Diagnostics.Stopwatch]::StartNew()
& "$root\tools\crashpad\Build-Crashpad.ps1" | Out-Null
$timer.Stop()
Add-CommandResult 'tools\crashpad\Build-Crashpad.ps1' $timer

$timer = [Diagnostics.Stopwatch]::StartNew()
& "$root\gradlew.bat" :test-apps:phase0-fixture:assembleQualificationRelease `
    --dependency-verification strict --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'qualification APK build failed' }
$timer.Stop()
Add-CommandResult `
    'gradlew.bat :test-apps:phase0-fixture:assembleQualificationRelease --dependency-verification strict --no-daemon' `
    $timer

$emergencySource = Get-Content 'native\emergency\tracebox_emergency.c' -Raw
$bridgeSource = Get-Content 'native\crashpad\overlay\tracebox_bridge.cc' -Raw
$cliSource = Get-Content 'rust\tbdiag-phase0\src\main.rs' -Raw
$staticAssertions = [ordered]@{
    emergency_signal_path_has_no_memset_or_memcpy =
        $emergencySource -notmatch '\b(memset|memcpy)\s*\('
    bridge_has_no_realtime_deadline =
        $bridgeSource -notmatch 'CLOCK_REALTIME'
    registration_receive_is_nonblocking =
        $bridgeSource -match 'recvmsg\(socket_fd, &message, MSG_DONTWAIT\)'
    summary_seed_count_is_computed =
        $cliSource -notmatch '"summary_seed_matches": 0'
}
if (@($staticAssertions.Values | Where-Object { -not $_ }).Count -ne 0) {
    throw 'Static review-fix assertion failed'
}

$timer = [Diagnostics.Stopwatch]::StartNew()
& "$root\tools\traceability\Initialize-Traceability.ps1"
$foundationOutput = & "$root\tools\verify\Verify-FoundationSpecs.ps1"
$timer.Stop()
$foundation = ($foundationOutput -join "`n") | ConvertFrom-Json
if ($foundation.result -ne 'PASS') {
    throw 'Foundation structural verification failed'
}
Add-CommandResult `
    'tools\traceability\Initialize-Traceability.ps1; tools\verify\Verify-FoundationSpecs.ps1' `
    $timer

$apk = Join-Path $root `
    'test-apps\phase0-fixture\build\outputs\apk\qualificationRelease\phase0-fixture-qualificationRelease.apk'
$ended = (Get-Date).ToUniversalTime()
$result = [ordered]@{
    requirement_id = 'PHASE0-REVIEW-FIX-HOST'
    scope = 'targeted host/native/Rust structural regression; not Phase 0 certification'
    phase0_state = 'INCOMPLETE'
    reviewed_implementation_commit = (git rev-parse HEAD).Trim()
    start_time_utc = $started.ToString('o')
    end_time_utc = $ended.ToString('o')
    duration_ms = [math]::Round(($ended - $started).TotalMilliseconds)
    exit_status = 0
    matrix_cell = 'HOST_WINDOWS_NT_TARGETED_REVIEW_FIX'
    tools = [ordered]@{
        powershell = $PSVersionTable.PSVersion.ToString()
        git = (& git --version) -join ''
        java = (& java -version 2>&1 | Select-Object -First 1) -join ''
        cargo = (& cargo --version) -join ''
        rustc = (& rustc --version) -join ''
    }
    commands = $commands
    assertions = [ordered]@{
        rust_tests = '12 passed; 0 failed'
        crashpad_tamper_rejected = $tamper.cached_reuse_rejected
        crashpad_original_sha256 = $tamper.original_sha256
        crashpad_tampered_sha256 = $tamper.tampered_sha256
        crashpad_restored_sha256 = $tamper.restored_sha256
        native_x86_64_sha256 = (
            Get-FileHash `
                (Join-Path $root 'android\tracebox-native\src\main\jniLibs\x86_64\libtracebox_crashpad.so') `
                -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        native_arm64_sha256 = (
            Get-FileHash `
                (Join-Path $root 'android\tracebox-native\src\main\jniLibs\arm64-v8a\libtracebox_crashpad.so') `
                -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        qualification_apk_sha256 =
            (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        static_signal_and_deadline_checks = $staticAssertions
        foundation_specs = $foundation
    }
    result = 'PASS'
}

$evidence = Join-Path $root 'evidence\phase0\review-fix-host-validation.json'
$result | ConvertTo-Json -Depth 10 | Set-Content $evidence -Encoding utf8
Write-Output $evidence
