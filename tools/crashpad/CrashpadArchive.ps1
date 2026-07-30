$ErrorActionPreference = 'Stop'

function Test-CrashpadArchiveEntryName {
    param(
        [Parameter(Mandatory)]
        [string] $Name,
        [Parameter(Mandatory)]
        [string] $Destination
    )

    if ([string]::IsNullOrWhiteSpace($Name) -or
        $Name.IndexOfAny([char[]](0..31 + 127)) -ge 0 -or
        $Name.Contains('\') -or
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

function Invoke-CrashpadTar {
    param(
        [Parameter(Mandatory)]
        [string[]] $Arguments
    )

    $command = Get-Command tar.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $command) {
        $command = Get-Command tar -ErrorAction SilentlyContinue |
            Select-Object -First 1
    }
    if (-not $command) {
        throw 'A tar executable is required to inspect authenticated Crashpad archives'
    }
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $command.Source
    $info.Arguments = (
        $Arguments |
            ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
    ) -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) {
            throw "Unable to start tar executable: $($command.Source)"
        }
        $standardOutput = $process.StandardOutput.ReadToEndAsync()
        $standardError = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $output = $standardOutput.Result
        $errorOutput = $standardError.Result
        if ($process.ExitCode -ne 0) {
            throw "tar failed with exit code $($process.ExitCode): $errorOutput"
        }
        return $output
    } finally {
        $process.Dispose()
    }
}

function Get-CrashpadTarProfile {
    param(
        [Parameter(Mandatory)]
        [string] $Archive,
        [Parameter(Mandatory)]
        [string] $Destination
    )

    $names = @(
        (Invoke-CrashpadTar @('-tzf', $Archive)) -split "`r?`n" |
            Where-Object { $_.Length -ne 0 }
    )
    $verbose = @(
        (Invoke-CrashpadTar @('-tvzf', $Archive)) -split "`r?`n" |
            Where-Object { $_.Length -ne 0 }
    )
    if ($names.Count -eq 0 -or $names.Count -ne $verbose.Count) {
        throw 'Archive listing is empty or inconsistent'
    }
    if ($names.Count -gt 500000) {
        throw "Archive entry count exceeds validation bound: $($names.Count)"
    }
    $paths = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::OrdinalIgnoreCase)
    for ($index = 0; $index -lt $names.Count; $index++) {
        $name = $names[$index]
        $type = $verbose[$index][0]
        if ($type -notin @('-', 'd')) {
            throw "Unsafe archive entry type '$type': $name"
        }
        $target = Test-CrashpadArchiveEntryName $name $Destination
        if (-not $paths.Add($target)) {
            throw "Duplicate archive path: $name"
        }
    }
    return [pscustomobject]@{
        Count = $names.Count
        Paths = @($paths)
    }
}

function Expand-CrashpadTar {
    param(
        [Parameter(Mandatory)]
        [string] $Archive,
        [Parameter(Mandatory)]
        [string] $Destination
    )

    Invoke-CrashpadTar @(
        '-xzf',
        $Archive,
        '-C',
        $Destination,
        '--no-same-owner',
        '--no-same-permissions'
    ) | Out-Null
    $reparsePoints = @(
        Get-ChildItem -LiteralPath $Destination -Recurse -Force |
            Where-Object {
                ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
            }
    )
    if ($reparsePoints.Count -ne 0) {
        throw "Archive extraction created a reparse point: $($reparsePoints[0].FullName)"
    }
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
            $actualSha256 = (
                [BitConverter]::ToString($sha256.ComputeHash($stream)) -replace '-', ''
            ).ToLowerInvariant()
        } finally {
            $sha256.Dispose()
        }
        if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
            throw "Archive SHA-256 mismatch: $Archive"
        }

        $profile = Get-CrashpadTarProfile $Archive $Destination

        if (Test-Path $Destination) {
            Remove-Item $Destination -Recurse -Force
        }
        New-Item -ItemType Directory -Force $Destination | Out-Null
        try {
            Expand-CrashpadTar $Archive $Destination
            foreach ($path in $profile.Paths) {
                if (-not (Test-Path -LiteralPath $path)) {
                    throw "Validated archive entry was not extracted: $path"
                }
            }
        } catch {
            Remove-Item $Destination -Recurse -Force -ErrorAction SilentlyContinue
            throw
        }

        return [ordered]@{
            archive_size = $stream.Length
            archive_sha256 = $actualSha256
            entry_count = $profile.Count
        }
    } finally {
        $stream.Dispose()
    }
}
