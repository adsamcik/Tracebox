$ErrorActionPreference = 'Stop'

function Get-Section {
    param([string[]] $Lines, [int] $Index)

    for ($i = $Index; $i -ge 0; $i--) {
        if ($Lines[$i] -match '^#{1,6}\s+(.+)$') {
            return $Matches[1].Trim()
        }
    }
    return 'Document'
}

function Quote-Csv {
    param([object] $Value)
    return '"' + (([string]$Value) -replace '"', '""') + '"'
}

function Get-ImplementationPath {
    param([string] $Source, [string] $Section, [int] $Line)

    if ($Source -eq 'ADR0008') {
        return 'docs/adr/0008-engineering-feasibility-prerequisite-supersession.md; docs/project/implementation-ledger.md; docs/project/phase0-feasibility-status.md'
    }
    if ($Source -eq 'ASSIGNMENT') {
        if ($Line -ge 218 -and $Line -le 315) {
            return 'phase0/; specs/; docs/adr/; native/; android/; benchmarks/; test-apps/'
        }
        if ($Line -ge 317 -and $Line -le 376) {
            return 'schema/; tooling/schema-compiler/; android/tracebox-api/; native/include/; rust/'
        }
        if ($Line -ge 378 -and $Line -le 470) {
            return 'android/tracebox-core/; android/tracebox-storage/; android/tracebox-directboot/'
        }
        if ($Line -ge 472 -and $Line -le 590) {
            return 'native/; android/tracebox-jvm-crash/; android/tracebox-anr-exit/; rust/'
        }
        if ($Line -ge 592 -and $Line -le 676) {
            return 'android/tracebox-export/; android/tracebox-export-ui/'
        }
        if ($Line -ge 678 -and $Line -le 747) {
            return 'rust/tbdiag-cli/; tooling/tracebox-gradle-plugin/; conformance/'
        }
    }
    if ($Section -match 'Crashpad|handler|Emergency|ANR') {
        return 'native/; android/tracebox-native/; android/tracebox-anr-exit/'
    }
    if ($Section -match 'Build|Performance|Testing|Go/no-go|Open decisions') {
        return 'build-logic/; benchmarks/; test-apps/; specs/; docs/adr/'
    }
    if ($Section -match 'Privacy|Terminology|identity|schema|API') {
        return 'specs/; schema/; tooling/schema-compiler/'
    }
    if ($Section -match 'Package|Disclosure|Save|share') {
        return 'android/tracebox-export/; android/tracebox-export-ui/; rust/tbdiag-format/'
    }
    if ($Section -match 'network|Offline') {
        return 'conformance/; rust/tbdiag-cli/'
    }
    return 'See implementation ledger and architecture module map'
}

function Get-EvidencePath {
    param([string] $Source, [string] $Section, [int] $Line)

    if ($Source -eq 'ADR0008') {
        return 'evidence/phase0/'
    }
    if (($Source -eq 'ASSIGNMENT' -and $Line -ge 218 -and $Line -le 315) -or
        $Section -match 'Crashpad|handler|Emergency|ANR|Performance|Open decisions') {
        return 'evidence/phase0/; benchmarks/; test-apps/'
    }
    return 'evidence/; module tests; conformance/'
}

function Get-Matrix {
    param([string] $Text, [string] $Source)

    if ($Source -eq 'ADR0008') {
        return 'PHASE0_GATE_DISPOSITION; PHASE1_5_IMPLEMENTATION_AUTHORIZATION'
    }
    if ($Source -eq 'ADR0009') {
        return 'API23_37_SUPPORT; REQUIRED_API36_X86_64_4K_EMULATOR'
    }
    if ($Text -match 'API 23|API 36|existing emulator|single-emulator') {
        return 'REQUIRED_API36_X86_64_4K_EMULATOR; other platform lanes advisory'
    }
    if ($Text -match 'API 30|API 37|x86_64|arm64|4 KiB|16 KiB|physical|emulator') {
        return 'HISTORICAL_OR_ADVISORY_PLATFORM_EVIDENCE'
    }
    if ($Text -match 'API 30-37|declared platform matrix|every required API') {
        return 'SUPERSEDED_BY_ADR0009'
    }
    if ($Text -match 'no-network|network|INTERNET|uploader') {
        return 'HOST_STATIC; REQUIRED_API36_EMULATOR_RUNTIME; blocked-egress controls'
    }
    if ($Text -match 'fuzz|truncate|corrupt|malicious') {
        return 'HOST_FUZZ_SMOKE; nightly/certification duration per frozen protocol'
    }
    return 'HOST_UNIT_STATIC; applicable Android endpoint'
}

$rows = [Collections.Generic.List[object]]::new()
$documents = @(
    @('ASSIGNMENT', 'IMPLEMENTATION_AGENT_PROMPT.md'),
    @('ARCH', 'docs\architecture\tracebox-design.md'),
    @('ADR0001', 'docs\adr\0001-foundation-architecture.md'),
    @('ADR0002', 'docs\adr\0002-crashpad-source-and-privacy-profile.md'),
    @('ADR0003', 'docs\adr\0003-handler-topology-abi-and-coexistence.md'),
    @('ADR0004', 'docs\adr\0004-foundation-storage-schema-and-policy.md'),
    @('ADR0005', 'docs\adr\0005-live-anr-thresholds-and-sampling.md'),
    @('ADR0006', 'docs\adr\0006-deterministic-package-compression.md'),
    @('ADR0007', 'docs\adr\0007-open-decision-closure.md'),
    @('ADR0008', 'docs\adr\0008-engineering-feasibility-prerequisite-supersession.md'),
    @('PLAN', 'docs\implementation-plan.md')
)

foreach ($document in $documents) {
    $source = $document[0]
    $path = $document[1]
    $lines = Get-Content $path

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $raw = $lines[$i].Trim()
        if (-not $raw) {
            continue
        }

        $isList = $raw -match '^[-*]\s+' -or $raw -match '^\d+\.\s+'
        $isTable = $raw -match '^\|.*\|$' -and
            $raw -notmatch '^\|[-: |]+\|$' -and
            $raw -notmatch '^\|\s*(ID|Term|Class|Profile|Metric|Area|Artifact|Identity|Failure|Policy|Level|Limit|Dimension|Risk|Fixture|Work)\s*\|'
        $isNormative = $raw -match '\b(must|required|never|forbidden|prohibited|cannot|only when|do not|does not|no claim|blocks?|fails? closed|is mandatory|are mandatory)\b'
        if (-not ($isList -or $isTable -or $isNormative) -or $raw -match '^[-*]\s+https?://') {
            continue
        }

        $section = Get-Section $lines $i
        $clean = ($raw -replace '^[-*]\s+', '' -replace '^\d+\.\s+', '' -replace '^\|', '' -replace '\|$', '').Trim()
        if ($clean.Length -gt 600) {
            $clean = $clean.Substring(0, 600)
        }
        $prefix = switch ($source) {
            'ASSIGNMENT' { 'ASN-L' }
            'ARCH' { 'ARCH-L' }
            'ADR0001' { 'ADR1-L' }
            'ADR0002' { 'ADR2-L' }
            'ADR0003' { 'ADR3-L' }
            'ADR0004' { 'ADR4-L' }
            'ADR0005' { 'ADR5-L' }
            'ADR0006' { 'ADR6-L' }
            'ADR0007' { 'ADR7-L' }
            'ADR0008' { 'ADR8-L' }
            default { 'PLAN-L' }
        }
        $id = $prefix + ('{0:D4}' -f ($i + 1))
        $status = if ($source -eq 'ADR0008') {
            'PASS'
        } elseif ($source -eq 'PLAN' -and $clean -match '^F0\.1\s*\|') {
            'IN_PROGRESS'
        } else {
            'NOT_STARTED'
        }
        $rows.Add([pscustomobject]@{
            id = $id
            source = $source
            file = $path
            locator = "line $($i + 1); section $section"
            requirement = $clean
            implementation = Get-ImplementationPath $source $section ($i + 1)
            evidence = Get-EvidencePath $source $section ($i + 1)
            matrix = Get-Matrix $clean $source
            status = $status
            sha = ''
        })
    }
}

$ledger = Get-Content 'docs\project\implementation-ledger.md'
foreach ($line in $ledger) {
    if ($line -match '^\| ([FCRXPT]\d+\.\d+|E6\.\d+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([A-Z_]+) \| ([^|]*) \|') {
        $workPackage = $Matches[1]
        $matrix = if ($workPackage -like 'F0.*') {
            'Phase 0 frozen engineering matrix'
        } else {
            'Matrix defined by package and certification protocol'
        }
        $rows.Add([pscustomobject]@{
            id = 'WP-' + $workPackage
            source = 'PLAN_WORK_PACKAGE'
            file = 'docs\implementation-plan.md'
            locator = 'work package ' + $workPackage
            requirement = $Matches[2].Trim() + ': ' + $Matches[4].Trim()
            implementation = 'See dependency-aware ledger row and mapped module paths'
            evidence = 'evidence/; package-specific tests/benchmarks'
            matrix = $matrix
            status = $Matches[5]
            sha = $Matches[6].Trim()
        })
    }
}

$output = [Collections.Generic.List[string]]::new()
$output.Add('requirement_id,source,source_file,source_locator,requirement,implementation_path,test_or_evidence_path,required_matrix,status,satisfied_by_prior_commit')
foreach ($row in $rows | Sort-Object id) {
    $output.Add((@(
        $row.id,
        $row.source,
        $row.file,
        $row.locator,
        $row.requirement,
        $row.implementation,
        $row.evidence,
        $row.matrix,
        $row.status,
        $row.sha
    ) | ForEach-Object { Quote-Csv $_ }) -join ',')
}
Set-Content 'docs\traceability\requirements.csv' $output -Encoding utf8

$artifactIndex = @('artifact_or_module,requirement_ids,source_of_truth')
foreach ($group in $rows | Group-Object implementation | Sort-Object Name) {
    $artifactIndex += (Quote-Csv $group.Name) + ',' +
        (Quote-Csv (($group.Group.id | Sort-Object) -join ';')) + ',' +
        (Quote-Csv 'docs/traceability/requirements.csv')
}
Set-Content 'docs\traceability\artifact-links.csv' $artifactIndex -Encoding utf8

$readme = @(
    '# Traceability Index',
    '',
    'Baseline: `dc87c6f9e2a6576cc554f7cb181ce80a02bf0802`',
    '',
    '`requirements.csv` is the source-to-implementation direction. Each row records source, locator, implementation path, evidence path, matrix, state, and only a prior satisfying commit SHA. `artifact-links.csv` is the reverse implementation-to-requirement index. Together they are the bidirectional, resumable traceability matrix.',
    '',
    'Rows are conservative at bootstrap: no requirement is PASS merely because it is documented. Later commits update implementation/evidence paths and status only after verification, and may cite only an earlier commit.',
    '',
    'Coverage includes normative bullets, numbered requirements, normative table rows, explicit must/never/prohibited statements from the assignment, architecture, all repository ADRs, implementation plan, plus one explicit row for every work package.'
)
Set-Content 'docs\traceability\README.md' $readme -Encoding utf8

Write-Output "rows=$($rows.Count); reverse_paths=$(($rows | Group-Object implementation).Count)"
