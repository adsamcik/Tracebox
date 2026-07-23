plugins {
    id("tracebox.android.library")
    `maven-publish`
}

android {
    namespace = "dev.tracebox.export"
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(project(":android:tracebox-api"))
    implementation(project(":android:tracebox-storage"))
    testImplementation(project(":android:tracebox-core"))
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox export"
extra["traceboxPublicationDescription"] = "Deterministic offline Tracebox package materialization."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
