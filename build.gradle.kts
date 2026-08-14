// SPDX-License-Identifier: Apache-2.0

import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    base
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

tasks.register("printVersion") {
    group = "release"
    description = "Prints the version resolved for this build."
    doLast {
        println(traceboxVersion)
    }
}

tasks.register("verifyNoNetworkBoundary") {
    group = "verification"
    description = "Rejects networking permissions and client APIs in Tracebox-owned runtime sources."

    val forbidden = Regex(
        """android\.permission\.INTERNET|java\.net\.(Socket|ServerSocket|DatagramSocket|HttpURLConnection)|""" +
            """android\.net\.(ConnectivityManager|NetworkCapabilities|NetworkRequest)|""" +
            """android\.webkit\.|java\.nio\.channels\.SocketChannel|InetAddress|SSLSocket|""" +
            """OkHttpClient|okhttp3\.|Retrofit|retrofit2\.|\bSocket\s*\(|<sys/socket\.h>""",
    )

    doLast {
        val ownedFiles = fileTree("android") {
            include("**/*.kt", "**/*.java", "**/*.c", "**/*.cc", "**/*.cpp", "**/*.h")
            exclude("**/src/test/**", "**/src/androidTest/**")
        }.files + fileTree("android") {
            include("**/src/main/AndroidManifest.xml")
        }.files
        val violations = ownedFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line)) {
                    "${file.relativeTo(rootDir)}:${index + 1}: $line"
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

tasks.register("verifyPublishedArtifacts") {
    group = "verification"
    description = "Checks every release AAR manifest for an INTERNET permission string."
    dependsOn(publishedModules.map { ":android:$it:assembleRelease" })

    doLast {
        val aars = publishedModules.map { module ->
            file("android/$module/build/outputs/aar/$module-release.aar")
        }
        val missing = aars.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "Missing release AARs: ${missing.joinToString { it.relativeTo(rootDir).path }}"
        }
        aars.forEach { aar ->
            ZipFile(aar).use { archive ->
                val manifest = archive.getEntry("AndroidManifest.xml")
                    ?: error("${aar.relativeTo(rootDir)} has no AndroidManifest.xml")
                val manifestBytes = archive.getInputStream(manifest).readBytes()
                val manifestText = manifestBytes.toString(Charsets.ISO_8859_1)
                check("android.permission.INTERNET" !in manifestText) {
                    "${aar.relativeTo(rootDir)} declares android.permission.INTERNET"
                }
            }
        }
    }
}

tasks.register("verifyReleaseMetadata") {
    group = "release"
    description = "Validates immutable metadata required before package publication."

    doLast {
        val versionPattern = Regex("""[0-9]+\.[0-9]+\.[0-9]+-alpha\.[0-9]+""")
        check(versionPattern.matches(traceboxVersion)) {
            "Alpha publication version must be MAJOR.MINOR.PATCH-alpha.N, got $traceboxVersion"
        }

        val repository = providers.gradleProperty("traceboxGitHubRepository")
            .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
            .orNull
        check(repository?.matches(Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""")) == true) {
            "Set traceboxGitHubRepository (or GITHUB_REPOSITORY) to OWNER/REPOSITORY before publishing."
        }

        providers.environmentVariable("TRACEBOX_RELEASE_TAG").orNull?.let { releaseTag ->
            check(releaseTag == "v$traceboxVersion") {
                "Release tag $releaseTag does not match version $traceboxVersion."
            }
        }
    }
}

tasks.register("createReleaseChecksums") {
    group = "release"
    description = "Creates SHA-256 checksums for all distributable Tracebox AARs."
    dependsOn(publishedModules.map { ":android:$it:assembleRelease" })

    val outputFile = layout.buildDirectory.file("release/tracebox-$traceboxVersion-sha256sums.txt")
    outputs.file(outputFile)

    doLast {
        val artifacts = publishedModules.map { module ->
            file("android/$module/build/outputs/aar/$module-release.aar")
        }
        check(artifacts.all { it.isFile }) { "All Tracebox release AARs must exist before checksumming." }

        val lines = artifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(artifact.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            "$digest  ${artifact.name}"
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
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
