$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$acquire = Join-Path $PSScriptRoot 'Acquire-Crashpad.ps1'
$checkout = Join-Path $root 'third_party\crashpad\checkout'
$manifestPath = Join-Path $checkout 'verified-sources.json'
$lock = Get-Content (Join-Path $root 'third_party\crashpad\source-lock.json') -Raw |
    ConvertFrom-Json
$target = Join-Path $root 'third_party\crashpad\checkout\crashpad\client\crashpad_client.h'

function Get-TestTreeHash {
    param([string] $Directory, [string[]] $ExcludedRelativePrefixes)

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
        if (-not $excluded) {
            $hash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$relative`n$hash`n"
        }
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($entries -join ''))
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

& $acquire | Out-Null
$originalBytes = [IO.File]::ReadAllBytes($target)
$originalHash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant()
$tamper = [Text.Encoding]::UTF8.GetBytes("`n// tracebox tamper regression`n")
$tamperedBytes = [byte[]]::new($originalBytes.Length + $tamper.Length)
[Array]::Copy($originalBytes, $tamperedBytes, $originalBytes.Length)
[Array]::Copy(
    $tamper,
    0,
    $tamperedBytes,
    $originalBytes.Length,
    $tamper.Length)

try {
    [IO.File]::WriteAllBytes($target, $tamperedBytes)
    $tamperedHash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant()
    $tamperedTreeHash = Get-TestTreeHash `
        $checkout @($lock.post_patch_tree_excluded_generated_paths)
    $mutableManifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
    $mutableManifest.post_patch_tree_sha256 = $tamperedTreeHash
    $mutableManifest | ConvertTo-Json -Depth 5 |
        Set-Content $manifestPath -Encoding utf8
    $output = & $acquire 3>&1
    $restoredHash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant()
    $reused = ($output | Out-String).Contains('Reusing verified Crashpad checkout')
    if ($tamperedHash -eq $originalHash) {
        throw 'Tamper fixture did not change the source hash'
    }
    if ($reused) {
        throw 'Tampered cached source was trusted'
    }
    if ($restoredHash -ne $originalHash) {
        throw 'Immutable source reconstruction did not restore the expected file'
    }

    [ordered]@{
        target = $target.Substring($root.Length + 1)
        original_sha256 = $originalHash
        tampered_sha256 = $tamperedHash
        restored_sha256 = $restoredHash
        mutable_manifest_post_patch_sha256 = $tamperedTreeHash
        tracked_post_patch_sha256 = $lock.post_patch_tree_sha256
        cached_reuse_rejected = -not $reused
        result = 'PASS'
    } | ConvertTo-Json
} finally {
    if ((Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne
        $originalHash) {
        [IO.File]::WriteAllBytes($target, $originalBytes)
    }
}
