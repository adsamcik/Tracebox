$ErrorActionPreference = 'Stop'

& "$PSScriptRoot\..\traceability\Initialize-Traceability.ps1"
& "$PSScriptRoot\..\verify\Verify-FoundationSpecs.ps1"
& "$PSScriptRoot\..\verify\Verify-Toolchains.ps1"
& "$PSScriptRoot\..\verify\Verify-NoNetworkStatic.ps1"
& "$PSScriptRoot\..\crashpad\Acquire-Crashpad.ps1"

& "$PSScriptRoot\..\..\gradlew.bat" phase0Check --dependency-verification strict --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Push-Location (Join-Path $PSScriptRoot '..\..')
try {
    & cargo fmt --all -- --check
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & cargo clippy --workspace --all-targets --locked --offline -- -D warnings
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & cargo test --workspace --locked --offline
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & cargo build -p tracebox-phase0 --target aarch64-linux-android --locked --offline
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & cargo build -p tracebox-phase0 --target x86_64-linux-android --locked --offline
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
