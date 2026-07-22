$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$checkout = Join-Path $root 'third_party\crashpad\checkout\crashpad'
$overlay = Join-Path $checkout 'tracebox_overlay'
$bootstrap = Join-Path $root '.bootstrap\gn'
$toolLock = Get-Content (Join-Path $root 'third_party\crashpad\build-tools-lock.json') -Raw |
    ConvertFrom-Json
$ndk = Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358'
$ninja = Join-Path $env:ANDROID_HOME 'cmake\4.1.2\bin\ninja.exe'
$readelf = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
$strings = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strings.exe'
$strip = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'

& (Join-Path $PSScriptRoot 'Acquire-Crashpad.ps1')

New-Item -ItemType Directory -Force $bootstrap | Out-Null
$gnArchive = Join-Path $bootstrap 'gn.zip'
if (-not (Test-Path $gnArchive)) {
    & curl.exe -fsSL $toolLock.gn.url -o $gnArchive
    if ($LASTEXITCODE -ne 0) { throw 'GN download failed' }
}
if ((Get-FileHash $gnArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne
    $toolLock.gn.sha256) {
    throw 'GN checksum mismatch'
}
$gnDirectory = Join-Path $bootstrap 'bin'
$gn = Join-Path $gnDirectory 'gn.exe'
if (-not (Test-Path $gn)) {
    Expand-Archive $gnArchive $gnDirectory -Force
}
if ((& $gn --version) -ne $toolLock.gn.version) {
    throw 'GN version mismatch'
}
if ((& $ninja --version) -ne $toolLock.ninja.version) {
    throw 'Ninja version mismatch'
}
if (-not (Test-Path (Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-ar.exe'))) {
    throw 'Pinned NDK archive tool is missing'
}

if (Test-Path $overlay) {
    Remove-Item $overlay -Recurse -Force
}
Copy-Item (Join-Path $root 'native\crashpad\overlay') $overlay -Recurse
New-Item -ItemType Directory -Force (Join-Path $overlay 'emergency'),
    (Join-Path $overlay 'include\tracebox') | Out-Null
Copy-Item (Join-Path $root 'native\emergency\tracebox_emergency.c') `
    (Join-Path $overlay 'emergency\tracebox_emergency.c')
Copy-Item (Join-Path $root 'native\include\tracebox\abi.h') `
    (Join-Path $overlay 'include\tracebox\abi.h')
Copy-Item (Join-Path $root 'native\include\tracebox\generated_events.h') `
    (Join-Path $overlay 'include\tracebox\generated_events.h')
Copy-Item (Join-Path $root 'native\include\tracebox\emergency.h') `
    (Join-Path $overlay 'include\tracebox\emergency.h')
Copy-Item (Join-Path $root 'native\include\tracebox\emergency_initialization.h') `
    (Join-Path $overlay 'include\tracebox\emergency_initialization.h')

$env:PATH = 'C:\Program Files\Git\usr\bin;' + $env:PATH
$targets = @(
    @{ cpu = 'x64'; abi = 'x86_64' },
    @{ cpu = 'arm64'; abi = 'arm64-v8a' }
)
$results = @()
Set-Location $checkout
foreach ($target in $targets) {
    $out = Join-Path $checkout "out\tracebox-$($target.cpu)"
    $args = 'target_os="android" ' +
        "target_cpu=`"$($target.cpu)`" " +
        "android_ndk_root=`"$($ndk.Replace('\', '/'))`" " +
        'android_api_level=23 is_debug=false ' +
        'extra_cflags="-ffunction-sections -fdata-sections" ' +
        'extra_ldflags="-static-libstdc++ -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096"'
    & $gn gen $out "--args=$args" --fail-on-unused-args
    if ($LASTEXITCODE -ne 0) { throw "GN generation failed: $($target.abi)" }
    & $ninja -C $out tracebox_capture_only crashpad_handler
    if ($LASTEXITCODE -ne 0) { throw "Crashpad build failed: $($target.abi)" }

    $library = Join-Path $out 'tracebox_crashpad.so'
    $handler = Join-Path $out 'crashpad_handler'
    & $strip --strip-unneeded $library $handler
    if ($LASTEXITCODE -ne 0) { throw "Native strip failed: $($target.abi)" }
    $jniDirectory = Join-Path $root "android\tracebox-native\src\main\jniLibs\$($target.abi)"
    New-Item -ItemType Directory -Force $jniDirectory | Out-Null
    Copy-Item $library (Join-Path $jniDirectory 'libtracebox_crashpad.so') -Force

    $programHeaders = (& $readelf -lW $library) -join "`n"
    if ($programHeaders -notmatch '0x4000') {
        throw "Missing 16 KiB ELF alignment: $($target.abi)"
    }
    $nativeStrings = (& $strings $library) -join "`n"
    foreach ($forbidden in @('--url=', 'no-upload-gzip', 'Breakpad server',
            'HTTPTransport', 'http_transport_socket')) {
        if ($nativeStrings.Contains($forbidden)) {
            throw "Forbidden uploader/network string '$forbidden': $($target.abi)"
        }
    }

    $results += [ordered]@{
        abi = $target.abi
        library = $library
        library_sha256 = (Get-FileHash $library -Algorithm SHA256).Hash.ToLowerInvariant()
        library_bytes = (Get-Item $library).Length
        handler = $handler
        handler_sha256 = (Get-FileHash $handler -Algorithm SHA256).Hash.ToLowerInvariant()
        handler_bytes = (Get-Item $handler).Length
        page_alignment = '0x4000'
    }
}
Set-Location $root

$results | ConvertTo-Json -Depth 4
