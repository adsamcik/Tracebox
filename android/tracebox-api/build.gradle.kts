plugins {
    id("tracebox.android.library")
    `maven-publish`
}

android {
    namespace = "dev.tracebox.api"
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox API"
extra["traceboxPublicationDescription"] = "Generated, privacy-classified Tracebox public API."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
