$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$hostControlRoot = (
    Resolve-Path (Join-Path $root 'test-apps\phase0-fixture\src\hostNetwork')
).Path
$hostControlManifest = Join-Path $hostControlRoot 'AndroidManifest.xml'
$manifests = Get-ChildItem $root -Recurse -File -Filter AndroidManifest.xml |
    Where-Object { $_.FullName -notmatch '\\build\\|\\third_party\\crashpad\\checkout\\' }
foreach ($manifest in $manifests) {
    $text = Get-Content $manifest.FullName -Raw
    $isHostControl = $manifest.FullName.Equals(
        $hostControlManifest,
        [StringComparison]::OrdinalIgnoreCase
    )
    if ($isHostControl -and $text -notmatch 'android\.permission\.INTERNET') {
        throw "Host-network positive control lacks INTERNET: $($manifest.FullName)"
    }
    if (-not $isHostControl -and $text -match 'android\.permission\.INTERNET') {
        throw "Tracebox-owned INTERNET permission: $($manifest.FullName)"
    }
}

$sourceFiles = Get-ChildItem @(
    (Join-Path $root 'android'),
    (Join-Path $root 'test-apps'),
    (Join-Path $root 'benchmarks'),
    (Join-Path $root 'native'),
    (Join-Path $root 'rust')
) -Recurse -File | Where-Object {
    $_.FullName -notmatch '\\build\\|\\target\\' -and
    -not $_.FullName.StartsWith(
        $hostControlRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase
    )
}

$forbidden = @(
    'java\.net\.',
    'okhttp',
    'retrofit',
    'ktor-client',
    'reqwest',
    'hyper::',
    'curl_easy',
    'CrashReportUpload'
)
foreach ($file in $sourceFiles) {
    $text = Get-Content $file.FullName -Raw
    foreach ($pattern in $forbidden) {
        if ($text -match $pattern) {
            throw "Forbidden network surface '$pattern': $($file.FullName)"
        }
    }
}

[ordered]@{
    manifests_scanned = $manifests.Count
    source_files_scanned = $sourceFiles.Count
    isolated_host_network_control = $hostControlRoot.Substring($root.Length + 1)
    forbidden_matches = 0
    result = 'PASS'
} | ConvertTo-Json
