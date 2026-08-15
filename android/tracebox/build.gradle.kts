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
    // Native capture is an explicit host dependency. The managed artifact compiles against its
    // bridge but does not publish or package native binaries for applications that do not opt in.
    compileOnly(project(":android:tracebox-native"))
    implementation(project(":android:tracebox-export"))
    implementation(project(":android:tracebox-export-ui"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":android:tracebox-native"))
}

extra["traceboxPublicationName"] = "Tracebox Android"
extra["traceboxPublicationDescription"] = "Offline, user-controlled Android diagnostics runtime."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
