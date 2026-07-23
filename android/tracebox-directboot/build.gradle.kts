plugins {
    id("tracebox.android.library")
    `maven-publish`
}

android {
    namespace = "dev.tracebox.directboot"
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

extra["traceboxPublicationName"] = "Tracebox Direct Boot"
extra["traceboxPublicationDescription"] = "C0-only Direct Boot policy and storage support."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
