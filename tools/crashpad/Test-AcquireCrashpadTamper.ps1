$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$acquire = Join-Path $PSScriptRoot 'Acquire-Crashpad.ps1'
$target = Join-Path $root 'third_party\crashpad\checkout\crashpad\client\crashpad_client.h'

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
        cached_reuse_rejected = -not $reused
        result = 'PASS'
    } | ConvertTo-Json
} finally {
    if ((Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne
        $originalHash) {
        [IO.File]::WriteAllBytes($target, $originalBytes)
    }
}
