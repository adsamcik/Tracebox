$ErrorActionPreference = 'Stop'

$requiredAdrs = 2..7 | ForEach-Object { 'docs\adr\{0:D4}-*.md' -f $_ }
$resolvedAdrs = foreach ($pattern in $requiredAdrs) {
    $match = Get-ChildItem $pattern
    if ($match.Count -ne 1) {
        throw "Expected exactly one ADR for $pattern"
    }
    $match
}

foreach ($adr in $resolvedAdrs) {
    $text = Get-Content $adr.FullName -Raw
    if (-not $text.Contains('Accepted by implementation assignment')) {
        throw "$($adr.Name) does not have the assignment-authorized status"
    }
}

$closure = Get-Content 'docs\adr\0007-open-decision-closure.md' -Raw
foreach ($decision in 1..12) {
    if ($closure -notmatch "(?m)^\| $decision\. ") {
        throw "Open decision $decision is not mapped"
    }
}

$protocol = Get-Content 'specs\phase0-measurement-protocol.md' -Raw
$requiredProtocolTerms = @(
    'Existing API 36 x86_64 emulator, 4 KiB',
    'Additional API, ABI, page-size, physical-device, and OEM lanes are advisory',
    'Run one ten-minute healthy watchdog false-candidate observation',
    'Handler timer/poll wakeups',
    'Ineligible watchdog wakeups after settling',
    'Healthy false confirmations',
    'Deterministic six-second stall',
    'Handler-hang timeout/cancellation',
    'Presubmit: 60 seconds',
    'Personal release: 5 minutes'
)
foreach ($term in $requiredProtocolTerms) {
    if (-not $protocol.Contains($term)) {
        throw "Measurement protocol is missing: $term"
    }
}

$retargeting = Get-Content 'docs\adr\0009-api-23-single-emulator-qualification.md' -Raw
if (-not $retargeting.Contains('Accepted by explicit user decision on 2026-07-22') -or
    -not $retargeting.Contains('minSdk 23') -or
    -not $retargeting.Contains('existing API 36') -or
    -not $retargeting.Contains('`x86_64`, 4 KiB emulator')) {
    throw 'ADR-0009 does not freeze the API 23 single-emulator contract'
}

$personalScope = Get-Content 'docs\adr\0010-personal-project-release-scope.md' -Raw
foreach ($term in @(
        'Accepted by explicit user decision on 2026-07-29',
        '`IMPLEMENTATION_COMPLETE`',
        '`PERSONAL_RELEASE_READY`',
        'one configurable `tracebox-lab`',
        'one final baseline-to-HEAD review',
        'Tracker integration'
    )) {
    if (-not $personalScope.Contains($term)) {
        throw "ADR-0010 does not freeze the personal-project contract: $term"
    }
}

$libraryPlugin = Get-Content `
    'build-logic\src\main\kotlin\tracebox\buildlogic\TraceboxAndroidLibraryPlugin.kt' -Raw
$applicationPlugin = Get-Content `
    'build-logic\src\main\kotlin\tracebox\buildlogic\TraceboxAndroidApplicationPlugin.kt' -Raw
foreach ($plugin in @($libraryPlugin, $applicationPlugin)) {
    if ($plugin -notmatch 'minSdk\s*=\s*23' -or
        $plugin -notmatch 'isCoreLibraryDesugaringEnabled\s*=\s*true' -or
        -not $plugin.Contains('desugar-jdk-libs-nio')) {
        throw 'Android convention plugins do not enforce API 23 with NIO desugaring'
    }
}

$catalog = Get-Content 'gradle\libs.versions.toml' -Raw
if ($catalog -notmatch 'minSdk\s*=\s*"23"' -or
    $catalog -notmatch 'compileSdk\s*=\s*"37"' -or
    $catalog -notmatch 'targetSdk\s*=\s*"37"' -or
    $catalog -notmatch 'desugarJdkLibsNio\s*=\s*"2\.1\.5"') {
    throw 'Version catalog does not pin the API 23/SDK 37/desugaring contract'
}

foreach ($manifest in @(
        'android\tracebox-anr-exit\src\main\AndroidManifest.xml',
        'android\tracebox-export-ui\src\main\AndroidManifest.xml',
        'android\tracebox-native\src\main\AndroidManifest.xml'
    )) {
    if (-not (Get-Content $manifest -Raw).Contains('android:minSdkVersion="23"')) {
        throw "$manifest does not declare minSdkVersion 23"
    }
}

$qualificationRunner = Get-Content 'tools\android\Run-Phase0Qualification.ps1' -Raw
foreach ($term in @(
        '[int] $ExpectedApi = 36',
        '[int] $ExpectedPageSize = 4096',
        '[switch] $Advisory',
        "qualification_scope = `$qualificationScope",
        'working_tree_patch_sha256 = Get-SourcePatchSha256',
        "Required qualification must run on the existing x86_64 emulator"
    )) {
    if (-not $qualificationRunner.Contains($term)) {
        throw "Qualification runner is not bound to ADR-0009: $term"
    }
}

$emergency = Get-Content 'specs\emergency-record-v1.md' -Raw
if ($emergency -notmatch 'One preallocated slot is exactly 256 bytes' -or
    $emergency -notmatch '\| 248 \| 8 \| completion marker') {
    throw 'Emergency record layout is incomplete'
}

$traceability = Import-Csv 'docs\traceability\personal-release-checklist.csv'
if ($traceability.Count -lt 20) {
    throw 'Personal-release traceability coverage unexpectedly small'
}
$scopeRequirement = $traceability | Where-Object requirement_id -eq 'PR-001'
if ($scopeRequirement.status -ne 'PASS') {
    throw 'PR-001 is not recorded as PASS'
}

[pscustomobject]@{
    accepted_adrs = $resolvedAdrs.Count
    explicit_scope_adrs = 2
    open_decisions = 12
    personal_release_requirements = $traceability.Count
    emergency_record_bytes = 256
    result = 'PASS'
} | ConvertTo-Json
