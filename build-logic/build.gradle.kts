plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
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
