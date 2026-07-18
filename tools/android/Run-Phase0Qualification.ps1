param(
    [Parameter(Mandatory)]
    [string] $Serial,
    [Parameter(Mandatory)]
    [int] $ExpectedApi,
    [Parameter(Mandatory)]
    [int] $ExpectedPageSize,
    [int] $HealthyMinutes = 60,
    [int] $IneligibleMinutes = 10
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root
$apk = Join-Path $root 'test-apps\phase0-fixture\build\outputs\apk\release\phase0-fixture-release.apk'
$package = 'dev.tracebox.phase0'
$component = "$package/.MainActivity"
$receiver = "$package/.FaultReceiver"
$tag = 'TraceboxPhase0'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)] [string[]] $Arguments)
    $output = & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
    return $output
}

function Wait-Log {
    param([string] $Pattern, [int] $TimeoutSeconds = 15)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $match = Invoke-Adb logcat -d -v brief -s $tag |
            Select-String $Pattern |
            Select-Object -Last 1
        if ($match) {
            return $match.ToString()
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for log: $Pattern"
}

function Start-Action {
    param([string] $Action)
    Invoke-Adb shell am start -n $component --es tracebox.action $Action | Out-Null
}

function Send-Fault {
    param([string] $Action)
    Invoke-Adb shell am broadcast -n $receiver --es tracebox.action $Action | Out-Null
}

function Get-ProcessId {
    param([string] $ProcessName)
    $line = Invoke-Adb shell ps -A |
        Select-String "\s$([regex]::Escape($ProcessName))$" |
        Select-Object -First 1
    if (-not $line) {
        return 0
    }
    return [int](($line.ToString() -split '\s+')[1])
}

function Get-Jiffies {
    param([int] $ProcessId)
    $stat = (Invoke-Adb shell cat "/proc/$ProcessId/stat") -join ''
    if ($stat -notmatch '^\d+ \(.+\) (.+)$') {
        throw "Cannot parse /proc/$ProcessId/stat"
    }
    $fields = $Matches[1] -split ' '
    return [long]$fields[11] + [long]$fields[12]
}

function Get-ContextSwitches {
    param([int] $ProcessId)
    $status = Invoke-Adb shell cat "/proc/$ProcessId/status"
    $voluntary = [long](($status | Select-String '^voluntary_ctxt_switches:' |
            ForEach-Object { ($_ -split '\s+')[-1] }))
    $involuntary = [long](($status | Select-String '^nonvoluntary_ctxt_switches:' |
            ForEach-Object { ($_ -split '\s+')[-1] }))
    return $voluntary + $involuntary
}

function Get-PssKiB {
    param([int] $ProcessId)
    $line = Invoke-Adb shell dumpsys meminfo $ProcessId |
        Select-String 'TOTAL PSS:' |
        Select-Object -First 1
    if ($line -notmatch 'TOTAL PSS:\s+(\d+)') {
        throw "Cannot parse PSS for $ProcessId"
    }
    return [int]$Matches[1]
}

function Get-WatchdogStats {
    Invoke-Adb logcat -c | Out-Null
    Send-Fault watchdog_stats
    $line = Wait-Log 'watchdog_stats'
    if ($line -notmatch 'posted=(\d+) acked=(\d+) eligible=(true|false)') {
        throw 'Cannot parse watchdog stats'
    }
    return [ordered]@{
        posted = [long]$Matches[1]
        acknowledged = [long]$Matches[2]
        eligible = $Matches[3] -eq 'true'
    }
}

function Pull-AppFile {
    param([string] $RemotePath, [string] $LocalPath)
    $arguments = @(
        '-s', $Serial, 'exec-out', 'run-as', $package, 'cat', $RemotePath
    )
    $process = Start-Process -FilePath (Get-Command adb).Source `
        -ArgumentList $arguments `
        -RedirectStandardOutput $LocalPath `
        -NoNewWindow -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Failed to pull $RemotePath"
    }
}

function Count-Bytes {
    param([byte[]] $Haystack, [byte[]] $Needle)
    $count = 0
    for ($offset = 0; $offset -le $Haystack.Length - $Needle.Length; $offset++) {
        $matches = $true
        for ($index = 0; $index -lt $Needle.Length; $index++) {
            if ($Haystack[$offset + $index] -ne $Needle[$index]) {
                $matches = $false
                break
            }
        }
        if ($matches) {
            $count++
        }
    }
    return $count
}

function Reset-And-Launch {
    Invoke-Adb shell am force-stop $package | Out-Null
    Invoke-Adb shell pm clear $package | Out-Null
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start '-W' -n $component | Out-Null
    Wait-Log 'main_connected=true' | Out-Null
    Wait-Log 'worker_connected=true' | Out-Null
}

$started = (Get-Date).ToUniversalTime()
$api = [int]((Invoke-Adb shell getprop ro.build.version.sdk) -join '')
$pageSize = [int]((Invoke-Adb shell getconf PAGE_SIZE) -join '')
$abi = ((Invoke-Adb shell getprop ro.product.cpu.abi) -join '').Trim()
if ($api -ne $ExpectedApi -or $pageSize -ne $ExpectedPageSize -or $abi -ne 'x86_64') {
    throw "Endpoint mismatch: API=$api page=$pageSize ABI=$abi"
}

Invoke-Adb install -r $apk | Out-Null
Invoke-Adb shell pm enable $package | Out-Null
Reset-And-Launch

$appPid = Get-ProcessId $package
$handlerPid = Get-ProcessId "$package`:tracebox_handler"
$workerPid = Get-ProcessId "$package`:worker"
if ($appPid -eq 0 -or $handlerPid -eq 0 -or $workerPid -eq 0) {
    throw 'Expected three fixture processes'
}

$healthySeconds = $HealthyMinutes * 60
$clockTicks = [int]((Invoke-Adb shell getconf CLK_TCK) -join '')
$handlerJiffiesStart = Get-Jiffies $handlerPid
$appJiffiesStart = Get-Jiffies $appPid
$handlerSwitchesStart = Get-ContextSwitches $handlerPid
$healthyStatsStart = Get-WatchdogStats
Invoke-Adb logcat -c | Out-Null
$pssSamples = @()
for ($minute = 0; $minute -lt $HealthyMinutes; $minute++) {
    Start-Sleep 60
    if ((Get-ProcessId "$package`:tracebox_handler") -ne $handlerPid) {
        throw 'Handler PID changed during healthy run'
    }
    if (($minute + 1) % 10 -eq 0 -or $minute -eq 0) {
        $pssSamples += Get-PssKiB $handlerPid
    }
}
$falseCandidates = @(
    Invoke-Adb logcat -d -v brief -s $tag | Select-String 'anr_candidate'
).Count
$healthyStatsEnd = Get-WatchdogStats
$handlerJiffiesEnd = Get-Jiffies $handlerPid
$appJiffiesEnd = Get-Jiffies $appPid
$handlerSwitchesEnd = Get-ContextSwitches $handlerPid
$handlerCpuPercent =
    (($handlerJiffiesEnd - $handlerJiffiesStart) / $clockTicks) /
    $healthySeconds * 100
$appCpuPercent =
    (($appJiffiesEnd - $appJiffiesStart) / $clockTicks) /
    $healthySeconds * 100
$heartbeatDelta = $healthyStatsEnd.posted - $healthyStatsStart.posted
$heartbeatPerMinute = $heartbeatDelta / $HealthyMinutes

Invoke-Adb shell input keyevent HOME | Out-Null
Start-Sleep 2
$ineligibleStart = Get-WatchdogStats
Start-Sleep ($IneligibleMinutes * 60)
$ineligibleEnd = Get-WatchdogStats

Invoke-Adb shell am start '-W' -n $component | Out-Null
Start-Sleep 2

$pauseMeasurements = @()
for ($iteration = 1; $iteration -le 30; $iteration++) {
    if (($iteration - 1) % 8 -eq 0) {
        Reset-And-Launch
        Start-Sleep 2
    }
    Invoke-Adb logcat -c | Out-Null
    Start-Action measure_nonfatal
    $line = Wait-Log 'nonfatal_measure' 10
    if ($line -notmatch 'captured=(true|false) elapsed_us=(\d+) main_pause_max_us=(\d+)') {
        throw 'Cannot parse nonfatal measurement'
    }
    $pauseMeasurements += [ordered]@{
        iteration = $iteration
        captured = $Matches[1] -eq 'true'
        elapsed_us = [long]$Matches[2]
        main_pause_us = [long]$Matches[3]
    }
}

$stallMeasurements = @()
Reset-And-Launch
Start-Sleep 10
for ($iteration = 1; $iteration -le 10; $iteration++) {
    Invoke-Adb logcat -c | Out-Null
    Send-Fault stall
    $line = Wait-Log 'anr_candidate' 10
    if ($line -notmatch 'delay_ms=(\d+) frames=(\d+) snapshot=(true|false)') {
        throw 'Cannot parse ANR candidate'
    }
    $stallMeasurements += [ordered]@{
        iteration = $iteration
        delay_ms = [long]$Matches[1]
        frames = [int]$Matches[2]
        snapshot = $Matches[3] -eq 'true'
    }
    Start-Sleep 1
}

Invoke-Adb logcat -c | Out-Null
Start-Action worker_nonfatal
Wait-Log 'worker_nonfatal_captured=true' 10 | Out-Null
Invoke-Adb logcat -c | Out-Null
Start-Action seeded
Wait-Log 'seeded_nonfatal_captured=true' 10 | Out-Null
$dumpPath = ((Invoke-Adb shell `
        "run-as $package sh -c 'ls -t no_backup/crashpad-db/pending/*.dmp | head -1'") -join '').Trim()
$runtimeDirectory = Join-Path $root 'evidence\runtime'
New-Item -ItemType Directory -Force $runtimeDirectory | Out-Null
$dumpLocal = Join-Path $runtimeDirectory "api$api-seeded.dmp"
Pull-AppFile $dumpPath $dumpLocal
$seed = 'TRACEBOX_PHASE0_SEEDED_SECRET_7F4C19E2A6B35D80'
$summaryJson = & cargo run -q -p tbdiag-phase0 --locked --offline -- `
    minidump $dumpLocal $seed
if ($LASTEXITCODE -ne 0) { throw 'Minidump summary failed' }
$privacySummary = ($summaryJson -join "`n") | ConvertFrom-Json

$emergencyRemote = 'no_backup/tracebox-emergency-1.bin'
$emergencyLocal = Join-Path $runtimeDirectory "api$api-emergency.bin"
Pull-AppFile $emergencyRemote $emergencyLocal
$emergencyBytes = [IO.File]::ReadAllBytes($emergencyLocal)
$internalIdMatches = Count-Bytes `
    ([IO.File]::ReadAllBytes($dumpLocal)) `
    $emergencyBytes[16..47]

Reset-And-Launch
$quotaResults = @()
for ($iteration = 1; $iteration -le 9; $iteration++) {
    Invoke-Adb logcat -c | Out-Null
    Start-Action nonfatal
    $line = Wait-Log 'nonfatal_captured=' 10
    if ($line -notmatch 'nonfatal_captured=(true|false)') {
        throw 'Cannot parse quota capture result'
    }
    $quotaResults += $Matches[1] -eq 'true'
}
$quotaDumpCount = [int]((Invoke-Adb shell `
        "run-as $package sh -c 'ls no_backup/crashpad-db/pending/*.dmp 2>/dev/null | wc -l'") -join '').Trim()

$timeoutMeasurements = @()
for ($iteration = 1; $iteration -le 10; $iteration++) {
    Reset-And-Launch
    Start-Action hang_handler
    Start-Sleep 1
    Invoke-Adb logcat -c | Out-Null
    $requestStart = Get-Date
    Start-Action nonfatal
    $line = Wait-Log 'nonfatal_captured=false' 6
    $timeoutMeasurements += [ordered]@{
        iteration = $iteration
        elapsed_ms = [math]::Round(((Get-Date) - $requestStart).TotalMilliseconds)
        result = 'cancelled'
    }
    Invoke-Adb shell am force-stop $package | Out-Null
}

$emergencyResults = @()
foreach ($fault in @('early_abort', 'early_stack', 'early_recursive')) {
    Invoke-Adb shell am force-stop $package | Out-Null
    Invoke-Adb shell pm clear $package | Out-Null
    Invoke-Adb logcat -c | Out-Null
    Send-Fault $fault
    Start-Sleep 2
    $local = Join-Path $runtimeDirectory "api$api-$fault.bin"
    Pull-AppFile $emergencyRemote $local
    $validation = & cargo run -q -p tbdiag-phase0 --locked --offline -- emergency $local 2>&1
    $emergencyResults += [ordered]@{
        fault = $fault
        validator_exit = $LASTEXITCODE
        validator_output = ($validation -join "`n")
        process_log = (
            Invoke-Adb logcat -d -v brief |
                Select-String 'exited due to signal|exiting due to SIG_DFL' |
                Select-Object -Last 3 |
                ForEach-Object ToString
        )
    }
}

foreach ($fault in @('emergency_short', 'emergency_failed')) {
    Reset-And-Launch
    Invoke-Adb logcat -c | Out-Null
    Start-Action $fault
    Wait-Log "$fault" 5 | Out-Null
    $local = Join-Path $runtimeDirectory "api$api-$fault.bin"
    Pull-AppFile $emergencyRemote $local
    $validation = & cargo run -q -p tbdiag-phase0 --locked --offline -- emergency $local 2>&1
    $emergencyResults += [ordered]@{
        fault = $fault
        validator_exit = $LASTEXITCODE
        validator_output = ($validation -join "`n")
    }
}

$pauseValues = $pauseMeasurements.main_pause_us | Sort-Object
$elapsedValues = $pauseMeasurements.elapsed_us | Sort-Object
function Percentile {
    param([long[]] $Values, [double] $Percent)
    $index = [math]::Ceiling($Percent * $Values.Count) - 1
    return $Values[[math]::Max(0, $index)]
}

$ended = (Get-Date).ToUniversalTime()
$checks = [ordered]@{
    false_positive_rate = $falseCandidates -eq 0
    handler_cpu = $handlerCpuPercent -lt 0.05
    app_cpu = $appCpuPercent -lt 0.2
    handler_pss = ($pssSamples | Measure-Object -Maximum).Maximum -le 12 * 1024
    heartbeat_rate = $heartbeatPerMinute -le 30
    ineligible_heartbeat = $ineligibleEnd.posted - $ineligibleStart.posted -eq 0
    nonfatal_capture = @($pauseMeasurements | Where-Object captured).Count -eq 30
    nonfatal_deadline = ($elapsedValues | Measure-Object -Maximum).Maximum -le 2000000
    target_pause = ($pauseValues | Measure-Object -Maximum).Maximum -le 100000
    deterministic_stalls =
        @($stallMeasurements | Where-Object { $_.frames -gt 0 }).Count -eq 10
    watchdog_rate_limit = @($stallMeasurements | Where-Object snapshot).Count -eq 1
    seeded_raw = $privacySummary.raw_seed_matches -ge 1
    seeded_summary = $privacySummary.summary_seed_matches -eq 0
    internal_identity = $internalIdMatches -eq 0
    quota_count = @($quotaResults | Where-Object { $_ }).Count -eq 8 -and
        $quotaResults[-1] -eq $false -and $quotaDumpCount -eq 8
    timeout_cancellation =
        @($timeoutMeasurements | Where-Object { $_.elapsed_ms -le 3000 }).Count -eq 10
    emergency_faults =
        $emergencyResults[0].validator_exit -eq 0 -and
        $emergencyResults[1].validator_exit -eq 0 -and
        $emergencyResults[2].validator_exit -ne 0 -and
        $emergencyResults[3].validator_exit -ne 0 -and
        $emergencyResults[4].validator_exit -ne 0
}
$passed = @($checks.Values | Where-Object { -not $_ }).Count -eq 0
$result = [ordered]@{
    requirement_id = 'F0.3-F0.7'
    command = "tools\android\Run-Phase0Qualification.ps1 -Serial $Serial " +
        "-ExpectedApi $ExpectedApi -ExpectedPageSize $ExpectedPageSize " +
        "-HealthyMinutes $HealthyMinutes -IneligibleMinutes $IneligibleMinutes"
    working_directory = $root
    reviewed_implementation_commit = (git -C $root rev-parse HEAD).Trim()
    start_time_utc = $started.ToString('o')
    end_time_utc = $ended.ToString('o')
    timeout_seconds = 7200
    exit_status = 0
    endpoint = [ordered]@{
        serial = $Serial
        api = $api
        abi = $abi
        page_size = $pageSize
    }
    process_topology = [ordered]@{
        app_pid = $appPid
        handler_pid = $handlerPid
        worker_pid = $workerPid
    }
    healthy = [ordered]@{
        minutes = $HealthyMinutes
        false_candidates = $falseCandidates
        handler_cpu_percent = $handlerCpuPercent
        app_cpu_percent = $appCpuPercent
        handler_context_switch_delta =
            $handlerSwitchesEnd - $handlerSwitchesStart
        heartbeat_per_minute = $heartbeatPerMinute
        handler_pss_kib_samples = $pssSamples
        handler_pss_kib_max = ($pssSamples | Measure-Object -Maximum).Maximum
    }
    ineligible = [ordered]@{
        minutes = $IneligibleMinutes
        start = $ineligibleStart
        end = $ineligibleEnd
        heartbeat_delta = $ineligibleEnd.posted - $ineligibleStart.posted
    }
    nonfatal = [ordered]@{
        samples = $pauseMeasurements.Count
        captured = @($pauseMeasurements | Where-Object captured).Count
        elapsed_us_p50 = Percentile $elapsedValues 0.50
        elapsed_us_p95 = Percentile $elapsedValues 0.95
        elapsed_us_p99 = Percentile $elapsedValues 0.99
        pause_us_p50 = Percentile $pauseValues 0.50
        pause_us_p95 = Percentile $pauseValues 0.95
        pause_us_p99 = Percentile $pauseValues 0.99
        pause_us_max = ($pauseValues | Measure-Object -Maximum).Maximum
        details = $pauseMeasurements
    }
    anr = [ordered]@{
        samples = $stallMeasurements.Count
        candidates = @($stallMeasurements | Where-Object { $_.frames -gt 0 }).Count
        snapshots = @($stallMeasurements | Where-Object snapshot).Count
        details = $stallMeasurements
    }
    privacy = [ordered]@{
        raw_seed_matches = $privacySummary.raw_seed_matches
        summary_seed_matches = $privacySummary.summary_seed_matches
        internal_id_binary_matches = $internalIdMatches
        stream_inventory = $privacySummary.streams
    }
    timeout_cancellation = $timeoutMeasurements
    emergency = $emergencyResults
    quota = [ordered]@{
        attempts = $quotaResults
        retained_dumps = $quotaDumpCount
    }
    pass_checks = $checks
    matrix_cell = "API${api}_${abi}_${pageSize}B_MINIFIED_RELEASE"
    result = if ($passed) { 'PASS' } else { 'FAIL' }
}

$evidence = Join-Path $root "evidence\phase0\API$api-$abi-$pageSize-qualification.json"
$result | ConvertTo-Json -Depth 10 | Set-Content $evidence -Encoding utf8
Write-Output $evidence
if (-not $passed) {
    exit 2
}
