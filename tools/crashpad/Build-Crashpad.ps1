$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    $modernPowerShell = Get-Command pwsh.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $modernPowerShell) {
        throw 'The Crashpad build requires PowerShell 7 or newer'
    }
    & $modernPowerShell.Source -NoProfile -File $PSCommandPath
    if ($LASTEXITCODE -ne 0) {
        throw "PowerShell 7 Crashpad build failed with exit code $LASTEXITCODE"
    }
    return
}

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

function Assert-ElfLoadAlignment {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Abi
    )

    $programHeaders = @(& $readelf -lW $Path)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect ELF program headers: $Abi $Path"
    }
    $loadSegments = @($programHeaders | Where-Object { $_ -match '^\s*LOAD\s' })
    if ($loadSegments.Count -eq 0) {
        throw "ELF has no load segments: $Abi $Path"
    }
    foreach ($segment in $loadSegments) {
        if ($segment -notmatch '(0x[0-9a-fA-F]+)\s*$') {
            throw "Unable to decode ELF load alignment: $Abi $segment"
        }
        $alignment = [Convert]::ToUInt64($Matches[1].Substring(2), 16)
        if ($alignment -lt 0x4000) {
            throw "ELF load segment is not 16 KiB aligned: $Abi $Path $segment"
        }
    }
}

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
    (Join-Path $overlay 'signal'),
    (Join-Path $overlay 'include\tracebox') | Out-Null
Copy-Item (Join-Path $root 'native\emergency\tracebox_emergency.c') `
    (Join-Path $overlay 'emergency\tracebox_emergency.c')
Copy-Item (Join-Path $root 'native\signal\tracebox_signal_stack.cc') `
    (Join-Path $overlay 'signal\tracebox_signal_stack.cc')
Copy-Item (Join-Path $root 'native\include\tracebox\abi.h') `
    (Join-Path $overlay 'include\tracebox\abi.h')
Copy-Item (Join-Path $root 'native\include\tracebox\generated_events.h') `
    (Join-Path $overlay 'include\tracebox\generated_events.h')
Copy-Item (Join-Path $root 'native\include\tracebox\emergency.h') `
    (Join-Path $overlay 'include\tracebox\emergency.h')
Copy-Item (Join-Path $root 'native\include\tracebox\emergency_initialization.h') `
    (Join-Path $overlay 'include\tracebox\emergency_initialization.h')
Copy-Item (Join-Path $root 'native\include\tracebox\client_registration.h') `
    (Join-Path $overlay 'include\tracebox\client_registration.h')
Copy-Item (Join-Path $root 'native\include\tracebox\client_lifecycle_journal.h') `
    (Join-Path $overlay 'include\tracebox\client_lifecycle_journal.h')
Copy-Item (Join-Path $root 'native\include\tracebox\handler_lifecycle_drain.h') `
    (Join-Path $overlay 'include\tracebox\handler_lifecycle_drain.h')
Copy-Item (Join-Path $root 'native\include\tracebox\handler_socket_cleanup.h') `
    (Join-Path $overlay 'include\tracebox\handler_socket_cleanup.h')
Copy-Item (Join-Path $root 'native\include\tracebox\policy_transition.h') `
    (Join-Path $overlay 'include\tracebox\policy_transition.h')
Copy-Item (Join-Path $root 'native\include\tracebox\rust_bridge.h') `
    (Join-Path $overlay 'include\tracebox\rust_bridge.h')
Copy-Item (Join-Path $root 'native\include\tracebox\signal_stack.h') `
    (Join-Path $overlay 'include\tracebox\signal_stack.h')

$env:PATH = 'C:\Program Files\Git\usr\bin;' + $env:PATH
$targets = @(
    @{
        cpu = 'x86'
        abi = 'x86'
        rust_target = 'i686-linux-android'
        linker = 'i686-linux-android23-clang.cmd'
        linker_variable = 'CARGO_TARGET_I686_LINUX_ANDROID_LINKER'
    },
    @{
        cpu = 'x64'
        abi = 'x86_64'
        rust_target = 'x86_64-linux-android'
        linker = 'x86_64-linux-android23-clang.cmd'
        linker_variable = 'CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER'
    },
    @{
        cpu = 'arm'
        abi = 'armeabi-v7a'
        rust_target = 'armv7-linux-androideabi'
        linker = 'armv7a-linux-androideabi23-clang.cmd'
        linker_variable = 'CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER'
    },
    @{
        cpu = 'arm64'
        abi = 'arm64-v8a'
        rust_target = 'aarch64-linux-android'
        linker = 'aarch64-linux-android23-clang.cmd'
        linker_variable = 'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER'
    }
)
$results = @()
Set-Location $checkout
foreach ($target in $targets) {
    $rustTarget = $target.rust_target
    $linker = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\$($target.linker)"
    Set-Item -Path "Env:$($target.linker_variable)" -Value $linker
    & cargo build -p tracebox-android-bridge --release --target $rustTarget --locked --offline `
        --manifest-path (Join-Path $root 'Cargo.toml')
    if ($LASTEXITCODE -ne 0) { throw "Rust Android bridge build failed: $($target.abi)" }
    $rustDirectory = Join-Path $overlay "rust\$($target.abi)"
    New-Item -ItemType Directory -Force $rustDirectory | Out-Null
    Copy-Item (Join-Path $root "target\$rustTarget\release\libtracebox_android_bridge.a") `
        (Join-Path $rustDirectory 'libtracebox_android_bridge.a') -Force

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

    Assert-ElfLoadAlignment -Path $library -Abi $target.abi
    Assert-ElfLoadAlignment -Path $handler -Abi $target.abi
    $dynamicSymbols = (& $readelf --dyn-syms --wide $library) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect final dynamic symbols: $($target.abi)"
    }
    if ($dynamicSymbols -match '(?m)\bUND\b.*\b(getaddrinfo|freeaddrinfo)\b') {
        throw "Final capture library imports a DNS resolver: $($target.abi)"
    }
    if ($dynamicSymbols -match '(?m)\bGLOBAL\b.*(?:std3net|std\.\.net)') {
        throw "Final capture library exports dormant Rust std::net code: $($target.abi)"
    }
    foreach ($requiredSymbol in @(
            'Java_dev_tracebox_nativecapture_NativeRuntime_nativeInitializeEmergency',
            'tb_register_current_thread_signal_stack_v1',
            'tb_unregister_current_thread_signal_stack_v1',
            'tb_current_thread_signal_stack_registered_v1')) {
        if ($dynamicSymbols -notmatch [regex]::Escape($requiredSymbol)) {
            throw "Final capture library is missing '$requiredSymbol': $($target.abi)"
        }
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
