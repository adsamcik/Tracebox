plugins {
    id("tracebox.android.application")
}

android {
    namespace = "dev.tracebox.phase0"

    defaultConfig {
        applicationId = "dev.tracebox.phase0"
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("qualificationRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("debuggableRelease") {
            initWith(getByName("release"))
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}

dependencies {
    implementation(project(":android:tracebox-anr-exit"))
    implementation(project(":android:tracebox-native"))
}
