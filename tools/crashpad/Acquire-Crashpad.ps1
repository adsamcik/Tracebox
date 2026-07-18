param([switch] $Force)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$lockPath = Join-Path $root 'third_party\crashpad\source-lock.json'
$checkout = Join-Path $root 'third_party\crashpad\checkout'
$downloads = Join-Path $root '.bootstrap\crashpad-acquire'
$lock = Get-Content $lockPath -Raw | ConvertFrom-Json
$sourceLockHash =
    (Get-FileHash $lockPath -Algorithm SHA256).Hash.ToLowerInvariant()
$seriesPath = Join-Path $root 'third_party\crashpad\patches\series'
$series = Get-Content $seriesPath |
    Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') }
$patchMaterial = foreach ($relativePatch in $series) {
    $patch = Join-Path $root "third_party\crashpad\patches\$relativePatch"
    "$relativePatch`n$((Get-FileHash $patch -Algorithm SHA256).Hash.ToLowerInvariant())`n"
}
$patchSetHash = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData(
        [Text.Encoding]::UTF8.GetBytes(($patchMaterial -join '')))).ToLowerInvariant()

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
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
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
    $archive = Join-Path $downloads "$($component.name).tar.gz"
    if (-not (Test-Path $archive)) {
        & curl.exe -fsSL $component.url -o $archive
        if ($LASTEXITCODE -ne 0) {
            throw "Download failed: $($component.name)"
        }
    }

    $destination = Join-Path $checkout $component.destination
    if (Test-Path $destination) {
        Remove-Item $destination -Recurse -Force
    }
    New-Item -ItemType Directory -Force $destination | Out-Null
    & tar.exe -xzf $archive -C $destination
    if ($LASTEXITCODE -ne 0) {
        throw "Extraction failed: $($component.name)"
    }
    $actualTreeHash = Get-TreeHash $destination
    if ($actualTreeHash -ne $component.tree_sha256) {
        throw "Source tree verification failed: $($component.name)"
    }
}

foreach ($relativePatch in $series) {
    $patch = Join-Path $root "third_party\crashpad\patches\$relativePatch"
    if (-not (Test-Path $patch)) {
        throw "Missing patch: $relativePatch"
    }
    & git -C $root apply --check --unsafe-paths --ignore-space-change --ignore-whitespace `
        --directory='third_party/crashpad/checkout/crashpad' $patch
    if ($LASTEXITCODE -ne 0) {
        throw "Patch check failed: $relativePatch"
    }
    & git -C $root apply --unsafe-paths --ignore-space-change --ignore-whitespace `
        --directory='third_party/crashpad/checkout/crashpad' $patch
    if ($LASTEXITCODE -ne 0) {
        throw "Patch apply failed: $relativePatch"
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
