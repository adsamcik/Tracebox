plugins {
    id("tracebox.android.library")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    `maven-publish`
}

android {
    namespace = "dev.tracebox.ui.compose"
    buildFeatures.compose = true
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":android:tracebox"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox Compose diagnostics UI"
extra["traceboxPublicationDescription"] =
    "Optional reusable Compose diagnostics controls and zero-custom-UI activity."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
