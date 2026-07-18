plugins {
    id("tracebox.android.application")
}

android {
    namespace = "dev.tracebox.phase0.benchmark"
    defaultConfig {
        applicationId = "dev.tracebox.phase0.benchmark"
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation(project(":android:tracebox-anr-exit"))
    implementation(project(":android:tracebox-native"))
}
