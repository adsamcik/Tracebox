plugins {
    id("tracebox.android.library")
    alias(libs.plugins.kotlin.compose)
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
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox Compose diagnostics UI"
extra["traceboxPublicationDescription"] =
    "Optional reusable Compose diagnostics controls and zero-custom-UI activity."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
