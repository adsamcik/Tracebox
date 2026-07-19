$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
& (Join-Path $root 'tools\verify\Verify-FoundationSpecs.ps1')
& (Join-Path $root 'tools\verify\Verify-Phase5NoNetworkStatic.ps1')
Push-Location $root
try {
  & cargo test --workspace
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  & .\gradlew.bat phase1Check phase2Check phase4CoreCheck --offline --no-daemon
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
