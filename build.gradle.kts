// SPDX-License-Identifier: Apache-2.0

import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    base
    alias(libs.plugins.android.library) apply false
}

val traceboxGroup = providers.gradleProperty("traceboxGroup").get()
val traceboxVersion = providers.gradleProperty("traceboxVersion").get()

allprojects {
    group = traceboxGroup
    version = traceboxVersion
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.activateDependencyLocking()
    }
}

tasks.named("check") {
    dependsOn(
        ":android:tracebox-api:check",
        ":android:tracebox:check",
        "verifyNoNetworkBoundary",
        "verifyPublishedArtifacts",
    )
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
    description = "Rejects a bounded denylist of networking APIs in Tracebox-owned runtime sources."

    val forbidden = Regex(
        """android\.permission\.INTERNET|java\.net\.|android\.net\.|android\.webkit\.|""" +
            """java\.nio\.channels\.SocketChannel|InetAddress|SSLSocket|HttpURLConnection|""" +
            """URLConnection|OkHttpClient|okhttp3\.|Retrofit|retrofit2\.|DatagramSocket|""" +
            """\bSocket\s*\(|<sys/socket\.h>""",
    )

    doLast {
        val sourceFiles = fileTree("android") {
            include("**/*.kt", "**/*.java", "**/*.c", "**/*.cc", "**/*.cpp", "**/*.h")
        }.files
        val manifestFiles = fileTree("android") {
            include("**/AndroidManifest.xml")
        }.files
        val violations = (sourceFiles + manifestFiles)
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) {
                        "${file.relativeTo(rootDir)}:${index + 1}: $line"
                    } else {
                        null
                    }
                }
            }

        check(violations.isEmpty()) {
            "Tracebox's alpha runtime must not introduce networking.\n${violations.joinToString("\n")}"
        }
    }
}

tasks.register("verifyPublishedArtifacts") {
    group = "verification"
    description = "Checks release AAR manifests for an INTERNET permission string."
    dependsOn(
        ":android:tracebox-api:assembleRelease",
        ":android:tracebox:assembleRelease",
    )

    doLast {
        val aars = fileTree("android") {
            include("**/build/outputs/aar/*-release.aar")
        }.files.sorted()
        check(aars.size == 2) {
            "Expected exactly two alpha AARs, found ${aars.size}: ${aars.joinToString()}"
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
    description = "Validates the immutable metadata required before GitHub Packages publication."

    doLast {
        val versionPattern = Regex("""[0-9]+\.[0-9]+\.[0-9]+-alpha\.[0-9]+""")
        check(versionPattern.matches(traceboxVersion)) {
            "Alpha publication version must be MAJOR.MINOR.PATCH-alpha.N, got $traceboxVersion"
        }
        check(!traceboxVersion.endsWith("-SNAPSHOT")) {
            "GitHub Packages releases must not publish a SNAPSHOT version."
        }

        val repository = providers.gradleProperty("traceboxGitHubRepository")
            .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
            .orNull
        check(repository?.matches(Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""")) == true) {
            "Set traceboxGitHubRepository (or GITHUB_REPOSITORY) to OWNER/REPOSITORY before publishing."
        }

        val releaseTag = providers.environmentVariable("TRACEBOX_RELEASE_TAG").orNull
        if (releaseTag != null) {
            check(releaseTag == "v$traceboxVersion") {
                "Release tag $releaseTag does not match version $traceboxVersion."
            }
        }
    }
}

tasks.register("createReleaseChecksums") {
    group = "release"
    description = "Creates SHA-256 checksums for distributable alpha AARs."
    dependsOn(
        ":android:tracebox-api:assembleRelease",
        ":android:tracebox:assembleRelease",
    )

    val outputFile = layout.buildDirectory.file("release/tracebox-$traceboxVersion-sha256sums.txt")
    outputs.file(outputFile)

    doLast {
        val artifacts = fileTree("android") {
            include("**/build/outputs/aar/*-release.aar")
        }.files.sortedBy { it.relativeTo(rootDir).path }
        check(artifacts.size == 2) { "Expected two alpha AARs before checksumming." }

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
