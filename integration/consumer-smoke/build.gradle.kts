// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.security.MessageDigest

val traceboxVersion = providers.gradleProperty("traceboxVersion").orNull
    ?: error("Set traceboxVersion before resolving the published consumer smoke test.")
val githubRepository = providers.gradleProperty("traceboxGitHubRepository").orNull
val localRepository = providers.gradleProperty("traceboxLocalRepository").orNull
val expectedArtifactRoot = providers.gradleProperty("traceboxExpectedArtifactRoot")
    .map { File(it) }
    .orNull
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

check(githubRepository != null || localRepository != null) {
    "Set traceboxGitHubRepository or traceboxLocalRepository before resolving the smoke test."
}
githubRepository?.let {
    check(it.matches(Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"""))) {
        "traceboxGitHubRepository must be OWNER/REPOSITORY."
    }
}

repositories {
    google()
    mavenCentral()
    localRepository?.let { maven { url = uri(it) } }
    githubRepository?.let { repository ->
        maven {
            url = uri("https://maven.pkg.github.com/${repository.lowercase()}")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
    }
}

val metadataConsumer = configurations.create("metadataConsumer") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val publishedAars = configurations.create("publishedAars") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(metadataConsumer.name, "io.github.tracebox:tracebox:$traceboxVersion")
    publishedModules.forEach { module ->
        add(publishedAars.name, "io.github.tracebox:$module:$traceboxVersion@aar") {
            isTransitive = false
        }
    }
}

tasks.register("resolvePublishedArtifacts") {
    group = "verification"
    description = "Resolves Tracebox metadata and every published Android AAR as a clean consumer."

    doLast {
        val metadataAarNames = metadataConsumer.resolve().map { it.name }.toSet()
        check("tracebox-api-$traceboxVersion.aar" in metadataAarNames) {
            "tracebox did not resolve its tracebox-api dependency: $metadataAarNames"
        }
        val resolvedAars = publishedAars.resolve().associateBy { it.name }
        val aarNames = resolvedAars.keys
        val expected = publishedModules.mapTo(mutableSetOf()) { "$it-$traceboxVersion.aar" }
        check(expected == aarNames) {
            "Expected exactly $expected, but Gradle resolved $aarNames"
        }
        expectedArtifactRoot?.let { checkout ->
            publishedModules.forEach { module ->
                val resolved = checkNotNull(resolvedAars["$module-$traceboxVersion.aar"])
                val built = checkout.resolve(
                    "android/$module/build/outputs/aar/$module-release.aar",
                )
                check(built.isFile) { "Missing locally verified release artifact: $built" }
                check(resolved.sha256().contentEquals(built.sha256())) {
                    "Published $module bytes differ from the locally verified release artifact"
                }
            }
        }
        println("Resolved published Tracebox artifacts: ${expected.sorted().joinToString()}")
    }
}

private fun File.sha256(): ByteArray = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest()
}
