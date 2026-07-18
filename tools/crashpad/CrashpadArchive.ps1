$ErrorActionPreference = 'Stop'

function Test-CrashpadArchiveEntryName {
    param(
        [Parameter(Mandatory)]
        [string] $Name,
        [Parameter(Mandatory)]
        [string] $Destination
    )

    if ([string]::IsNullOrWhiteSpace($Name) -or $Name.Contains('\') -or
        $Name.StartsWith('/') -or $Name -match '^[A-Za-z]:' -or
        $Name.Contains('//')) {
        throw "Unsafe archive path: $Name"
    }
    $normalized = $Name.TrimEnd('/')
    if ([string]::IsNullOrWhiteSpace($normalized) -or
        @($normalized.Split('/') | Where-Object { $_ -in @('.', '..', '') }).Count -ne 0) {
        throw "Unsafe archive path: $Name"
    }

    $root = [IO.Path]::GetFullPath($Destination).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
    $relative = $normalized.Replace('/', [IO.Path]::DirectorySeparatorChar)
    $candidate = [IO.Path]::GetFullPath((Join-Path $root $relative))
    $prefix = $root + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith(
            $prefix,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Archive path escapes destination: $Name"
    }
    return $candidate
}

function Read-CrashpadTar {
    param(
        [Parameter(Mandatory)]
        [IO.Stream] $ArchiveStream,
        [Parameter(Mandatory)]
        [string] $Destination,
        [Parameter(Mandatory)]
        [bool] $Extract
    )

    $gzip = [IO.Compression.GZipStream]::new(
        $ArchiveStream,
        [IO.Compression.CompressionMode]::Decompress,
        $true)
    $reader = [System.Formats.Tar.TarReader]::new($gzip, $true)
    $paths = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::OrdinalIgnoreCase)
    $entryCount = 0
    try {
        while ($entry = $reader.GetNextEntry()) {
            $entryCount++
            if ($entry.EntryType -notin @(
                    [System.Formats.Tar.TarEntryType]::RegularFile,
                    [System.Formats.Tar.TarEntryType]::V7RegularFile,
                    [System.Formats.Tar.TarEntryType]::Directory)) {
                throw "Unsafe archive entry type $($entry.EntryType): $($entry.Name)"
            }
            $target = Test-CrashpadArchiveEntryName $entry.Name $Destination
            if (-not $paths.Add($target)) {
                throw "Duplicate archive path: $($entry.Name)"
            }
            if (-not $Extract) {
                continue
            }
            if ($entry.EntryType -eq [System.Formats.Tar.TarEntryType]::Directory) {
                New-Item -ItemType Directory -Force $target | Out-Null
                continue
            }
            $parent = Split-Path $target -Parent
            New-Item -ItemType Directory -Force $parent | Out-Null
            $entry.ExtractToFile($target, $false)
        }
    } finally {
        $reader.Dispose()
        $gzip.Dispose()
    }
    return $entryCount
}

function Expand-AuthenticatedTarGzip {
    param(
        [Parameter(Mandatory)]
        [string] $Archive,
        [Parameter(Mandatory)]
        [string] $Destination,
        [Parameter(Mandatory)]
        [long] $ExpectedSize,
        [Parameter(Mandatory)]
        [string] $ExpectedSha256
    )

    if ($ExpectedSize -le 0 -or
        $ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw 'Archive lock is missing a valid byte size or SHA-256'
    }

    $stream = [IO.File]::Open(
        $Archive,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read)
    try {
        if ($stream.Length -ne $ExpectedSize) {
            throw "Archive size mismatch: $Archive"
        }
        $sha256 = [Security.Cryptography.SHA256]::Create()
        try {
            $actualSha256 = [Convert]::ToHexString(
                $sha256.ComputeHash($stream)).ToLowerInvariant()
        } finally {
            $sha256.Dispose()
        }
        if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
            throw "Archive SHA-256 mismatch: $Archive"
        }

        $stream.Position = 0
        $entryCount = Read-CrashpadTar $stream $Destination $false
        if ($entryCount -eq 0) {
            throw "Archive has no entries: $Archive"
        }

        if (Test-Path $Destination) {
            Remove-Item $Destination -Recurse -Force
        }
        New-Item -ItemType Directory -Force $Destination | Out-Null
        try {
            $stream.Position = 0
            $extractedCount = Read-CrashpadTar $stream $Destination $true
            if ($extractedCount -ne $entryCount) {
                throw "Archive entry count changed during extraction: $Archive"
            }
        } catch {
            Remove-Item $Destination -Recurse -Force -ErrorAction SilentlyContinue
            throw
        }

        return [ordered]@{
            archive_size = $stream.Length
            archive_sha256 = $actualSha256
            entry_count = $entryCount
        }
    } finally {
        $stream.Dispose()
    }
}
