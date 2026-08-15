plugins {
    id("tracebox.android.library")
    `maven-publish`
}

android {
    namespace = "dev.tracebox.storage"
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(project(":android:tracebox-core"))
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox storage"
extra["traceboxPublicationDescription"] = "Bounded Tracebox segment, capture, and quota storage."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
