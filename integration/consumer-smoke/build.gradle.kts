// SPDX-License-Identifier: Apache-2.0

val traceboxVersion = providers.gradleProperty("traceboxVersion").orNull
    ?: error("Set traceboxVersion before resolving the published consumer smoke test.")
val githubRepository = providers.gradleProperty("traceboxGitHubRepository").orNull
    ?: error("Set traceboxGitHubRepository to OWNER/REPOSITORY before resolving the smoke test.")

check(githubRepository.matches(Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"""))) {
    "traceboxGitHubRepository must be OWNER/REPOSITORY."
}

repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/${githubRepository.lowercase()}")
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

val metadataConsumer by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val publishedAars by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(metadataConsumer.name, "io.github.tracebox:tracebox:$traceboxVersion")

    add(publishedAars.name, "io.github.tracebox:tracebox:$traceboxVersion@aar") {
        isTransitive = false
    }
    add(publishedAars.name, "io.github.tracebox:tracebox-api:$traceboxVersion@aar") {
        isTransitive = false
    }
}

tasks.register("resolvePublishedArtifacts") {
    group = "verification"
    description = "Resolves the published Tracebox POM metadata and both Android AARs as a clean consumer."

    doLast {
        // Resolving this configuration exercises normal Gradle Maven metadata and transitives.
        val metadataAarNames = metadataConsumer.resolve().map { it.name }.toSet()
        check("tracebox-api-$traceboxVersion.aar" in metadataAarNames) {
            "tracebox did not resolve its tracebox-api dependency: $metadataAarNames"
        }
        val aarNames = publishedAars.resolve().map { it.name }.toSet()
        val expected = setOf(
            "tracebox-$traceboxVersion.aar",
            "tracebox-api-$traceboxVersion.aar",
        )
        check(expected.all(aarNames::contains)) {
            "Expected published AARs $expected, but Gradle resolved $aarNames"
        }
        println("Resolved published Tracebox artifacts: ${expected.sorted().joinToString()}")
    }
}
