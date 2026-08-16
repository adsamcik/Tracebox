param(
    [Parameter(Mandatory = $true)][string] $RepositoryRoot,
    [Parameter(Mandatory = $true)][string] $LockFile
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$lockPath = (Resolve-Path -LiteralPath $LockFile).Path
$lock = @{}
foreach ($line in Get-Content -LiteralPath $lockPath) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
        continue
    }
    $separator = $line.IndexOf('=')
    if ($separator -le 0) {
        throw "Malformed Crashpad prebuilt lock entry: $line"
    }
    $key = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1).Trim()
    if ($lock.ContainsKey($key)) {
        throw "Duplicate Crashpad prebuilt lock key: $key"
    }
    $lock[$key] = $value
}

if ($lock.format -ne '1') {
    throw 'Unsupported Crashpad prebuilt lock format'
}
if ($lock.source_commit -notmatch '^[0-9a-f]{40}$') {
    throw 'Crashpad prebuilt lock has invalid source provenance'
}
if ($lock.input_tree_sha256 -notmatch '^[0-9a-f]{64}$') {
    throw 'Crashpad prebuilt lock has invalid input-tree provenance'
}

$inputRoots = @(
    'Cargo.lock',
    'Cargo.toml',
    'rust-toolchain.toml',
    'gradle/toolchains.lock.toml',
    'native/crashpad/overlay',
    'native/emergency',
    'native/include/tracebox',
    'native/signal',
    'rust/tracebox',
    'rust/tracebox-android-bridge',
    'rust/tracebox-identity',
    'rust/tracebox-phase0',
    'rust/tracebox-sys',
    'third_party/crashpad/build-tools-lock.json',
    'third_party/crashpad/source-lock.json',
    'third_party/crashpad/patches',
    'tools/crashpad'
)
$inputFiles = @()
foreach ($relativeRoot in $inputRoots) {
    $input = Join-Path $root $relativeRoot
    if (Test-Path -LiteralPath $input -PathType Container) {
        $inputFiles += Get-ChildItem -LiteralPath $input -Recurse -File
    } elseif (Test-Path -LiteralPath $input -PathType Leaf) {
        $inputFiles += Get-Item -LiteralPath $input
    } else {
        throw "Missing Crashpad build input: $relativeRoot"
    }
}
$entryHashes = @{}
foreach ($inputFile in $inputFiles) {
    $relative = $inputFile.FullName.Substring($root.Length + 1).Replace('\', '/')
    if ($entryHashes.ContainsKey($relative)) {
        throw "Duplicate Crashpad build input: $relative"
    }
    $entryHashes[$relative] = (Get-FileHash -LiteralPath $inputFile.FullName -Algorithm SHA256).
        Hash.ToLowerInvariant()
}
$relativePaths = [string[]] @($entryHashes.Keys)
[Array]::Sort($relativePaths, [StringComparer]::Ordinal)
$material = ($relativePaths | ForEach-Object { "$_`n$($entryHashes[$_])`n" }) -join ''
$algorithm = [Security.Cryptography.SHA256]::Create()
try {
    $inputTreeSha256 = (
        [BitConverter]::ToString(
            $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($material))) -replace '-', ''
    ).ToLowerInvariant()
} finally {
    $algorithm.Dispose()
}
if ($inputTreeSha256 -ne $lock.input_tree_sha256) {
    throw (
        'Crashpad build inputs changed; rebuild and explicitly review the locked prebuilts. ' +
        "expected=$($lock.input_tree_sha256); actual=$inputTreeSha256"
    )
}

$jniRoot = Join-Path $root 'android/tracebox-native/src/main/jniLibs'
$abis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
$required = @{}
foreach ($abi in $abis) {
    $required[$abi] = Join-Path $jniRoot "$abi/libtracebox_crashpad.so"
}
$actualPrebuilts = @(
    Get-ChildItem -LiteralPath $jniRoot -Recurse -File -Filter 'libtracebox_crashpad.so'
)
if ($actualPrebuilts.Count -ne $required.Count) {
    throw "Crashpad prebuilt ABI set must contain exactly four libraries"
}
foreach ($abi in $abis) {
    $prebuilt = $required[$abi]
    if (-not (Test-Path -LiteralPath $prebuilt -PathType Leaf)) {
        throw "Missing $prebuilt; run tools/crashpad/Build-Crashpad.ps1 on Windows"
    }
    $bytesKey = "$abi.bytes"
    $shaKey = "$abi.sha256"
    $lockedBytes = 0L
    if (-not [long]::TryParse($lock[$bytesKey], [ref] $lockedBytes) -or $lockedBytes -le 0) {
        throw "Crashpad prebuilt lock has invalid byte count for $abi"
    }
    $lockedSha256 = $lock[$shaKey]
    if ($lockedSha256 -notmatch '^[0-9a-f]{64}$') {
        throw "Crashpad prebuilt lock has invalid SHA-256 for $abi"
    }
    $actualBytes = (Get-Item -LiteralPath $prebuilt).Length
    $actualSha256 = (Get-FileHash -LiteralPath $prebuilt -Algorithm SHA256).
        Hash.ToLowerInvariant()
    if ($actualBytes -ne $lockedBytes) {
        throw "Crashpad prebuilt size mismatch for $abi"
    }
    if ($actualSha256 -ne $lockedSha256) {
        throw "Crashpad prebuilt SHA-256 mismatch for $abi"
    }
}

[ordered]@{
    result = 'PASS'
    source_commit = $lock.source_commit
    input_files = $relativePaths.Count
    input_tree_sha256 = $inputTreeSha256
    abis = $abis
} | ConvertTo-Json -Depth 3
