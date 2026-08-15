plugins {
    id("tracebox.android.library")
    `maven-publish`
}

android {
    namespace = "dev.tracebox.core"
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":android:tracebox-api"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox core"
extra["traceboxPublicationDescription"] = "Bounded runtime, policy, and coordinator implementation."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
