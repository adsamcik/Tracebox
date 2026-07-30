param(
    [switch] $AllowFirewallMutation
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$temporary = Join-Path (
    [IO.Path]::GetTempPath()
) ("tracebox-tbdiag-egress-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null

function Add-Be32 {
    param([Collections.Generic.List[byte]] $Bytes, [uint32] $Value)
    $Bytes.Add([byte](($Value -shr 24) -band 0xff))
    $Bytes.Add([byte](($Value -shr 16) -band 0xff))
    $Bytes.Add([byte](($Value -shr 8) -band 0xff))
    $Bytes.Add([byte]($Value -band 0xff))
}

function Add-Be64 {
    param([Collections.Generic.List[byte]] $Bytes, [uint64] $Value)
    for ($shift = 56; $shift -ge 0; $shift -= 8) {
        $Bytes.Add([byte](($Value -shr $shift) -band 0xff))
    }
}

function Add-Le32 {
    param([Collections.Generic.List[byte]] $Bytes, [uint32] $Value)
    $Bytes.AddRange([BitConverter]::GetBytes($Value))
}

function Add-Le64 {
    param([Collections.Generic.List[byte]] $Bytes, [uint64] $Value)
    $Bytes.AddRange([BitConverter]::GetBytes($Value))
}

function Invoke-IsolatedTbdiag {
    param([string[]] $Arguments)
    if ($script:platform -eq 'windows') {
        $output = & $script:tbdiag @Arguments 2>&1
    } elseif ($script:platform -eq 'linux') {
        $output = & unshare '--user' '--map-root-user' '--net' '--' $script:tbdiag @Arguments 2>&1
    } else {
        $profile = '(version 1) (allow default) (deny network*)'
        $output = & sandbox-exec '-p' $profile $script:tbdiag @Arguments 2>&1
    }
    if ($LASTEXITCODE -ne 0) {
        throw "tbdiag command failed in blocked-egress sandbox: $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return ($output -join [Environment]::NewLine)
}

$firewallRuleName = $null
try {
    Push-Location $root
    try {
        & cargo build -q -p tbdiag-cli --locked --offline
        if ($LASTEXITCODE -ne 0) {
            throw "tbdiag build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }

    $script:platform = if ($env:OS -eq 'Windows_NT') {
        'windows'
    } elseif ($PSVersionTable.Platform -eq 'Unix' -and
        (Get-Command unshare -ErrorAction SilentlyContinue)) {
        'linux'
    } elseif (Get-Command sandbox-exec -ErrorAction SilentlyContinue) {
        'macos'
    } else {
        throw 'No supported process network-isolation mechanism is available'
    }
    $extension = if ($script:platform -eq 'windows') { '.exe' } else { '' }
    $script:tbdiag = (
        Resolve-Path (Join-Path $root "target\debug\tbdiag$extension")
    ).Path

    Add-Type -AssemblyName System.IO.Compression
    $recordBytes = [Collections.Generic.List[byte]]::new()
    foreach ($value in @(1, 3, 1, 1, 0, 1)) {
        Add-Be32 $recordBytes ([uint32]$value)
    }
    Add-Be64 $recordBytes 42
    Add-Le32 $recordBytes 7
    Add-Le64 $recordBytes 9
    $archivePath = Join-Path $temporary 'valid.tbdiag'
    $archiveStream = [IO.File]::Create($archivePath)
    try {
        $archive = [IO.Compression.ZipArchive]::new(
            $archiveStream,
            [IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            $entry = $archive.CreateEntry(
                'records/000001.tbr',
                [IO.Compression.CompressionLevel]::NoCompression
            )
            $entryStream = $entry.Open()
            try {
                $payload = $recordBytes.ToArray()
                $entryStream.Write($payload, 0, $payload.Length)
            } finally {
                $entryStream.Dispose()
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $archiveStream.Dispose()
    }

    $catalogPath = Join-Path $temporary 'catalog.tsv'
    $catalog = @(
        '# tracebox-symbol-catalog-v2'
        "build`tbuild-good`tschema`tdev.tracebox`t7`t1.0`trelease`t-`t-`t-`t-`t-"
        "native`tlibtracebox.so`tsha256:good`tx86_64`t42`tnative_symbol"
        "r8`tsha256:mapping`ta.b.c`tdev.tracebox.Original.method"
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText(
        $catalogPath,
        $catalog + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false)
    )

    if ($script:platform -eq 'windows') {
        if (-not $AllowFirewallMutation) {
            throw 'Windows requires -AllowFirewallMutation to install the temporary, executable-scoped outbound block rule'
        }
        $principal = [Security.Principal.WindowsPrincipal]::new(
            [Security.Principal.WindowsIdentity]::GetCurrent()
        )
        if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
            throw 'Windows blocked-egress proof must run from an elevated PowerShell session'
        }
        $firewallRuleName = "Tracebox tbdiag blocked egress $([Guid]::NewGuid().ToString('N'))"
        New-NetFirewallRule `
            -DisplayName $firewallRuleName `
            -Direction Outbound `
            -Action Block `
            -Program $script:tbdiag `
            -Profile Any `
            -Enabled True | Out-Null
        $rule = Get-NetFirewallRule -DisplayName $firewallRuleName
        if (-not $rule -or $rule.Enabled -ne 'True' -or $rule.Action -ne 'Block') {
            throw 'Temporary tbdiag outbound firewall rule is not active'
        }
    }

    $commands = @(
        [pscustomobject]@{ id = 'inspect'; args = @('inspect', $archivePath); needle = 'records/000001.tbr' }
        [pscustomobject]@{ id = 'validate'; args = @('validate', $archivePath); needle = 'valid:' }
        [pscustomobject]@{ id = 'decode'; args = @('decode', $archivePath); needle = '"event_type":"Breadcrumb"' }
        [pscustomobject]@{ id = 'filter'; args = @('filter', $archivePath, 'Breadcrumb'); needle = '"event_type":"Breadcrumb"' }
        [pscustomobject]@{ id = 'retrace'; args = @('retrace', $catalogPath, 'build-good', 'sha256:mapping', 'a.b.c'); needle = 'dev.tracebox.Original.method' }
        [pscustomobject]@{ id = 'symbolize'; args = @('symbolize', $catalogPath, 'build-good', 'x86_64', 'libtracebox.so', 'sha256:good', '42'); needle = 'native_symbol' }
    )
    $results = foreach ($command in $commands) {
        $output = Invoke-IsolatedTbdiag -Arguments $command.args
        if (-not $output.Contains($command.needle)) {
            throw "Unexpected output from blocked-egress command $($command.id): $output"
        }
        [pscustomobject]@{
            command = $command.id
            isolated = $true
            result = 'PASS'
        }
    }

    [ordered]@{
        schema = 'tracebox-tbdiag-blocked-egress-v1'
        platform = $script:platform
        executable_sha256 = (Get-FileHash -Algorithm SHA256 $script:tbdiag).Hash.ToLowerInvariant()
        commands = @($results)
        positive_control = 'test-apps/phase0-fixture hostNetwork variant'
        result = 'PASS'
    } | ConvertTo-Json -Depth 4
} finally {
    if ($firewallRuleName) {
        Remove-NetFirewallRule -DisplayName $firewallRuleName -ErrorAction SilentlyContinue
    }
    $resolvedTemporary = [IO.Path]::GetFullPath($temporary)
    $resolvedSystemTemporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolvedTemporary.StartsWith(
            $resolvedSystemTemporary,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "Refusing to clean unexpected temporary path: $resolvedTemporary"
    }
    if (Test-Path -LiteralPath $resolvedTemporary) {
        Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
    }
}
