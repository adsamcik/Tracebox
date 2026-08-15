param(
    [Parameter(Mandatory)]
    [string] $Aar
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$checkout = Join-Path $root 'third_party\crashpad\checkout'
$sourceLockPath = Join-Path $root 'third_party\crashpad\source-lock.json'
$verifiedSourcesPath = Join-Path $checkout 'verified-sources.json'
$resourcePath = Join-Path $root (
    'android\tracebox\src\main\res\raw\tracebox_third_party_notices.txt'
)
$resourceEntryName = 'res/raw/tracebox_third_party_notices.txt'
$maximumTextBytes = 2MB
$maximumAarBytes = 128MB

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Convert-HashBytesToHex {
    param([byte[]] $Bytes)
    return ([BitConverter]::ToString($Bytes) -replace '-', '').ToLowerInvariant()
}

function Get-TextSha256 {
    param([string] $Text)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return Convert-HashBytesToHex (
            $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))
        )
    } finally {
        $algorithm.Dispose()
    }
}

function Normalize-NoticeText {
    param([string] $Text)
    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    if ($normalized.Length -gt 0 -and $normalized[0] -eq [char]0xfeff) {
        $normalized = $normalized.Substring(1)
    }
    if (-not $normalized.EndsWith("`n", [StringComparison]::Ordinal)) {
        $normalized += "`n"
    }
    return $normalized
}

function Convert-StrictUtf8 {
    param(
        [byte[]] $Bytes,
        [string] $Label
    )
    if ($Bytes.Length -gt $maximumTextBytes) {
        throw "$Label exceeds the $maximumTextBytes-byte notice verification bound"
    }
    try {
        $encoding = [Text.UTF8Encoding]::new($false, $true)
        return Normalize-NoticeText ($encoding.GetString($Bytes))
    } catch {
        throw "$Label is not strict UTF-8: $($_.Exception.Message)"
    }
}

function Read-NormalizedUtf8 {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required notice input is missing: $Path"
    }
    return Convert-StrictUtf8 ([IO.File]::ReadAllBytes($Path)) $Path
}

function Read-BoundedStreamBytes {
    param(
        [IO.Stream] $Stream,
        [long] $MaximumBytes,
        [string] $Label
    )
    $buffer = [byte[]]::new(16KB)
    $memory = [IO.MemoryStream]::new()
    try {
        [long] $total = 0
        while (($count = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $total += $count
            if ($total -gt $MaximumBytes) {
                throw "$Label exceeds the $MaximumBytes-byte verification bound"
            }
            $memory.Write($buffer, 0, $count)
        }
        return ,$memory.ToArray()
    } finally {
        $memory.Dispose()
    }
}

function Assert-BoundedArchiveEntry {
    param(
        [IO.Compression.ZipArchiveEntry] $Entry,
        [long] $MaximumBytes,
        [string] $Label
    )
    if (
        $Entry.Length -lt 0 -or
        $Entry.CompressedLength -lt 0 -or
        $Entry.Length -gt $MaximumBytes
    ) {
        throw (
            "$Label has an invalid or oversized archive length: " +
            "expanded=$($Entry.Length) compressed=$($Entry.CompressedLength) " +
            "maximum=$MaximumBytes"
        )
    }
}

function Get-NoticeSection {
    param(
        [string] $Resource,
        [string] $Name,
        [int] $MinimumOffset
    )
    $begin = "===== BEGIN $Name =====`n"
    $end = "===== END $Name =====`n"
    $beginOffset = $Resource.IndexOf($begin, $MinimumOffset, [StringComparison]::Ordinal)
    if ($beginOffset -lt 0) {
        throw "Committed Android notice resource is missing section '$Name'"
    }
    if ($Resource.IndexOf($begin, $beginOffset + $begin.Length, [StringComparison]::Ordinal) -ge 0) {
        throw "Committed Android notice resource duplicates section '$Name'"
    }
    $bodyOffset = $beginOffset + $begin.Length
    $endOffset = $Resource.IndexOf($end, $bodyOffset, [StringComparison]::Ordinal)
    if ($endOffset -lt 0) {
        throw "Committed Android notice resource has no end marker for '$Name'"
    }
    return [pscustomobject]@{
        body = $Resource.Substring($bodyOffset, $endOffset - $bodyOffset)
        next_offset = $endOffset + $end.Length
    }
}

if (-not (Test-Path -LiteralPath $checkout -PathType Container)) {
    throw 'Pinned Crashpad checkout is absent; run tools/crashpad/Acquire-Crashpad.ps1 first'
}
$sourceLock = Get-Content -LiteralPath $sourceLockPath -Raw | ConvertFrom-Json
$verifiedSources = Get-Content -LiteralPath $verifiedSourcesPath -Raw | ConvertFrom-Json
$sourceLockSha256 = (
    Get-FileHash -LiteralPath $sourceLockPath -Algorithm SHA256
).Hash.ToLowerInvariant()
if ($verifiedSources.source_lock_sha256 -cne $sourceLockSha256) {
    throw 'Crashpad verified-sources manifest does not bind the committed source lock'
}
if (
    $verifiedSources.post_patch_tree_sha256 -cne $sourceLock.post_patch_tree_sha256 -or
    $verifiedSources.post_patch_tree_hash_algorithm -cne
        $sourceLock.post_patch_tree_hash_algorithm
) {
    throw 'Crashpad verified-sources manifest does not bind the pinned post-patch tree'
}
$treeExclusions = @($sourceLock.post_patch_tree_excluded_generated_paths)
if ($treeExclusions.Count -eq 0) {
    throw 'Crashpad source lock has no reviewed generated-path exclusions'
}

$mappings = @(
    [pscustomobject]@{
        name = 'crashpad'
        component = 'crashpad'
        destination = 'crashpad'
        source = 'crashpad/LICENSE'
        extraction = 'full'
        expected_sha256 = 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'
    },
    [pscustomobject]@{
        name = 'mini_chromium'
        component = 'mini_chromium'
        destination = 'crashpad/third_party/mini_chromium/mini_chromium'
        source = 'crashpad/third_party/mini_chromium/mini_chromium/LICENSE'
        extraction = 'full'
        expected_sha256 = '12bbc9942399b4014dac7584f3df08afb966ab92157e21e47e6a05db13c04ae0'
    },
    [pscustomobject]@{
        name = 'linux_syscall_support'
        component = 'lss'
        destination = 'crashpad/third_party/lss/lss'
        source = 'crashpad/third_party/lss/lss/linux_syscall_support.h'
        extraction = 'leading_comment'
        expected_sha256 = '325b8440550179bd46d3389d9defd6cdab7bb9a3519ef38d2eda008dd02236ab'
    },
    [pscustomobject]@{
        name = 'zlib'
        component = 'zlib'
        destination = 'crashpad/third_party/zlib/zlib'
        source = 'crashpad/third_party/zlib/zlib/LICENSE'
        extraction = 'full'
        expected_sha256 = 'e1cfcc55c325b3f78cf55df9664abaa066e2271dffe8213347d9fccdfbac8f2c'
    },
    [pscustomobject]@{
        name = 'googletest'
        component = 'googletest'
        destination = 'crashpad/third_party/googletest/googletest'
        source = 'crashpad/third_party/googletest/googletest/LICENSE'
        extraction = 'full'
        expected_sha256 = '9702de7e4117a8e2b20dafab11ffda58c198aede066406496bef670d40a22138'
    },
    [pscustomobject]@{
        name = 'chromium_buildtools'
        component = 'buildtools'
        destination = 'buildtools'
        source = 'buildtools/LICENSE'
        extraction = 'full'
        expected_sha256 = 'ff11d445fb41a1087c7630e120ab15f1a2cb67c1b707173cb494141805fca35e'
    }
)

$lockComponents = @{}
foreach ($component in $sourceLock.components) {
    if ($lockComponents.ContainsKey($component.name)) {
        throw "Crashpad source lock duplicates component '$($component.name)'"
    }
    $lockComponents[$component.name] = $component
}
if ($lockComponents.Count -ne $mappings.Count) {
    throw (
        "Crashpad source lock contains $($lockComponents.Count) components, " +
        "but the Android notice mapping contains $($mappings.Count)"
    )
}
$verifiedComponents = @{}
foreach ($component in $verifiedSources.components) {
    if ($verifiedComponents.ContainsKey($component.name)) {
        throw "Crashpad verified-sources manifest duplicates component '$($component.name)'"
    }
    $verifiedComponents[$component.name] = $component
}
if ($verifiedComponents.Count -ne $lockComponents.Count) {
    throw 'Crashpad verified-sources component count differs from the committed source lock'
}
foreach ($name in $lockComponents.Keys) {
    if (-not $verifiedComponents.ContainsKey($name)) {
        throw "Crashpad verified-sources manifest is missing component '$name'"
    }
    $locked = $lockComponents[$name]
    $verified = $verifiedComponents[$name]
    foreach ($property in @(
            'revision',
            'archive_size',
            'archive_sha256',
            'tree_sha256',
            'destination',
            'license'
        )) {
        if ([string]$verified.$property -cne [string]$locked.$property) {
            throw "Crashpad verified-sources component '$name' differs at '$property'"
        }
    }
}

$committedResource = Read-NormalizedUtf8 $resourcePath
$beginMarkerCount = [regex]::Matches(
    $committedResource,
    '(?m)^===== BEGIN [a-z0-9_]+ =====$'
).Count
$endMarkerCount = [regex]::Matches(
    $committedResource,
    '(?m)^===== END [a-z0-9_]+ =====$'
).Count
if ($beginMarkerCount -ne $mappings.Count -or $endMarkerCount -ne $mappings.Count) {
    throw (
        'Committed Android notice resource has an unexpected section count: ' +
        "begin=$beginMarkerCount end=$endMarkerCount expected=$($mappings.Count)"
    )
}

$sectionReports = @()
$sectionOffset = 0
foreach ($mapping in $mappings) {
    if (-not $lockComponents.ContainsKey($mapping.component)) {
        throw "Crashpad source lock is missing component '$($mapping.component)'"
    }
    $component = $lockComponents[$mapping.component]
    if ($component.destination -cne $mapping.destination) {
        throw (
            "Crashpad component '$($mapping.component)' moved from the reviewed notice path: " +
            "expected=$($mapping.destination) actual=$($component.destination)"
        )
    }
    $sourcePath = Join-Path $checkout $mapping.source
    $expected = Read-NormalizedUtf8 $sourcePath
    if ($mapping.extraction -eq 'leading_comment') {
        $match = [regex]::Match(
            $expected,
            '\A/\*.*?\*/\n',
            [Text.RegularExpressions.RegexOptions]::Singleline
        )
        if (-not $match.Success) {
            throw "Pinned LSS header has no leading license/notice comment: $sourcePath"
        }
        $expected = $match.Value
    } elseif ($mapping.extraction -cne 'full') {
        throw "Unsupported notice extraction '$($mapping.extraction)'"
    }
    $expectedSha256 = Get-TextSha256 $expected
    if ($expectedSha256 -cne $mapping.expected_sha256) {
        throw (
            "Pinned verified checkout notice input '$($mapping.source)' changed: " +
            "expected_sha256=$($mapping.expected_sha256) actual_sha256=$expectedSha256"
        )
    }

    $section = Get-NoticeSection $committedResource $mapping.name $sectionOffset
    $sectionOffset = $section.next_offset
    if ($section.body -cne $expected) {
        throw (
            "Committed Android notice section '$($mapping.name)' differs from the " +
            "pinned verified checkout: expected_sha256=$(Get-TextSha256 $expected) " +
            "actual_sha256=$(Get-TextSha256 $section.body)"
        )
    }
    $sectionReports += [ordered]@{
        name = $mapping.name
        source = $mapping.source
        sha256 = $expectedSha256
    }
}

$aarPath = (Resolve-Path -LiteralPath $Aar).Path
$rootPrefix = $root + [IO.Path]::DirectorySeparatorChar
if (-not $aarPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Tracebox AAR is outside the workspace: $aarPath"
}
$aarInfo = Get-Item -LiteralPath $aarPath
if (
    -not $aarInfo.PSIsContainer -and
    $aarInfo.Length -gt 0 -and
    $aarInfo.Length -le $maximumAarBytes
) {
    # The archive is a bounded regular file.
} else {
    throw (
        "Tracebox AAR must be a non-empty file no larger than $maximumAarBytes bytes: " +
        "$aarPath"
    )
}
$archive = [IO.Compression.ZipFile]::OpenRead($aarPath)
try {
    $resourceEntries = @(
        $archive.Entries |
            Where-Object { $_.FullName -ceq $resourceEntryName }
    )
    if ($resourceEntries.Count -ne 1) {
        throw (
            "Tracebox release AAR must contain exactly one $resourceEntryName; " +
            "found $($resourceEntries.Count)"
        )
    }
    Assert-BoundedArchiveEntry `
        $resourceEntries[0] `
        $maximumTextBytes `
        "$aarPath::$resourceEntryName"
    $resourceStream = $resourceEntries[0].Open()
    try {
        $packagedResource = Convert-StrictUtf8 (
            Read-BoundedStreamBytes `
                $resourceStream `
                $maximumTextBytes `
                "$aarPath::$resourceEntryName"
        ) "$aarPath::$resourceEntryName"
    } finally {
        $resourceStream.Dispose()
    }
    if ($packagedResource -cne $committedResource) {
        throw 'Tracebox release AAR third-party notice resource differs from the committed resource'
    }

    $symbolEntries = @($archive.Entries | Where-Object { $_.FullName -ceq 'R.txt' })
    if ($symbolEntries.Count -ne 1) {
        throw "Tracebox release AAR must contain exactly one R.txt; found $($symbolEntries.Count)"
    }
    Assert-BoundedArchiveEntry `
        $symbolEntries[0] `
        $maximumTextBytes `
        "$aarPath::R.txt"
    $symbolStream = $symbolEntries[0].Open()
    try {
        $symbols = Convert-StrictUtf8 (
            Read-BoundedStreamBytes `
                $symbolStream `
                $maximumTextBytes `
                "$aarPath::R.txt"
        ) "$aarPath::R.txt"
    } finally {
        $symbolStream.Dispose()
    }
    if ($symbols -notmatch '(?m)\braw\s+tracebox_third_party_notices\b') {
        throw 'Tracebox release AAR does not export R.raw.tracebox_third_party_notices'
    }
} finally {
    $archive.Dispose()
}

[ordered]@{
    schema = 'tracebox-crashpad-third-party-notices-v1'
    source_lock_sha256 = $sourceLockSha256
    verified_post_patch_tree_sha256 = $verifiedSources.post_patch_tree_sha256
    sections_verified = $sectionReports.Count
    sections = $sectionReports
    committed_resource = $resourcePath.Substring($root.Length + 1)
    committed_resource_sha256 = Get-TextSha256 $committedResource
    aar = $aarPath.Substring($root.Length + 1)
    aar_entry = $resourceEntryName
    exported_symbol = 'dev.tracebox.R.raw.tracebox_third_party_notices'
    result = 'PASS'
} | ConvertTo-Json -Depth 5
