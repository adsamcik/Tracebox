function ConvertFrom-PmPackageUid {
    [OutputType([int])]
    param(
        [Parameter(Mandatory)]
        [ValidateNotNullOrEmpty()]
        [string] $Package,
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [AllowNull()]
        [string[]] $Lines
    )

    $pattern =
        '^\s*package:' +
        [regex]::Escape($Package) +
        '\s+uid:(\d+)\s*$'
    $uids = @(
        foreach ($line in @($Lines)) {
            if ($null -eq $line -or $line -notmatch $pattern) {
                continue
            }
            $parsed = [long]$Matches[1]
            if ($parsed -gt [int]::MaxValue) {
                throw "Android package UID exceeds Int32 range: $parsed"
            }
            [int]$parsed
        }
    )
    if ($uids.Count -ne 1) {
        throw (
            "Expected exactly one exact UID row for Android package '$Package' " +
            "from 'pm list packages -U --user 0'; found $($uids.Count)"
        )
    }
    return [int]$uids[0]
}

function Get-RepositorySourceState {
    param(
        [Parameter(Mandatory)]
        [ValidateNotNullOrEmpty()]
        [string] $Root
    )

    $resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
    $entries = [Collections.Generic.List[string]]::new()
    $trackedChanges = @(
        & git -C $resolvedRoot diff `
            --name-status `
            --no-renames `
            HEAD `
            -- `
            . `
            ':(exclude)evidence/**'
    )
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to enumerate tracked source changes'
    }
    foreach ($change in $trackedChanges) {
        if ([string]::IsNullOrWhiteSpace($change)) {
            continue
        }
        $fields = $change -split "`t", 2
        if ($fields.Count -ne 2) {
            throw "Malformed tracked source-state row: $change"
        }
        $path = $fields[1].Replace('\', '/')
        $absolute = Join-Path $resolvedRoot $path
        $contentSha256 = if (Test-Path -LiteralPath $absolute -PathType Leaf) {
            (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash.ToLowerInvariant()
        } else {
            'MISSING'
        }
        $entries.Add("$($fields[0])`t$path`t$contentSha256")
    }

    $untrackedPaths = @(
        & git -C $resolvedRoot ls-files --others --exclude-standard |
            Where-Object { $_ -notlike 'evidence/*' }
    )
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to enumerate untracked source changes'
    }
    foreach ($path in $untrackedPaths) {
        $normalizedPath = $path.Replace('\', '/')
        $absolute = Join-Path $resolvedRoot $normalizedPath
        if (-not (Test-Path -LiteralPath $absolute -PathType Leaf)) {
            throw "Untracked source-state path is not a file: $normalizedPath"
        }
        $contentSha256 =
            (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash.ToLowerInvariant()
        $entries.Add("??`t$normalizedPath`t$contentSha256")
    }

    $sortedEntries = @($entries | Sort-Object)
    $serialized = "tracebox-source-state-v1`n$($sortedEntries -join "`n")"
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $sha256 = (
            [BitConverter]::ToString(
                $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($serialized))
            ) -replace '-',
            ''
        ).ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
    return [pscustomobject]@{
        sha256 = $sha256
        entries = $sortedEntries
    }
}

function Build-TbdiagExecutable {
    [OutputType([string])]
    param(
        [Parameter(Mandatory)]
        [ValidateNotNullOrEmpty()]
        [string] $Root
    )

    $resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
    & cargo build `
        -q `
        -p tbdiag-cli `
        --bin tbdiag `
        --locked `
        --offline `
        --manifest-path (Join-Path $resolvedRoot 'Cargo.toml')
    $buildExitCode = $LASTEXITCODE
    if ($buildExitCode -ne 0) {
        throw "tbdiag build failed with exit code $buildExitCode"
    }

    $extension = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
    return (
        Resolve-Path -LiteralPath (
            Join-Path $resolvedRoot "target\debug\tbdiag$extension"
        )
    ).Path
}
