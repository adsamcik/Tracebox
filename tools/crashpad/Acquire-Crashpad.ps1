param([switch] $Force)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    $modernPowerShell = Get-Command pwsh.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $modernPowerShell) {
        throw 'Crashpad source verification requires PowerShell 7 or newer'
    }
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $PSCommandPath
    )
    if ($Force) {
        $arguments += '-Force'
    }
    & $modernPowerShell.Source @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "PowerShell 7 Crashpad acquisition failed with exit code $LASTEXITCODE"
    }
    return
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'CrashpadArchive.ps1')
$lockPath = Join-Path $root 'third_party\crashpad\source-lock.json'
$checkout = Join-Path $root 'third_party\crashpad\checkout'
$downloads = Join-Path $root '.bootstrap\crashpad-acquire'
$lock = Get-Content $lockPath -Raw | ConvertFrom-Json
$sourceLockHash =
    (Get-FileHash $lockPath -Algorithm SHA256).Hash.ToLowerInvariant()

function Get-Sha256Hex {
    param([byte[]] $Bytes)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return (
            [BitConverter]::ToString($algorithm.ComputeHash($Bytes)) -replace '-', ''
        ).ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

$seriesPath = Join-Path $root 'third_party\crashpad\patches\series'
$series = Get-Content $seriesPath |
    Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') }
$patchMaterial = foreach ($relativePatch in $series) {
    $patch = Join-Path $root "third_party\crashpad\patches\$relativePatch"
    "$relativePatch`n$((Get-FileHash $patch -Algorithm SHA256).Hash.ToLowerInvariant())`n"
}
$patchSetHash =
    Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes(($patchMaterial -join '')))

function Get-TreeHash {
    param(
        [string] $Directory,
        [string[]] $ExcludedRelativePrefixes = @()
    )

    $entries = foreach ($file in Get-ChildItem $Directory -Recurse -File |
            Sort-Object FullName) {
        $relative = $file.FullName.Substring($Directory.Length + 1).Replace('\', '/')
        $excluded = $false
        foreach ($prefix in $ExcludedRelativePrefixes) {
            if ($relative -eq $prefix -or $relative.StartsWith($prefix)) {
                $excluded = $true
                break
            }
        }
        if ($excluded) {
            continue
        }
        $hash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relative`n$hash`n"
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($entries -join ''))
    return Get-Sha256Hex $bytes
}

New-Item -ItemType Directory -Force $checkout, $downloads | Out-Null
$verifiedManifest = Join-Path $checkout 'verified-sources.json'
$generatedTreeExclusions = @($lock.post_patch_tree_excluded_generated_paths)
$expectedPostPatchTreeHash = $lock.post_patch_tree_sha256
if (-not $expectedPostPatchTreeHash -or $generatedTreeExclusions.Count -eq 0) {
    throw 'Source lock is missing the reviewed post-patch tree profile'
}
if (-not $Force -and (Test-Path $verifiedManifest)) {
    $verified = Get-Content $verifiedManifest -Raw | ConvertFrom-Json
    if ($verified.source_lock_sha256 -eq $sourceLockHash -and
        $verified.patch_set_sha256 -eq $patchSetHash -and
        $verified.components.Count -eq $lock.components.Count) {
        $actualPostPatchTreeHash =
            Get-TreeHash $checkout $generatedTreeExclusions
        if ($actualPostPatchTreeHash -eq $expectedPostPatchTreeHash) {
            Write-Output "Reusing verified Crashpad checkout with $($lock.components.Count) components."
            return
        }
        Write-Warning 'Cached Crashpad checkout failed post-patch tree verification; reconstructing.'
    }
}

foreach ($component in $lock.components) {
    if ($component.archive_size -le 0 -or
        $component.archive_sha256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw "Source lock is missing archive authentication: $($component.name)"
    }
    $vendoredArchive = if ($component.archive_path) {
        Join-Path $root "third_party\crashpad\$($component.archive_path)"
    } else {
        $null
    }
    $archive = if ($vendoredArchive -and (Test-Path -LiteralPath $vendoredArchive -PathType Leaf)) {
        $vendoredArchive
    } else {
        Join-Path $downloads "$($component.name).tar.gz"
    }
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
        & curl.exe -fsSL $component.url -o $archive
        if ($LASTEXITCODE -ne 0) {
            throw "Download failed: $($component.name)"
        }
    }

    $destination = Join-Path $checkout $component.destination
    Expand-AuthenticatedTarGzip `
        $archive `
        $destination `
        $component.archive_size `
        $component.archive_sha256 | Out-Null
    $actualTreeHash = Get-TreeHash $destination
    if ($actualTreeHash -ne $component.tree_sha256) {
        throw (
            "Source tree verification failed: $($component.name); " +
            "expected=$($component.tree_sha256); actual=$actualTreeHash")
    }
}

foreach ($relativePatch in $series) {
    $patch = Join-Path $root "third_party\crashpad\patches\$relativePatch"
    if (-not (Test-Path $patch)) {
        throw "Missing patch: $relativePatch"
    }
    $normalizedPatch = Join-Path $downloads "$([IO.Path]::GetFileName($relativePatch)).lf"
    try {
        $patchText = [IO.File]::ReadAllText($patch)
        [IO.File]::WriteAllText(
            $normalizedPatch,
            $patchText.Replace("`r`n", "`n").Replace("`r", "`n"),
            [Text.UTF8Encoding]::new($false))
        & git -C $root -c core.autocrlf=false -c core.eol=lf apply --check --unsafe-paths --ignore-space-change --ignore-whitespace `
            --directory='third_party/crashpad/checkout/crashpad' $normalizedPatch
        if ($LASTEXITCODE -ne 0) {
            throw "Patch check failed: $relativePatch"
        }
        & git -C $root -c core.autocrlf=false -c core.eol=lf apply --unsafe-paths --ignore-space-change --ignore-whitespace `
            --directory='third_party/crashpad/checkout/crashpad' $normalizedPatch
        if ($LASTEXITCODE -ne 0) {
            throw "Patch apply failed: $relativePatch"
        }
    } finally {
        Remove-Item $normalizedPatch -Force -ErrorAction SilentlyContinue
    }
}

$actualPostPatchTreeHash = Get-TreeHash $checkout $generatedTreeExclusions
if ($actualPostPatchTreeHash -ne $expectedPostPatchTreeHash) {
    throw 'Reconstructed Crashpad source does not match the reviewed post-patch tree hash'
}

$manifest = foreach ($component in $lock.components) {
    [ordered]@{
        name = $component.name
        revision = $component.revision
        archive_size = $component.archive_size
        archive_sha256 = $component.archive_sha256
        tree_sha256 = $component.tree_sha256
        destination = $component.destination
        license = $component.license
    }
}
[ordered]@{
    source_lock_sha256 = $sourceLockHash
    patch_set_sha256 = $patchSetHash
    post_patch_tree_sha256 = $expectedPostPatchTreeHash
    post_patch_tree_hash_algorithm =
        'sha256(UTF8(sorted(relative_path + LF + file_sha256 + LF)))'
    excluded_generated_paths = $generatedTreeExclusions
    components = $manifest
} | ConvertTo-Json -Depth 5 |
    Set-Content $verifiedManifest -Encoding utf8

Write-Output "Verified $($lock.components.Count) Crashpad source components."
