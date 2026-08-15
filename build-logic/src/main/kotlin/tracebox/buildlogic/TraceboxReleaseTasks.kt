// SPDX-License-Identifier: Apache-2.0

package tracebox.buildlogic

import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Makes typed release tasks available to the root Kotlin build script. */
class TraceboxReleaseSupportPlugin : Plugin<Project> {
    override fun apply(target: Project) = Unit
}

@DisableCachingByDefault(because = "Printing a value has no cacheable output")
abstract class TraceboxPrintVersionTask : DefaultTask() {
    @get:Input
    abstract val publicationVersion: Property<String>

    @TaskAction
    fun printVersion() {
        logger.quiet(publicationVersion.get())
    }
}

@DisableCachingByDefault(because = "Metadata validation has no cacheable output")
abstract class TraceboxVerifyReleaseMetadataTask : DefaultTask() {
    @get:Input
    abstract val publicationVersion: Property<String>

    @get:Input
    abstract val githubRepository: Property<String>

    @get:Input
    abstract val releaseTag: Property<String>

    @TaskAction
    fun verifyMetadata() {
        val version = publicationVersion.get()
        check(Regex("""[0-9]+\.[0-9]+\.[0-9]+-alpha\.[0-9]+""").matches(version)) {
            "Alpha publication version must be MAJOR.MINOR.PATCH-alpha.N, got $version"
        }

        val repository = githubRepository.get()
        check(Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""").matches(repository)) {
            "Set traceboxGitHubRepository (or GITHUB_REPOSITORY) to OWNER/REPOSITORY before publishing."
        }

        val tag = releaseTag.get()
        if (tag.isNotEmpty()) {
            check(tag == "v$version") {
                "Release tag $tag does not match version $version."
            }
        }
    }
}

@CacheableTask
abstract class TraceboxCreateReleaseChecksumsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseAars: ConfigurableFileCollection

    @get:OutputFile
    abstract val checksumFile: RegularFileProperty

    @TaskAction
    fun createChecksums() {
        val artifacts = releaseAars.files.sortedBy { it.name }
        check(artifacts.all { it.isFile }) {
            "All Tracebox release AARs must exist before checksumming."
        }
        val lines = artifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(artifact.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            "$digest  ${artifact.name}"
        }
        checksumFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}

@DisableCachingByDefault(because = "Artifact conformance validation has no cacheable output")
abstract class TraceboxVerifyPublishedArtifactsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseAars: ConfigurableFileCollection

    @TaskAction
    fun verifyArtifacts() {
        val aars = releaseAars.files.sortedBy { it.name }
        val missing = aars.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "Missing release AARs: ${missing.joinToString { it.path }}"
        }
        aars.forEach { aar ->
            ZipFile(aar).use { archive ->
                val manifest = archive.getEntry("AndroidManifest.xml")
                    ?: error("${aar.path} has no AndroidManifest.xml")
                val manifestText = archive.getInputStream(manifest)
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
                check("android.permission.INTERNET" !in manifestText) {
                    "${aar.path} declares android.permission.INTERNET"
                }
            }
        }

        val nativeAar = aars.single { it.name == "tracebox-native-release.aar" }
        val expectedNativeEntries = setOf(
            "jni/armeabi-v7a/libtracebox_crashpad.so",
            "jni/arm64-v8a/libtracebox_crashpad.so",
            "jni/x86/libtracebox_crashpad.so",
            "jni/x86_64/libtracebox_crashpad.so",
        )
        ZipFile(nativeAar).use { archive ->
            val packagedNativeEntries = archive.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("jni/") && it.endsWith(".so") }
                .toSet()
            check(packagedNativeEntries == expectedNativeEntries) {
                "Unexpected native payload in ${nativeAar.path}: " +
                    "expected $expectedNativeEntries, found $packagedNativeEntries"
            }
        }
    }
}
