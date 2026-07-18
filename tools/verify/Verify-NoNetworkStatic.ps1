$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifests = Get-ChildItem $root -Recurse -File -Filter AndroidManifest.xml |
    Where-Object { $_.FullName -notmatch '\\build\\|\\third_party\\crashpad\\checkout\\' }
foreach ($manifest in $manifests) {
    $text = Get-Content $manifest.FullName -Raw
    if ($text -match 'android\.permission\.INTERNET') {
        throw "Tracebox-owned INTERNET permission: $($manifest.FullName)"
    }
}

$sourceFiles = Get-ChildItem @(
    (Join-Path $root 'android'),
    (Join-Path $root 'test-apps'),
    (Join-Path $root 'benchmarks'),
    (Join-Path $root 'native'),
    (Join-Path $root 'rust')
) -Recurse -File | Where-Object { $_.FullName -notmatch '\\build\\|\\target\\' }

$forbidden = @(
    'java\.net\.',
    'android\.net\.',
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
    forbidden_matches = 0
    result = 'PASS'
} | ConvertTo-Json
