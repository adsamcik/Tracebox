param(
    [switch] $FullDiagnosticSuite
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$scenarioPath = Join-Path $root 'tooling\fixtures\personal-release-scenarios.json'
$runnerPath = Join-Path $root 'tools\verify\Invoke-PersonalReleaseEmulator.ps1'
$runnerSupportPath = Join-Path $root 'tools\verify\PersonalReleaseRunnerSupport.ps1'
$hostReadinessPath = Join-Path $root 'tools\verify\Invoke-Phase5HostReadiness.ps1'
$fixtureRoot = Join-Path $root 'test-apps\phase0-fixture'
. $runnerSupportPath

$expectedIds = @(
    'INSTALL.READINESS',
    'HANDLER.COLD_START',
    'HANDLER.RUNNING_ATTACH',
    'HANDLER.CONFLICT',
    'HANDLER.DEATH',
    'HANDLER.RESTART',
    'HANDLER.TIMEOUT',
    'HANDLER.BACKGROUND_LIFETIME',
    'MULTIPROCESS.CAPTURE',
    'MULTIPROCESS.POLICY_BARRIER',
    'FAULT.JVM_UNCAUGHT',
    'FAULT.CPP_ABORT',
    'FAULT.CPP_SEGV',
    'FAULT.RUST_PANIC',
    'FAULT.EMERGENCY',
    'FAULT.RECURSIVE',
    'FAULT.OOM',
    'FAULT.STACK_OVERFLOW',
    'ANR.CANDIDATE',
    'ANR.RESPONSIVE',
    'ANR.TIMEOUT',
    'ANR.LIFECYCLE_SUPPRESSION',
    'EXIT.RESTART_RECONCILIATION',
    'DIRECT_BOOT.C0_CAPTURE',
    'STORAGE.PRESSURE',
    'DELETE.ALL_RESTART',
    'DELETE.NO_ACCESSIBLE_DATA',
    'PACKAGE.DISCLOSURE',
    'PACKAGE.EXACT_APPROVAL',
    'PACKAGE.SAVE_SHARE',
    'SYMBOL.R8_RETRACE',
    'SYMBOL.ELF',
    'NETWORK.NO_INTERNET',
    'NETWORK.HOST_CONTROL',
    'NETWORK.BLOCKED_EGRESS',
    'RESOURCE.BASELINE',
    'CORPUS.PACKAGE',
    'CORPUS.ARCHIVE',
    'CORPUS.SYMBOL'
)

$expectedPersonalReleaseIds = @(
    'INSTALL.READINESS',
    'HANDLER.RESTART',
    'FAULT.JVM_UNCAUGHT',
    'FAULT.CPP_SEGV',
    'ANR.CANDIDATE',
    'EXIT.RESTART_RECONCILIATION',
    'DELETE.ALL_RESTART',
    'PACKAGE.DISCLOSURE',
    'PACKAGE.EXACT_APPROVAL',
    'PACKAGE.SAVE_SHARE',
    'NETWORK.NO_INTERNET',
    'NETWORK.HOST_CONTROL',
    'NETWORK.BLOCKED_EGRESS'
)

$manifest = Get-Content -LiteralPath $scenarioPath -Raw | ConvertFrom-Json
if ($manifest.schema -ne 'tracebox-personal-release-scenarios-v1') {
    throw "Unexpected personal-release scenario schema: $($manifest.schema)"
}
$scenarios = @($manifest.scenarios)
$manifestIds = @($scenarios | ForEach-Object id)
$duplicateManifestIds = @(
    $manifestIds |
        Group-Object |
        Where-Object Count -ne 1 |
        ForEach-Object Name
)
if ($duplicateManifestIds) {
    throw "Duplicate diagnostic scenario IDs: $($duplicateManifestIds -join ', ')"
}
if ($FullDiagnosticSuite) {
    $missingExpected = @($expectedIds | Where-Object { $_ -notin $manifestIds })
    $unexpectedManifest = @($manifestIds | Where-Object { $_ -notin $expectedIds })
    if (
        $manifestIds.Count -ne $expectedIds.Count -or
        $missingExpected -or
        $unexpectedManifest
    ) {
        throw (
            "Diagnostic inventory drift. Missing=$($missingExpected -join ', ') " +
            "unexpected=$($unexpectedManifest -join ', ')"
        )
    }
}
$personalReleaseIds = @($manifest.personal_release_required)
$duplicatePersonalReleaseIds = @(
    $personalReleaseIds |
        Group-Object |
        Where-Object Count -ne 1 |
        ForEach-Object Name
)
$unknownPersonalReleaseIds = @(
    $personalReleaseIds | Where-Object { $_ -notin $manifestIds }
)
$missingPersonalReleaseIds = @(
    $expectedPersonalReleaseIds | Where-Object { $_ -notin $personalReleaseIds }
)
$unexpectedPersonalReleaseIds = @(
    $personalReleaseIds | Where-Object { $_ -notin $expectedPersonalReleaseIds }
)
if ($personalReleaseIds.Count -eq 0 -or
    $duplicatePersonalReleaseIds -or
    $unknownPersonalReleaseIds -or
    $missingPersonalReleaseIds -or
    $unexpectedPersonalReleaseIds) {
    throw (
        "Personal-release scenario drift. duplicate=$($duplicatePersonalReleaseIds -join ', ') " +
        "unknown=$($unknownPersonalReleaseIds -join ', ') " +
        "missing=$($missingPersonalReleaseIds -join ', ') " +
        "unexpected=$($unexpectedPersonalReleaseIds -join ', ')"
    )
}

$allowedTransports = @('activity', 'broadcast', 'direct_boot', 'runner', 'host')
$allowedVariants = @('noInternet', 'hostNetwork', 'either')
$scenariosToValidate = if ($FullDiagnosticSuite) {
    $scenarios
} else {
    @($scenarios | Where-Object id -in $personalReleaseIds)
}
foreach ($scenario in $scenariosToValidate) {
    if ($scenario.id -notmatch '^[A-Z][A-Z0-9_]*(\.[A-Z0-9_]+)+$' -or
        $scenario.id.Length -gt 48) {
        throw "Invalid stable scenario ID: $($scenario.id)"
    }
    if ($scenario.transport -notin $allowedTransports) {
        throw "Invalid transport for $($scenario.id): $($scenario.transport)"
    }
    if ($scenario.variant -notin $allowedVariants) {
        throw "Invalid variant for $($scenario.id): $($scenario.variant)"
    }
    if ($scenario.action -notmatch '^[a-z][a-z0-9_]*$' -or
        $scenario.expected -notmatch '^[a-z][a-z0-9_]*$') {
        throw "Invalid bounded action/expectation for $($scenario.id)"
    }
}
if ($FullDiagnosticSuite) {
    foreach ($fatalExpectation in @(
        [pscustomobject]@{
            id = 'FAULT.RUST_PANIC'
            expected = 'process_death_real_rust_hook_capture_and_restart_ingestion'
        },
        [pscustomobject]@{
            id = 'FAULT.RECURSIVE'
            expected = 'process_death_capture_and_restart_ingestion'
        },
        [pscustomobject]@{
            id = 'FAULT.STACK_OVERFLOW'
            expected = 'process_death_capture_and_restart_ingestion'
        }
        )) {
        $manifestScenario = @(
            $scenarios | Where-Object id -eq $fatalExpectation.id
        ) | Select-Object -First 1
        if (-not $manifestScenario -or
            $manifestScenario.expected -ne $fatalExpectation.expected) {
            throw (
                "Fatal scenario contract drift for $($fatalExpectation.id): " +
                "$($manifestScenario.expected)"
            )
        }
    }
}

$runner = Get-Content -LiteralPath $runnerPath -Raw
if ($runner -match '(?im)^\s*\$pid\s*=') {
    throw 'Emulator controller assigns PowerShell automatic variable $PID'
}
if ($runner -match '(?im)^\s*\$matches\s*=') {
    throw 'Emulator controller assigns PowerShell automatic variable $Matches'
}
$handlerDumpReader = [regex]::Match(
    $runner,
    '(?s)function Get-TraceboxHandlerDumps\s*\{(?<body>.*?)' +
        'function Get-TraceboxCrashpadPendingEntries'
)
if (
    -not $handlerDumpReader.Success -or
    -not $handlerDumpReader.Groups['body'].Value.Contains('tracebox-handler-handoff') -or
    -not $handlerDumpReader.Groups['body'].Value.Contains('[0-9a-f]{64}\.dmp$') -or
    -not $handlerDumpReader.Groups['body'].Value.Contains('crashpad-db/pending') -or
    -not $handlerDumpReader.Groups['body'].Value.Contains("'.meta'") -or
    -not $handlerDumpReader.Groups['body'].Value.Contains("'.lock'") -or
    -not $handlerDumpReader.Groups['body'].Value.Contains(
        '$metadataBytes -eq ''32'''
    )
) {
    throw (
        'Emulator controller must recognize only a complete Crashpad pending pair ' +
        'or a durable Tracebox handoff'
    )
}
foreach ($fatalRecoveryContract in @(
        'Wait-CrashpadPendingRetired',
        'Assert-ProcessDeathAction ''FAULT.CPP_SEGV'' ''segv'' -RequireHandlerDump'
    )) {
    if (-not $runner.Contains($fatalRecoveryContract)) {
        throw "Emulator controller is missing fatal recovery contract: $fatalRecoveryContract"
    }
}
foreach ($activityContract in @(
        '$productionActivity = ''dev.tracebox.phase0.MainActivity''',
        '$labPackageActivity = ''dev.tracebox.phase0.LabPackageActivity''',
        '"$hostNetworkPackage/$productionActivity"'
    )) {
    if (-not $runner.Contains($activityContract)) {
        throw "Emulator controller is missing qualified fixture activity: $activityContract"
    }
}
$genericProductionLaunches = [regex]::Matches(
    $runner,
    [regex]::Escape('"$Package/$productionActivity"')
).Count
$genericLabLaunches = [regex]::Matches(
    $runner,
    [regex]::Escape('"$Package/$labPackageActivity"')
).Count
if ($genericProductionLaunches -lt 1 -or $genericLabLaunches -lt 2) {
    throw (
        'Emulator controller does not route every package variant through qualified activities: ' +
        "production=$genericProductionLaunches lab=$genericLabLaunches"
    )
}
$resetAndLaunch = [regex]::Match(
    $runner,
    '(?s)function Reset-And-Launch\s*\{(?<body>.*?)function Add-ScenarioResult'
)
if (
    -not $resetAndLaunch.Success -or
    -not $resetAndLaunch.Groups['body'].Value.Contains('Start-LabAction')
) {
    throw 'Reset-And-Launch does not use the central qualified activity launcher'
}
if ($runner -match '/\.(?:MainActivity|LabPackageActivity|\$productionActivity|\$labPackageActivity)') {
    throw 'Emulator controller uses an application-ID-relative fixture activity'
}
$android16UidRows = @(
    'package:dev.tracebox.phase0.hostnetwork uid:10228',
    'package:dev.tracebox.phase0 uid:10227'
)
$android16Uid =
    ConvertFrom-PmPackageUid -Package 'dev.tracebox.phase0' -Lines $android16UidRows
if ($android16Uid -ne 10227) {
    throw "Android 16 package UID parser returned $android16Uid instead of 10227"
}
foreach ($invalidUidRows in @(
        @('package:dev.tracebox.phase0.hostnetwork uid:10228'),
        @('package:dev.tracebox.phase0 uid=10227'),
        @(
            'package:dev.tracebox.phase0 uid:10227',
            'package:dev.tracebox.phase0 uid:10227'
        )
    )) {
    $acceptedInvalidUidRows = $false
    try {
        ConvertFrom-PmPackageUid `
            -Package 'dev.tracebox.phase0' `
            -Lines $invalidUidRows | Out-Null
        $acceptedInvalidUidRows = $true
    } catch {
        # Expected: missing, malformed, and ambiguous rows must not select a UID.
    }
    if ($acceptedInvalidUidRows) {
        throw "Android package UID parser accepted invalid rows: $($invalidUidRows -join ', ')"
    }
}
$explicitIds = @(
    [regex]::Matches($runner, "Invoke-CertScenario\s+'([^']+)'") |
        ForEach-Object { $_.Groups[1].Value }
)
$groupedIds = @(
    [regex]::Matches($runner, "\[pscustomobject\]@\{\s*id = '(CORPUS\.[^']+)'") |
        ForEach-Object { $_.Groups[1].Value }
)
$implementedIds = @($explicitIds + $groupedIds)
$requiredControllerIds = if ($FullDiagnosticSuite) {
    $manifestIds
} else {
    $personalReleaseIds
}
$duplicateImplementations = @(
    $implementedIds |
        Group-Object |
        Where-Object { $_.Name -in $requiredControllerIds -and $_.Count -ne 1 } |
        ForEach-Object Name
)
$missingImplementations = @(
    $requiredControllerIds | Where-Object { $_ -notin $implementedIds }
)
$unexpectedImplementations = if ($FullDiagnosticSuite) {
    @($implementedIds | Where-Object { $_ -notin $manifestIds })
} else {
    @()
}
if ($duplicateImplementations -or $missingImplementations -or $unexpectedImplementations) {
    throw (
        "Emulator controller coverage drift. duplicate=$($duplicateImplementations -join ', ') " +
        "missing=$($missingImplementations -join ', ') unexpected=$($unexpectedImplementations -join ', ')"
    )
}
$requiredRunnerBindings = @(
        '[int] $ExpectedApi = 36',
        '[int] $ExpectedPageSize = 4096',
        "`$abi -ne 'x86_64'",
        '[switch] $FullDiagnosticSuite',
        '$requiredIds = if ($FullDiagnosticSuite)',
        'working_tree_patch_sha256',
        'scenario_manifest_sha256',
        'no_internet_apk_sha256',
        'host_network_apk_sha256',
        'Start-ProductionFixtureAction',
        'scenario_share_handoff',
        'ChooserActivity|ResolverActivity',
        'dumpsys activity top-resumed',
        "'^/data/user/0/', '/data/data/'",
        'phase=post_delete_restart',
        'Fatal action terminated without durable Tracebox evidence',
        'restart_segment=',
        'exit-tombstones-v1',
        'osexit_segments=',
        "'APPROVE AND CONTINUE'",
        "'SHOW TECHNICAL DETAILS'",
        "'HIDE TECHNICAL DETAILS'",
        'Technical disclosure facts were visible before explicit expansion',
        'Exact disclosure facts were not visible after explicit expansion',
        'function Remove-UidPacketCounter',
        'Remove-UidPacketCounter -Uid $uid -Chain $Chain',
        'scenario_anr_stall_started',
        "keyevent '--async' KEYCODE_DPAD_CENTER",
        'Wait-AndroidAnr',
        'am_anr',
        'android:id/aerr_close',
        'anr_auto_terminated=true'
)
if ($FullDiagnosticSuite) {
    $requiredRunnerBindings += @(
        'locksettings set-pin',
        'getprop sys.user.0.ce_available',
        'dumpsys user',
        'RUNNING_UNLOCKED',
        'RUNNING_LOCKED',
        'locksettings clear --old',
        'phase=explicit_reenable',
        'actual_stall=true',
        'startup_readiness_ms=',
        'scheduler_wakeup_delta=',
        'capture_overlap_heartbeat_samples=',
        'capture_overlap_target_pause_ms=',
        'capture_latency_ms=',
        'package_pss_kib=',
        'release_aar_bytes='
    )
}

foreach ($binding in $requiredRunnerBindings) {
    if (-not $runner.Contains($binding)) {
        throw "Personal-release runner is missing endpoint/provenance binding: $binding"
    }
}
$releaseScenarioRunner = $runner.Substring(
    $runner.IndexOf("Invoke-CertScenario 'INSTALL.READINESS'", [StringComparison]::Ordinal)
)
foreach ($staleRunnerBinding in @(
    'show_first_crash_dialog',
    'anr_show_background',
    'mResumedActivity'
)) {
    if ($releaseScenarioRunner.Contains($staleRunnerBinding)) {
        throw "Personal release runner retains stale platform binding: $staleRunnerBinding"
    }
}
foreach ($legacyInvocation in @(
        'LegacyPhase0Activity',
        'FaultReceiver',
        ':phase0_main',
        ':phase0_handler',
        ':worker',
        '-Lane Legacy',
        'Send-LabFault'
    )) {
    if ($releaseScenarioRunner.Contains($legacyInvocation)) {
        throw "Release gate invokes historical phase-0 control: $legacyInvocation"
    }
}
foreach ($productionEvidence in @(
        ':tracebox_handler',
        ':production_participant',
        'Assert-ProcessDeathAction',
        'Wait-RecoveredSegment',
        'scenario_anr_armed',
        'Get-TraceboxSegmentFingerprints'
    )) {
    if (-not $releaseScenarioRunner.Contains($productionEvidence)) {
        throw "Release gate lacks installed-production evidence: $productionEvidence"
    }
}
if ($FullDiagnosticSuite -and
    -not $releaseScenarioRunner.Contains('Assert-ProductionFixtureFaultCapture')) {
    throw 'Full diagnostic gate lacks production recursive-fault evidence'
}

$hostGateIndex = $runner.IndexOf('if (-not $SkipHostChecks)', [StringComparison]::Ordinal)
$buildIndex = $runner.IndexOf('if (-not $SkipBuild)', [StringComparison]::Ordinal)
$sourceFreezeIndex = $runner.IndexOf(
    '$sourceState = Get-RepositorySourceState -Root $root',
    [StringComparison]::Ordinal
)
$sourceRecheckIndex = $runner.IndexOf(
    '$currentSourceState = Get-RepositorySourceState -Root $root',
    [StringComparison]::Ordinal
)
$apkFreezeIndex = $runner.IndexOf(
    '$noInternetApkSha256 =',
    [StringComparison]::Ordinal
)
$apkInstallIndex = $runner.IndexOf(
    "Invoke-Adb install '-r' '-t' `$noInternetApk.FullName",
    [StringComparison]::Ordinal
)
$apkRecheckIndex = $runner.IndexOf(
    '$currentNoInternetApkSha256 =',
    [StringComparison]::Ordinal
)
$reportIndex = $runner.IndexOf('$report = [ordered]@{', [StringComparison]::Ordinal)
if (
    $sourceFreezeIndex -lt 0 -or
    $sourceFreezeIndex -gt $hostGateIndex -or
    $sourceFreezeIndex -gt $buildIndex -or
    $sourceRecheckIndex -lt $buildIndex -or
    $sourceRecheckIndex -gt $reportIndex -or
    $apkFreezeIndex -lt $buildIndex -or
    $apkFreezeIndex -gt $apkInstallIndex -or
    $apkRecheckIndex -lt $apkInstallIndex -or
    $apkRecheckIndex -gt $reportIndex -or
    -not $runner.Contains('$hostNetworkApkSha256 =') -or
    -not $runner.Contains('$currentHostNetworkApkSha256 =') -or
    -not $runner.Contains(
        '$currentNoInternetApkSha256 -ne $noInternetApkSha256'
    ) -or
    -not $runner.Contains(
        '$currentHostNetworkApkSha256 -ne $hostNetworkApkSha256'
    ) -or
    -not $runner.Contains('no_internet_apk_sha256 = $noInternetApkSha256') -or
    -not $runner.Contains('host_network_apk_sha256 = $hostNetworkApkSha256') -or
    -not $runner.Contains('source_state_stable = $true') -or
    -not $runner.Contains(
        'Source, scenario manifest, or installed fixture APK changed'
    )
) {
    throw (
        'Personal-release shortcut rejection or frozen source/APK provenance is incomplete'
    )
}

if (-not $FullDiagnosticSuite) {
    [ordered]@{
        schema = 'tracebox-personal-release-lab-host-v1'
        mode = 'PERSONAL_RELEASE'
        diagnostic_inventory_scenarios = $manifestIds.Count
        personal_release_required_scenarios = $personalReleaseIds.Count
        required_controller_implementations = $requiredControllerIds.Count
        transports = @($scenariosToValidate.transport | Sort-Object -Unique)
        variants = @($scenariosToValidate.variant | Sort-Object -Unique)
        minified_variants = @('noInternetRelease', 'hostNetworkRelease')
        qualification_variants = @(
            'noInternetQualificationRelease',
            'hostNetworkQualificationRelease'
        )
        optional_full_diagnostic_verifier = (
            'tools\verify\Verify-PersonalReleaseLab.ps1 -FullDiagnosticSuite'
        )
        emulator_execution = 'NOT_RUN_HOST_STATIC_ONLY'
        result = 'PASS'
    } | ConvertTo-Json -Depth 4
    return
}

$fatalCompletionStart = $runner.IndexOf(
    'function Complete-FatalCaptureAndRestart',
    [StringComparison]::Ordinal
)
$fatalCompletionEnd = $runner.IndexOf(
    'function Assert-ProductionFixtureFaultCapture',
    $fatalCompletionStart,
    [StringComparison]::Ordinal
)
$fatalCompletionBlock =
    $runner.Substring($fatalCompletionStart, $fatalCompletionEnd - $fatalCompletionStart)
foreach ($fatalCompletionContract in @(
        'Fatal action terminated without durable Tracebox evidence',
        '[switch] $RequireHandlerDump',
        'if ($RequireHandlerDump)',
        'Native fixture fault terminated without a new handler dump',
        "Start-LabAction `$noInternetPackage `$Scenario 'recover' -Wait",
        "Start-LabAction `$noInternetPackage `$Scenario 'readiness' -Wait",
        'Wait-RecoveredSegment',
        'Wait-ProductionHandlerReady',
        'Wait-ProductionReadiness',
        'Managed fault evidence did not remain durable after restart',
        'restartSegment = $recoveredSegment',
        'restartReadiness = $restartReadiness'
    )) {
    if (-not $fatalCompletionBlock.Contains($fatalCompletionContract)) {
        throw "Fatal restart-ingestion helper is missing: $fatalCompletionContract"
    }
}
if ($fatalCompletionBlock.Contains('am force-stop')) {
    throw 'Fatal restart helper must preserve the separate Tracebox handler process'
}
$recoveredIndex = $fatalCompletionBlock.IndexOf('Wait-RecoveredSegment', [StringComparison]::Ordinal)
$handlerReadyIndex = $fatalCompletionBlock.IndexOf(
    'Wait-ProductionHandlerReady',
    [StringComparison]::Ordinal
)
$finalReadinessIndex = $fatalCompletionBlock.IndexOf(
    "Start-LabAction `$noInternetPackage `$Scenario 'readiness' -Wait",
    [StringComparison]::Ordinal
)
if (
    $recoveredIndex -lt 0 -or
    $handlerReadyIndex -le $recoveredIndex -or
    $finalReadinessIndex -le $handlerReadyIndex
) {
    throw 'Fatal restart helper must retire evidence and re-arm the handler before final readiness'
}

$fixtureFatalStart = $fatalCompletionEnd
$fixtureFatalEnd = $runner.IndexOf(
    'function Get-DeviceFileSha256',
    $fixtureFatalStart,
    [StringComparison]::Ordinal
)
$fixtureFatalBlock =
    $runner.Substring($fixtureFatalStart, $fixtureFatalEnd - $fixtureFatalStart)
foreach ($fixtureFatalContract in @(
        'Start-ProductionFixtureActionAsync',
        'scenario_fault_armed',
        'Complete-FatalCaptureAndRestart',
        '-RequireHandlerDump',
        'restart_segment='
    )) {
    if (-not $fixtureFatalBlock.Contains($fixtureFatalContract)) {
        throw "Production fixture fatal proof is missing: $fixtureFatalContract"
    }
}

$processFatalStart = $runner.IndexOf(
    'function Assert-ProcessDeathAction',
    [StringComparison]::Ordinal
)
$processFatalEnd = $runner.IndexOf(
    '# LegacyPhase0Activity',
    $processFatalStart,
    [StringComparison]::Ordinal
)
$processFatalBlock =
    $runner.Substring($processFatalStart, $processFatalEnd - $processFatalStart)
foreach ($processFatalContract in @(
        'Start-LabAction $noInternetPackage $Scenario $Action',
        '$preDeathEvidence = Wait-Log $RequiredPreDeathMarker $TimeoutSeconds',
        '$fatalEvidenceBeforeSegments =',
        'Complete-FatalCaptureAndRestart',
        'restart_segment='
    )) {
    if (-not $processFatalBlock.Contains($processFatalContract)) {
        throw "Process-death proof is missing: $processFatalContract"
    }
}

$rustFatalBlock = [regex]::Match(
    $runner,
    "Invoke-CertScenario 'FAULT\.RUST_PANIC' \{([\s\S]*?)\r?\n\}"
)
foreach ($rustFatalContract in @(
        "Assert-ProcessDeathAction",
        "'FAULT.RUST_PANIC'",
        "'rust_panic'",
        '-RequiredPreDeathMarker',
        'scenario_state id=FAULT\.RUST_PANIC phase=rust_panic_probe',
        'outcome=PASS persisted=true payload_class=(?:0|1|2)',
        'location_code=\d{1,10} flags=7$'
    )) {
    if (-not $rustFatalBlock.Success -or
        -not $rustFatalBlock.Value.Contains($rustFatalContract)) {
        throw "Rust fatal diagnostic scenario is missing: $rustFatalContract"
    }
}
foreach ($fixtureFatalScenario in @('FAULT.RECURSIVE', 'FAULT.STACK_OVERFLOW')) {
    $fixtureScenarioBlock = [regex]::Match(
        $runner,
        "Invoke-CertScenario '$([regex]::Escape($fixtureFatalScenario))' " +
            "\{([\s\S]*?)\r?\n\}"
    )
    if (-not $fixtureScenarioBlock.Success -or
        -not $fixtureScenarioBlock.Value.Contains('Assert-ProductionFixtureFaultCapture')) {
        throw "$fixtureFatalScenario bypasses the production fatal restart-ingestion helper"
    }
}

$backgroundLifetimeBlock = [regex]::Match(
    $runner,
    "Invoke-CertScenario 'HANDLER\.BACKGROUND_LIFETIME' \{([\s\S]*?)\r?\n\}"
)
foreach ($backgroundContract in @(
        'input keyevent KEYCODE_HOME',
        'am make-uid-idle $noInternetPackage',
        'cmd deviceidle force-idle',
        'cmd deviceidle get deep',
        'cmd deviceidle unforce',
        'Get-TopResumedActivity',
        '$noInternetPackage`:tracebox_handler',
        "kill '-6' `$mainPid",
        'Wait-NewHandlerDump',
        'Wait-RecoveredSegment'
    )) {
    if (-not $backgroundLifetimeBlock.Success -or
        -not $backgroundLifetimeBlock.Value.Contains($backgroundContract)) {
        throw "Background-handler diagnostic scenario is missing: $backgroundContract"
    }
}
$blockedEgressBlock = [regex]::Match(
    $runner,
    "Invoke-CertScenario 'NETWORK\.BLOCKED_EGRESS' \{([\s\S]*?)\r?\n\}"
)
if (-not $blockedEgressBlock.Success -or
    -not $blockedEgressBlock.Value.Contains('-Package $hostNetworkPackage') -or
    -not $blockedEgressBlock.Value.Contains(
        "Start-LabAction `$hostNetworkPackage 'NETWORK.BLOCKED_EGRESS'"
    )) {
    throw 'Blocked-egress workflows must run in the host-network-capable positive-control app'
}

$resourceGateStart = $runner.IndexOf(
    "Invoke-CertScenario 'RESOURCE.BASELINE'",
    [StringComparison]::Ordinal
)
if ($resourceGateStart -lt 0) {
    throw 'Optional resource-baseline diagnostic block is missing'
}
$resourceGateEnd = $runner.IndexOf(
    '$corpusResult =',
    $resourceGateStart,
    [StringComparison]::Ordinal
)
if ($resourceGateEnd -le $resourceGateStart) {
    throw 'Optional resource-baseline diagnostic block is missing'
}
$resourceGate = $runner.Substring(
    $resourceGateStart,
    $resourceGateEnd - $resourceGateStart
)
foreach ($resourceGateContract in @(
        'capture_overlap_heartbeat_samples=(\d+)',
        'capture_overlap_target_pause_ms=(\d+)',
        '$captureOverlapSamples = [int]$Matches[1]',
        '$captureOverlapTargetPauseMillis = [long]$Matches[2]',
        '$captureOverlapSamples -ne 16',
        '$captureRecords -ne 32',
        '$captureOverlapTargetPauseMillis -gt 2000'
    )) {
    if (-not $resourceGate.Contains($resourceGateContract)) {
        throw "Resource gate does not parse and bound capture-overlap evidence: $resourceGateContract"
    }
}

$rustIsolationTask = ':test-apps:phase0-fixture:verifyFixtureRustPanicProbeIsolation'
$emulatorBuildGraph = [regex]::Match(
    $runner,
    '\$qualificationTasks\s*=\s*@\(([\s\S]*?)\)\s*if\s*\(-not\s+\$SkipBuild\)'
)
if (-not $emulatorBuildGraph.Success -or
    -not $emulatorBuildGraph.Value.Contains($rustIsolationTask)) {
    throw 'Emulator release build graph does not require fixture Rust panic-probe isolation'
}
$hostReadiness = Get-Content -LiteralPath $hostReadinessPath -Raw
$hostAndroidGraph = [regex]::Match(
    $hostReadiness,
    "Invoke-HostCheck 'android_jvm_fixture_and_release_conformance' \{" +
        "([\s\S]*?)Assert-LastExitCode 'Android JVM/fixture/release-conformance checks'\s*\r?\n\s*\}"
)
if (-not $hostAndroidGraph.Success -or
    -not $hostAndroidGraph.Value.Contains($rustIsolationTask)) {
    throw 'Host-readiness Android graph does not require fixture Rust panic-probe isolation'
}

$build = Get-Content -LiteralPath (Join-Path $fixtureRoot 'build.gradle.kts') -Raw
foreach ($buildContract in @(
        'buildConfig = true',
        'create("noInternet")',
        'create("hostNetwork")',
        'applicationIdSuffix = ".hostnetwork"',
        'isMinifyEnabled = true',
        'create("qualificationRelease")',
        'initWith(getByName("release"))'
    )) {
    if (-not $build.Contains($buildContract)) {
        throw "Consolidated lab build model is missing: $buildContract"
    }
}

$mainManifest = Get-Content -LiteralPath (Join-Path $fixtureRoot 'src\main\AndroidManifest.xml') -Raw
foreach ($componentContract in @(
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android:name=".MainActivity"',
        'android:name=".LegacyPhase0Activity"',
        'android:name=".LabPackageActivity"',
        'android:name=".HandlerService"',
        'android:name=".WorkerService"',
        'android:name=".ProductionParticipantService"',
        'android:name=".FaultReceiver"',
        'android:name=".LabDirectBootReceiver"',
        'android:directBootAware="true"',
        'android:process=":phase0_main"',
        'android:process=":phase0_handler"',
        'android:process=":worker"',
        'android:process=":production_participant"'
    )) {
    if (-not $mainManifest.Contains($componentContract)) {
        throw "Consolidated lab manifest is missing: $componentContract"
    }
}
$privateComponents = @(
    'HandlerService',
    'WorkerService',
    'ProductionParticipantService',
    'LabDirectBootReceiver'
)
foreach ($component in $privateComponents) {
    $componentPattern = 'android:name="\.' + [regex]::Escape($component) +
        '"[^>]*android:exported="false"'
    if ($mainManifest -notmatch $componentPattern) {
        throw "Lab component must remain private: $component"
    }
}
$legacyActivityPattern =
    'android:name="\.LegacyPhase0Activity"[^>]*android:exported="true"' +
    '[^>]*android:process=":phase0_main"'
if ($mainManifest -notmatch $legacyActivityPattern) {
    throw 'Legacy phase-0 activity is not isolated in :phase0_main'
}
$legacyReceiverPattern =
    'android:name="\.FaultReceiver"[^>]*android:exported="true"' +
    '[^>]*android:process=":phase0_main"'
if ($mainManifest -notmatch $legacyReceiverPattern) {
    throw 'Legacy fault receiver is not isolated in :phase0_main'
}

$productionActivity = Get-Content -LiteralPath (
    Join-Path $fixtureRoot 'src\main\kotlin\dev\tracebox\phase0\MainActivity.kt'
) -Raw
foreach ($legacyControl in @(
        'LabNativeIdentity',
        'HandlerService',
        'WorkerService',
        'AnrWatchdog',
        'NativeRuntime',
        'Phase0WatchdogRegistry'
    )) {
    if ($productionActivity.Contains($legacyControl)) {
        throw "Production activity references legacy phase-0 control: $legacyControl"
    }
}
$labRuntime = Get-Content -LiteralPath (
    Join-Path $fixtureRoot 'src\main\kotlin\dev\tracebox\phase0\LabRuntime.kt'
) -Raw
foreach ($runtimeContract in @(
        'result == PolicyUpdateResult.SUCCESS',
        'report == dev.tracebox.api.DeleteReport.COMPLETE',
        'isDurablyDisabled',
        'storageProgressed',
        'before_digest=',
        'after_digest='
    )) {
    if (-not $labRuntime.Contains($runtimeContract)) {
        throw "Production fixture runtime can pass without proven work: $runtimeContract"
    }
}
$packageActivity = Get-Content -LiteralPath (
    Join-Path $fixtureRoot 'src\main\kotlin\dev\tracebox\phase0\LabPackageActivity.kt'
) -Raw
foreach ($packageContract in @(
        'diagnosticPackage.save(this, destination)',
        'destinationDigest(destination)',
        'contentEquals(diagnosticPackage.plaintextDigestSha256)',
        'startActivityForResult(share, SHARE_REQUEST)',
        'chooser_returned=true'
    )) {
    if (-not $packageActivity.Contains($packageContract)) {
        throw "Save/share validation lacks exact evidence: $packageContract"
    }
}
$resourceProbe = $packageActivity.Substring(
    $packageActivity.IndexOf('private fun runResourceProbe()', [StringComparison]::Ordinal),
    $packageActivity.IndexOf('private fun logResult', [StringComparison]::Ordinal) -
        $packageActivity.IndexOf('private fun runResourceProbe()', [StringComparison]::Ordinal)
)
$heartbeatPost = $resourceProbe.IndexOf(
    'mainHandler.postDelayed(',
    [StringComparison]::Ordinal
)
$captureInvocation = $resourceProbe.IndexOf(
    'captureInvocations.incrementAndGet()',
    $heartbeatPost,
    [StringComparison]::Ordinal
)
$generatedCapture = $resourceProbe.IndexOf(
    'GeneratedDiagnostics.breadcrumb(',
    $captureInvocation,
    [StringComparison]::Ordinal
)
$heartbeatAwait = $resourceProbe.IndexOf(
    'completed.await(',
    $generatedCapture,
    [StringComparison]::Ordinal
)
if ($heartbeatPost -lt 0 -or
    $captureInvocation -le $heartbeatPost -or
    $generatedCapture -le $captureInvocation -or
    $heartbeatAwait -le $generatedCapture) {
    throw 'Resource target-pause heartbeat does not overlap GeneratedDiagnostics capture'
}
foreach ($resourceContract in @(
        'elapsed -',
        'RESOURCE_HEARTBEAT_INTERVAL_MILLIS',
        'captureInvocations.get() > capturesBeforeHeartbeat',
        'captureOverlapSamples.incrementAndGet()',
        'capture_overlap_heartbeat_samples=',
        'capture_overlap_target_pause_ms='
    )) {
    if (-not $resourceProbe.Contains($resourceContract)) {
        throw "Resource capture-overlap evidence is missing: $resourceContract"
    }
}
$productionNativeManifest = Get-Content -LiteralPath (
    Join-Path $root 'android\tracebox-native\src\main\AndroidManifest.xml'
) -Raw
foreach ($handlerContract in @(
        'android:name=".TraceboxHandlerService"',
        'android:exported="false"',
        'android:process=":tracebox_handler"'
    )) {
    if (-not $productionNativeManifest.Contains($handlerContract)) {
        throw "Production native handler manifest is missing: $handlerContract"
    }
}

$noInternetManifest = Get-Content -LiteralPath (
    Join-Path $fixtureRoot 'src\noInternet\AndroidManifest.xml'
) -Raw
$hostNetworkManifest = Get-Content -LiteralPath (
    Join-Path $fixtureRoot 'src\hostNetwork\AndroidManifest.xml'
) -Raw
if ($noInternetManifest -match 'android\.permission\.INTERNET') {
    throw 'The noInternet lab source manifest declares INTERNET'
}
if ($hostNetworkManifest -notmatch 'android\.permission\.INTERNET') {
    throw 'The hostNetwork positive-control manifest does not declare INTERNET'
}
$noInternetControl = Get-Content -LiteralPath (
    Join-Path $fixtureRoot 'src\noInternet\kotlin\dev\tracebox\phase0\HostNetworkControl.kt'
) -Raw
$hostNetworkControl = Get-Content -LiteralPath (
    Join-Path $fixtureRoot 'src\hostNetwork\kotlin\dev\tracebox\phase0\HostNetworkControl.kt'
) -Raw
if ($noInternetControl -match 'java\.net' -or $hostNetworkControl -notmatch 'java\.net') {
    throw 'Network-control source separation is incomplete'
}

$productionLabMentions = @(
    Get-ChildItem (Join-Path $root 'android') -Recurse -File -Include *.kt, *.java, *.xml, *.c, *.cc, *.h |
        Where-Object {
            $_.FullName -notmatch '\\build\\' -and
            (Get-Content -LiteralPath $_.FullName -Raw).Contains('dev.tracebox.phase0')
        } |
        ForEach-Object FullName
)
if ($productionLabMentions) {
    throw "Lab namespace leaked into production source: $($productionLabMentions -join ', ')"
}

[ordered]@{
    schema = 'tracebox-personal-release-lab-host-v1'
    mode = 'FULL_DIAGNOSTIC'
    diagnostic_inventory_scenarios = $manifestIds.Count
    personal_release_required_scenarios = $personalReleaseIds.Count
    controller_implementations = $implementedIds.Count
    transports = @($scenarios.transport | Sort-Object -Unique)
    variants = @($scenarios.variant | Sort-Object -Unique)
    minified_variants = @('noInternetRelease', 'hostNetworkRelease')
    qualification_variants = @(
        'noInternetQualificationRelease',
        'hostNetworkQualificationRelease'
    )
    emulator_execution = 'NOT_RUN_HOST_STATIC_ONLY'
    result = 'PASS'
} | ConvertTo-Json -Depth 4
