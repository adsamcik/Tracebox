import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("tracebox.android.application")
    id("dev.tracebox.identity")
}

val fixtureRustProbeJniLibs =
    layout.buildDirectory.dir("generated/fixtureRustPanicProbe/jniLibs")
val fixtureRustProbeLibraryName = "libtracebox_fixture_panic_probe.so"
val rustAndroidTargets = listOf(
    Triple("x86_64", "x86_64-linux-android", "X86_64"),
    Triple("arm64-v8a", "aarch64-linux-android", "Aarch64"),
)
val androidSdkRoot = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
val rustAndroidHostTag = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
        "windows-x86_64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
        "darwin-x86_64"
    else -> "linux-x86_64"
}
val rustAndroidCommandSuffix =
    if (rustAndroidHostTag.startsWith("windows")) ".cmd" else ""
val packageFixtureRustProbeTasks = rustAndroidTargets.map { (abi, rustTarget, taskSegment) ->
    val packagedLibrary = fixtureRustProbeJniLibs.map {
        it.file("$abi/$fixtureRustProbeLibraryName")
    }
    val compiledLibrary = rootProject.layout.projectDirectory.file(
        "target/$rustTarget/fixture-panic-probe/$fixtureRustProbeLibraryName",
    )
    val linkerPrefix =
        if (rustTarget == "x86_64-linux-android") {
            "x86_64-linux-android23-clang"
        } else {
            "aarch64-linux-android23-clang"
        }
    val linker = androidSdkRoot.map { sdkRoot ->
        file(
            "$sdkRoot/ndk/28.2.13676358/toolchains/llvm/prebuilt/" +
                "$rustAndroidHostTag/bin/$linkerPrefix$rustAndroidCommandSuffix",
        )
    }
    val compileTask = tasks.register<Exec>("compile${taskSegment}FixtureRustPanicProbe") {
        group = "build"
        description = "Compiles the fixture-only bounded Rust panic JNI probe for $abi."
        workingDir(rootProject.projectDir)
        inputs.file(rootProject.file("Cargo.toml"))
        inputs.file(rootProject.file("Cargo.lock"))
        inputs.files(
            rootProject.fileTree("rust/tracebox-fixture-panic-probe") {
                include("Cargo.toml", "src/**/*.rs")
            },
            rootProject.fileTree("rust/tracebox") {
                include("Cargo.toml", "src/**/*.rs")
            },
            rootProject.fileTree("rust/tracebox-sys") {
                include("Cargo.toml", "src/**/*.rs")
            },
        )
        inputs.file(linker)
        outputs.file(compiledLibrary)
        doFirst {
            val linkerFile = linker.get()
            check(linkerFile.isFile) { "Pinned Android Rust linker is missing: $linkerFile" }
            environment(
                "CARGO_TARGET_${rustTarget.uppercase().replace('-', '_')}_LINKER",
                linkerFile.absolutePath,
            )
        }
        commandLine(
            "cargo",
            "build",
            "-p",
            "tracebox-fixture-panic-probe",
            "--profile",
            "fixture-panic-probe",
            "--target",
            rustTarget,
            "--locked",
            "--offline",
        )
    }
    tasks.register<Sync>("package${taskSegment}FixtureRustPanicProbe") {
        group = "build"
        description = "Stages the $abi fixture Rust panic probe for APK packaging."
        dependsOn(compileTask)
        from(compiledLibrary)
        into(packagedLibrary.map { it.asFile.parentFile })
    }
}
val buildFixtureRustPanicProbe = tasks.register("buildFixtureRustPanicProbe") {
    group = "build"
    description = "Builds every supported ABI of the fixture-only Rust panic probe."
    dependsOn(packageFixtureRustProbeTasks)
}

android {
    namespace = "dev.tracebox.phase0"
    buildFeatures {
        buildConfig = true
    }
    sourceSets.getByName("main").jniLibs.directories.add(
        fixtureRustProbeJniLibs.get().asFile.absolutePath,
    )

    defaultConfig {
        applicationId = "dev.tracebox.phase0"
        versionCode = 1
        versionName = "0.1"
    }
    flavorDimensions += "networkMode"
    productFlavors {
        create("noInternet") {
            dimension = "networkMode"
        }
        create("hostNetwork") {
            dimension = "networkMode"
            applicationIdSuffix = ".hostnetwork"
            versionNameSuffix = "-host-network-control"
        }
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
    implementation(project(":android:tracebox"))
    implementation(project(":android:tracebox-anr-exit"))
    implementation(project(":android:tracebox-native"))
    testImplementation(kotlin("test-junit"))
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(buildFixtureRustPanicProbe)
    }
}

val productionTraceboxModules = listOf(
    ":android:tracebox-api",
    ":android:tracebox-core",
    ":android:tracebox-directboot",
    ":android:tracebox-anr-exit",
    ":android:tracebox-storage",
    ":android:tracebox-native",
    ":android:tracebox-export",
    ":android:tracebox-export-ui",
    ":android:tracebox",
)
val verifyFixtureRustPanicProbeIsolation = tasks.register(
    "verifyFixtureRustPanicProbeIsolation",
) {
    group = "verification"
    description = "Proves fixture panic injection is absent from every production Tracebox AAR."
    dependsOn(productionTraceboxModules.map { "$it:bundleReleaseAar" })
    inputs.files(
        productionTraceboxModules.map { modulePath ->
            project(modulePath).fileTree(
                project(modulePath).layout.buildDirectory.dir("outputs/aar"),
            ) {
                include("*.aar")
            }
        },
    )
    doLast {
        val forbidden = listOf(
            "tracebox_fixture_panic_probe",
            "LabRustPanicProbe",
            "nativeRunBoundedPanicProbe",
            "fixture-only bounded Rust panic probe",
        )
        val normalizedForbidden = forbidden.map(String::lowercase)
        val scanner = object {
            fun scanArchive(label: String, archiveBytes: ByteArray, depth: Int): String? {
                check(depth <= 4) { "Production artifact archive nesting exceeds four: $label" }
                var entries = 0
                ZipInputStream(ByteArrayInputStream(archiveBytes)).use { archive ->
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        entries += 1
                        check(entries <= 20_000) {
                            "Production artifact has too many archive entries: $label"
                        }
                        val normalizedName = entry.name
                            .replace('\\', '/')
                            .split('/')
                            .filterNot { it.isEmpty() || it == "." }
                            .joinToString("/")
                            .lowercase()
                        normalizedForbidden.firstOrNull(normalizedName::contains)?.let { token ->
                            return "Fixture token '$token' leaked in entry name $label!/$normalizedName"
                        }

                        val content = readBounded(archive, "$label!/$normalizedName")
                        val decoded = content.toString(Charsets.ISO_8859_1).lowercase()
                        normalizedForbidden.firstOrNull(decoded::contains)?.let { token ->
                            return "Fixture token '$token' leaked in entry bytes $label!/$normalizedName"
                        }
                        if (isNestedArchive(normalizedName, content)) {
                            scanArchive("$label!/$normalizedName", content, depth + 1)?.let {
                                return it
                            }
                        }
                        archive.closeEntry()
                    }
                }
                check(entries > 0) { "Production artifact archive is empty or invalid: $label" }
                return null
            }

            private fun readBounded(input: InputStream, label: String): ByteArray {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 64 * 1024 * 1024) {
                        "Production artifact entry exceeds 64 MiB: $label"
                    }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }

            private fun isNestedArchive(normalizedName: String, content: ByteArray): Boolean =
                normalizedName.endsWith(".jar") ||
                    normalizedName.endsWith(".zip") ||
                    normalizedName.endsWith(".aar") ||
                    (
                        content.size >= 4 &&
                            content[0] == 0x50.toByte() &&
                            content[1] == 0x4b.toByte() &&
                            content[2] in setOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte())
                    )
        }
        fun archiveOf(vararg entries: Pair<String, ByteArray>): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { archive ->
                entries.forEach { (name, content) ->
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(content)
                    archive.closeEntry()
                }
            }
            return bytes.toByteArray()
        }

        // Focused negative controls prove recursive uncompressed name and content inspection.
        val negativeNestedName = archiveOf(
            "classes.jar" to archiveOf(
                "dev/tracebox/phase0/LabRustPanicProbe.class" to byteArrayOf(0),
            ),
        )
        check(scanner.scanArchive("negative-nested-name.aar", negativeNestedName, 0) != null)
        val negativeNestedContent = archiveOf(
            "classes.jar" to archiveOf(
                "dev/tracebox/Safe.class" to
                    "nativeRunBoundedPanicProbe".toByteArray(Charsets.UTF_8),
            ),
        )
        check(scanner.scanArchive("negative-nested-content.aar", negativeNestedContent, 0) != null)
        val negativeJniName = archiveOf(
            "jni/x86_64/libtracebox_fixture_panic_probe.so" to byteArrayOf(0),
        )
        check(scanner.scanArchive("negative-jni-name.aar", negativeJniName, 0) != null)

        val aars = inputs.files.files.filter { it.extension == "aar" }
        check(aars.size >= 9) { "Expected every production Tracebox AAR, found $aars" }
        aars.forEach { aar ->
            check(aar.length() <= 128L * 1024L * 1024L) {
                "Production AAR exceeds the 128 MiB isolation-scan bound: $aar"
            }
            val leak = scanner.scanArchive(aar.path, aar.readBytes(), 0)
            check(leak == null) { leak.orEmpty() }
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyFixtureRustPanicProbeIsolation)
}
