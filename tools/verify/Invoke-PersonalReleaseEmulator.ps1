param(
    [Parameter(Mandatory)]
    [string] $Serial,
    [int] $ExpectedApi = 36,
    [int] $ExpectedPageSize = 4096,
    [string] $ProbeHost = '10.0.2.2',
    [ValidateRange(1, 65535)]
    [int] $ProbePort = 9,
    [string] $Output,
    [switch] $SkipBuild,
    [switch] $SkipHostChecks,
    [switch] $RunHostBlockedEgress,
    [switch] $FullDiagnosticSuite
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalReleaseRunnerSupport.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gradle = Join-Path $root 'gradlew.bat'
$scenarioPath = Join-Path $root 'tooling\fixtures\personal-release-scenarios.json'
$scenarioManifest = Get-Content $scenarioPath -Raw | ConvertFrom-Json
$inventoryIds = @($scenarioManifest.scenarios | ForEach-Object id)
$personalReleaseIds = @($scenarioManifest.personal_release_required)
if ($personalReleaseIds.Count -eq 0) {
    throw 'Scenario manifest has no personal-release-required scenarios'
}
$unknownPersonalReleaseIds = @(
    $personalReleaseIds | Where-Object { $_ -notin $inventoryIds }
)
if ($unknownPersonalReleaseIds) {
    throw (
        'Personal-release-required scenario IDs are absent from the inventory: ' +
        ($unknownPersonalReleaseIds -join ', ')
    )
}
$requiredIds = if ($FullDiagnosticSuite) {
    $inventoryIds
} else {
    $personalReleaseIds
}
$noInternetPackage = 'dev.tracebox.phase0'
$hostNetworkPackage = 'dev.tracebox.phase0.hostnetwork'
$productionActivity = 'dev.tracebox.phase0.MainActivity'
$labPackageActivity = 'dev.tracebox.phase0.LabPackageActivity'
$directBootPin = '246810'
$tag = 'TraceboxLab'
$results = [Collections.Generic.List[object]]::new()
$hostGates = [Collections.Generic.List[object]]::new()
$script:deletedPayloadPaths = @()
$started = (Get-Date).ToUniversalTime()

if (-not $Output) {
    $Output = Join-Path $root (
        'evidence\personal-release\API{0}-x86_64-{1}-{2}.json' -f
            $ExpectedApi,
            $ExpectedPageSize,
            $started.ToString('yyyyMMdd-HHmmss')
    )
}

$sourceBaseCommitOutput = & git -C $root rev-parse HEAD
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to capture the source base commit before validation'
}
$sourceBaseCommit = ($sourceBaseCommitOutput -join '').Trim()
$sourceState = Get-RepositorySourceState -Root $root
$sourcePatchSha256 = $sourceState.sha256
$scenarioManifestSha256 =
    (Get-FileHash $scenarioPath -Algorithm SHA256).Hash.ToLowerInvariant()

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)] [string[]] $Arguments)
    $output = & adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Clear-DeviceLog {
    Invoke-Adb logcat '-b' main '-b' system '-b' crash '-b' events '-c' | Out-Null
}

function Get-LabLog {
    return @(
        Invoke-Adb logcat '-d' '-v' brief |
            Select-String $tag |
            ForEach-Object ToString
    )
}

function Wait-Log {
    param(
        [string] $Pattern,
        [int] $TimeoutSeconds = 20
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $match = Get-LabLog |
            Select-String $Pattern |
            Select-Object -Last 1
        if ($match) {
            return $match.ToString()
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "Timed out waiting for device log: $Pattern"
}

function Wait-ProductionReadiness {
    param([int] $TimeoutSeconds = 30)
    $line = Wait-Log (
        'scenario_result id=INSTALL\.READINESS outcome=(PASS|FAIL) ' +
        'readiness=[A-Z_]+ health=[A-Z_]+'
    ) $TimeoutSeconds
    if ($line -notmatch 'outcome=PASS readiness=DURABLE health=READY') {
        throw "Tracebox did not reach durable production readiness: $line"
    }
    return $line
}

function Wait-AndroidAnr {
    param(
        [string] $Package,
        [int] $TimeoutSeconds = 30
    )
    $pattern =
        'am_anr.*\[\d+,\d+,' +
        [regex]::Escape($Package) +
        ','
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $match =
            Invoke-Adb logcat '-b' events '-d' '-v' brief |
                Select-String $pattern |
                Select-Object -Last 1
        if ($match) { return $match.ToString() }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "Android did not classify the stalled process as ANR: $Package"
}

function Start-LabAction {
    param(
        [string] $Package,
        [string] $Scenario,
        [string] $Action,
        [switch] $Wait,
        [switch] $WithParticipant
    )
    $arguments = @(
        'shell', 'am', 'start'
    )
    if ($Wait) { $arguments += '-W' }
    $arguments += @(
        '-n', "$Package/$productionActivity",
        '--es', 'tracebox.scenario_id', $Scenario,
        '--es', 'tracebox.action', $Action
    )
    if ($WithParticipant) {
        $arguments += @('--ez', 'tracebox.start_participant', 'true')
    }
    Invoke-Adb @arguments | Out-Null
}

function Start-ProductionFixtureAction {
    param(
        [string] $Package,
        [string] $Scenario,
        [string] $Action
    )
    Invoke-Adb shell am start '-W' `
        '-n' "$Package/$labPackageActivity" `
        '--es' tracebox.scenario_id $Scenario `
        '--es' tracebox.action $Action | Out-Null
}

function Start-ProductionFixtureActionAsync {
    param(
        [string] $Package,
        [string] $Scenario,
        [string] $Action
    )
    Invoke-Adb shell am start `
        '-n' "$Package/$labPackageActivity" `
        '--es' tracebox.scenario_id $Scenario `
        '--es' tracebox.action $Action | Out-Null
}

function Get-AppPid {
    param([string] $ProcessName)
    $output = & adb -s $Serial shell pidof $ProcessName 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $output) {
        return 0
    }
    $first = (($output -join ' ').Trim() -split '\s+')[0]
    return [int]$first
}

function Get-TopResumedActivity {
    return ((Invoke-Adb shell dumpsys activity top-resumed) -join ' ').Trim()
}

function Wait-AppPidGone {
    param(
        [string] $ProcessName,
        [int] $OriginalPid,
        [int] $TimeoutSeconds = 20
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $current = Get-AppPid $ProcessName
        if ($current -eq 0 -or $current -ne $OriginalPid) {
            return
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "Process did not terminate: $ProcessName PID=$OriginalPid"
}

function Test-DeviceSocket {
    param([string] $Path)
    & adb -s $Serial shell test '-S' $Path 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Test-ListeningDeviceSocket {
    param([string] $Path)
    if (-not (Test-DeviceSocket $Path)) { return $false }
    $canonicalOutput = & adb -s $Serial shell readlink '-f' $Path 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $canonicalOutput) { return $false }
    $canonicalPath = (($canonicalOutput -join '').Trim())
    if (-not $canonicalPath) { return $false }
    $listenerPaths = @($Path, $canonicalPath)
    $listenerPaths += @(
        $listenerPaths |
            ForEach-Object { $_ -replace '^/data/user/0/', '/data/data/' }
    )
    $listenerPaths = @($listenerPaths | Where-Object { $_ } | Sort-Object -Unique)
    $entries = & adb -s $Serial shell cat /proc/net/unix 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $entries) { return $false }
    foreach ($candidatePath in $listenerPaths) {
        $listener = $entries |
            Select-String "$([regex]::Escape($candidatePath))\s*$" |
            Select-Object -First 1
        if ($listener) { return $true }
    }
    return $false
}

function Get-TraceboxHandlerDumps {
    param([string] $Package)
    $nativeRoot = "/data/user/0/$Package/no_backup/tracebox/native-handler"
    $output = @(
        & adb -s $Serial shell find "$nativeRoot/crashpad-db/pending" `
            '-type' f '-name' '*.dmp' 2>$null
        & adb -s $Serial shell find "$nativeRoot/tracebox-handler-handoff" `
            '-type' f '-name' '*.dmp' 2>$null
    )
    return @(
        $output |
            Where-Object {
                if ($_ -match '/tracebox-handler-handoff/[0-9a-f]{64}\.dmp$') {
                    return $true
                }
                if ($_ -notmatch (
                        '/crashpad-db/pending/' +
                        '[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\.dmp$'
                    )
                ) {
                    return $false
                }
                $metadata = $_ -replace '\.dmp$', '.meta'
                $lock = $_ -replace '\.dmp$', '.lock'
                & adb -s $Serial shell test '-f' $metadata 2>$null | Out-Null
                if ($LASTEXITCODE -ne 0) { return $false }
                $metadataBytes =
                    ((& adb -s $Serial shell stat '-c' '%s' $metadata 2>$null) -join '').Trim()
                & adb -s $Serial shell test '-e' $lock 2>$null | Out-Null
                return $metadataBytes -eq '32' -and $LASTEXITCODE -ne 0
            } |
            Sort-Object -Unique
    )
}

function Get-TraceboxCrashpadPendingEntries {
    param([string] $Package)
    $root = (
        "/data/user/0/$Package/no_backup/tracebox/native-handler/" +
        "crashpad-db/pending"
    )
    $output = & adb -s $Serial shell find $root '-type' f 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $output) {
        return @()
    }
    return @($output | Where-Object { $_ } | Sort-Object -Unique)
}

function Get-TraceboxSegmentFingerprints {
    param([string] $Package)
    $root = "/data/user/0/$Package/no_backup/tracebox"
    $output = & adb -s $Serial shell find $root '-type' f '-name' '*.tbseg' 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $output) {
        return @()
    }
    return @(
        $output |
            Where-Object { $_ } |
            Sort-Object -Unique |
            ForEach-Object { "$_=$(Get-DeviceFileSha256 $_)" }
    )
}

function Get-ProductionHandlerSocket {
    param([string] $Package)
    return (
        "/data/user/0/$Package/no_backup/tracebox/" +
        "native-handler/tracebox-handler.sock"
    )
}

function Wait-ProductionHandlerReady {
    param(
        [string] $Package,
        [int] $PreviousPid = 0,
        [int] $TimeoutSeconds = 30
    )
    $processName = "$Package`:tracebox_handler"
    $socket = Get-ProductionHandlerSocket $Package
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $currentHandlerPid = Get-AppPid $processName
        if (
            $currentHandlerPid -ne 0 -and
            ($PreviousPid -eq 0 -or $currentHandlerPid -ne $PreviousPid) -and
            (Test-ListeningDeviceSocket $socket)
        ) {
            return $currentHandlerPid
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw (
        "Production handler did not become ready: process=$processName " +
        "previous=$PreviousPid socket=$socket"
    )
}

function Wait-TraceboxSegmentChange {
    param(
        [string] $Package,
        [string[]] $Before,
        [int] $TimeoutSeconds = 20
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $current = @(Get-TraceboxSegmentFingerprints $Package)
        if (@($current | Where-Object { $_ -notin $Before }).Count -gt 0) {
            return $current
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw 'Production Tracebox segments did not record observable progress'
}

function Assert-TraceboxSegmentsStable {
    param(
        [string] $Package,
        [string[]] $Before,
        [int] $WindowSeconds
    )
    Start-Sleep -Seconds $WindowSeconds
    $after = @(Get-TraceboxSegmentFingerprints $Package)
    if (
        $after.Count -ne $Before.Count -or
        @($after | Where-Object { $_ -notin $Before }).Count -gt 0
    ) {
        throw (
            "Production diagnostic segments changed during a no-candidate window: " +
            "before=$($Before -join ',') after=$($after -join ',')"
        )
    }
    return $after
}

function Wait-NewHandlerDump {
    param(
        [string] $Package,
        [string[]] $Before,
        [int] $TimeoutSeconds = 20
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $current = @(Get-TraceboxHandlerDumps $Package)
        $added = @($current | Where-Object { $_ -notin $Before })
        if ($added.Count -ge 1) {
            return $added[0]
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw 'Timed out waiting for the background native crash handler dump'
}

function Wait-RecoveredSegment {
    param(
        [string] $Package,
        [string] $SourceDump,
        [string[]] $Before,
        [int] $TimeoutSeconds = 30
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        & adb -s $Serial shell test '-f' $SourceDump 2>$null | Out-Null
        $sourceExists = $LASTEXITCODE -eq 0
        $current = @(Get-TraceboxSegmentFingerprints $Package)
        $changed = @($current | Where-Object { $_ -notin $Before })
        if (-not $sourceExists -and $changed.Count -ge 1) {
            return $changed[0]
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw 'Background crash dump was not retired into a changed durable segment after restart'
}

function Wait-CrashpadPendingRetired {
    param(
        [string] $Package,
        [string[]] $Before,
        [int] $TimeoutSeconds = 10
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $current = @(Get-TraceboxCrashpadPendingEntries $Package)
        $added = @($current | Where-Object { $_ -notin $Before })
        if ($added.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw 'Crashpad pending report or metadata sidecar survived durable restart ingestion'
}

function Complete-FatalCaptureAndRestart {
    param(
        [string] $Scenario,
        [string] $Action,
        [string[]] $BeforeDumps,
        [string[]] $BeforePendingEntries,
        [string[]] $BeforeSegments,
        [int] $TimeoutSeconds,
        [switch] $RequireHandlerDump
    )
    $newDumps = @()
    $newSegments = @()
    $evidenceTimer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $currentDumps = @(Get-TraceboxHandlerDumps $noInternetPackage)
        $newDumps = @($currentDumps | Where-Object { $_ -notin $BeforeDumps })
        $currentSegments = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
        $newSegments = @($currentSegments | Where-Object { $_ -notin $BeforeSegments })
        $evidenceReady =
            if ($RequireHandlerDump) {
                $newDumps.Count -gt 0
            } else {
                $newDumps.Count -gt 0 -or $newSegments.Count -gt 0
            }
        if ($evidenceReady) { break }
        Start-Sleep -Milliseconds 100
    } while ($evidenceTimer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    if ($RequireHandlerDump -and $newDumps.Count -eq 0) {
        throw (
            "Native fixture fault terminated without a new handler dump: " +
            "scenario=$Scenario action=$Action"
        )
    }
    if ($newDumps.Count -eq 0 -and $newSegments.Count -eq 0) {
        throw (
            "Fatal action terminated without durable Tracebox evidence: " +
            "scenario=$Scenario action=$Action"
        )
    }

    $dumpEvidence = 'none'
    $dump = $null
    if ($newDumps.Count -gt 0) {
        $dump = $newDumps[0]
        $dumpEvidence = "$dump=$(Get-DeviceFileSha256 $dump)"
    }

    Clear-DeviceLog
    Start-LabAction $noInternetPackage $Scenario 'recover' -Wait
    if ($dump) {
        $recoveredSegment =
            Wait-RecoveredSegment $noInternetPackage $dump $BeforeSegments 45
    } else {
        $afterRestart = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
        $durableAfterRestart = @(
            $afterRestart | Where-Object { $_ -notin $BeforeSegments }
        )
        if ($durableAfterRestart.Count -eq 0) {
            throw "Managed fault evidence did not remain durable after restart: $Scenario"
        }
        $recoveredSegment = $durableAfterRestart[0]
    }
    $recoveryHandlerPid = Wait-ProductionHandlerReady $noInternetPackage 0 45
    Clear-DeviceLog
    Start-LabAction $noInternetPackage $Scenario 'readiness' -Wait
    $restartReadiness = Wait-ProductionReadiness 30
    Wait-CrashpadPendingRetired $noInternetPackage $BeforePendingEntries

    return [pscustomobject]@{
        dumpEvidence = $dumpEvidence
        changedSegmentCount = $newSegments.Count
        restartSegment = $recoveredSegment
        recoveryHandlerPid = $recoveryHandlerPid
        restartReadiness = $restartReadiness
    }
}

function Assert-ProductionFixtureFaultCapture {
    param(
        [string] $Scenario,
        [string] $Action,
        [int] $TimeoutSeconds = 30
    )
    Reset-And-Launch -ClearData
    $processName = $noInternetPackage
    $mainPid = Get-AppPid $processName
    if ($mainPid -eq 0) { throw 'Production process was not running before fixture fault' }
    $handlerPid = Wait-ProductionHandlerReady $noInternetPackage
    $beforeDumps = @(Get-TraceboxHandlerDumps $noInternetPackage)
    $beforePendingEntries = @(Get-TraceboxCrashpadPendingEntries $noInternetPackage)
    $beforeSegments = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    Clear-DeviceLog
    $captureTimer = [Diagnostics.Stopwatch]::StartNew()
    Start-ProductionFixtureActionAsync $noInternetPackage $Scenario $Action
    $armed = Wait-Log "scenario_fault_armed id=$([regex]::Escape($Scenario)) .*policy=SUCCESS" 20
    Wait-AppPidGone $processName $mainPid $TimeoutSeconds
    $terminationMillis = $captureTimer.ElapsedMilliseconds
    $capture = Complete-FatalCaptureAndRestart `
        -Scenario $Scenario `
        -Action $Action `
        -BeforeDumps $beforeDumps `
        -BeforePendingEntries $beforePendingEntries `
        -BeforeSegments $beforeSegments `
        -TimeoutSeconds $TimeoutSeconds `
        -RequireHandlerDump
    return (
        "$armed terminated_pid=$mainPid handler_pid=$handlerPid termination_ms=$terminationMillis " +
        "new_dump=$($capture.dumpEvidence) " +
        "changed_segments=$($capture.changedSegmentCount) " +
        "restart_segment=$($capture.restartSegment) " +
        "recovery_handler=$($capture.recoveryHandlerPid) " +
        "restart_readiness='$($capture.restartReadiness)'"
    )
}

function Get-DeviceFileSha256 {
    param([string] $Path)
    $output = (Invoke-Adb shell sha256sum $Path) -join ' '
    if ($output -notmatch '^([0-9a-fA-F]{64})\s') {
        throw "Cannot parse device SHA-256 for $Path`: $output"
    }
    return $Matches[1].ToLowerInvariant()
}

function Reset-And-Launch {
    param(
        [string] $Package = $noInternetPackage,
        [switch] $ClearData,
        [switch] $WithParticipant
    )
    Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
    Invoke-Adb shell am force-stop $Package | Out-Null
    if ($ClearData) {
        Invoke-Adb shell pm clear $Package | Out-Null
    }
    Clear-DeviceLog
    Start-LabAction `
        $Package 'INSTALL.READINESS' 'readiness' `
        -Wait `
        -WithParticipant:$WithParticipant
    Wait-ProductionReadiness 30 | Out-Null
    if ($WithParticipant) {
        Wait-Log 'production_participant_ready=true readiness=DURABLE health=READY' 30 |
            Out-Null
    }
}

function Add-ScenarioResult {
    param(
        [string] $Id,
        [string] $Outcome,
        [object] $Evidence
    )
    $results.Add([pscustomobject]@{
        id = $Id
        outcome = $Outcome
        evidence = @($Evidence)
    })
}

function Invoke-CertScenario {
    param(
        [string] $Id,
        [scriptblock] $Body
    )
    if ($Id -notin $requiredIds) {
        return
    }
    try {
        $evidence = @(& $Body)
        Add-ScenarioResult $Id 'PASS' $evidence
    } catch {
        Add-ScenarioResult $Id 'FAIL' $_.Exception.Message
    }
}

function Assert-ProcessDeathAction {
    param(
        [string] $Scenario,
        [string] $Action,
        [int] $TimeoutSeconds = 20,
        [string] $RequiredPreDeathMarker,
        [switch] $RequireHandlerDump
    )
    Reset-And-Launch -ClearData
    $processName = $noInternetPackage
    $mainPid = Get-AppPid $processName
    if ($mainPid -eq 0) { throw 'Main process was not running before fatal action' }
    $handlerPid = Wait-ProductionHandlerReady $noInternetPackage
    $beforeDumps = @(Get-TraceboxHandlerDumps $noInternetPackage)
    $beforePendingEntries = @(Get-TraceboxCrashpadPendingEntries $noInternetPackage)
    $beforeSegments = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    Clear-DeviceLog
    $captureTimer = [Diagnostics.Stopwatch]::StartNew()
    Start-LabAction $noInternetPackage $Scenario $Action
    $preDeathEvidence = 'not_required'
    $fatalEvidenceBeforeSegments = $beforeSegments
    if ($RequiredPreDeathMarker) {
        $preDeathEvidence = Wait-Log $RequiredPreDeathMarker $TimeoutSeconds
        # Exclude the explicitly persisted pre-death record from the fatal-capture proof.
        # The ensuing process death must still add raw or managed evidence of its own.
        $fatalEvidenceBeforeSegments =
            @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    }
    Wait-AppPidGone $processName $mainPid $TimeoutSeconds
    $terminationMillis = $captureTimer.ElapsedMilliseconds
    $capture = Complete-FatalCaptureAndRestart `
        -Scenario $Scenario `
        -Action $Action `
        -BeforeDumps $beforeDumps `
        -BeforePendingEntries $beforePendingEntries `
        -BeforeSegments $fatalEvidenceBeforeSegments `
        -TimeoutSeconds $TimeoutSeconds `
        -RequireHandlerDump:$RequireHandlerDump
    return (
        "terminated_pid=$mainPid handler_pid=$handlerPid termination_ms=$terminationMillis " +
        "pre_death_evidence='$preDeathEvidence' new_dump=$($capture.dumpEvidence) " +
        "changed_segments=$($capture.changedSegmentCount) " +
        "restart_segment=$($capture.restartSegment) " +
        "recovery_handler=$($capture.recoveryHandlerPid) " +
        "restart_readiness='$($capture.restartReadiness)'"
    )
}

# LegacyPhase0Activity, HandlerService, WorkerService, and FaultReceiver remain as an explicitly
# historical phase-0 lane. The personal-release gate below never launches those components.

function Wait-BootCompleted {
    Invoke-Adb wait-for-device | Out-Null
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $completed = ((Invoke-Adb shell getprop sys.boot_completed) -join '').Trim()
        if ($completed -eq '1') { return }
        Start-Sleep -Milliseconds 500
    } while ($timer.Elapsed.TotalSeconds -lt 180)
    throw 'Emulator did not finish boot within 180 seconds'
}

function Assert-AdbRoot {
    param([string] $Context = 'personal-release preflight')

    $rootOutput = @()
    $rootExitCode = -1
    try {
        $rootOutput = @(& adb -s $Serial root 2>&1)
        $rootExitCode = $LASTEXITCODE
    } catch {
        $rootOutput += $_.Exception.Message
        if ($LASTEXITCODE -is [int]) {
            $rootExitCode = $LASTEXITCODE
        }
    }

    $rootDetail = ($rootOutput -join [Environment]::NewLine).Trim()
    if ($rootExitCode -ne 0) {
        throw (
            'Tracebox personal-release validation requires a rootable adb emulator image. ' +
            "adb root failed during $Context with exit code $rootExitCode. " +
            'Use an AOSP or Google APIs userdebug image; Google Play images commonly ' +
            "disable adb root. adb root output: $rootDetail"
        )
    }

    try {
        # adb root can restart adbd. Do not trust its exit code or success text:
        # reconnect, wait for Android to remain boot-complete, and inspect the shell UID.
        Wait-BootCompleted
        $uid = ((Invoke-Adb shell id '-u') -join '').Trim()
    } catch {
        throw (
            'Tracebox personal-release validation requires a rootable adb emulator image. ' +
            "adbd did not reconnect as root during $Context. " +
            'Use an AOSP or Google APIs userdebug image; Google Play images commonly ' +
            "disable adb root. adb root output: $rootDetail. " +
            "Reconnect failure: $($_.Exception.Message)"
        )
    }

    if ($uid -ne '0') {
        throw (
            'Tracebox personal-release validation requires a rootable adb emulator image. ' +
            "adb shell id -u returned '$uid' after adb root and reconnect during $Context. " +
            'Use an AOSP or Google APIs userdebug image; Google Play images commonly ' +
            "disable adb root. adb root output: $rootDetail"
        )
    }
    return $uid
}

function Get-UserUnlocked {
    $state = ((Invoke-Adb shell getprop sys.user.0.ce_available) -join '').Trim().ToLowerInvariant()
    if ($state -in @('true', 'false')) {
        return $state -eq 'true'
    }

    $userState = @(
        Invoke-Adb shell dumpsys user |
            Select-String '^\s*(?:State:|Started users state:).*RUNNING_(UNLOCKED|LOCKED)' |
            ForEach-Object ToString
    ) -join ' '
    if ($userState -match 'RUNNING_UNLOCKED') { return $true }
    if ($userState -match 'RUNNING_LOCKED') { return $false }

    throw (
        'Cannot parse credential-encrypted storage state: ' +
        "sys.user.0.ce_available=$state dumpsys=$userState"
    )
}

function Wait-UserUnlocked {
    param(
        [bool] $Expected,
        [int] $TimeoutSeconds = 45
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Get-UserUnlocked) -eq $Expected) { return }
        Start-Sleep -Milliseconds 250
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "User-unlocked state did not become $Expected within $TimeoutSeconds seconds"
}

function Unlock-DeviceWithPin {
    param([string] $Pin)
    Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_MENU | Out-Null
    Invoke-Adb shell wm dismiss-keyguard | Out-Null
    Invoke-Adb shell input swipe 500 1800 500 500 250 | Out-Null
    Invoke-Adb shell input text $Pin | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_ENTER | Out-Null
    Wait-UserUnlocked -Expected $true
}

function Get-TraceboxDiagnosticPayloadFiles {
    param([string] $Package)
    $paths = @(
        "/data/user/0/$Package/no_backup/tracebox",
        "/data/user_de/0/$Package/no_backup/tracebox-directboot"
    )
    $found = @()
    foreach ($path in $paths) {
        $output = & adb -s $Serial shell find $path '-type' f 2>$null
        if ($LASTEXITCODE -eq 0 -and $output) {
            $found += @(
                $output |
                    Where-Object {
                        $_ -match '\.(tbseg|tbraw|tbsummary|tbstaging|tbdiag|dmp)$'
                    }
            )
        }
    }
    return @($found | Sort-Object -Unique)
}

function Get-UiHierarchy {
    Invoke-Adb shell uiautomator dump /sdcard/tracebox-window.xml | Out-Null
    return (Invoke-Adb shell cat /sdcard/tracebox-window.xml) -join ''
}

function Invoke-UiText {
    param(
        [string] $Xml,
        [string[]] $Text
    )
    foreach ($candidate in $Text) {
        $escaped = [Security.SecurityElement]::Escape($candidate)
        $node = [regex]::Match(
            $Xml,
            "<node[^>]*(?:text|content-desc)=""$([regex]::Escape($escaped))""[^>]*/>"
        )
        if (-not $node.Success) { continue }
        $bounds = [regex]::Match(
            $node.Value,
            'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        )
        if (-not $bounds.Success) { continue }
        $x = ([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2
        $y = ([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2
        Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
        return
    }
    throw "None of the requested UI nodes was present: $($Text -join ', ')"
}

function Invoke-UiResourceId {
    param(
        [string] $Xml,
        [string] $ResourceId
    )
    $node = [regex]::Match(
        $Xml,
        "<node[^>]*resource-id=""$([regex]::Escape($ResourceId))""[^>]*/>"
    )
    if (-not $node.Success) { return $false }
    $bounds = [regex]::Match(
        $node.Value,
        'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    )
    if (-not $bounds.Success) { return $false }
    $x = ([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2
    $y = ([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2
    Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
    return $true
}

function Wait-AnrTermination {
    param(
        [string] $Package,
        [int] $OriginalPid,
        [int] $TimeoutSeconds = 45
    )
    $closeLabels = @('Close app', 'CLOSE APP')
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $currentPid = Get-AppPid $Package
        if ($currentPid -eq 0 -or $currentPid -ne $OriginalPid) {
            return "anr_auto_terminated=true elapsed_ms=$($timer.ElapsedMilliseconds)"
        }
        try {
            $xml = Get-UiHierarchy
            if (Invoke-UiResourceId $xml 'android:id/aerr_close') {
                return "anr_dialog_close=true selector=resource_id elapsed_ms=$($timer.ElapsedMilliseconds)"
            }
            if (
                $closeLabels |
                    Where-Object {
                        $escaped = [Security.SecurityElement]::Escape($_)
                        $xml -match (
                            '(?:text|content-desc)="' +
                            [regex]::Escape($escaped) +
                            '"'
                        )
                    }
            ) {
                Invoke-UiText $xml $closeLabels
                return "anr_dialog_close=true selector=text elapsed_ms=$($timer.ElapsedMilliseconds)"
            }
        } catch {
            # UIAutomator can briefly fail while the system ANR surface replaces the app window.
        }
        Start-Sleep -Milliseconds 250
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    $windows = (
        & adb -s $Serial shell dumpsys window windows 2>$null |
            Select-String '(?i)(not responding|anr|application error)' |
            ForEach-Object ToString
    ) -join ' '
    throw "Timed out waiting for Android to terminate the confirmed ANR: $windows"
}

function Get-OptionalDeviceFileFingerprint {
    param([string] $Path)
    & adb -s $Serial shell test '-f' $Path 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { return 'absent' }
    return "$Path=$(Get-DeviceFileSha256 $Path)"
}

function Invoke-PackageUi {
    param(
        [string] $Scenario,
        [switch] $Approve
    )
    $approveLabels = @('Approve package', 'APPROVE PACKAGE')
    Reset-And-Launch -ClearData
    Clear-DeviceLog
    Invoke-Adb shell am start '-W' `
        '-n' "$noInternetPackage/$labPackageActivity" `
        '--es' tracebox.scenario_id $Scenario | Out-Null
    Wait-Log "scenario_ready id=$([regex]::Escape($Scenario))" 30 | Out-Null

    $timer = [Diagnostics.Stopwatch]::StartNew()
    $xml = ''
    $approveVisible = $false
    do {
        $xml = Get-UiHierarchy
        $approveVisible = [bool](
            $approveLabels |
                Where-Object {
                    $escaped = [Security.SecurityElement]::Escape($_)
                    $xml -match (
                        '(?:text|content-desc)="' +
                        [regex]::Escape($escaped) +
                        '"'
                    )
                } |
                Select-Object -First 1
        )
        if ($approveVisible) { break }
        Start-Sleep -Milliseconds 250
    } while ($timer.Elapsed.TotalSeconds -lt 15)
    if (-not $xml.Contains('Included values:') -or -not $xml.Contains('SHA-256:')) {
        throw 'Exact disclosure facts were not visible in the Tracebox-owned UI'
    }
    if (-not $approveVisible) {
        throw 'Tracebox approval action was not visible in the disclosure UI'
    }

    if (-not $Approve) {
        Invoke-Adb shell input keyevent BACK | Out-Null
    } else {
        Invoke-UiText $xml $approveLabels
    }
    if ($Scenario -eq 'PACKAGE.SAVE_SHARE' -and $Approve) {
        $savePicker =
            Wait-Log 'scenario_save_picker id=PACKAGE\.SAVE_SHARE expected_bytes=\d+' 20
        $timer.Restart()
        do {
            $xml = Get-UiHierarchy
            if ($xml -match '(?:text|content-desc)="(?:Save|SAVE)"') { break }
            Start-Sleep -Milliseconds 250
        } while ($timer.Elapsed.TotalSeconds -lt 15)
        Invoke-UiText $xml @('Save', 'SAVE')

        $handoff =
            Wait-Log (
                'scenario_share_handoff id=PACKAGE\.SAVE_SHARE saved_bytes=(\d+) ' +
                'expected_bytes=(\d+) digest_match=true receipt=CHOOSER_OPENED'
            ) 30
        if (
            $handoff -notmatch 'saved_bytes=(\d+) expected_bytes=(\d+)' -or
            [long]$Matches[1] -le 0 -or
            [long]$Matches[1] -ne [long]$Matches[2]
        ) {
            throw "SAF copy did not prove exact non-empty bytes: $handoff"
        }

        $timer.Restart()
        $chooser = ''
        try {
            do {
                $chooser = Get-TopResumedActivity
                if ($chooser -match '(ChooserActivity|ResolverActivity)') { break }
                Start-Sleep -Milliseconds 250
            } while ($timer.Elapsed.TotalSeconds -lt 15)
            if ($chooser -notmatch '(ChooserActivity|ResolverActivity)') {
                throw "Android Sharesheet was not observably resumed: $chooser"
            }
        } finally {
            & adb -s $Serial shell input keyevent BACK 2>$null | Out-Null
        }
        $completed =
            Wait-Log (
                'scenario_result id=PACKAGE\.SAVE_SHARE outcome=PASS ' +
                'saved_bytes=\d+ expected_bytes=\d+ digest_match=true ' +
                'chooser_returned=true receipt=CHOOSER_OPENED staging_deleted=true'
            ) 20
        return "$savePicker $handoff chooser=$chooser $completed"
    }
    return Wait-Log "scenario_result id=$([regex]::Escape($Scenario)) outcome=PASS" 20
}

function Get-AppUid {
    param([string] $Package)
    $rows =
        Invoke-Adb shell pm list packages '-U' '--user' '0' $Package
    return ConvertFrom-PmPackageUid -Package $Package -Lines $rows
}

function Invoke-WithUidPacketCounter {
    param(
        [string] $Package,
        [string] $Chain,
        [scriptblock] $Body,
        [switch] $Drop
    )
    Assert-AdbRoot -Context "UID packet counter for $Package" | Out-Null
    $uid = Get-AppUid $Package
    & adb -s $Serial shell iptables '-D' OUTPUT '-m' owner '--uid-owner' $uid '-j' $Chain 2>$null | Out-Null
    & adb -s $Serial shell iptables '-F' $Chain 2>$null | Out-Null
    & adb -s $Serial shell iptables '-X' $Chain 2>$null | Out-Null
    try {
        $target = if ($Drop) { 'DROP' } else { 'RETURN' }
        Invoke-Adb shell iptables '-N' $Chain | Out-Null
        Invoke-Adb shell iptables '-A' $Chain '-j' $target | Out-Null
        Invoke-Adb shell iptables '-I' OUTPUT 1 '-m' owner '--uid-owner' $uid '-j' $Chain | Out-Null
        Invoke-Adb shell iptables '-Z' $Chain | Out-Null
        & $Body
        Start-Sleep -Milliseconds 500
        $listing = Invoke-Adb shell iptables '-L' $Chain '-n' '-v' '-x'
        $packets = 0L
        foreach ($line in $listing) {
            if ($line -match "^\s*(\d+)\s+\d+\s+$target\b") {
                $packets += [long]$Matches[1]
            }
        }
        return $packets
    } finally {
        & adb -s $Serial shell iptables '-D' OUTPUT '-m' owner '--uid-owner' $uid '-j' $Chain 2>$null | Out-Null
        & adb -s $Serial shell iptables '-F' $Chain 2>$null | Out-Null
        & adb -s $Serial shell iptables '-X' $Chain 2>$null | Out-Null
    }
}

function Get-ProcessJiffies {
    param([int] $ProcessId)
    $stat = (Invoke-Adb shell cat "/proc/$ProcessId/stat") -join ''
    if ($stat -notmatch '^\d+ \(.+\) (.+)$') {
        throw "Cannot parse /proc/$ProcessId/stat"
    }
    $fields = $Matches[1] -split ' '
    return [long]$fields[11] + [long]$fields[12]
}

function Get-ProcessSchedulerWakeups {
    param([int] $ProcessId)
    $schedule = & adb -s $Serial shell cat "/proc/$ProcessId/sched" 2>$null
    if ($LASTEXITCODE -eq 0 -and $schedule) {
        $line = $schedule |
            Select-String '^\s*nr_wakeups\s*:\s*(\d+)\s*$' |
            Select-Object -First 1
        if ($line -and $line -match 'nr_wakeups\s*:\s*(\d+)') {
            return [pscustomobject]@{
                value = [long]$Matches[1]
                source = 'proc_sched_nr_wakeups'
            }
        }
    }

    $status = (Invoke-Adb shell cat "/proc/$ProcessId/status") -join "`n"
    $voluntary = [regex]::Match($status, '(?m)^voluntary_ctxt_switches:\s*(\d+)\s*$')
    $involuntary = [regex]::Match(
        $status,
        '(?m)^nonvoluntary_ctxt_switches:\s*(\d+)\s*$'
    )
    if (-not $voluntary.Success -or -not $involuntary.Success) {
        throw "Cannot read scheduler wakeup proxy for PID $ProcessId"
    }
    return [pscustomobject]@{
        value =
            [long]$voluntary.Groups[1].Value +
            [long]$involuntary.Groups[1].Value
        source = 'proc_status_context_switches'
    }
}

function Get-ProcessPssKiB {
    param([int] $ProcessId)
    $line = Invoke-Adb shell dumpsys meminfo $ProcessId |
        Select-String 'TOTAL PSS:\s+(\d+)' |
        Select-Object -First 1
    if (-not $line -or $line -notmatch 'TOTAL PSS:\s+(\d+)') {
        throw "Cannot parse PSS for PID $ProcessId"
    }
    return [int]$Matches[1]
}

function Invoke-CatalogCommand {
    param([ValidateSet('r8', 'elf')] [string] $Kind)
    $catalog = Resolve-Path (
        Join-Path $root (
            'test-apps\phase0-fixture\build\tracebox\' +
            'noInternetQualificationRelease-symbol-catalog.tsv'
        )
    )
    $lines = Get-Content $catalog
    $build = $lines |
        Where-Object { $_.StartsWith("build`t", [StringComparison]::Ordinal) } |
        Select-Object -First 1
    if (-not $build) { throw 'Symbol catalog has no full build identity row' }
    $buildId = ($build -split "`t")[1]

    if (
        -not $script:tbdiagExecutable -or
        -not (Test-Path -LiteralPath $script:tbdiagExecutable -PathType Leaf)
    ) {
        throw 'The mandatory tbdiag build artifact is unavailable'
    }
    if ($Kind -eq 'r8') {
        $row = $lines |
            Where-Object {
                $_.StartsWith("r8`t", [StringComparison]::Ordinal) -and
                ($_ -split "`t")[2] -ne '<identity>'
            } |
            Select-Object -First 1
        if (-not $row) { throw 'Symbol catalog has no concrete R8 mapping row' }
        $fields = $row -split "`t"
        $output = & $script:tbdiagExecutable `
            retrace $catalog $buildId $fields[1] $fields[2] 2>&1
        if ($LASTEXITCODE -ne 0 -or ($output -join '') -notmatch [regex]::Escape($fields[3])) {
            throw "R8 retrace did not resolve exact catalog row: $($output -join ' ')"
        }
        return ($output -join [Environment]::NewLine)
    }
    $row = $lines |
        Where-Object {
            $_.StartsWith("native`t", [StringComparison]::Ordinal) -and
            ($_ -split "`t")[5] -ne 'identity-only'
        } |
        Select-Object -First 1
    if (-not $row) { throw 'Symbol catalog has no concrete ELF symbol row' }
    $fields = $row -split "`t"
    $output = & $script:tbdiagExecutable symbolize `
        $catalog $buildId $fields[3] $fields[1] $fields[2] $fields[4] 2>&1
    if ($LASTEXITCODE -ne 0 -or ($output -join '') -notmatch [regex]::Escape($fields[5])) {
        throw "ELF symbolication did not resolve exact catalog row: $($output -join ' ')"
    }
    return ($output -join [Environment]::NewLine)
}

function Invoke-HostGate {
    param(
        [string] $Name,
        [scriptblock] $Body
    )
    try {
        & $Body | Out-Null
        $hostGates.Add([pscustomobject]@{ name = $Name; outcome = 'PASS' })
    } catch {
        $hostGates.Add([pscustomobject]@{
            name = $Name
            outcome = 'FAIL'
            detail = $_.Exception.Message
        })
    }
}

Invoke-Adb wait-for-device | Out-Null
$api = [int]((Invoke-Adb shell getprop ro.build.version.sdk) -join '')
$pageSize = [int]((Invoke-Adb shell getconf PAGE_SIZE) -join '')
$abi = ((Invoke-Adb shell getprop ro.product.cpu.abi) -join '').Trim()
$emulator = ((Invoke-Adb shell getprop ro.kernel.qemu) -join '').Trim() -eq '1'
if (
    $api -ne $ExpectedApi -or
    $pageSize -ne $ExpectedPageSize -or
    $abi -ne 'x86_64' -or
    -not $emulator
) {
    throw "Endpoint mismatch: API=$api page=$pageSize ABI=$abi emulator=$emulator"
}
Assert-AdbRoot -Context 'initial endpoint preflight' | Out-Null
Wait-UserUnlocked -Expected $true
$needsTbdiag =
    $FullDiagnosticSuite -or
    ($RunHostBlockedEgress -and -not $SkipHostChecks)
if ($needsTbdiag) {
    $script:tbdiagExecutable = Build-TbdiagExecutable -Root $root
}

if (-not $SkipHostChecks) {
    Invoke-HostGate 'static_release_artifacts' {
        & (Join-Path $PSScriptRoot 'Verify-Phase5NoNetworkStatic.ps1') -SkipBuild:$SkipBuild
    }
    Invoke-HostGate 'malicious_corpora' {
        & (Join-Path $PSScriptRoot 'Verify-MaliciousCorpora.ps1')
    }
    if ($RunHostBlockedEgress) {
        Invoke-HostGate 'tbdiag_blocked_egress' {
            & (Join-Path $PSScriptRoot 'Invoke-TbdiagBlockedEgress.ps1') `
                -TbdiagExecutable $script:tbdiagExecutable `
                -AllowFirewallMutation:($env:OS -eq 'Windows_NT')
        }
    }
}

$qualificationTasks = @(
    ':test-apps:phase0-fixture:assembleNoInternetQualificationRelease',
    ':test-apps:phase0-fixture:assembleHostNetworkQualificationRelease',
    ':test-apps:phase0-fixture:captureTraceboxBuildIdentityNoInternetQualificationRelease',
    ':test-apps:phase0-fixture:verifyFixtureRustPanicProbeIsolation'
)
if (-not $SkipBuild) {
    & $gradle @qualificationTasks '--offline' '--no-daemon'
    if ($LASTEXITCODE -ne 0) {
        throw "Emulator fixture build failed with exit code $LASTEXITCODE"
    }
}

function Get-OneApk {
    param([string] $VariantPattern)
    $matchingApks = @(
        Get-ChildItem (
            Join-Path $root 'test-apps\phase0-fixture\build\outputs\apk'
        ) -Recurse -File -Filter '*.apk' |
            Where-Object { $_.FullName -match $VariantPattern }
    )
    if ($matchingApks.Count -ne 1) {
        throw "Expected one APK matching $VariantPattern, found $($matchingApks.Count)"
    }
    return $matchingApks[0]
}

$noInternetApk = Get-OneApk '\\noInternet\\qualificationRelease\\'
$hostNetworkApk = Get-OneApk '\\hostNetwork\\qualificationRelease\\'
$noInternetApkSha256 =
    (Get-FileHash $noInternetApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$hostNetworkApkSha256 =
    (Get-FileHash $hostNetworkApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()

Invoke-Adb install '-r' '-t' $noInternetApk.FullName | Out-Null
Invoke-Adb install '-r' '-t' $hostNetworkApk.FullName | Out-Null
Invoke-Adb shell pm enable $noInternetPackage | Out-Null
Invoke-Adb shell pm enable $hostNetworkPackage | Out-Null

Invoke-CertScenario 'INSTALL.READINESS' {
    Reset-And-Launch -ClearData
    Clear-DeviceLog
    Start-LabAction $noInternetPackage 'INSTALL.READINESS' 'readiness'
    Wait-Log 'scenario_result id=INSTALL\.READINESS outcome=PASS'
}

Invoke-CertScenario 'HANDLER.COLD_START' {
    Reset-And-Launch -ClearData -WithParticipant
    $main = Get-AppPid $noInternetPackage
    $handler = Wait-ProductionHandlerReady $noInternetPackage
    $participant = Get-AppPid "$noInternetPackage`:production_participant"
    if ($main -eq 0 -or $handler -eq 0 -or $participant -eq 0) {
        throw (
            "Expected installed production processes: " +
            "main=$main handler=$handler participant=$participant"
        )
    }
    "main=$main handler=$handler participant=$participant " +
        "socket=$(Get-ProductionHandlerSocket $noInternetPackage)"
}

Invoke-CertScenario 'HANDLER.RUNNING_ATTACH' {
    Reset-And-Launch -ClearData -WithParticipant
    $handler = Wait-ProductionHandlerReady $noInternetPackage
    $participant = Get-AppPid "$noInternetPackage`:production_participant"
    if ($participant -eq 0) {
        throw 'Production participant did not attach to the installed UID handler'
    }
    Start-Sleep -Seconds 2
    $stableHandler = Get-AppPid "$noInternetPackage`:tracebox_handler"
    $stableParticipant = Get-AppPid "$noInternetPackage`:production_participant"
    if (
        $stableHandler -ne $handler -or
        $stableParticipant -ne $participant -or
        -not (Test-DeviceSocket (Get-ProductionHandlerSocket $noInternetPackage))
    ) {
        throw (
            "Production running-attach was not stable: handler=$handler/$stableHandler " +
            "participant=$participant/$stableParticipant"
        )
    }
    "handler=$handler attached_participant=$participant stable=true"
}

Invoke-CertScenario 'HANDLER.CONFLICT' {
    Assert-ProcessDeathAction 'HANDLER.CONFLICT' 'handler_conflict'
}

Invoke-CertScenario 'HANDLER.DEATH' {
    Reset-And-Launch -ClearData
    $main = Get-AppPid $noInternetPackage
    $handlerProcess = "$noInternetPackage`:tracebox_handler"
    $handler = Wait-ProductionHandlerReady $noInternetPackage
    Invoke-Adb shell kill '-9' $handler | Out-Null
    Wait-AppPidGone $handlerProcess $handler
    $mainAfter = Get-AppPid $noInternetPackage
    if ($mainAfter -ne $main) {
        throw "Production client died with its handler: before=$main after=$mainAfter"
    }
    $replacement = Get-AppPid $handlerProcess
    "terminated_handler=$handler immediate_replacement=$replacement client_pid=$mainAfter"
}

Invoke-CertScenario 'HANDLER.RESTART' {
    Reset-And-Launch -ClearData
    $main = Get-AppPid $noInternetPackage
    $handlerProcess = "$noInternetPackage`:tracebox_handler"
    $handler = Wait-ProductionHandlerReady $noInternetPackage
    if ($main -eq 0) {
        throw 'Installed production client is missing'
    }
    Invoke-Adb shell kill '-9' $handler | Out-Null
    Wait-AppPidGone $handlerProcess $handler
    $mainAfter = Get-AppPid $noInternetPackage
    if ($mainAfter -ne $main) {
        throw "The production client died with its handler: main=$main/$mainAfter"
    }
    $replacement = Wait-ProductionHandlerReady $noInternetPackage $handler 30
    Clear-DeviceLog
    Start-LabAction $noInternetPackage 'HANDLER.RESTART' 'policy_barrier'
    $policy =
        Wait-Log 'scenario_result id=MULTIPROCESS\.POLICY_BARRIER outcome=PASS .*standard=SUCCESS' 30
    "$policy main=$main previous_handler=$handler replacement_handler=$replacement"
}

Invoke-CertScenario 'HANDLER.TIMEOUT' {
    Reset-And-Launch -ClearData
    $handler = Wait-ProductionHandlerReady $noInternetPackage
    $line = $null
    try {
        Invoke-Adb shell kill '-STOP' $handler | Out-Null
        Clear-DeviceLog
        Start-ProductionFixtureAction `
            $noInternetPackage 'HANDLER.TIMEOUT' 'handler_timeout_policy'
        $line =
            Wait-Log (
                'scenario_result id=HANDLER\.TIMEOUT outcome=PASS ' +
                'policy=(PARTIAL|FAILED|LOCAL_ONLY_RESTRICTED) elapsed_ms=(\d+)'
            ) 15
        if ($line -notmatch 'elapsed_ms=(\d+)' -or [long]$Matches[1] -gt 10000) {
            throw "Installed production handler did not fail within the bounded timeout: $line"
        }
    } finally {
        & adb -s $Serial shell kill '-CONT' $handler 2>$null | Out-Null
    }
    "$line stopped_handler=$handler"
}

Invoke-CertScenario 'HANDLER.BACKGROUND_LIFETIME' {
    Reset-And-Launch -ClearData
    $mainPid = Get-AppPid $noInternetPackage
    $handlerProcess = "$noInternetPackage`:tracebox_handler"
    $handlerPid = Get-AppPid $handlerProcess
    $handlerSocket = Get-ProductionHandlerSocket $noInternetPackage
    if (
        $mainPid -eq 0 -or
        $handlerPid -eq 0 -or
        -not (Test-ListeningDeviceSocket $handlerSocket)
    ) {
        throw (
            "Production native capture was not ready before backgrounding: " +
            "main=$mainPid handler=$handlerPid " +
            "socket=$(Test-ListeningDeviceSocket $handlerSocket)"
        )
    }
    $beforeDumps = @(Get-TraceboxHandlerDumps $noInternetPackage)
    $beforeSegments = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    $forcedIdle = $false
    try {
        Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
        Invoke-Adb shell am make-uid-idle $noInternetPackage | Out-Null
        Invoke-Adb shell cmd deviceidle force-idle | Out-Null
        $forcedIdle = $true
        Start-Sleep -Seconds 2

        $idleState = ((Invoke-Adb shell cmd deviceidle get deep) -join '').Trim()
        $resumedActivity = Get-TopResumedActivity
        $backgroundMainPid = Get-AppPid $noInternetPackage
        $backgroundHandlerPid = Get-AppPid $handlerProcess
        if ($idleState -ne 'IDLE') {
            throw "Device did not enter bounded deep idle: $idleState"
        }
        if ($resumedActivity -match [regex]::Escape($noInternetPackage)) {
            throw "Fixture remained resumed after HOME/idle: $resumedActivity"
        }
        if ($backgroundMainPid -ne $mainPid) {
            throw "Fixture main process did not remain backgrounded: before=$mainPid after=$backgroundMainPid"
        }
        if ($backgroundHandlerPid -ne $handlerPid -or
            -not (Test-ListeningDeviceSocket $handlerSocket)) {
            throw (
                "Held production handler lost native readiness while idle: " +
                "before=$handlerPid after=$backgroundHandlerPid " +
                "socket=$(Test-ListeningDeviceSocket $handlerSocket)"
            )
        }

        Invoke-Adb shell kill '-6' $mainPid | Out-Null
        Wait-AppPidGone $noInternetPackage $mainPid
        $postCrashHandlerPid = Get-AppPid $handlerProcess
        $handlerDump = Wait-NewHandlerDump $noInternetPackage $beforeDumps
        $handlerDumpSha256 = Get-DeviceFileSha256 $handlerDump
    } finally {
        if ($forcedIdle) {
            Invoke-Adb shell cmd deviceidle unforce | Out-Null
        }
        Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    }

    Clear-DeviceLog
    Start-LabAction $noInternetPackage 'HANDLER.BACKGROUND_LIFETIME' 'readiness' -Wait
    $readiness =
        Wait-Log 'scenario_result id=INSTALL\.READINESS outcome=PASS readiness=DURABLE health=READY' 30
    $recoveredSegment =
        Wait-RecoveredSegment $noInternetPackage $handlerDump $beforeSegments
    $restartHandlerPid = Get-AppPid $handlerProcess
    if (
        $restartHandlerPid -eq 0 -or
        -not (Test-ListeningDeviceSocket $handlerSocket)
    ) {
        throw 'Production handler was not native-ready after background crash recovery'
    }
    "$readiness background_main=$mainPid held_handler=$handlerPid " +
        "post_crash_handler=$postCrashHandlerPid restart_handler=$restartHandlerPid " +
        "handler_dump=$handlerDump handler_dump_sha256=$handlerDumpSha256 " +
        "recovered_segment=$recoveredSegment"
}

Invoke-CertScenario 'MULTIPROCESS.CAPTURE' {
    Reset-And-Launch -ClearData -WithParticipant
    $participantProcess = "$noInternetPackage`:production_participant"
    $participant = Get-AppPid $participantProcess
    if ($participant -eq 0) {
        throw 'Production participant was not running before multiprocess capture'
    }
    $handler = Wait-ProductionHandlerReady $noInternetPackage
    $beforeDumps = @(Get-TraceboxHandlerDumps $noInternetPackage)
    $beforeSegments = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    Invoke-Adb shell kill '-6' $participant | Out-Null
    Wait-AppPidGone $participantProcess $participant
    $dump = Wait-NewHandlerDump $noInternetPackage $beforeDumps
    $dumpSha256 = Get-DeviceFileSha256 $dump
    Invoke-Adb shell am force-stop $noInternetPackage | Out-Null
    Clear-DeviceLog
    Start-LabAction $noInternetPackage 'MULTIPROCESS.CAPTURE' 'readiness' -Wait
    $readiness =
        Wait-Log 'scenario_result id=INSTALL\.READINESS outcome=PASS readiness=DURABLE health=READY' 30
    $recovered = Wait-RecoveredSegment $noInternetPackage $dump $beforeSegments 30
    "$readiness participant_pid=$participant handler_pid=$handler dump_sha256=$dumpSha256 " +
        "recovered_segment=$recovered"
}

Invoke-CertScenario 'MULTIPROCESS.POLICY_BARRIER' {
    Reset-And-Launch -ClearData -WithParticipant
    Clear-DeviceLog
    Start-LabAction $noInternetPackage 'MULTIPROCESS.POLICY_BARRIER' 'policy_barrier'
    Wait-Log 'scenario_result id=MULTIPROCESS\.POLICY_BARRIER outcome=PASS'
}

Invoke-CertScenario 'FAULT.JVM_UNCAUGHT' {
    Assert-ProcessDeathAction 'FAULT.JVM_UNCAUGHT' 'jvm_uncaught'
}
Invoke-CertScenario 'FAULT.CPP_ABORT' {
    Assert-ProcessDeathAction 'FAULT.CPP_ABORT' 'abort' -RequireHandlerDump
}
Invoke-CertScenario 'FAULT.CPP_SEGV' {
    Assert-ProcessDeathAction 'FAULT.CPP_SEGV' 'segv' -RequireHandlerDump
}
Invoke-CertScenario 'FAULT.RUST_PANIC' {
    Assert-ProcessDeathAction `
        'FAULT.RUST_PANIC' `
        'rust_panic' `
        -RequiredPreDeathMarker (
            'scenario_state id=FAULT\.RUST_PANIC phase=rust_panic_probe ' +
            'outcome=PASS persisted=true payload_class=(?:0|1|2) ' +
            'location_code=\d{1,10} flags=7$'
        )
}
Invoke-CertScenario 'FAULT.EMERGENCY' {
    Reset-And-Launch -ClearData
    $slot =
        "/data/user/0/$noInternetPackage/no_backup/tracebox/" +
        "native-handler/tracebox-emergency-11.bin"
    $before = Get-DeviceFileSha256 $slot
    $beforeSegments = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    $mainPid = Get-AppPid $noInternetPackage
    if ($mainPid -eq 0) { throw 'Main process was not running before emergency fault' }
    $captureTimer = [Diagnostics.Stopwatch]::StartNew()
    Start-LabAction $noInternetPackage 'FAULT.EMERGENCY' 'emergency'
    Wait-AppPidGone $noInternetPackage $mainPid
    $terminationMillis = $captureTimer.ElapsedMilliseconds
    $after = Get-DeviceFileSha256 $slot
    if ($after -eq $before) {
        throw 'Installed production signal handler did not update the emergency slot'
    }
    Clear-DeviceLog
    Start-LabAction $noInternetPackage 'FAULT.EMERGENCY' 'readiness' -Wait
    Wait-Log 'scenario_result id=INSTALL\.READINESS outcome=PASS' 30 | Out-Null
    $recovered = Wait-TraceboxSegmentChange $noInternetPackage $beforeSegments 30
    $retired = Get-DeviceFileSha256 $slot
    if ($retired -eq $after) {
        throw 'Emergency slot was not consumed or reset after durable restart ingestion'
    }
    "terminated_pid=$mainPid termination_ms=$terminationMillis slot_sha256=$after " +
        "retired_slot_sha256=$retired recovered_segments=$($recovered.Count)"
}
Invoke-CertScenario 'FAULT.RECURSIVE' {
    Assert-ProductionFixtureFaultCapture 'FAULT.RECURSIVE' 'recursive_fault'
}
Invoke-CertScenario 'FAULT.OOM' {
    Assert-ProcessDeathAction 'FAULT.OOM' 'oom' -TimeoutSeconds 45
}
Invoke-CertScenario 'FAULT.STACK_OVERFLOW' {
    Assert-ProductionFixtureFaultCapture 'FAULT.STACK_OVERFLOW' 'stack_overflow' 45
}

Invoke-CertScenario 'ANR.CANDIDATE' {
    Reset-And-Launch -ClearData
    Clear-DeviceLog
    Start-ProductionFixtureAction $noInternetPackage 'ANR.CANDIDATE' 'anr_stall'
    $armed =
        Wait-Log 'scenario_anr_armed id=ANR\.CANDIDATE stall=true policy=SUCCESS' 30
    $before = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    $completed =
        Wait-Log 'scenario_anr_window_complete id=ANR\.CANDIDATE stall=true' 20
    $after = Wait-TraceboxSegmentChange $noInternetPackage $before 20
    "$armed $completed segment_count_before=$($before.Count) segment_count_after=$($after.Count)"
}

Invoke-CertScenario 'ANR.RESPONSIVE' {
    Reset-And-Launch -ClearData
    Clear-DeviceLog
    Start-ProductionFixtureAction $noInternetPackage 'ANR.RESPONSIVE' 'anr_responsive'
    $armed =
        Wait-Log 'scenario_anr_armed id=ANR\.RESPONSIVE stall=false policy=SUCCESS' 30
    $before = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    $completed =
        Wait-Log 'scenario_anr_window_complete id=ANR\.RESPONSIVE stall=false' 15
    $after = Assert-TraceboxSegmentsStable $noInternetPackage $before 1
    "$armed $completed stable_segments=$($after.Count)"
}

Invoke-CertScenario 'ANR.TIMEOUT' {
    Reset-And-Launch -ClearData
    $handler = Wait-ProductionHandlerReady $noInternetPackage
    Clear-DeviceLog
    Start-ProductionFixtureAction $noInternetPackage 'ANR.TIMEOUT' 'anr_stall'
    $armed =
        Wait-Log 'scenario_anr_armed id=ANR\.TIMEOUT stall=true policy=SUCCESS' 30
    $before = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    try {
        Invoke-Adb shell kill '-STOP' $handler | Out-Null
        $completed =
            Wait-Log 'scenario_anr_window_complete id=ANR\.TIMEOUT stall=true' 25
        $after = Wait-TraceboxSegmentChange $noInternetPackage $before 20
    } finally {
        & adb -s $Serial shell kill '-CONT' $handler 2>$null | Out-Null
    }
    "$armed $completed stopped_handler=$handler " +
        "segment_count_before=$($before.Count) segment_count_after=$($after.Count)"
}

Invoke-CertScenario 'ANR.LIFECYCLE_SUPPRESSION' {
    Reset-And-Launch -ClearData
    Clear-DeviceLog
    Start-ProductionFixtureAction `
        $noInternetPackage 'ANR.LIFECYCLE_SUPPRESSION' 'anr_stall'
    $armed =
        Wait-Log (
            'scenario_anr_armed id=ANR\.LIFECYCLE_SUPPRESSION ' +
            'stall=true policy=SUCCESS'
        ) 30
    $before = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    Invoke-Adb shell input keyevent HOME | Out-Null
    $completed =
        Wait-Log 'scenario_anr_window_complete id=ANR\.LIFECYCLE_SUPPRESSION stall=true' 15
    $after = Assert-TraceboxSegmentsStable $noInternetPackage $before 2
    $backgroundMainPid = Get-AppPid $noInternetPackage
    if ($backgroundMainPid -eq 0) {
        throw 'Production process died during background lifecycle suppression'
    }
    "$armed $completed background_pid=$backgroundMainPid actual_stall=true stable_segments=$($after.Count)"
}

Invoke-CertScenario 'EXIT.RESTART_RECONCILIATION' {
    Reset-And-Launch -ClearData
    $tombstone =
        "/data/user/0/$noInternetPackage/no_backup/tracebox/exit-tombstones-v1"
    $journalRoot =
        "/data/user/0/$noInternetPackage/no_backup/tracebox/exit-import-journal"
    $beforeTombstone = Get-OptionalDeviceFileFingerprint $tombstone
    $beforeSegments = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    $mainPid = Get-AppPid $noInternetPackage
    if ($mainPid -eq 0) { throw 'Main process was not running before ANR exit probe' }
    Clear-DeviceLog
    Invoke-Adb shell am start `
        '-n' "$noInternetPackage/$labPackageActivity" `
        '--es' tracebox.scenario_id 'EXIT.RESTART_RECONCILIATION' `
        '--es' tracebox.action 'anr_exit' | Out-Null
    $armed =
        Wait-Log (
            'scenario_anr_armed id=EXIT\.RESTART_RECONCILIATION ' +
            'stall=true policy=SUCCESS'
        ) 30
    $stalled =
        Wait-Log (
            'scenario_anr_stall_started id=EXIT\.RESTART_RECONCILIATION ' +
            'stall=true'
        ) 10
    Invoke-Adb shell input keyevent '--async' KEYCODE_DPAD_CENTER | Out-Null
    $osAnr = Wait-AndroidAnr $noInternetPackage 30
    $termination = Wait-AnrTermination $noInternetPackage $mainPid 45
    Wait-AppPidGone $noInternetPackage $mainPid 20
    Clear-DeviceLog
    Start-LabAction $noInternetPackage 'EXIT.RESTART_RECONCILIATION' 'readiness' -Wait
    Wait-Log 'scenario_result id=INSTALL\.READINESS outcome=PASS' 20 | Out-Null

    $afterTombstone = 'absent'
    $afterSegments = @()
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $afterTombstone = Get-OptionalDeviceFileFingerprint $tombstone
        $current = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
        $afterSegments = @($current | Where-Object { $_ -notin $beforeSegments })
        if (
            $afterTombstone -ne 'absent' -and
            $afterTombstone -ne $beforeTombstone -and
            $afterSegments.Count -gt 0
        ) {
            break
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt 30)
    if (
        $afterTombstone -eq 'absent' -or
        $afterTombstone -eq $beforeTombstone -or
        $afterSegments.Count -eq 0
    ) {
        throw (
            "ANR exit was not terminalized into a new tombstone and OSEXIT segment: " +
            "before_tombstone=$beforeTombstone after_tombstone=$afterTombstone " +
            "changed_segments=$($afterSegments.Count)"
        )
    }
    $pendingJournals =
        & adb -s $Serial shell find $journalRoot '-type' f '-name' '*.tbexitjournal' 2>$null
    if ($LASTEXITCODE -eq 0 -and $pendingJournals) {
        throw "Exit import journal did not reach a terminal state: $($pendingJournals -join ',')"
    }
    "$armed $stalled $osAnr $termination before_tombstone=$beforeTombstone " +
        "after_tombstone=$afterTombstone osexit_segments=$($afterSegments.Count) " +
        "pending_journals=0"
}

Invoke-CertScenario 'DIRECT_BOOT.C0_CAPTURE' {
    $credentialInstalled = $false
    try {
        Reset-And-Launch -ClearData
        $records =
            "/data/user_de/0/$noInternetPackage/no_backup/" +
            "tracebox-directboot/tracebox-c0.records"
        $emptyHash = Get-DeviceFileSha256 $records

        Invoke-Adb shell locksettings set-pin $directBootPin | Out-Null
        $credentialInstalled = $true
        Clear-DeviceLog
        Invoke-Adb reboot | Out-Null
        Wait-BootCompleted
        Assert-AdbRoot -Context 'Direct Boot reboot' | Out-Null
        Wait-UserUnlocked -Expected $false

        $line =
            Wait-Log `
                'scenario_result id=DIRECT_BOOT\.C0_CAPTURE outcome=PASS result=WRITTEN' `
                30
        if (Get-UserUnlocked) {
            throw 'Direct Boot receiver evidence was collected after CE storage unlocked'
        }
        $capturedHash = Get-DeviceFileSha256 $records
        if ($capturedHash -eq $emptyHash) {
            throw 'Production Direct Boot capture did not change the preallocated C0 store'
        }

        Unlock-DeviceWithPin $directBootPin
        Clear-DeviceLog
        Start-LabAction $noInternetPackage 'DIRECT_BOOT.C0_CAPTURE' 'readiness' -Wait
        Wait-Log `
            'scenario_result id=INSTALL\.READINESS outcome=PASS readiness=DURABLE health=READY' `
            30 | Out-Null
        $retiredHash = Get-DeviceFileSha256 $records
        if ($retiredHash -ne $emptyHash) {
            throw 'Unlocked CE import did not durably retire the Direct Boot C0 frame'
        }
        "$line user_locked_at_capture=true captured_sha256=$capturedHash retired_sha256=$retiredHash"
    } finally {
        if ($credentialInstalled) {
            if (-not (Get-UserUnlocked)) {
                Unlock-DeviceWithPin $directBootPin
            }
            Invoke-Adb shell locksettings clear --old $directBootPin | Out-Null
        }
    }
}

Invoke-CertScenario 'STORAGE.PRESSURE' {
    Reset-And-Launch -ClearData
    Clear-DeviceLog
    Start-LabAction $noInternetPackage 'STORAGE.PRESSURE' 'storage_pressure'
    $line =
        Wait-Log (
            'scenario_result id=STORAGE\.PRESSURE outcome=PASS policy=SUCCESS ' +
            'attempted=20000 .*persisted_delta=(\d+) ' +
            'before_digest=([0-9a-f]{64}) ' +
            'after_digest=([0-9a-f]{64})'
        ) 60
    if (
        $line -notmatch (
            'persisted_delta=(\d+) before_digest=([0-9a-f]{64}) ' +
            'after_digest=([0-9a-f]{64})'
        ) -or
        [long]$Matches[1] -le 0 -or
        $Matches[2] -eq $Matches[3]
    ) {
        throw "Storage pressure did not prove persisted byte progress: $line"
    }
    $line
}

Invoke-CertScenario 'DELETE.ALL_RESTART' {
    Reset-And-Launch -ClearData
    $script:deletedPayloadPaths = @(Get-TraceboxDiagnosticPayloadFiles $noInternetPackage)
    if ($script:deletedPayloadPaths.Count -eq 0) {
        throw 'Delete-all smoke has no pre-existing diagnostic payload to prove removal'
    }
    Clear-DeviceLog
    Start-LabAction $noInternetPackage 'DELETE.ALL_RESTART' 'delete_all'
    $deletion =
        Wait-Log (
            'scenario_result id=DELETE\.ALL_RESTART outcome=PASS ' +
            'report=COMPLETE readiness=DURABLE health=DISABLED'
        ) 30
    Invoke-Adb shell am force-stop $noInternetPackage | Out-Null
    Clear-DeviceLog
    Start-ProductionFixtureAction `
        $noInternetPackage 'DELETE.ALL_RESTART' 'disabled_state'
    $disabled =
        Wait-Log (
            'scenario_state id=DELETE\.ALL_RESTART phase=post_delete_restart ' +
            'outcome=PASS readiness=DURABLE health=DISABLED'
        ) 30
    $current = @(Get-TraceboxDiagnosticPayloadFiles $noInternetPackage)
    if ($current.Count -ne 0) {
        throw (
            'Diagnostic payload remains accessible after disabled restart: ' +
            ($current -join ', ')
        )
    }
    "$deletion $disabled pre_delete_payloads=$($script:deletedPayloadPaths.Count) " +
        'surviving=0 current_payloads=0'
}

Invoke-CertScenario 'DELETE.NO_ACCESSIBLE_DATA' {
    if ($script:deletedPayloadPaths.Count -eq 0) {
        throw 'Delete/no-accessible-data ran without a preceding payload inventory'
    }
    $current = @(Get-TraceboxDiagnosticPayloadFiles $noInternetPackage)
    $survivors = @($script:deletedPayloadPaths | Where-Object { $_ -in $current })
    if ($survivors) {
        throw "Pre-delete diagnostic payload remains accessible: $($survivors -join ', ')"
    }
    Clear-DeviceLog
    Start-ProductionFixtureAction `
        $noInternetPackage 'DELETE.NO_ACCESSIBLE_DATA' 'explicit_reenable'
    $reenabled =
        Wait-Log (
            'scenario_state id=DELETE\.NO_ACCESSIBLE_DATA phase=explicit_reenable ' +
            'outcome=PASS policy=SUCCESS readiness=DURABLE health=READY'
        ) 30
    Reset-And-Launch
    "pre_delete_payloads=$($script:deletedPayloadPaths.Count) surviving=0 " +
        "disabled_current_payloads=$($current.Count) $reenabled"
}

Invoke-CertScenario 'PACKAGE.DISCLOSURE' {
    Invoke-PackageUi 'PACKAGE.DISCLOSURE'
}
Invoke-CertScenario 'PACKAGE.EXACT_APPROVAL' {
    Invoke-PackageUi 'PACKAGE.EXACT_APPROVAL' -Approve
}
Invoke-CertScenario 'PACKAGE.SAVE_SHARE' {
    Invoke-PackageUi 'PACKAGE.SAVE_SHARE' -Approve
}

Invoke-CertScenario 'SYMBOL.R8_RETRACE' {
    Invoke-CatalogCommand r8
}
Invoke-CertScenario 'SYMBOL.ELF' {
    Invoke-CatalogCommand elf
}

Invoke-CertScenario 'NETWORK.NO_INTERNET' {
    $packets = Invoke-WithUidPacketCounter $noInternetPackage "TBXNI$PID" {
        Reset-And-Launch -Package $noInternetPackage -ClearData
        Clear-DeviceLog
        Start-LabAction $noInternetPackage 'NETWORK.NO_INTERNET' 'network_control'
        Wait-Log 'scenario_result id=NETWORK\.NO_INTERNET outcome=PASS capability=ABSENT dns=false connect=false' | Out-Null
    }
    if ($packets -ne 0) {
        throw "No-INTERNET control emitted $packets packets"
    }
    "uid_packets=$packets"
}

Invoke-CertScenario 'NETWORK.HOST_CONTROL' {
    $packets = Invoke-WithUidPacketCounter $hostNetworkPackage "TBXHC$PID" {
        Reset-And-Launch -Package $hostNetworkPackage -ClearData
        Clear-DeviceLog
        Invoke-Adb shell am start `
            '-n' "$hostNetworkPackage/$productionActivity" `
            '--es' tracebox.scenario_id 'NETWORK.HOST_CONTROL' `
            '--es' tracebox.action network_control `
            '--es' tracebox.probe_host $ProbeHost `
            '--ei' tracebox.probe_port $ProbePort | Out-Null
        Wait-Log 'scenario_result id=NETWORK\.HOST_CONTROL outcome=PASS capability=HOST_CONTROL dns=true connect=true' | Out-Null
    }
    if ($packets -le 0) {
        throw 'Host-network positive control produced no observable UID packets'
    }
    "uid_packets=$packets"
}

Invoke-CertScenario 'NETWORK.BLOCKED_EGRESS' {
    $blockedEgressActions = if ($FullDiagnosticSuite) {
        @('readiness', 'policy_barrier', 'storage_pressure', 'package_disclosure')
    } else {
        @('readiness', 'package_disclosure')
    }
    $packets = Invoke-WithUidPacketCounter `
        -Package $hostNetworkPackage `
        -Chain "TBXBE$PID" `
        -Drop `
        -Body {
        Reset-And-Launch -Package $hostNetworkPackage -ClearData
        foreach ($action in $blockedEgressActions) {
            Clear-DeviceLog
            Start-LabAction $hostNetworkPackage 'NETWORK.BLOCKED_EGRESS' $action
            switch ($action) {
                'readiness' { Wait-Log 'scenario_result id=INSTALL\.READINESS outcome=PASS' | Out-Null }
                'policy_barrier' { Wait-Log 'scenario_result id=MULTIPROCESS\.POLICY_BARRIER outcome=PASS' | Out-Null }
                'storage_pressure' { Wait-Log 'scenario_result id=STORAGE\.PRESSURE outcome=PASS' 60 | Out-Null }
                'package_disclosure' { Wait-Log 'scenario_result id=PACKAGE\.DISCLOSURE outcome=(PASS|NOT_READY)' 30 | Out-Null }
            }
        }
    }
    if ($packets -ne 0) {
        throw "Tracebox runtime workflows emitted $packets packets"
    }
    "uid_packets=$packets workflows=$($blockedEgressActions.Count)"
}

Invoke-CertScenario 'RESOURCE.BASELINE' {
    Invoke-Adb shell am force-stop $noInternetPackage | Out-Null
    Invoke-Adb shell pm clear $noInternetPackage | Out-Null
    Clear-DeviceLog
    $startupTimer = [Diagnostics.Stopwatch]::StartNew()
    Start-LabAction `
        $noInternetPackage 'RESOURCE.BASELINE' 'readiness' `
        -Wait `
        -WithParticipant
    $readiness =
        Wait-Log 'scenario_result id=INSTALL\.READINESS outcome=PASS readiness=DURABLE health=READY' 30
    $startupReadinessMillis = $startupTimer.ElapsedMilliseconds
    if ($startupReadinessMillis -gt 30000) {
        throw "Startup/readiness exceeded 30 seconds: $startupReadinessMillis ms"
    }

    $appPid = Get-AppPid $noInternetPackage
    $handlerPid = Wait-ProductionHandlerReady $noInternetPackage
    $participantPid = Get-AppPid "$noInternetPackage`:production_participant"
    $pids = @($appPid, $handlerPid, $participantPid)
    if ($pids | Where-Object { $_ -eq 0 }) {
        throw 'Resource baseline requires all three installed production processes'
    }
    $segments = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    $startJiffies = $pids | ForEach-Object { Get-ProcessJiffies $_ }
    $startWakeups = $pids | ForEach-Object { Get-ProcessSchedulerWakeups $_ }
    Start-Sleep -Seconds 10
    $endJiffies = $pids | ForEach-Object { Get-ProcessJiffies $_ }
    $endWakeups = $pids | ForEach-Object { Get-ProcessSchedulerWakeups $_ }
    $pss = $pids | ForEach-Object { Get-ProcessPssKiB $_ }
    if (($pss | Measure-Object -Sum).Sum -gt 256 * 1024) {
        throw "Personal-project process PSS exceeds 256 MiB: $($pss -join ',') KiB"
    }
    $jiffyDeltas = for ($index = 0; $index -lt $pids.Count; $index++) {
        $endJiffies[$index] - $startJiffies[$index]
    }
    $totalJiffyDelta = ($jiffyDeltas | Measure-Object -Sum).Sum
    if ($totalJiffyDelta -gt 500) {
        throw (
            "Idle production processes consumed more than 500 jiffies in 10 seconds: " +
            "$($jiffyDeltas -join ',')"
        )
    }
    $wakeupDeltas = for ($index = 0; $index -lt $pids.Count; $index++) {
        if ($startWakeups[$index].source -ne $endWakeups[$index].source) {
            throw "Scheduler wakeup source changed for PID $($pids[$index])"
        }
        $endWakeups[$index].value - $startWakeups[$index].value
    }
    $totalWakeupDelta = ($wakeupDeltas | Measure-Object -Sum).Sum
    if ($totalWakeupDelta -gt 5000) {
        throw (
            "Idle production processes exceeded 5000 scheduler wakeups/context switches: " +
            "$($wakeupDeltas -join ',')"
        )
    }
    $afterSegments = @(Get-TraceboxSegmentFingerprints $noInternetPackage)
    if (
        $afterSegments.Count -ne $segments.Count -or
        @($afterSegments | Where-Object { $_ -notin $segments }).Count -gt 0
    ) {
        throw 'Installed production resource window produced unexpected diagnostic writes'
    }

    $beforeCapture = @($afterSegments)
    Clear-DeviceLog
    $captureTimer = [Diagnostics.Stopwatch]::StartNew()
    Start-ProductionFixtureAction $noInternetPackage 'RESOURCE.BASELINE' 'resource_probe'
    $probe =
        Wait-Log (
            'scenario_result id=RESOURCE\.BASELINE outcome=PASS ' +
            'resource_probe=true capture_overlap_heartbeat_samples=\d+ ' +
            'capture_overlap_target_pause_ms=\d+ ' +
            'capture_records=\d+'
        ) 20
    if (
        $probe -notmatch (
            'capture_overlap_heartbeat_samples=(\d+) ' +
            'capture_overlap_target_pause_ms=(\d+) capture_records=(\d+)'
        )
    ) {
        throw "Resource probe omitted capture-overlap target-pause evidence: $probe"
    }
    $captureOverlapSamples = [int]$Matches[1]
    $captureOverlapTargetPauseMillis = [long]$Matches[2]
    $captureRecords = [int]$Matches[3]
    $captureSegments =
        Wait-TraceboxSegmentChange $noInternetPackage $beforeCapture 10
    $captureLatencyMillis = $captureTimer.ElapsedMilliseconds
    if (
        $captureOverlapSamples -ne 16 -or
        $captureRecords -ne 32 -or
        $captureOverlapTargetPauseMillis -gt 2000 -or
        $captureLatencyMillis -gt 10000
    ) {
        throw (
            "Resource probe exceeded personal bounds: " +
            "capture_overlap_samples=$captureOverlapSamples " +
            "capture_records=$captureRecords " +
            "capture_overlap_target_pause_ms=$captureOverlapTargetPauseMillis " +
            "capture_latency_ms=$captureLatencyMillis"
        )
    }

    $prePackagePss = Get-ProcessPssKiB $appPid
    Clear-DeviceLog
    Invoke-Adb shell am start '-W' `
        '-n' "$noInternetPackage/$labPackageActivity" `
        '--es' tracebox.scenario_id 'RESOURCE.BASELINE' | Out-Null
    $packageReady =
        Wait-Log 'scenario_ready id=RESOURCE\.BASELINE values=\d+ bytes=\d+ raw=\d+' 30
    $packagePss = Get-ProcessPssKiB (Get-AppPid $noInternetPackage)
    if ($packagePss -gt 256 * 1024) {
        throw "Package preparation process PSS exceeds 256 MiB: $packagePss KiB"
    }
    $packagePssDelta = [Math]::Max(0, $packagePss - $prePackagePss)
    Invoke-Adb shell am force-stop $noInternetPackage | Out-Null

    $releaseAars = @(
        Get-ChildItem (Join-Path $root 'android') -Recurse -File -Filter '*-release.aar' |
            Where-Object { $_.FullName -match '\\build\\outputs\\aar\\' }
    )
    $apkBytes = [long]$noInternetApk.Length
    $aarBytes = [long](($releaseAars | Measure-Object Length -Sum).Sum)
    if (
        $apkBytes -le 0 -or
        $aarBytes -le 0 -or
        $apkBytes -gt 128MB -or
        $aarBytes -gt 128MB
    ) {
        throw "Artifact-size baseline is missing or unreasonable: apk=$apkBytes aars=$aarBytes"
    }

    "$readiness production_pids=$($pids -join ',') " +
        "startup_readiness_ms=$startupReadinessMillis pss_kib=$($pss -join ',') " +
        "idle_jiffy_delta=$($jiffyDeltas -join ',') " +
        "scheduler_wakeup_delta=$($wakeupDeltas -join ',') " +
        "scheduler_wakeup_source=$($startWakeups.source -join ',') " +
        "capture_overlap_heartbeat_samples=$captureOverlapSamples " +
        "capture_overlap_target_pause_ms=$captureOverlapTargetPauseMillis " +
        "capture_latency_ms=$captureLatencyMillis " +
        "capture_segments=$($captureSegments.Count) " +
        "package_pss_kib=$packagePss package_pss_delta_kib=$packagePssDelta " +
        "apk_bytes=$apkBytes release_aar_count=$($releaseAars.Count) " +
        "release_aar_bytes=$aarBytes stable_idle_segments=$($afterSegments.Count) " +
        "package_ready='$packageReady'"
}

$corpusScenarios = @(
    [pscustomobject]@{ id = 'CORPUS.PACKAGE'; prefix = 'PACKAGE.' },
    [pscustomobject]@{ id = 'CORPUS.ARCHIVE'; prefix = 'ARCHIVE.' },
    [pscustomobject]@{ id = 'CORPUS.SYMBOL'; prefix = 'SYMBOL.' }
)
if (@($corpusScenarios | Where-Object { $_.id -in $requiredIds }).Count -gt 0) {
    $corpusResult = $null
    try {
        $corpusJson = & (Join-Path $PSScriptRoot 'Verify-MaliciousCorpora.ps1')
        $corpusResult = $corpusJson | ConvertFrom-Json
    } catch {
        $corpusFailure = $_.Exception.Message
    }
    foreach ($corpusScenario in $corpusScenarios) {
        Invoke-CertScenario $corpusScenario.id {
            if (-not $corpusResult) {
                throw "Corpus verifier failed: $corpusFailure"
            }
            $matchingCorpusCases = @(
                $corpusResult.cases |
                    Where-Object { $_.id.StartsWith($corpusScenario.prefix) }
            )
            if (
                $matchingCorpusCases.Count -eq 0 -or
                @($matchingCorpusCases | Where-Object { -not $_.rejected }).Count -ne 0
            ) {
                throw "Corpus group did not fail closed: $($corpusScenario.prefix)"
            }
            "rejected_cases=$($matchingCorpusCases.Count)"
        }
    }
}

$currentBaseCommitOutput = & git -C $root rev-parse HEAD
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to recapture the source base commit after validation'
}
$currentBaseCommit = ($currentBaseCommitOutput -join '').Trim()
$currentSourceState = Get-RepositorySourceState -Root $root
$currentSourcePatchSha256 = $currentSourceState.sha256
$currentScenarioManifestSha256 =
    (Get-FileHash $scenarioPath -Algorithm SHA256).Hash.ToLowerInvariant()
$currentNoInternetApkSha256 =
    (Get-FileHash $noInternetApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$currentHostNetworkApkSha256 =
    (Get-FileHash $hostNetworkApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if (
    $currentBaseCommit -ne $sourceBaseCommit -or
    $currentSourcePatchSha256 -ne $sourcePatchSha256 -or
    $currentScenarioManifestSha256 -ne $scenarioManifestSha256 -or
    $currentNoInternetApkSha256 -ne $noInternetApkSha256 -or
    $currentHostNetworkApkSha256 -ne $hostNetworkApkSha256
) {
    throw (
        'Source, scenario manifest, or installed fixture APK changed during personal-release ' +
        'validation; refusing to bind emulator results to stale build provenance.'
    )
}

$observedIds = @($results | ForEach-Object id)
$missingIds = @($requiredIds | Where-Object { $_ -notin $observedIds })
$duplicateIds = @(
    $observedIds |
        Group-Object |
        Where-Object Count -ne 1 |
        ForEach-Object Name
)
$unexpectedIds = @($observedIds | Where-Object { $_ -notin $requiredIds })
$scenarioCoveragePass =
    $missingIds.Count -eq 0 -and
    $duplicateIds.Count -eq 0 -and
    $unexpectedIds.Count -eq 0
$scenarioPass = @($results | Where-Object outcome -ne 'PASS').Count -eq 0
$hostPass = @($hostGates | Where-Object outcome -ne 'PASS').Count -eq 0
$passed = $scenarioCoveragePass -and $scenarioPass -and $hostPass
$ended = (Get-Date).ToUniversalTime()
$commandParts = @(
    'tools\verify\Invoke-PersonalReleaseEmulator.ps1',
    '-Serial',
    $Serial
)
if ($SkipBuild) { $commandParts += '-SkipBuild' }
if ($SkipHostChecks) { $commandParts += '-SkipHostChecks' }
if ($RunHostBlockedEgress) { $commandParts += '-RunHostBlockedEgress' }
if ($FullDiagnosticSuite) { $commandParts += '-FullDiagnosticSuite' }

$report = [ordered]@{
    schema = 'tracebox-personal-release-emulator-result-v1'
    scope = if ($FullDiagnosticSuite) {
        'Optional full diagnostic inventory on one API-36 x86_64 4-KiB emulator.'
    } else {
        'Representative personal-release smoke on one API-36 x86_64 4-KiB emulator.'
    }
    mode = if ($FullDiagnosticSuite) { 'FULL_DIAGNOSTIC' } else { 'PERSONAL_RELEASE' }
    command = $commandParts -join ' '
    start_time_utc = $started.ToString('o')
    end_time_utc = $ended.ToString('o')
    endpoint = [ordered]@{
        serial = $Serial
        api = $api
        abi = $abi
        page_size = $pageSize
        emulator = $emulator
    }
    provenance = [ordered]@{
        base_commit = $sourceBaseCommit
        working_tree_patch_sha256 = $sourcePatchSha256
        source_state_recheck_sha256 = $currentSourcePatchSha256
        source_state_stable = $true
        source_state_entries = @($sourceState.entries)
        scenario_manifest_sha256 = $scenarioManifestSha256
        no_internet_apk_sha256 = $noInternetApkSha256
        host_network_apk_sha256 = $hostNetworkApkSha256
        no_internet_apk = $noInternetApk.FullName.Substring($root.Length + 1)
        host_network_apk = $hostNetworkApk.FullName.Substring($root.Length + 1)
    }
    host_gates = @($hostGates)
    scenario_coverage = [ordered]@{
        inventory = $inventoryIds.Count
        required = $requiredIds.Count
        observed = $observedIds.Count
        missing = $missingIds
        duplicate = $duplicateIds
        unexpected = $unexpectedIds
        result = if ($scenarioCoveragePass) { 'PASS' } else { 'FAIL' }
    }
    scenarios = @($results)
    result = if ($passed) { 'PASS' } else { 'FAIL' }
}

$outputDirectory = Split-Path -Parent $Output
if (
    $outputDirectory -and
    -not (Test-Path -LiteralPath $outputDirectory -PathType Container)
) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Output -Encoding utf8
Write-Output $Output
if (-not $passed) {
    exit 2
}
