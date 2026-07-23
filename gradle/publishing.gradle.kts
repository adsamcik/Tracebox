import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

val publicationName = extra["traceboxPublicationName"] as String
val publicationDescription = extra["traceboxPublicationDescription"] as String
val githubRepository = providers.gradleProperty("traceboxGitHubRepository")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
    .orNull
    ?: "adsamcik/Tracebox"
val projectUrl = "https://github.com/$githubRepository"

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
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        url.set(projectUrl)
                        connection.set("scm:git:$projectUrl.git")
                        developerConnection.set("scm:git:ssh://git@github.com/$githubRepository.git")
                    }
                }
            }
        }
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
