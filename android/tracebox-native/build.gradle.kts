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
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
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

val crashpadPrebuiltLock = file("crashpad-prebuilt-lock.properties")
val crashpadAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val crashpadPrebuilts = crashpadAbis.associateWith { abi ->
    file("src/main/jniLibs/$abi/libtracebox_crashpad.so")
}
val crashpadPrebuiltVerifier = rootProject.file("tools/crashpad/Verify-CrashpadPrebuilt.ps1")

val verifyCrashpadPrebuilt = tasks.register<Exec>("verifyCrashpadPrebuilt") {
    inputs.file(crashpadPrebuiltLock)
    inputs.file(crashpadPrebuiltVerifier)
    inputs.files(crashpadPrebuilts.values)
    val executable = if (System.getProperty("os.name").startsWith("Windows")) {
        "pwsh.exe"
    } else {
        "pwsh"
    }
    commandLine(
        executable,
        "-NoProfile",
        "-File",
        crashpadPrebuiltVerifier,
        "-RepositoryRoot",
        rootProject.projectDir,
        "-LockFile",
        crashpadPrebuiltLock,
    )
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("NativeLibs")) {
        dependsOn(verifyCrashpadPrebuilt)
    }
}
