$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ledgerPath = Join-Path $repositoryRoot 'docs\project\implementation-ledger.md'
$checklistPath = Join-Path $repositoryRoot 'docs\traceability\personal-release-checklist.csv'
$workPackagePath = Join-Path $repositoryRoot 'docs\traceability\work-packages.csv'

$allowedStates = @(
    'NOT_STARTED',
    'IN_PROGRESS',
    'PASS',
    'FAIL',
    'BLOCKED_PRODUCT_DECISION',
    'UNAVAILABLE_EXTERNAL',
    'NOT_APPLICABLE_WITH_RATIONALE'
)

$workPackages = [Collections.Generic.List[object]]::new()
foreach ($line in Get-Content $ledgerPath -Encoding utf8) {
    if ($line -notmatch '^\| ((?:F0|C1|R2|X3|P4|T5|E6)\.\d+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([A-Z_]+) \| ([^|]*) \| ([^|]*) \|$') {
        continue
    }

    $state = $Matches[5].Trim()
    if ($state -notin $allowedStates) {
        throw "Unsupported work-package state '$state' for $($Matches[1])."
    }

    $workPackages.Add([pscustomobject]@{
        work_package = $Matches[1].Trim()
        work = $Matches[2].Trim()
        dependencies = $Matches[3].Trim()
        acceptance = $Matches[4].Trim()
        status = $state
        satisfied_by_prior_commit = $Matches[6].Trim()
        notes_or_evidence = $Matches[7].Trim()
    })
}

if ($workPackages.Count -lt 40) {
    throw "Expected the complete Phase 0-6 work-package ledger, found only $($workPackages.Count) rows."
}

$duplicatePackages = $workPackages |
    Group-Object work_package |
    Where-Object Count -ne 1
if ($duplicatePackages) {
    throw "Duplicate work-package IDs: $(($duplicatePackages.Name | Sort-Object) -join ', ')"
}

$checklist = Import-Csv $checklistPath
if ($checklist.Count -lt 20) {
    throw "Personal release checklist is unexpectedly incomplete: $($checklist.Count) rows."
}

$requiredChecklistColumns = @(
    'requirement_id',
    'area',
    'requirement',
    'implementation_or_evidence',
    'required_lane',
    'status'
)
foreach ($column in $requiredChecklistColumns) {
    if ($column -notin $checklist[0].PSObject.Properties.Name) {
        throw "Personal release checklist is missing column '$column'."
    }
}

$duplicateRequirements = $checklist |
    Group-Object requirement_id |
    Where-Object Count -ne 1
if ($duplicateRequirements) {
    throw "Duplicate personal-release requirement IDs: $(($duplicateRequirements.Name | Sort-Object) -join ', ')"
}

foreach ($requirement in $checklist) {
    if ([string]::IsNullOrWhiteSpace($requirement.requirement_id) -or
        [string]::IsNullOrWhiteSpace($requirement.requirement) -or
        [string]::IsNullOrWhiteSpace($requirement.implementation_or_evidence) -or
        [string]::IsNullOrWhiteSpace($requirement.required_lane)) {
        throw "Personal-release requirement contains an empty required field: $($requirement.requirement_id)"
    }
    if ($requirement.status -notin $allowedStates) {
        throw "Unsupported checklist state '$($requirement.status)' for $($requirement.requirement_id)."
    }
}

$workPackages |
    Sort-Object work_package |
    Export-Csv $workPackagePath -NoTypeInformation -Encoding utf8

Write-Output "work_packages=$($workPackages.Count); personal_requirements=$($checklist.Count)"
