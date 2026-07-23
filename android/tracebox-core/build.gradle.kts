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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox core"
extra["traceboxPublicationDescription"] = "Bounded runtime, policy, and coordinator implementation."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
