$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$lockPath = Join-Path $root 'third_party\crashpad\source-lock.json'
$checkout = Join-Path $root 'third_party\crashpad\checkout'
$downloads = Join-Path $root '.bootstrap\crashpad-acquire'
$lock = Get-Content $lockPath -Raw | ConvertFrom-Json

New-Item -ItemType Directory -Force $checkout, $downloads | Out-Null

function Get-TreeHash {
    param([string] $Directory)

    $entries = foreach ($file in Get-ChildItem $Directory -Recurse -File | Sort-Object FullName) {
        $relative = $file.FullName.Substring($Directory.Length + 1).Replace('\', '/')
        $hash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relative`n$hash`n"
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($entries -join ''))
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
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

$series = Get-Content (Join-Path $root 'third_party\crashpad\patches\series') |
    Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') }
foreach ($relativePatch in $series) {
    $patch = Join-Path $root "third_party\crashpad\patches\$relativePatch"
    if (-not (Test-Path $patch)) {
        throw "Missing patch: $relativePatch"
    }
    & git -C (Join-Path $checkout 'crashpad') apply --check $patch
    if ($LASTEXITCODE -ne 0) {
        throw "Patch check failed: $relativePatch"
    }
    & git -C (Join-Path $checkout 'crashpad') apply $patch
    if ($LASTEXITCODE -ne 0) {
        throw "Patch apply failed: $relativePatch"
    }
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
$manifest | ConvertTo-Json -Depth 4 |
    Set-Content (Join-Path $checkout 'verified-sources.json') -Encoding utf8

Write-Output "Verified $($lock.components.Count) Crashpad source components."
