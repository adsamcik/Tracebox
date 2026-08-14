// SPDX-License-Identifier: Apache-2.0

val traceboxVersion = providers.gradleProperty("traceboxVersion").orNull
    ?: error("Set traceboxVersion before resolving the published consumer smoke test.")
val githubRepository = providers.gradleProperty("traceboxGitHubRepository").orNull
val localRepository = providers.gradleProperty("traceboxLocalRepository").orNull
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
        val aarNames = publishedAars.resolve().map { it.name }.toSet()
        val expected = publishedModules.mapTo(mutableSetOf()) { "$it-$traceboxVersion.aar" }
        check(expected == aarNames) {
            "Expected exactly $expected, but Gradle resolved $aarNames"
        }
        println("Resolved published Tracebox artifacts: ${expected.sorted().joinToString()}")
    }
}
