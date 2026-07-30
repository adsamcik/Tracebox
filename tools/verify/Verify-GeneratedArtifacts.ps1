$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$goldenRoot = Join-Path $root 'tooling\schema-compiler\tests\golden'
$pairs = @(
    [pscustomobject]@{
        checked_in = 'android\tracebox-api\src\main\kotlin\dev\tracebox\api\generated\GeneratedSchema.kt'
        golden = 'android\tracebox-api\src\main\kotlin\dev\tracebox\api\generated\GeneratedSchema.kt'
    },
    [pscustomobject]@{
        checked_in = 'android\tracebox-directboot\src\main\kotlin\dev\tracebox\directboot\GeneratedDirectBootSchema.kt'
        golden = 'android\tracebox-directboot\src\main\kotlin\dev\tracebox\directboot\GeneratedDirectBootSchema.kt'
    },
    [pscustomobject]@{
        checked_in = 'native\generated\tracebox_generated_events.c'
        golden = 'native\generated\tracebox_generated_events.c'
    },
    [pscustomobject]@{
        checked_in = 'native\include\tracebox\generated_events.h'
        golden = 'native\include\tracebox\generated_events.h'
    },
    [pscustomobject]@{
        checked_in = 'rust\tracebox-sys\src\generated.rs'
        golden = 'rust\tracebox-sys\src\generated.rs'
    },
    [pscustomobject]@{
        checked_in = 'schema\generated\decoder-metadata.json'
        golden = 'schema\generated\decoder-metadata.json'
    },
    [pscustomobject]@{
        checked_in = 'schema\generated\disclosure-labels.json'
        golden = 'schema\generated\disclosure-labels.json'
    },
    [pscustomobject]@{
        checked_in = 'schema\generated\tracebox_records.proto'
        golden = 'schema\generated\tracebox_records.proto'
    },
    [pscustomobject]@{
        checked_in = 'docs\generated\schema-reference.md'
        golden = 'docs\generated\schema-reference.md'
    }
)

$results = foreach ($pair in $pairs) {
    $checkedIn = Join-Path $root $pair.checked_in
    $golden = Join-Path $goldenRoot $pair.golden
    if (-not (Test-Path -LiteralPath $checkedIn -PathType Leaf)) {
        throw "Missing checked-in generated artifact: $checkedIn"
    }
    if (-not (Test-Path -LiteralPath $golden -PathType Leaf)) {
        throw "Missing schema-compiler golden: $golden"
    }
    $checkedInHash = (Get-FileHash -LiteralPath $checkedIn -Algorithm SHA256).Hash.ToLowerInvariant()
    $goldenHash = (Get-FileHash -LiteralPath $golden -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($checkedInHash -ne $goldenHash) {
        throw "Generated artifact drift: $($pair.checked_in) differs from its compiler golden"
    }
    [pscustomobject]@{
        path = $pair.checked_in
        sha256 = $checkedInHash
    }
}

[ordered]@{
    schema = 'tracebox-generated-artifact-drift-v1'
    artifacts = @($results)
    count = $results.Count
    result = 'PASS'
} | ConvertTo-Json -Depth 4
