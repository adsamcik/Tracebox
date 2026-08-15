plugins {
    id("tracebox.android.library")
    `maven-publish`
}

android {
    namespace = "dev.tracebox.anr"
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

extra["traceboxPublicationName"] = "Tracebox ANR and exit"
extra["traceboxPublicationDescription"] = "Bounded ANR watchdog and exit-reconciliation support."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
