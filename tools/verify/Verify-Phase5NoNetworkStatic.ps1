param(
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gradle = Join-Path $root 'gradlew.bat'

# This is a bounded release-artifact and dependency denylist. The dynamic claim is
# intentionally made only by Invoke-PersonalReleaseEmulator.ps1, where packet
# counters can distinguish the no-INTERNET application from the positive control.
$denyCrates = @(
    'reqwest',
    'hyper',
    'curl',
    'isahc',
    'surf',
    'ureq',
    'trust-dns',
    'hickory',
    'tokio'
)
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

$hostControlManifest = (
    Resolve-Path (
        Join-Path $root 'test-apps\phase0-fixture\src\hostNetwork\AndroidManifest.xml'
    )
).Path
$sourceManifestFiles = Get-ChildItem $root -Recurse -File -Filter AndroidManifest.xml |
    Where-Object { $_.FullName -notmatch '\\(build|third_party\\crashpad\\checkout)\\' }
foreach ($file in $sourceManifestFiles) {
    $hasInternet = (Get-Content $file.FullName -Raw) -match 'android\.permission\.INTERNET'
    if ($file.FullName.Equals($hostControlManifest, [StringComparison]::OrdinalIgnoreCase)) {
        if (-not $hasInternet) {
            throw "The isolated host-network positive-control manifest must declare INTERNET: $($file.FullName)"
        }
    } elseif ($hasInternet) {
        throw "INTERNET permission outside the isolated host-network control: $($file.FullName)"
    }
}

$androidModules = @(
    'tracebox-anr-exit',
    'tracebox-native',
    'tracebox-api',
    'tracebox-core',
    'tracebox-storage',
    'tracebox-export',
    'tracebox-export-ui',
    'tracebox-ui-compose',
    'tracebox-directboot',
    'tracebox'
)
$buildTasks = @(
    ':test-apps:phase0-fixture:processNoInternetReleaseManifest',
    ':test-apps:phase0-fixture:processHostNetworkReleaseManifest',
    ':test-apps:phase0-fixture:assembleNoInternetRelease',
    ':test-apps:phase0-fixture:assembleHostNetworkRelease'
) + ($androidModules | ForEach-Object { ":android:${_}:assembleRelease" })
if (-not $SkipBuild) {
    & $gradle @buildTasks '--offline' '--no-daemon'
    if ($LASTEXITCODE -ne 0) {
        throw "Release artifact build failed with exit code $LASTEXITCODE"
    }
}

$mergedRoot = Join-Path $root 'test-apps\phase0-fixture\build\intermediates\merged_manifests'
function Get-OneMergedManifest {
    param([string] $Variant)
    $matches = @(
        Get-ChildItem $mergedRoot -Recurse -File -Filter AndroidManifest.xml |
            Where-Object { $_.FullName -match [regex]::Escape($Variant) }
    )
    if ($matches.Count -ne 1) {
        throw "Expected one $Variant merged manifest, found $($matches.Count)"
    }
    return $matches[0]
}

$noInternetMerged = Get-OneMergedManifest 'noInternetRelease'
$hostNetworkMerged = Get-OneMergedManifest 'hostNetworkRelease'
if ((Get-Content $noInternetMerged.FullName -Raw) -match 'android\.permission\.INTERNET') {
    throw "No-INTERNET release merged manifest declares INTERNET: $($noInternetMerged.FullName)"
}
if ((Get-Content $hostNetworkMerged.FullName -Raw) -notmatch 'android\.permission\.INTERNET') {
    throw "Host-network positive-control merged manifest lacks INTERNET: $($hostNetworkMerged.FullName)"
}

$apkOutput = Join-Path $root 'test-apps\phase0-fixture\build\outputs\apk'
$noInternetApks = @(
    Get-ChildItem $apkOutput -Recurse -File -Filter '*.apk' |
        Where-Object {
            $_.FullName -match '\\noInternet\\release\\' -and
            $_.Name -notmatch 'androidTest'
        }
)
if ($noInternetApks.Count -ne 1) {
    throw "Expected one no-INTERNET release APK, found $($noInternetApks.Count)"
}

$releaseAars = @(
    Get-ChildItem (Join-Path $root 'android') -Recurse -File -Filter '*-release.aar' |
        Where-Object { $_.FullName -match '\\build\\outputs\\aar\\' }
)
if ($releaseAars.Count -ne $androidModules.Count) {
    throw "Expected $($androidModules.Count) Android release AARs, found $($releaseAars.Count)"
}

$traceboxReleaseAars = @(
    $releaseAars |
        Where-Object { $_.Name -ceq 'tracebox-release.aar' }
)
if ($traceboxReleaseAars.Count -ne 1) {
    throw "Expected one public tracebox-release.aar, found $($traceboxReleaseAars.Count)"
}
$noticeVerifier = Join-Path $PSScriptRoot 'Verify-CrashpadThirdPartyNotices.ps1'
$noticeJson = & $noticeVerifier -Aar $traceboxReleaseAars[0].FullName
$noticeScan = $noticeJson | ConvertFrom-Json
if ($noticeScan.result -ne 'PASS') {
    throw 'Crashpad third-party notice verifier did not report PASS'
}

$artifactScanner = Join-Path $PSScriptRoot 'Test-TraceboxReleaseArtifacts.ps1'
$artifactScanJson = & $artifactScanner `
    -Apk $noInternetApks[0].FullName `
    -Aar @($releaseAars.FullName)
if ($LASTEXITCODE -ne 0) {
    throw "Release artifact scanner failed with exit code $LASTEXITCODE"
}
$artifactScan = $artifactScanJson | ConvertFrom-Json
if ($artifactScan.result -ne 'PASS') {
    throw 'Release artifact scanner did not report PASS'
}

$lockRoots = @((Join-Path $root 'android'), (Join-Path $root 'test-apps'))
$runtimeLockFiles = @(Get-ChildItem $lockRoots -Recurse -File -Filter gradle.lockfile)
if ($runtimeLockFiles.Count -eq 0) {
    throw 'No Android runtime gradle.lockfile files found'
}
$releaseRuntimeConfiguration = '(?i)(?:^|,)[a-z0-9]*releaseRuntimeClasspath(?:,|$)'
$runtimeLockCoordinates = @()
foreach ($lockFile in $runtimeLockFiles) {
    foreach ($line in Get-Content $lockFile.FullName) {
        if ($line.StartsWith('#') -or $line -eq 'empty=') { continue }
        $parts = $line -split '=', 2
        if ($parts.Count -ne 2 -or $parts[1] -notmatch $releaseRuntimeConfiguration) {
            continue
        }
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
    throw "Forbidden networking package in Android release-runtime closure: $($foundRuntimeArtifacts -join '; ')"
}

# The Gradle plugin is JVM build-time tooling, not Android runtime code. Its closure
# is reported separately so it cannot be confused with what ships in the APK/AARs.
$pluginLock = Join-Path $root 'tooling\tracebox-gradle-plugin\gradle.lockfile'
if (-not (Test-Path -LiteralPath $pluginLock -PathType Leaf)) {
    throw "Missing Gradle plugin lockfile: $pluginLock"
}
$pluginToolingNetworkCoordinates = foreach ($line in Get-Content $pluginLock) {
    if ($line.StartsWith('#')) { continue }
    $parts = $line -split '=', 2
    if (
        $parts.Count -eq 2 -and
        $parts[1] -match '(?i)(?:^|,)runtimeClasspath(?:,|$)' -and
        ($denyGradleRuntimePatterns | Where-Object { $parts[0] -match $_ })
    ) {
        $parts[0]
    }
}

$cargoLock = Join-Path $root 'Cargo.lock'
$packageNames = [regex]::Matches(
    (Get-Content $cargoLock -Raw),
    '(?m)^name = "([^"]+)"$'
) | ForEach-Object { $_.Groups[1].Value }
$foundCrates = @($packageNames | Where-Object { $denyCrates -contains $_ })
if ($foundCrates) {
    throw "Forbidden Rust networking dependencies: $($foundCrates -join ', ')"
}

$gradleFiles = @(
    Get-ChildItem `
        (Join-Path $root 'android'), `
        (Join-Path $root 'tooling'), `
        (Join-Path $root 'test-apps') `
        -Recurse -File -Include *.gradle, *.gradle.kts
)
$foundDeclarations = foreach ($file in $gradleFiles) {
    foreach ($pattern in $denyGradleRuntimePatterns) {
        if ((Get-Content $file.FullName -Raw) -match $pattern) {
            "$pattern in $($file.FullName)"
        }
    }
}
if ($foundDeclarations) {
    throw "Forbidden declared Gradle networking dependency: $($foundDeclarations -join '; ')"
}

$runtimeSources = @(
    Get-ChildItem `
        (Join-Path $root 'android'), `
        (Join-Path $root 'rust'), `
        (Join-Path $root 'native') `
        -Recurse -File -Include *.kt, *.java, *.rs, *.c, *.cc, *.h |
        Where-Object {
            $_.FullName -notmatch '\\(build|target|third_party\\crashpad\\checkout)\\' -and
            $_.Name -notin @('tracebox_bridge.cc', 'tracebox_emergency.c')
        }
)
$forbiddenRuntime = 'java\.net\.|okhttp|retrofit|ktor|reqwest|hyper::|curl_easy|CrashReportUpload|RemoteConfig'
$runtimeMatches = foreach ($file in $runtimeSources) {
    if ((Get-Content $file.FullName -Raw) -match $forbiddenRuntime) {
        $file.FullName
    }
}
if ($runtimeMatches) {
    throw "Forbidden runtime networking source surface: $($runtimeMatches -join '; ')"
}

$crashpadInputs = @(
    Get-ChildItem `
        (Join-Path $root 'native') `
        -Recurse -File -Include CMakeLists.txt, *.gn, *.gni, *.cc, *.c, *.h |
        Where-Object { $_.FullName -notmatch 'tracebox_bridge\.cc|tracebox_emergency\.c' }
)
$forbiddenCrashpad = 'CrashReportUpload|crash_report_upload_thread|http_transport_socket|obj/util/libnet\.a'
$crashpadMatches = foreach ($file in $crashpadInputs) {
    if ((Get-Content $file.FullName -Raw) -match $forbiddenCrashpad) {
        $file.FullName
    }
}
if ($crashpadMatches) {
    throw "Forbidden Crashpad uploader/network build input: $($crashpadMatches -join '; ')"
}

[ordered]@{
    schema = 'tracebox-no-network-static-v2'
    source_manifests_scanned = $sourceManifestFiles.Count
    no_internet_merged_manifest = $noInternetMerged.FullName.Substring($root.Length + 1)
    host_control_merged_manifest = $hostNetworkMerged.FullName.Substring($root.Length + 1)
    no_internet_release_apk = $noInternetApks[0].FullName.Substring($root.Length + 1)
    release_aars_scanned = $releaseAars.Count
    dex_entries_scanned = $artifactScan.dex_entries
    dex_type_descriptors_scanned = $artifactScan.dex_type_descriptors
    elf_entries_scanned = $artifactScan.native_entries
    crashpad_notice_sections_verified = $noticeScan.sections_verified
    crashpad_notice_aar_entry = $noticeScan.aar_entry
    android_release_runtime_lockfiles_scanned = $runtimeLockFiles.Count
    android_release_runtime_coordinates_scanned = $runtimeLockCoordinates.Count
    gradle_plugin_build_time_tooling_network_coordinates = @($pluginToolingNetworkCoordinates)
    rust_packages_scanned = $packageNames.Count
    gradle_declarations_scanned = $gradleFiles.Count
    runtime_sources_scanned = $runtimeSources.Count
    crashpad_inputs_scanned = $crashpadInputs.Count
    dynamic_blocked_egress = 'SEPARATE_EMULATOR_GATE'
    claim = 'No INTERNET capability or known networking surface was found in the no-INTERNET release variant or production Android artifacts. The isolated host-network variant provides the positive control.'
    claim_scope = 'Host-static source, merged-manifest, dependency-lock, structural DEX, ELF import, and production-AAR scans. Runtime packet observation is intentionally a separate emulator result.'
    result = 'PASS'
} | ConvertTo-Json -Depth 4
