// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.tracebox"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":android:tracebox-api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}

extra["traceboxPublicationName"] = "Tracebox Android"
extra["traceboxPublicationDescription"] =
    "Pre-certification offline Android diagnostics facade for Tracebox."

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
