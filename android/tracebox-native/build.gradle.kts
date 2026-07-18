plugins {
    id("tracebox.android.library")
}

android {
    namespace = "dev.tracebox.nativecapture"

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}

val verifyCrashpadPrebuilt by tasks.registering {
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
