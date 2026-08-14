plugins {
    id("tracebox.android.library")
    `maven-publish`
}

android {
    namespace = "dev.tracebox.nativecapture"
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}

dependencies {
    implementation(project(":android:tracebox-core"))
    implementation(project(":android:tracebox-storage"))
    testImplementation(kotlin("test-junit"))
}

extra["traceboxPublicationName"] = "Tracebox native capture"
extra["traceboxPublicationDescription"] = "Capture-only Crashpad and emergency-native runtime."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

val verifyCrashpadPrebuilt = tasks.register("verifyCrashpadPrebuilt") {
    val required = listOf(
        file("src/main/jniLibs/arm64-v8a/libtracebox_crashpad.so"),
        file("src/main/jniLibs/x86_64/libtracebox_crashpad.so"),
    )
    inputs.files(required)
    doLast {
        required.forEach {
            check(it.isFile) {
                "Missing ${it.path}; run tools\\crashpad\\Build-Crashpad.ps1"
            }
        }
    }
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(verifyCrashpadPrebuilt)
    }
}
