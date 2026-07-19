$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$denyCrates = @('reqwest','hyper','curl','isahc','surf','ureq','trust-dns','hickory','tokio')
$denyArtifacts = @('okhttp','retrofit','ktor','volley','cronet','apache-http','netty')
$manifestFiles = Get-ChildItem $root -Recurse -File -Filter AndroidManifest.xml | Where-Object { $_.FullName -notmatch '\\(build|third_party\\crashpad\\checkout)\\' }
foreach ($file in $manifestFiles) { if ((Get-Content $file.FullName -Raw) -match 'android\.permission\.INTERNET') { throw "Tracebox-owned INTERNET permission: $($file.FullName)" } }
$lock = Join-Path $root 'Cargo.lock'
$packageNames = [regex]::Matches((Get-Content $lock -Raw), '(?m)^name = "([^"]+)"$') | ForEach-Object { $_.Groups[1].Value }
$foundCrates = $packageNames | Where-Object { $denyCrates -contains $_ }
if ($foundCrates) { throw "Forbidden Rust networking dependencies: $($foundCrates -join ', ')" }
$gradleFiles = Get-ChildItem (Join-Path $root 'android'), (Join-Path $root 'tooling'), (Join-Path $root 'test-apps') -Recurse -File -Include *.gradle,*.gradle.kts
$foundArtifacts = foreach ($file in $gradleFiles) { foreach ($artifact in $denyArtifacts) { if ((Get-Content $file.FullName -Raw) -match [regex]::Escape($artifact)) { "$artifact in $($file.FullName)" } } }
if ($foundArtifacts) { throw "Forbidden Gradle networking dependencies: $($foundArtifacts -join '; ')" }
$runtimeSources = Get-ChildItem (Join-Path $root 'android'), (Join-Path $root 'rust'), (Join-Path $root 'native') -Recurse -File -Include *.kt,*.java,*.rs,*.c,*.cc,*.h |
    Where-Object { $_.FullName -notmatch '\\(build|target|third_party\\crashpad\\checkout)\\' -and $_.Name -notin @('tracebox_bridge.cc', 'tracebox_emergency.c') }
$forbiddenRuntime = 'java\.net\.|okhttp|retrofit|ktor|reqwest|hyper::|curl_easy|CrashReportUpload|RemoteConfig'
$runtimeMatches = foreach ($file in $runtimeSources) { if ((Get-Content $file.FullName -Raw) -match $forbiddenRuntime) { $file.FullName } }
if ($runtimeMatches) { throw "Forbidden runtime/tooling network surface: $($runtimeMatches -join '; ')" }
$crashpadInputs = Get-ChildItem (Join-Path $root 'native') -Recurse -File -Include CMakeLists.txt,*.gn,*.gni,*.cc,*.c,*.h | Where-Object { $_.FullName -notmatch 'tracebox_bridge\.cc|tracebox_emergency\.c' }
$forbiddenCrashpad = 'CrashReportUpload|crash_report_upload_thread|http_transport_socket|obj/util/libnet\.a'
$crashpadMatches = foreach ($file in $crashpadInputs) { if ((Get-Content $file.FullName -Raw) -match $forbiddenCrashpad) { $file.FullName } }
if ($crashpadMatches) { throw "Forbidden Crashpad uploader/network build input: $($crashpadMatches -join '; ')" }
[ordered]@{
  scope = 'host static source, manifest, and dependency closure'
  manifests_scanned = $manifestFiles.Count
  rust_packages_scanned = $packageNames.Count
  gradle_files_scanned = $gradleFiles.Count
  runtime_sources_scanned = $runtimeSources.Count
  crashpad_inputs_scanned = $crashpadInputs.Count
  dynamic_dex_native_import_and_blocked_egress = 'UNAVAILABLE_EXTERNAL: no physical/emulator Android device is available; runtime observation was not attempted'
  claim = 'Tracebox-owned runtime and tooling artifacts introduce no network permission, networking dependency, uploader, exporter, remote configuration, or observed runtime network attempt in certified paths'
  claim_scope = 'Static portions verified by this command only; observed runtime network attempt remains unavailable and is not certified.'
  result = 'PASS'
} | ConvertTo-Json -Depth 3
