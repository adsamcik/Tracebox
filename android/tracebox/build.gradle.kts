plugins {
    id("tracebox.android.library")
    `maven-publish`
}

android {
    namespace = "dev.tracebox"
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":android:tracebox-api"))
    implementation(project(":android:tracebox-core"))
    implementation(project(":android:tracebox-storage"))
    implementation(project(":android:tracebox-directboot"))
    implementation(project(":android:tracebox-anr-exit"))
    implementation(project(":android:tracebox-native"))
    implementation(project(":android:tracebox-export"))
    implementation(project(":android:tracebox-export-ui"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox Android"
extra["traceboxPublicationDescription"] = "Offline, user-controlled Android diagnostics runtime."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
