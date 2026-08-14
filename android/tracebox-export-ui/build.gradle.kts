plugins {
    id("tracebox.android.library")
    `maven-publish`
}

android {
    namespace = "dev.tracebox.export.ui"
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(project(":android:tracebox-export"))
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":android:tracebox-api"))
    testImplementation(project(":android:tracebox-storage"))
}

extra["traceboxPublicationName"] = "Tracebox export UI"
extra["traceboxPublicationDescription"] = "Tracebox-owned local disclosure, share, and save UI."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
