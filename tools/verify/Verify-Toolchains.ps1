$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root

$java = (& java -version 2>&1 | Select-Object -First 1) -join ''
$rust = (& rustc --version) -join ''
$cargo = (& cargo --version) -join ''
$cmake = (& "$env:ANDROID_HOME\cmake\4.1.2\bin\cmake.exe" --version | Select-Object -First 1) -join ''
$wrapper = (Get-FileHash 'gradle\wrapper\gradle-wrapper.jar' -Algorithm SHA256).Hash.ToLowerInvariant()

if ($java -notmatch '21\.0\.8') { throw "Unexpected Java: $java" }
if ($rust -notmatch '^rustc 1\.93\.1 ') { throw "Unexpected Rust: $rust" }
if ($cargo -notmatch '^cargo 1\.93\.1 ') { throw "Unexpected Cargo: $cargo" }
if ($cmake -ne 'cmake version 4.1.2') { throw "Unexpected CMake: $cmake" }
if ($wrapper -ne '497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7') {
    throw 'Unexpected Gradle wrapper JAR'
}

$requiredSdkPaths = @(
    "$env:ANDROID_HOME\platforms\android-37.0",
    "$env:ANDROID_HOME\build-tools\37.0.0",
    "$env:ANDROID_HOME\ndk\29.0.14206865",
    "$env:ANDROID_HOME\cmake\4.1.2"
)
foreach ($path in $requiredSdkPaths) {
    if (-not (Test-Path $path)) {
        throw "Missing Android toolchain path: $path"
    }
}

$metadataText = Get-Content 'gradle\verification-metadata.xml' -Raw
$dependencyComponents = [regex]::Matches($metadataText, '<component ').Count
if ($dependencyComponents -lt 100) {
    throw 'Dependency verification metadata is unexpectedly small'
}

$lockFiles = Get-ChildItem -Recurse -File -Filter 'gradle.lockfile'
if ($lockFiles.Count -lt 4) {
    throw 'Expected Gradle dependency lock files'
}

[ordered]@{
    java = $java
    rustc = $rust
    cargo = $cargo
    cmake = $cmake
    gradle_wrapper_sha256 = $wrapper
    dependency_components = $dependencyComponents
    gradle_lock_files = $lockFiles.Count
    result = 'PASS'
} | ConvertTo-Json
