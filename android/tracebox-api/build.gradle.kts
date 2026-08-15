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
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox API"
extra["traceboxPublicationDescription"] = "Generated, privacy-classified Tracebox public API."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
