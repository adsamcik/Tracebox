plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:9.2.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "tracebox.android.application"
            implementationClass = "tracebox.buildlogic.TraceboxAndroidApplicationPlugin"
        }
        register("androidLibrary") {
            id = "tracebox.android.library"
            implementationClass = "tracebox.buildlogic.TraceboxAndroidLibraryPlugin"
        }
    }
}
