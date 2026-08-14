param(
    [Parameter(Mandatory)]
    [string] $Apk,
    [string[]] $Aar = @()
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
if (-not $apkPath.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
    throw "APK is outside the Tracebox workspace: $apkPath"
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-U32Le {
    param([byte[]] $Bytes, [int] $Offset)
    if ($Offset -lt 0 -or $Offset + 4 -gt $Bytes.Length) {
        throw "DEX u32 offset is out of range: $Offset"
    }
    return [BitConverter]::ToUInt32($Bytes, $Offset)
}

function Read-Uleb128End {
    param([byte[]] $Bytes, [int] $Offset)
    $cursor = $Offset
    for ($index = 0; $index -lt 5; $index++) {
        if ($cursor -ge $Bytes.Length) { throw 'Truncated DEX ULEB128' }
        $value = $Bytes[$cursor]
        $cursor++
        if (($value -band 0x80) -eq 0) { return $cursor }
    }
    throw 'Oversized DEX ULEB128'
}

function Read-DexString {
    param([byte[]] $Bytes, [int] $Offset)
    $cursor = Read-Uleb128End $Bytes $Offset
    $start = $cursor
    while ($cursor -lt $Bytes.Length -and $Bytes[$cursor] -ne 0) {
        if ($cursor - $start -ge 4096) { throw 'DEX string exceeds 4096-byte scan bound' }
        $cursor++
    }
    if ($cursor -ge $Bytes.Length) { throw 'Unterminated DEX string' }
    return [Text.Encoding]::UTF8.GetString($Bytes, $start, $cursor - $start)
}

function Get-DexTypeDescriptors {
    param([byte[]] $Bytes)
    if ($Bytes.Length -lt 112 -or [Text.Encoding]::ASCII.GetString($Bytes, 0, 4) -ne "dex`n") {
        throw 'Malformed DEX header'
    }
    $stringCount = [int](Read-U32Le $Bytes 0x38)
    $stringOffset = [int](Read-U32Le $Bytes 0x3c)
    $typeCount = [int](Read-U32Le $Bytes 0x40)
    $typeOffset = [int](Read-U32Le $Bytes 0x44)
    if ($stringCount -gt 1000000 -or $typeCount -gt 1000000) {
        throw 'DEX identifier table exceeds scan bound'
    }
    if ($stringOffset -lt 0 -or $stringOffset + 4L * $stringCount -gt $Bytes.Length -or
        $typeOffset -lt 0 -or $typeOffset + 4L * $typeCount -gt $Bytes.Length) {
        throw 'DEX identifier table is out of range'
    }
    $descriptors = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($type = 0; $type -lt $typeCount; $type++) {
        $stringIndex = [int](Read-U32Le $Bytes ($typeOffset + 4 * $type))
        if ($stringIndex -lt 0 -or $stringIndex -ge $stringCount) {
            throw 'DEX type descriptor index is out of range'
        }
        $dataOffset = [int](Read-U32Le $Bytes ($stringOffset + 4 * $stringIndex))
        if ($dataOffset -lt 0 -or $dataOffset -ge $Bytes.Length) {
            throw 'DEX string data offset is out of range'
        }
        [void]$descriptors.Add((Read-DexString $Bytes $dataOffset))
    }
    return @($descriptors)
}

function Get-AndroidSdk {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $localProperties = Join-Path $root 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $line = Get-Content $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $value = ($line -split '=', 2)[1].Replace('\\', '\')
            if (Test-Path -LiteralPath $value -PathType Container) {
                return (Resolve-Path -LiteralPath $value).Path
            }
        }
    }
    throw 'Android SDK root is unavailable'
}

function Get-LlvmReadElf {
    $sdk = Get-AndroidSdk
    $prebuilt = Join-Path $sdk 'ndk\28.2.13676358\toolchains\llvm\prebuilt'
    $name = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'llvm-readelf.exe' } else { 'llvm-readelf' }
    $tool = Get-ChildItem $prebuilt -Recurse -File -Filter $name | Select-Object -First 1
    if (-not $tool) { throw "Pinned NDK llvm-readelf not found below $prebuilt" }
    return $tool.FullName
}

$forbiddenDexPrefixes = @(
    'Ljava/net/',
    'Ljavax/net/',
    'Lokhttp3/',
    'Lio/ktor/client/',
    'Lorg/apache/http/'
)
$allowedNonTransportDexDescriptors = @(
    # java.nio.file desugaring uses URI strictly as a file-scheme value object. Neither class
    # opens sockets, resolves hosts, or grants transport capability.
    'Ljava/net/URI;',
    'Ljava/net/URISyntaxException;'
)
$forbiddenNative = @(
    '(?m)\bUND\b.*\b(getaddrinfo|freeaddrinfo|gethostbyname|android_getaddrinfofornet|curl_easy|SSL_connect)\b',
    '(?m)\bGLOBAL\b.*(?:std3net|std\.\.net)',
    '(?m)\(NEEDED\).*\[(libcurl|libcronet|libssl)\.so'
)
$allowedNativeDiagnosticUrlPrefixes = @(
    'https://crashpad.chromium.org/',
    'https://android.googlesource.com/toolchain/llvm-project'
)

$temporary = Join-Path ([IO.Path]::GetTempPath()) ("tracebox-release-scan-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $readElf = Get-LlvmReadElf
    $dexEntries = 0
    $nativeEntries = 0
    $typeDescriptors = 0
    $violations = @()
    $archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        foreach ($entry in $archive.Entries) {
            $isDex = $entry.FullName -match '(^|/)classes(\d*)?\.dex$'
            $isNative = $entry.FullName -match '^lib/.+\.so$'
            if (-not $isDex -and -not $isNative) { continue }
            if ($entry.Length -gt 256MB) { throw "Oversized release binary: $($entry.FullName)" }
            $stream = $entry.Open()
            try {
                $memory = [IO.MemoryStream]::new()
                try {
                    $stream.CopyTo($memory)
                    $bytes = $memory.ToArray()
                } finally {
                    $memory.Dispose()
                }
            } finally {
                $stream.Dispose()
            }
            if ($isDex) {
                $dexEntries++
                $descriptors = Get-DexTypeDescriptors $bytes
                $typeDescriptors += $descriptors.Count
                foreach ($descriptor in $descriptors) {
                    if (
                        $descriptor -notin $allowedNonTransportDexDescriptors -and
                        ($forbiddenDexPrefixes | Where-Object {
                            $descriptor.StartsWith($_, [StringComparison]::Ordinal)
                        })
                    ) {
                        $violations += "DEX:$($entry.FullName):$descriptor"
                    }
                }
            } else {
                $nativeEntries++
                $nativePath = Join-Path $temporary ([IO.Path]::GetFileName($entry.FullName))
                [IO.File]::WriteAllBytes($nativePath, $bytes)
                $imports = (& $readElf '--dyn-syms' '--dynamic' '--wide' $nativePath 2>&1) -join "`n"
                if ($LASTEXITCODE -ne 0) { throw "llvm-readelf failed for $($entry.FullName)" }
                foreach ($pattern in $forbiddenNative) {
                    if ($imports -match $pattern) {
                        $violations += "ELF:$($entry.FullName):$($Matches[0])"
                    }
                }
                $ascii = [Text.Encoding]::ASCII.GetString($bytes)
                foreach ($match in [regex]::Matches($ascii, 'https?://[^\x00\s]+')) {
                    $url = $match.Value
                    if (-not ($allowedNativeDiagnosticUrlPrefixes | Where-Object {
                                $url.StartsWith($_, [StringComparison]::Ordinal)
                            })) {
                        $violations += "ELF:$($entry.FullName):$url"
                    }
                }
            }
        }
    } finally {
        $archive.Dispose()
    }
    if ($dexEntries -eq 0 -or $nativeEntries -eq 0) {
        throw "Expected DEX and ELF entries (DEX=$dexEntries ELF=$nativeEntries)"
    }
    if ($violations) {
        throw "Forbidden release networking surface: $($violations -join '; ')"
    }

    $artifactViolations = @()
    $artifactFiles = foreach ($path in $Aar) {
        (Resolve-Path -LiteralPath $path).Path
    }
    foreach ($aarPath in $artifactFiles) {
        if (-not $aarPath.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
            throw "AAR is outside the Tracebox workspace: $aarPath"
        }
        $aarArchive = [IO.Compression.ZipFile]::OpenRead($aarPath)
        try {
            foreach ($entry in $aarArchive.Entries) {
                if ($entry.FullName -match 'dev/tracebox/phase0|LabScenario|HostNetworkControl') {
                    $artifactViolations += "$aarPath::$($entry.FullName)"
                }
                if ($entry.FullName -eq 'classes.jar') {
                    $stream = $entry.Open()
                    try {
                        $memory = [IO.MemoryStream]::new()
                        try {
                            $stream.CopyTo($memory)
                            $jarBytes = $memory.ToArray()
                        } finally {
                            $memory.Dispose()
                        }
                    } finally {
                        $stream.Dispose()
                    }
                    $jarStream = [IO.MemoryStream]::new($jarBytes, $false)
                    try {
                        $jar = [IO.Compression.ZipArchive]::new(
                            $jarStream,
                            [IO.Compression.ZipArchiveMode]::Read,
                            $false
                        )
                        try {
                            foreach ($classEntry in $jar.Entries) {
                                if ($classEntry.FullName -match 'dev/tracebox/phase0|LabScenario|HostNetworkControl') {
                                    $artifactViolations += "$aarPath::$($classEntry.FullName)"
                                }
                                if (-not $classEntry.FullName.EndsWith('.class', [StringComparison]::Ordinal)) {
                                    continue
                                }
                                $classStream = $classEntry.Open()
                                try {
                                    $classMemory = [IO.MemoryStream]::new()
                                    try {
                                        $classStream.CopyTo($classMemory)
                                        $classText = [Text.Encoding]::ASCII.GetString($classMemory.ToArray())
                                    } finally {
                                        $classMemory.Dispose()
                                    }
                                } finally {
                                    $classStream.Dispose()
                                }
                                foreach ($needle in @(
                                        'approvalBypass',
                                        'autoApprove',
                                        'forgeApproval',
                                        'crashForTest',
                                        'stackOverflowForTest',
                                        'hangForTest',
                                        'recursiveSignalForTest',
                                        'prepareSignalChainForTest',
                                        'terminateHandlerForTest',
                                        'requestSeededNonFatalForTest',
                                        'writeEmergencyForTest',
                                        'writeEmergencyFaultForTest',
                                        'lastRegistrationOutcomeForTest'
                                    )) {
                                    if ($classText.Contains($needle)) {
                                        $artifactViolations += "$aarPath::$($classEntry.FullName)::$needle"
                                    }
                                }
                            }
                        } finally {
                            $jar.Dispose()
                        }
                    } finally {
                        $jarStream.Dispose()
                    }
                } elseif ($entry.FullName -match '^jni/.+\.so$') {
                    $stream = $entry.Open()
                    try {
                        $memory = [IO.MemoryStream]::new()
                        try {
                            $stream.CopyTo($memory)
                            $nativeText = [Text.Encoding]::ASCII.GetString($memory.ToArray())
                        } finally {
                            $memory.Dispose()
                        }
                    } finally {
                        $stream.Dispose()
                    }
                    foreach ($needle in @(
                            'crashForTest',
                            'stackOverflowForTest',
                            'hangForTest',
                            'recursiveSignalForTest',
                            'prepareSignalChainForTest',
                            'terminateHandlerForTest'
                        )) {
                        if ($nativeText.Contains($needle)) {
                            $artifactViolations += "$aarPath::$($entry.FullName)::$needle"
                        }
                    }
                }
            }
        } finally {
            $aarArchive.Dispose()
        }
    }
    if ($artifactViolations) {
        throw "Fixture fault or approval-bypass surface leaked into production AARs: $($artifactViolations -join '; ')"
    }

    [ordered]@{
        schema = 'tracebox-release-artifact-scan-v1'
        apk = $apkPath.Substring($root.Length + 1)
        dex_entries = $dexEntries
        dex_type_descriptors = $typeDescriptors
        native_entries = $nativeEntries
        aars_scanned = $artifactFiles.Count
        forbidden_matches = 0
        result = 'PASS'
    } | ConvertTo-Json -Depth 3
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
