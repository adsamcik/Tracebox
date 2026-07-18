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
    'API 30 x86_64',
    'API 37 x86_64',
    'False-positive run',
    'Handler idle PSS',
    'Handler healthy CPU',
    'Fatal crash to durable artifact',
    'Heartbeat post work',
    'Target-process pause',
    'Presubmit: 60 seconds',
    'Nightly: 30 minutes',
    'Certification: 4 hours'
)
foreach ($term in $requiredProtocolTerms) {
    if (-not $protocol.Contains($term)) {
        throw "Measurement protocol is missing: $term"
    }
}

$emergency = Get-Content 'specs\emergency-record-v1.md' -Raw
if ($emergency -notmatch 'One preallocated slot is exactly 256 bytes' -or
    $emergency -notmatch '\| 248 \| 8 \| completion marker') {
    throw 'Emergency record layout is incomplete'
}

$traceability = Import-Csv 'docs\traceability\requirements.csv'
if ($traceability.Count -lt 1000) {
    throw 'Traceability coverage unexpectedly small'
}
$workPackage = $traceability | Where-Object requirement_id -eq 'WP-F0.1'
if ($workPackage.status -ne 'PASS') {
    throw 'WP-F0.1 is not recorded as PASS'
}

[pscustomobject]@{
    accepted_adrs = $resolvedAdrs.Count
    open_decisions = 12
    traceability_rows = $traceability.Count
    emergency_record_bytes = 256
    result = 'PASS'
} | ConvertTo-Json
