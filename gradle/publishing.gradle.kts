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
    ?: "https://github.com/OWNER/REPOSITORY"

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
                            "scm:git:ssh://git@github.com/${githubRepository ?: "OWNER/REPOSITORY"}.git",
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
    }
}

// A direct GitHub Packages publication must not bypass immutable release metadata validation.
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(rootProject.tasks.named("verifyReleaseMetadata"))
}
