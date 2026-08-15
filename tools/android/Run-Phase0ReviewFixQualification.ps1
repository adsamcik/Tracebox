param(
    [Parameter(Mandatory)]
    [string] $Serial,
    [int] $ExpectedApi = 36,
    [int] $ExpectedPageSize = 4096
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root
$apk = Join-Path $root `
    'test-apps\phase0-fixture\build\outputs\apk\debuggableRelease\phase0-fixture-debuggableRelease.apk'
$package = 'dev.tracebox.phase0'
$component = "$package/.MainActivity"
$receiver = "$package/.FaultReceiver"
$tag = 'TraceboxPhase0'
$dataDirectory = '.'
$runtimeDirectory = Join-Path $root 'evidence\runtime'
$seed = 'TRACEBOX_PHASE0_SEEDED_SECRET_7F4C19E2A6B35D80'
$started = (Get-Date).ToUniversalTime()

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)] [string[]] $Arguments)
    $output = & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
    return $output
}

function Get-SourcePatchSha256 {
    $tracked = (& git -C $root --no-pager diff --binary HEAD -- . ':(exclude)evidence/**') -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to fingerprint tracked source changes'
    }
    $untracked = foreach ($path in (& git -C $root ls-files --others --exclude-standard |
            Where-Object { $_ -notlike 'evidence/*' } |
            Sort-Object)) {
        "$path`n$((Get-FileHash (Join-Path $root $path) -Algorithm SHA256).Hash.ToLowerInvariant())`n"
    }
    $material = "tracked`n$tracked`nuntracked`n$($untracked -join '')"
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($material)
        )
    ).ToLowerInvariant()
}

function Wait-Log {
    param([string] $Pattern, [int] $TimeoutSeconds = 15)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $match = Invoke-Adb logcat '-d' '-v' brief '-s' $tag |
            Select-String $Pattern |
            Select-Object -Last 1
        if ($match) {
            return $match.ToString()
        }
        Start-Sleep -Milliseconds 50
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "Timed out waiting for log: $Pattern"
}

function Start-Action {
    param([string] $Action)
    Invoke-Adb shell am start '-n' $component '--es' tracebox.action $Action | Out-Null
}

function Send-Fault {
    param([string] $Action)
    Invoke-Adb shell am broadcast '-n' $receiver '--es' tracebox.action $Action | Out-Null
}

function Get-ProcessId {
    param([string] $ProcessName)
    $line = Invoke-Adb shell ps '-A' |
        Select-String "\s$([regex]::Escape($ProcessName))$" |
        Select-Object -First 1
    if (-not $line) {
        return 0
    }
    return [int](($line.ToString() -split '\s+')[1])
}

function Wait-ProcessIdGone {
    param([int] $ProcessId, [string] $ProcessName, [int] $TimeoutSeconds = 10)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Get-ProcessId $ProcessName) -ne $ProcessId) {
            return $true
        }
        Start-Sleep -Milliseconds 50
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    return $false
}

function Get-DeathLog {
    return @(
        Invoke-Adb logcat '-d' '-v' brief |
            Select-String 'Fatal signal|exited due to signal|exiting due to SIG_DFL' |
            Select-Object -Last 5 |
            ForEach-Object ToString
    )
}

function Pull-AppFile {
    param([string] $RemotePath, [string] $LocalPath)
    $arguments = @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat', $RemotePath)
    $process = Start-Process -FilePath (Get-Command adb).Source `
        -ArgumentList $arguments `
        -RedirectStandardOutput $LocalPath `
        -NoNewWindow -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Failed to pull $RemotePath"
    }
}

function Invoke-AppShell {
    param([string] $Command)
    $output = & adb -s $Serial shell "run-as $package sh -c '$Command'"
    if ($LASTEXITCODE -ne 0) {
        throw "run-as failed: $Command"
    }
    return $output
}

function Count-Dumps {
    return [int]((Invoke-AppShell `
            "ls $dataDirectory/no_backup/crashpad-db/pending/*.dmp 2>/dev/null | wc -l") -join '').Trim()
}

function Reset-And-Launch {
    Invoke-Adb shell am force-stop $package | Out-Null
    Invoke-Adb shell pm clear $package | Out-Null
    Invoke-Adb logcat '-b' main '-b' system '-b' crash '-c' | Out-Null
    Invoke-Adb shell am start '-W' '-n' $component | Out-Null
    Wait-Log 'main_connected=true' 10 | Out-Null
    Wait-Log 'worker_connected=true' 10 | Out-Null
}

function Restart-Without-Clear {
    Invoke-Adb shell am force-stop $package | Out-Null
    Invoke-Adb logcat '-b' main '-b' system '-b' crash '-c' | Out-Null
    Invoke-Adb shell am start '-W' '-n' $component | Out-Null
    Wait-Log 'main_connected=true' 10 | Out-Null
    Wait-Log 'worker_connected=true' 10 | Out-Null
}

if (-not (Test-Path $apk)) {
    throw "Missing qualification APK: $apk"
}
New-Item -ItemType Directory -Force $runtimeDirectory | Out-Null
$api = [int]((Invoke-Adb shell getprop ro.build.version.sdk) -join '')
$pageSize = [int]((Invoke-Adb shell getconf PAGE_SIZE) -join '')
$abi = ((Invoke-Adb shell getprop ro.product.cpu.abi) -join '').Trim()
if ($api -ne $ExpectedApi -or $pageSize -ne $ExpectedPageSize -or
    $abi -ne 'x86_64') {
    throw "Endpoint mismatch: API=$api page=$pageSize ABI=$abi"
}
Invoke-Adb wait-for-device | Out-Null
Invoke-Adb install '-r' $apk | Out-Null
Invoke-Adb shell pm enable $package | Out-Null

Reset-And-Launch
$handlerPid = Get-ProcessId "$package`:tracebox_handler"
Start-Action hang_handler
Start-Sleep 1
Invoke-Adb logcat '-b' main '-b' system '-b' crash '-c' | Out-Null
Start-Action connect_hung_handler
$registrationLine = Wait-Log 'hung_registration_connected=' 5
if ($registrationLine -notmatch
    'hung_registration_connected=(true|false) outcome=(\d+) elapsed_us=(\d+)') {
    throw 'Cannot parse hung registration result'
}
$registration = [ordered]@{
    connected = $Matches[1] -eq 'true'
    outcome = [int]$Matches[2]
    elapsed_us = [long]$Matches[3]
}
Invoke-Adb logcat '-b' main '-b' system '-b' crash '-c' | Out-Null
Start-Action nonfatal
$timeoutLine = Wait-Log 'nonfatal_captured=false' 5
if ($timeoutLine -notmatch 'nonfatal_captured=false elapsed_us=(\d+)') {
    throw 'Cannot parse hung nonfatal result'
}
$hangTimeout = [ordered]@{
    captured = $false
    elapsed_us = [long]$Matches[1]
}
Invoke-Adb shell am force-stop $package | Out-Null

Reset-And-Launch
Invoke-Adb logcat '-b' main '-b' system '-b' crash '-c' | Out-Null
Start-Action seeded
Wait-Log 'seeded_nonfatal_captured=true' 10 | Out-Null
$dumpPath = ((Invoke-AppShell `
        "ls -t $dataDirectory/no_backup/crashpad-db/pending/*.dmp | head -1") -join '').Trim()
$dumpLocal = Join-Path $runtimeDirectory "api$api-review-seeded.dmp"
Pull-AppFile $dumpPath $dumpLocal
Start-Action emergency
Wait-Log 'emergency_written=true' 5 | Out-Null
$emergencyRemote = "$dataDirectory/no_backup/tracebox-emergency-1.bin"
$identityLocal = Join-Path $runtimeDirectory "api$api-review-identity.bin"
Pull-AppFile $emergencyRemote $identityLocal
$identityValidation = & cargo run -q -p tbdiag-phase0 --locked --offline -- `
    emergency $identityLocal 2>&1
$identityValidatorExit = $LASTEXITCODE
$identityBytes = [IO.File]::ReadAllBytes($identityLocal)
$identityEstablished =
    $identityValidatorExit -eq 0 -and
    [Text.Encoding]::ASCII.GetString($identityBytes, 0, 8) -eq 'TBEMERG1' -and
    [BitConverter]::ToUInt64($identityBytes, 248) -eq 0x5442454d434f4d50
$privacy = $null
$summaryParserExit = -1
$summaryParserOutput = 'identity unavailable; scan not run'
if ($identityEstablished) {
    $identityHex = [Convert]::ToHexString($identityBytes[16..47]).ToLowerInvariant()
    $summaryOutput = & cargo run -q -p tbdiag-phase0 --locked --offline -- `
        minidump $dumpLocal $seed $identityHex 2>&1
    $summaryParserExit = $LASTEXITCODE
    $summaryParserOutput = $summaryOutput -join "`n"
    if ($summaryParserExit -eq 0) {
        $privacy = $summaryParserOutput | ConvertFrom-Json
    }
}

Restart-Without-Clear
$restartLocal = Join-Path $runtimeDirectory "api$api-review-restart-reset.bin"
Pull-AppFile $emergencyRemote $restartLocal
$restartValidation = & cargo run -q -p tbdiag-phase0 --locked --offline -- `
    emergency $restartLocal 2>&1
$restartValidatorExit = $LASTEXITCODE
$restartBytes = [IO.File]::ReadAllBytes($restartLocal)
$restartReset = [ordered]@{
    prior_record_validator_exit = $identityValidatorExit
    restarted_record_validator_exit = $restartValidatorExit
    restarted_record_validator_output = $restartValidation -join "`n"
    restarted_record_all_zero =
        @($restartBytes | Where-Object { $_ -ne 0 }).Count -eq 0
    pm_clear_used = $false
}

Reset-And-Launch
$fallbackAppPid = Get-ProcessId $package
$fallbackDumpCountBefore = Count-Dumps
Start-Action terminate_handler
Start-Sleep 4
Start-Action alive
Wait-Log 'handler_alive=false' 10 | Out-Null
Invoke-Adb logcat '-b' main '-b' system '-b' crash '-c' | Out-Null
Send-Fault fatal
$fallbackProcessDied = Wait-ProcessIdGone $fallbackAppPid $package 10
Start-Sleep 1
$fallbackLocal = Join-Path $runtimeDirectory "api$api-review-handler-unavailable.bin"
Pull-AppFile $emergencyRemote $fallbackLocal
$fallbackValidation = & cargo run -q -p tbdiag-phase0 --locked --offline -- `
    emergency $fallbackLocal 2>&1
$fallbackValidatorExit = $LASTEXITCODE
$fallbackBytes = [IO.File]::ReadAllBytes($fallbackLocal)
$fallback = [ordered]@{
    validator_exit = $fallbackValidatorExit
    validator_output = $fallbackValidation -join "`n"
    slot_sequence = [BitConverter]::ToUInt64($fallbackBytes, 48)
    flags = [BitConverter]::ToUInt64($fallbackBytes, 120)
    raw_dump_delta = (Count-Dumps) - $fallbackDumpCountBefore
    process_death_observed = $fallbackProcessDied
    process_log = Get-DeathLog
}

Invoke-Adb shell am force-stop $package | Out-Null
Invoke-Adb shell pm clear $package | Out-Null
Invoke-Adb logcat '-b' main '-b' system '-b' crash '-c' | Out-Null
Send-Fault early_chain
Start-Sleep 2
$chainEmergencyLocal = Join-Path $runtimeDirectory "api$api-review-chain.bin"
$chainMarkerLocal = Join-Path $runtimeDirectory "api$api-review-chain-marker.bin"
Pull-AppFile $emergencyRemote $chainEmergencyLocal
Pull-AppFile "$dataDirectory/no_backup/tracebox-chain-marker.bin" $chainMarkerLocal
$chainValidation = & cargo run -q -p tbdiag-phase0 --locked --offline -- `
    emergency $chainEmergencyLocal 2>&1
$chainValidatorExit = $LASTEXITCODE
$chainBytes = [IO.File]::ReadAllBytes($chainEmergencyLocal)
$chain = [ordered]@{
    validator_exit = $chainValidatorExit
    validator_output = $chainValidation -join "`n"
    slot_sequence = [BitConverter]::ToUInt64($chainBytes, 48)
    prior_action_count = [IO.File]::ReadAllBytes($chainMarkerLocal)[0]
    process_death_observed = (Get-ProcessId $package) -eq 0
    process_log = Get-DeathLog
}

$checks = [ordered]@{
    registration_receive_deadline =
        -not $registration.connected -and $registration.outcome -eq 1 -and
        $registration.elapsed_us -ge 1900000 -and
        $registration.elapsed_us -le 2000000
    hang_timeout_deadline =
        -not $hangTimeout.captured -and $hangTimeout.elapsed_us -ge 1900000 -and
        $hangTimeout.elapsed_us -le 2000000
    live_identity_required = $identityEstablished -and $summaryParserExit -eq 0
    seeded_summary_scan =
        $summaryParserExit -eq 0 -and $privacy.raw_seed_matches -ge 1 -and
        $privacy.summary_seed_matches -eq 0
    known_identity_scan =
        $summaryParserExit -eq 0 -and $privacy.identity_encodings_scanned -ge 5 -and
        $privacy.raw_identity_matches -eq 0 -and
        $privacy.summary_identity_matches -eq 0
    emergency_slot_reset_on_process_restart =
        $restartReset.prior_record_validator_exit -eq 0 -and
        $restartReset.restarted_record_validator_exit -ne 0 -and
        $restartReset.restarted_record_all_zero -and
        -not $restartReset.pm_clear_used
    capture_only_stream_profile =
        $summaryParserExit -eq 0 -and $privacy.stream_profile_valid -and
        $privacy.unexpected_stream_types.Count -eq 0 -and
        $privacy.missing_required_stream_types.Count -eq 0
    handler_unavailable_fallback =
        $fallback.validator_exit -eq 0 -and $fallback.slot_sequence -eq 1 -and
        $fallback.flags -eq 3 -and $fallback.raw_dump_delta -eq 0 -and
        $fallback.process_death_observed -and $fallback.process_log.Count -gt 0
    previous_signal_action_once =
        $chain.validator_exit -eq 0 -and $chain.slot_sequence -eq 1 -and
        $chain.prior_action_count -eq 1 -and $chain.process_death_observed -and
        $chain.process_log.Count -gt 0
}
$passed = @($checks.Values | Where-Object { -not $_ }).Count -eq 0
$ended = (Get-Date).ToUniversalTime()
$result = [ordered]@{
    requirement_id = 'PHASE0-REVIEW-FIX-1-8'
    scope = 'targeted review-fix regression; not full Phase 0 qualification'
    phase0_state = 'INCOMPLETE'
    command = "tools\android\Run-Phase0ReviewFixQualification.ps1 -Serial $Serial " +
        "-ExpectedApi $ExpectedApi -ExpectedPageSize $ExpectedPageSize"
    working_directory = $root
    source_state = [ordered]@{
        base_commit = (git -C $root rev-parse HEAD).Trim()
        working_tree_patch_sha256 = Get-SourcePatchSha256
    }
    start_time_utc = $started.ToString('o')
    end_time_utc = $ended.ToString('o')
    duration_ms = [math]::Round(($ended - $started).TotalMilliseconds)
    exit_status = if ($passed) { 0 } else { 2 }
    tools = [ordered]@{
        adb = (& adb version | Select-Object -First 1) -join ''
        cargo = (& cargo --version) -join ''
        rustc = (& rustc --version) -join ''
    }
    endpoint = [ordered]@{
        serial = $Serial
        api = $api
        abi = $abi
        page_size = $pageSize
        matrix_cell = "API${api}_${abi}_${pageSize}B_TARGETED_REVIEW_FIX"
    }
    artifacts = [ordered]@{
        apk_sha256 = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        native_x86_64_sha256 = (
            Get-FileHash `
                'android\tracebox-native\src\main\jniLibs\x86_64\libtracebox_crashpad.so' `
                -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        seeded_dump_sha256 =
            (Get-FileHash $dumpLocal -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    registration = $registration
    hang_timeout = $hangTimeout
    privacy = [ordered]@{
        identity_established = $identityEstablished
        identity_validator_exit = $identityValidatorExit
        identity_validator_output = $identityValidation -join "`n"
        summary_parser_exit = $summaryParserExit
        summary_parser_output = $summaryParserOutput
        stream_profile_valid = $privacy.stream_profile_valid
        unexpected_stream_types = $privacy.unexpected_stream_types
        missing_required_stream_types = $privacy.missing_required_stream_types
        raw_seed_matches = $privacy.raw_seed_matches
        summary_seed_matches = $privacy.summary_seed_matches
        raw_identity_matches = $privacy.raw_identity_matches
        summary_identity_matches = $privacy.summary_identity_matches
        identity_encodings_scanned = $privacy.identity_encodings_scanned
        stream_inventory = $privacy.streams
    }
    emergency_restart_reset = $restartReset
    handler_unavailable_fallback = $fallback
    previous_signal_action = $chain
    assertions = $checks
    result = if ($passed) { 'PASS' } else { 'FAIL' }
}

$evidence = Join-Path $root `
    "evidence\phase0\API$api-$abi-$pageSize-review-fix-qualification.json"
$result | ConvertTo-Json -Depth 10 | Set-Content $evidence -Encoding utf8
Write-Output $evidence
if (-not $passed) {
    exit 2
}
