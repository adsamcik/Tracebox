// SPDX-License-Identifier: Apache-2.0

import tracebox.buildlogic.TraceboxCreateReleaseChecksumsTask
import tracebox.buildlogic.TraceboxPrintVersionTask
import tracebox.buildlogic.TraceboxVerifyPublishedArtifactsTask
import tracebox.buildlogic.TraceboxVerifyReleaseMetadataTask

plugins {
    base
    id("tracebox.release-support")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val traceboxGroup = providers.gradleProperty("traceboxGroup").get()
val traceboxVersion = providers.gradleProperty("traceboxVersion").get()
val publishedModules = listOf(
    "tracebox-api",
    "tracebox-core",
    "tracebox-storage",
    "tracebox-directboot",
    "tracebox-anr-exit",
    "tracebox-native",
    "tracebox-export",
    "tracebox-export-ui",
    "tracebox-ui-compose",
    "tracebox",
)
val releaseAarFiles = publishedModules.map { module ->
    layout.projectDirectory.file("android/$module/build/outputs/aar/$module-release.aar")
}

allprojects {
    group = traceboxGroup
    version = traceboxVersion
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("publishFoundation") {
    group = "publishing"
    description = "Publishes every Tracebox runtime artifact to the configured immutable Maven repository."
    dependsOn(publishedModules.map { ":android:$it:publish" })
}

tasks.register("phase0Check") {
    group = "verification"
    description = "Builds and tests every host-side Phase 0 component."
    dependsOn(
        ":android:tracebox-anr-exit:testDebugUnitTest",
        ":android:tracebox-native:testDebugUnitTest",
        ":test-apps:phase0-fixture:assembleDebug",
        ":test-apps:phase0-fixture:assembleRelease",
        ":test-apps:phase0-fixture:assembleQualificationRelease",
        ":test-apps:phase0-fixture:assembleDebuggableRelease",
        ":benchmarks:phase0-benchmark:assembleDebug",
        ":benchmarks:phase0-benchmark:assembleRelease",
    )
}

tasks.register("phase1Check") {
    group = "verification"
    description = "Runs the Phase 1 Kotlin API and build identity contract tests."
    dependsOn(
        ":android:tracebox-api:testDebugUnitTest",
        ":test-apps:phase0-fixture:captureTraceboxBuildIdentityNoInternetRelease",
        ":test-apps:phase0-fixture:captureTraceboxBuildIdentityHostNetworkRelease",
    )
}

tasks.register("phase2Check") {
    group = "verification"
    description = "Runs Phase 2 runtime and persistence fault-injection tests."
    dependsOn(
        ":android:tracebox-core:testDebugUnitTest",
        ":android:tracebox-storage:testDebugUnitTest",
        ":android:tracebox-directboot:testDebugUnitTest",
    )
}

tasks.register("phase4CoreCheck") {
    group = "verification"
    description = "Runs deterministic Phase 4 snapshot, manifest, and ZIP tests."
    dependsOn(":android:tracebox-export:testDebugUnitTest")
}

tasks.register<TraceboxPrintVersionTask>("printVersion") {
    group = "release"
    description = "Prints the version resolved for this build."
    publicationVersion.set(traceboxVersion)
}

val noNetworkBoundaryFiles = files(
    publishedModules.map { module ->
        fileTree("android/$module/src/main") {
            include(
                "**/*.kt",
                "**/*.java",
                "**/*.c",
                "**/*.cc",
                "**/*.cpp",
                "**/*.h",
                "AndroidManifest.xml",
            )
        }
    },
)

tasks.register("verifyNoNetworkBoundary") {
    group = "verification"
    description = "Rejects networking permissions and client APIs in Tracebox-owned runtime sources."
    inputs.files(noNetworkBoundaryFiles)
        .withPropertyName("ownedRuntimeSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val forbidden = Regex(
            """android\.permission\.INTERNET|java\.net\.(Socket|ServerSocket|DatagramSocket|HttpURLConnection)|""" +
                """android\.net\.(ConnectivityManager|NetworkCapabilities|NetworkRequest)|""" +
                """android\.webkit\.|java\.nio\.channels\.SocketChannel|InetAddress|SSLSocket|""" +
                """OkHttpClient|okhttp3\.|Retrofit|retrofit2\.|\bSocket\s*\(|<sys/socket\.h>""",
        )
        val violations = inputs.files.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line)) {
                    "${file.path}:${index + 1}: $line"
                } else {
                    null
                }
            }
        }

        check(violations.isEmpty()) {
            "Tracebox-owned runtime code must not introduce networking.\n${violations.joinToString("\n")}"
        }
    }
}

tasks.register<TraceboxVerifyPublishedArtifactsTask>("verifyPublishedArtifacts") {
    group = "verification"
    description = "Checks every release AAR manifest for an INTERNET permission string."
    dependsOn(publishedModules.map { ":android:$it:assembleRelease" })
    releaseAars.from(releaseAarFiles)
}

tasks.register<TraceboxVerifyReleaseMetadataTask>("verifyReleaseMetadata") {
    group = "release"
    description = "Validates immutable metadata required before package publication."
    publicationVersion.set(traceboxVersion)
    githubRepository.set(
        providers.gradleProperty("traceboxGitHubRepository")
            .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
            .orElse(""),
    )
    releaseTag.set(providers.environmentVariable("TRACEBOX_RELEASE_TAG").orElse(""))
}

tasks.register<TraceboxCreateReleaseChecksumsTask>("createReleaseChecksums") {
    group = "release"
    description = "Creates SHA-256 checksums for all distributable Tracebox AARs."
    dependsOn(publishedModules.map { ":android:$it:assembleRelease" })
    releaseAars.from(releaseAarFiles)
    checksumFile.set(layout.buildDirectory.file("release/tracebox-$traceboxVersion-sha256sums.txt"))
}

tasks.named("check") {
    dependsOn(
        "phase0Check",
        "phase1Check",
        "phase2Check",
        "phase4CoreCheck",
        "verifyNoNetworkBoundary",
        ":android:tracebox-export-ui:testDebugUnitTest",
        ":android:tracebox-ui-compose:testDebugUnitTest",
        ":android:tracebox:testDebugUnitTest",
    )
}
