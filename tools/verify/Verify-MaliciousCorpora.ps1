$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$corpus = Join-Path $root 'tooling\fixtures\malicious'
$temporary = Join-Path ([IO.Path]::GetTempPath()) ("tracebox-corpus-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null

function Invoke-ExpectedRejection {
    param([string[]] $Arguments, [string] $CaseId)
    Push-Location $root
    $previousPreference = $ErrorActionPreference
    try {
        # Windows PowerShell surfaces redirected native stderr as a non-terminating
        # NativeCommandError. The exit status remains the authoritative result.
        $ErrorActionPreference = 'Continue'
        $output = & cargo run -q -p tbdiag-cli --locked --offline -- @Arguments 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
        Pop-Location
    }
    if ($exit -eq 0) {
        throw "Malicious corpus case was accepted: $CaseId ($($output -join [Environment]::NewLine))"
    }
    [pscustomobject]@{ id = $CaseId; exit_status = $exit; rejected = $true }
}

function Convert-HexBytes {
    param([string] $Value)
    if (($Value.Length % 2) -ne 0) {
        throw 'Hex corpus value must contain whole bytes'
    }
    $bytes = [byte[]]::new($Value.Length / 2)
    for ($index = 0; $index -lt $bytes.Length; $index++) {
        $bytes[$index] = [Convert]::ToByte($Value.Substring($index * 2, 2), 16)
    }
    return $bytes
}

try {
    Add-Type -AssemblyName System.IO.Compression
    $results = @()
    $recipes = (Get-Content (Join-Path $corpus 'archive-cases.json') -Raw | ConvertFrom-Json).cases
    foreach ($case in $recipes) {
        $path = Join-Path $temporary ($case.id.Replace('.', '-').ToLowerInvariant() + '.tbdiag')
        if ($case.kind -eq 'hex') {
            if ($case.value.Length -eq 0) {
                [IO.File]::WriteAllBytes($path, [byte[]]@())
            } else {
                $bytes = Convert-HexBytes ([string]$case.value)
                [IO.File]::WriteAllBytes($path, $bytes)
            }
        } elseif ($case.kind -eq 'zip_entry') {
            $stream = [IO.File]::Create($path)
            try {
                $zip = [IO.Compression.ZipArchive]::new(
                    $stream,
                    [IO.Compression.ZipArchiveMode]::Create,
                    $false
                )
                try {
                    $entry = $zip.CreateEntry([string]$case.entry, [IO.Compression.CompressionLevel]::NoCompression)
                    $entryStream = $entry.Open()
                    try {
                        $payload = [Convert]::FromBase64String([string]$case.payload_base64)
                        $entryStream.Write($payload, 0, $payload.Length)
                    } finally {
                        $entryStream.Dispose()
                    }
                } finally {
                    $zip.Dispose()
                }
            } finally {
                $stream.Dispose()
            }
        } else {
            throw "Unknown malicious archive recipe kind: $($case.kind)"
        }
        $results += Invoke-ExpectedRejection `
            -Arguments @([string]$case.command, $path) `
            -CaseId ([string]$case.id)
    }

    foreach ($symbol in @(
            'symbol-duplicate-build.tsv',
            'symbol-invalid-offset.tsv',
            'symbol-missing-header.tsv',
            'symbol-missing-build.tsv',
            'symbol-legacy-row.tsv',
            'symbol-ambiguous-alias.tsv'
        )) {
        $path = Join-Path $corpus $symbol
        $results += Invoke-ExpectedRejection `
            -Arguments @(
                'symbolize', $path, 'build-one', 'x86_64', 'libtracebox.so', 'identity', '1'
            ) `
            -CaseId ("SYMBOL." + $symbol)
    }

    [ordered]@{
        schema = 'tracebox-malicious-corpus-result-v1'
        cases = $results
        result = 'PASS'
    } | ConvertTo-Json -Depth 4
} finally {
    $resolvedTemporary = [IO.Path]::GetFullPath($temporary)
    $resolvedSystemTemporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolvedTemporary.StartsWith($resolvedSystemTemporary, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected temporary path: $resolvedTemporary"
    }
    if (Test-Path -LiteralPath $resolvedTemporary) {
        Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
    }
}
