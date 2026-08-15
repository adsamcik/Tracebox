// SPDX-License-Identifier: Apache-2.0

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

val publicationDescription = extra["traceboxPublicationDescription"] as String
val publicationName = extra["traceboxPublicationName"] as String
val githubRepository = providers.gradleProperty("traceboxGitHubRepository")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
    .orNull
val projectUrl = githubRepository?.let { "https://github.com/$it" }
    ?: "https://github.com/adsamcik/Tracebox"
val isolatedCandidateRepository = providers.gradleProperty("traceboxLocalRepository").orNull

if (isolatedCandidateRepository != null) {
    check(!providers.environmentVariable("CI").isPresent) {
        "traceboxLocalRepository is a developer-only candidate-validation seam and is forbidden in CI."
    }
    val target = rootProject.file(isolatedCandidateRepository).canonicalFile
    val globalMavenLocal = file("${System.getProperty("user.home")}/.m2/repository").canonicalFile
    check(target != globalMavenLocal) {
        "traceboxLocalRepository must be isolated from the user's global Maven Local cache."
    }
}

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            register<MavenPublication>("release") {
                from(components.getByName("release"))

                artifactId = project.name
                pom {
                    name.set(publicationName)
                    description.set(publicationDescription)
                    url.set(projectUrl)

                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("tracebox")
                            name.set("Tracebox Contributors")
                        }
                    }
                    scm {
                        url.set(projectUrl)
                        connection.set("scm:git:$projectUrl.git")
                        developerConnection.set(
                            "scm:git:ssh://git@github.com/${githubRepository ?: "adsamcik/Tracebox"}.git",
                        )
                        tag.set("v${project.version}")
                    }
                }
            }
        }

        if (githubRepository != null) {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/${githubRepository.lowercase()}")
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR").orNull
                            ?: providers.gradleProperty("gpr.user").orNull
                        password = providers.environmentVariable("GITHUB_TOKEN").orNull
                            ?: providers.gradleProperty("gpr.key").orNull
                    }
                }
            }
        }

        if (isolatedCandidateRepository != null) {
            repositories {
                maven {
                    name = "IsolatedCandidate"
                    url = rootProject.uri(isolatedCandidateRepository)
                }
            }
        }
    }
}

// A direct GitHub Packages publication must not bypass immutable release metadata validation.
tasks.withType<PublishToMavenRepository>().configureEach {
    if (name.endsWith("ToGitHubPackagesRepository")) {
        dependsOn(rootProject.tasks.named("verifyReleaseMetadata"))
    }
}
