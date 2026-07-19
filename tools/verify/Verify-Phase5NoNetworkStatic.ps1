$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

# This is a best-effort static denylist, not a formal proof that a dependency cannot
# make a network request. It deliberately checks committed resolved release-runtime
# locks, rather than only source declarations, for packages commonly capable of I/O.
$denyCrates = @('reqwest', 'hyper', 'curl', 'isahc', 'surf', 'ureq', 'trust-dns', 'hickory', 'tokio')
$denyGradleRuntimePatterns = @(
    '(?i)(^|:)(httpclient|httpcore|httpmime)(:|$)',
    '(?i)^com\.squareup\.okhttp3:',
    '(?i)^com\.squareup\.retrofit2:',
    '(?i)^io\.ktor:ktor-client',
    '(?i)^io\.netty:',
    '(?i)^com\.android\.volley:',
    '(?i)^org\.chromium\.net:',
    '(?i)^com\.google\.android\.gms:play-services-cronet:',
    '(?i)^com\.microsoft\.azure:',
    '(?i)^software\.amazon\.awssdk:'
)

$sourceManifestFiles = Get-ChildItem $root -Recurse -File -Filter AndroidManifest.xml |
    Where-Object { $_.FullName -notmatch '\\(build|third_party\\crashpad\\checkout)\\' }
foreach ($file in $sourceManifestFiles) {
    if ((Get-Content $file.FullName -Raw) -match 'android\.permission\.INTERNET') {
        throw "Tracebox-owned INTERNET permission in source manifest: $($file.FullName)"
    }
}

$fixtureTask = ':test-apps:phase0-fixture:processReleaseManifest'
& (Join-Path $root 'gradlew.bat') $fixtureTask '--offline' '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw "$fixtureTask failed with exit code $LASTEXITCODE" }
$mergedManifest = Join-Path $root 'test-apps\phase0-fixture\build\intermediates\merged_manifests\release\processReleaseManifest\AndroidManifest.xml'
if (-not (Test-Path -LiteralPath $mergedManifest -PathType Leaf)) {
    throw "Expected generated release merged manifest was not produced: $mergedManifest"
}
if ((Get-Content $mergedManifest -Raw) -match 'android\.permission\.INTERNET') {
    throw "Tracebox-owned INTERNET permission in generated release merged manifest: $mergedManifest"
}

$lockRoots = @((Join-Path $root 'android'), (Join-Path $root 'test-apps'))
$runtimeLockFiles = Get-ChildItem $lockRoots -Recurse -File -Filter gradle.lockfile
if ($runtimeLockFiles.Count -eq 0) { throw 'No Android runtime gradle.lockfile files found' }
$releaseRuntimeConfiguration = '(?i)(?:^|,)[a-z0-9]*releaseRuntimeClasspath(?:,|$)'
$runtimeLockCoordinates = @()
foreach ($lockFile in $runtimeLockFiles) {
    foreach ($line in Get-Content $lockFile.FullName) {
        if ($line.StartsWith('#') -or $line -eq 'empty=') { continue }
        $parts = $line -split '=', 2
        if ($parts.Count -ne 2 -or $parts[1] -notmatch $releaseRuntimeConfiguration) { continue }
        $runtimeLockCoordinates += [pscustomobject]@{
            lockfile = $lockFile.FullName
            coordinate = $parts[0]
            configurations = $parts[1]
        }
    }
}
$foundRuntimeArtifacts = foreach ($entry in $runtimeLockCoordinates) {
    if ($denyGradleRuntimePatterns | Where-Object { $entry.coordinate -match $_ }) {
        "$($entry.coordinate) in $($entry.lockfile)"
    }
}
if ($foundRuntimeArtifacts) {
    throw "Forbidden known networking package in resolved Android release-runtime dependency closure: $($foundRuntimeArtifacts -join '; ')"
}

# The Gradle plugin is build-time tooling, not Android runtime code. Report, but do not
# conflate, its own JVM runtimeClasspath with what is packaged into a Tracebox APK.
$pluginLock = Join-Path $root 'tooling\tracebox-gradle-plugin\gradle.lockfile'
if (-not (Test-Path -LiteralPath $pluginLock -PathType Leaf)) { throw "Missing Gradle plugin lockfile: $pluginLock" }
$pluginToolingNetworkCoordinates = foreach ($line in Get-Content $pluginLock) {
    if ($line.StartsWith('#')) { continue }
    $parts = $line -split '=', 2
    if ($parts.Count -eq 2 -and $parts[1] -match '(?i)(?:^|,)runtimeClasspath(?:,|$)' -and
        ($denyGradleRuntimePatterns | Where-Object { $parts[0] -match $_ })) {
        $parts[0]
    }
}

$lock = Join-Path $root 'Cargo.lock'
$packageNames = [regex]::Matches((Get-Content $lock -Raw), '(?m)^name = "([^"]+)"$') | ForEach-Object { $_.Groups[1].Value }
$foundCrates = $packageNames | Where-Object { $denyCrates -contains $_ }
if ($foundCrates) { throw "Forbidden Rust networking dependencies: $($foundCrates -join ', ')" }

$gradleFiles = Get-ChildItem (Join-Path $root 'android'), (Join-Path $root 'tooling'), (Join-Path $root 'test-apps') -Recurse -File -Include *.gradle, *.gradle.kts
$foundDeclarations = foreach ($file in $gradleFiles) {
    foreach ($pattern in $denyGradleRuntimePatterns) {
        if ((Get-Content $file.FullName -Raw) -match $pattern) { "$pattern in $($file.FullName)" }
    }
}
if ($foundDeclarations) { throw "Forbidden declared Gradle networking dependency: $($foundDeclarations -join '; ')" }

$runtimeSources = Get-ChildItem (Join-Path $root 'android'), (Join-Path $root 'rust'), (Join-Path $root 'native') -Recurse -File -Include *.kt, *.java, *.rs, *.c, *.cc, *.h |
    Where-Object { $_.FullName -notmatch '\\(build|target|third_party\\crashpad\\checkout)\\' -and $_.Name -notin @('tracebox_bridge.cc', 'tracebox_emergency.c') }
$forbiddenRuntime = 'java\.net\.|okhttp|retrofit|ktor|reqwest|hyper::|curl_easy|CrashReportUpload|RemoteConfig'
$runtimeMatches = foreach ($file in $runtimeSources) {
    if ((Get-Content $file.FullName -Raw) -match $forbiddenRuntime) { $file.FullName }
}
if ($runtimeMatches) { throw "Forbidden runtime/tooling network surface: $($runtimeMatches -join '; ')" }

$crashpadInputs = Get-ChildItem (Join-Path $root 'native') -Recurse -File -Include CMakeLists.txt, *.gn, *.gni, *.cc, *.c, *.h |
    Where-Object { $_.FullName -notmatch 'tracebox_bridge\.cc|tracebox_emergency\.c' }
$forbiddenCrashpad = 'CrashReportUpload|crash_report_upload_thread|http_transport_socket|obj/util/libnet\.a'
$crashpadMatches = foreach ($file in $crashpadInputs) {
    if ((Get-Content $file.FullName -Raw) -match $forbiddenCrashpad) { $file.FullName }
}
if ($crashpadMatches) { throw "Forbidden Crashpad uploader/network build input: $($crashpadMatches -join '; ')" }

[ordered]@{
    scope = 'best-effort host static scan: Android source manifests; generated phase0-fixture release merged manifest; committed resolved Android release-runtime lock closures; Rust lock; declarations; runtime/native source'
    source_manifests_scanned = $sourceManifestFiles.Count
    generated_merged_manifest = $mergedManifest.Substring($root.Length + 1)
    android_release_runtime_lockfiles_scanned = $runtimeLockFiles.Count
    android_release_runtime_coordinates_scanned = $runtimeLockCoordinates.Count
    gradle_plugin_build_time_tooling_network_coordinates = @($pluginToolingNetworkCoordinates)
    gradle_plugin_tooling_scope = 'Reported only: tooling/tracebox-gradle-plugin is JVM Gradle build-time tooling and is not an Android artifact packaged into the representative release APK.'
    rust_packages_scanned = $packageNames.Count
    gradle_declarations_scanned = $gradleFiles.Count
    runtime_sources_scanned = $runtimeSources.Count
    crashpad_inputs_scanned = $crashpadInputs.Count
    dynamic_dex_native_import_and_blocked_egress = 'UNAVAILABLE_EXTERNAL: no physical/emulator Android device is available; runtime observation was not attempted'
    claim = 'Within the checked scope, no INTERNET permission was found in Tracebox-owned source manifests or the generated representative release merged manifest, and no known networking package was found in committed resolved Android release-runtime dependency closures.'
    claim_scope = 'Best-effort static denylist only; it does not formally prove absence of networking behavior. Gradle plugin JVM build-time dependencies are reported separately and are not claimed to be Android runtime dependencies. Device runtime/Dex/native-import/blocked-egress proof remains unavailable and is not certified.'
    result = 'PASS'
} | ConvertTo-Json -Depth 3
