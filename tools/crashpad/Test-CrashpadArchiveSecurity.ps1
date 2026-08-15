$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'CrashpadArchive.ps1')
$lock = Get-Content (Join-Path $root 'third_party\crashpad\source-lock.json') -Raw |
    ConvertFrom-Json
$workspace = Join-Path $root '.bootstrap\crashpad-archive-tests'

function New-TestArchive {
    param(
        [string] $Path,
        [System.Formats.Tar.TarEntryType] $EntryType,
        [string] $EntryName
    )

    $file = [IO.File]::Create($Path)
    $gzip = [IO.Compression.GZipStream]::new(
        $file,
        [IO.Compression.CompressionLevel]::SmallestSize,
        $true)
    $writer = [System.Formats.Tar.TarWriter]::new($gzip, $true)
    try {
        $entry = [System.Formats.Tar.PaxTarEntry]::new($EntryType, $EntryName)
        if ($EntryType -in @(
                [System.Formats.Tar.TarEntryType]::RegularFile,
                [System.Formats.Tar.TarEntryType]::V7RegularFile)) {
            $entry.DataStream = [IO.MemoryStream]::new(
                [Text.Encoding]::UTF8.GetBytes('tracebox archive fixture'))
        } elseif ($EntryType -in @(
                [System.Formats.Tar.TarEntryType]::SymbolicLink,
                [System.Formats.Tar.TarEntryType]::HardLink)) {
            $entry.LinkName = 'target'
        }
        $writer.WriteEntry($entry)
    } finally {
        $writer.Dispose()
        $gzip.Dispose()
        $file.Dispose()
    }
}

function Get-ArchiveIdentity {
    param([string] $Path)
    return [ordered]@{
        size = (Get-Item $Path).Length
        sha256 = (Get-FileHash $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Assert-RejectedBeforeWrite {
    param(
        [string] $Name,
        [string] $Archive,
        [long] $Size,
        [string] $Sha256
    )

    $destination = Join-Path $workspace "destination-$Name"
    $rejected = $false
    try {
        Expand-AuthenticatedTarGzip $Archive $destination $Size $Sha256 | Out-Null
    } catch {
        $rejected = $true
    }
    if (-not $rejected -or (Test-Path $destination)) {
        throw "Archive security case was not rejected before destination writes: $Name"
    }
}

Remove-Item $workspace -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $workspace | Out-Null
try {
    $component = $lock.components | Where-Object name -eq 'crashpad'
    $cached = Join-Path $root '.bootstrap\crashpad-acquire\crashpad.tar.gz'
    if (-not (Test-Path $cached)) {
        throw 'Run Acquire-Crashpad.ps1 before the archive security regression'
    }
    $tampered = Join-Path $workspace 'tampered.tar.gz'
    [IO.File]::WriteAllBytes($tampered, [IO.File]::ReadAllBytes($cached))
    $tamperedBytes = [IO.File]::ReadAllBytes($tampered)
    $tamperedBytes[$tamperedBytes.Length - 1] =
        $tamperedBytes[$tamperedBytes.Length - 1] -bxor 1
    [IO.File]::WriteAllBytes($tampered, $tamperedBytes)
    Assert-RejectedBeforeWrite `
        'tampered' $tampered $component.archive_size $component.archive_sha256

    $cases = @(
        @('absolute', [System.Formats.Tar.TarEntryType]::RegularFile, '/absolute.txt'),
        @('drive', [System.Formats.Tar.TarEntryType]::RegularFile, 'C:/drive.txt'),
        @('traversal', [System.Formats.Tar.TarEntryType]::RegularFile, '../escape.txt'),
        @('backslash', [System.Formats.Tar.TarEntryType]::RegularFile, '..\escape.txt'),
        @('symlink', [System.Formats.Tar.TarEntryType]::SymbolicLink, 'link'),
        @('hardlink', [System.Formats.Tar.TarEntryType]::HardLink, 'hardlink'),
        @('device', [System.Formats.Tar.TarEntryType]::CharacterDevice, 'device'),
        @('fifo', [System.Formats.Tar.TarEntryType]::Fifo, 'fifo')
    )
    $rejectedCases = @()
    foreach ($case in $cases) {
        $name = $case[0]
        $archive = Join-Path $workspace "$name.tar.gz"
        New-TestArchive $archive $case[1] $case[2]
        $identity = Get-ArchiveIdentity $archive
        Assert-RejectedBeforeWrite $name $archive $identity.size $identity.sha256
        $rejectedCases += $name
    }

    $safeArchive = Join-Path $workspace 'safe.tar.gz'
    New-TestArchive `
        $safeArchive `
        ([System.Formats.Tar.TarEntryType]::RegularFile) `
        'safe/file.txt'
    $safeIdentity = Get-ArchiveIdentity $safeArchive
    $safeDestination = Join-Path $workspace 'destination-safe'
    $safeResult = Expand-AuthenticatedTarGzip `
        $safeArchive `
        $safeDestination `
        $safeIdentity.size `
        $safeIdentity.sha256
    if ((Get-Content (Join-Path $safeDestination 'safe\file.txt') -Raw) -ne
        'tracebox archive fixture') {
        throw 'Authenticated safe archive did not extract expected bytes'
    }

    [ordered]@{
        tampered_archive_rejected_before_write = $true
        malicious_entries_rejected_before_write = $rejectedCases
        safe_archive_entry_count = $safeResult.entry_count
        result = 'PASS'
    } | ConvertTo-Json
} finally {
    Remove-Item $workspace -Recurse -Force -ErrorAction SilentlyContinue
}
