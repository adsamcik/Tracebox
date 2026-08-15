plugins {
    id("tracebox.android.library")
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "dev.tracebox.ui.compose"
    buildFeatures.compose = true
    testOptions.unitTests.isIncludeAndroidResources = true
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
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.robolectric)
    debugImplementation(libs.compose.ui.test.manifest)
}

extra["traceboxPublicationName"] = "Tracebox Compose diagnostics UI"
extra["traceboxPublicationDescription"] =
    "Optional reusable Compose diagnostics controls and zero-custom-UI activity."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
