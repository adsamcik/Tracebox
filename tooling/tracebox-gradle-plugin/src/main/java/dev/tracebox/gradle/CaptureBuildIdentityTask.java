package dev.tracebox.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Captures schema and Gradle project provenance as a generated build-identity artifact. */
@CacheableTask
public abstract class CaptureBuildIdentityTask extends DefaultTask {
    /** The authoritative schema captured by this build. */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSchemaFile();

    /** The applying Gradle project's captured path. */
    @Input
    public abstract Property<String> getProjectPath();

    /** Applying application ID, or the Android namespace for a library variant. */
    @Input
    public abstract Property<String> getApplicationId();

    /** Public AGP application output version code; zero for a non-application capture. */
    @Input
    public abstract Property<Integer> getVersionCode();

    /** Public AGP application output version name, or project version for a library. */
    @Input
    public abstract Property<String> getVersionName();

    /** The applying Gradle project's captured name, used as the Phase 1 variant label. */
    @Input
    public abstract Property<String> getVariant();

    /** The applying Gradle project's captured version. */
    @Input
    public abstract Property<String> getProjectVersion();

    /** Captured at configuration time so this cacheable task never reaches back into Project at execution. */
    @Input
    public abstract Property<String> getGradleVersion();

    /** Public AGP minimum SDK for this variant. */
    @Input
    public abstract Property<Integer> getMinSdk();

    /** Public AGP compile SDK for this variant. */
    @Input
    public abstract Property<Integer> getCompileSdk();

    /** Public AGP target SDK for this variant. */
    @Input
    public abstract Property<Integer> getTargetSdk();

    /** Optional R8/ProGuard mapping artifact supplied by a public AGP variant artifact. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getR8MappingFile();

    /** Optional directory or files containing native ELF artifacts from a public AGP variant artifact. */
    @Optional
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getNativeLibraries();

    /** Optional pinned Crashpad source lock. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCrashpadSourceLock();

    /** Ordered patch-set inputs, including the series file and every patch. */
    @Optional
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCrashpadPatchFiles();

    /** Optional pinned Rust dependency closure. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getRustLock();

    /** Optional Gradle dependency verification metadata. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getDependencyVerification();

    /** Applying project's committed dependency lock. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getDependencyLock();

    /** The generated identity/catalog artifact. */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Reusable exact-identity symbol catalog consumed by offline retrace/symbolication. */
    @OutputFile
    public abstract RegularFileProperty getSymbolCatalogFile();

    /** Reads the schema and writes deterministic provenance from the actual applying project. */
    @TaskAction
    public void capture() throws IOException {
        byte[] schema = Files.readAllBytes(getSchemaFile().get().getAsFile().toPath());
        String provenance = getProjectPath().get() + ";version=" + getProjectVersion().get()
                + ";gradle=" + getGradleVersion().get();
        Path mapping = getR8MappingFile().isPresent()
                ? getR8MappingFile().get().getAsFile().toPath()
                : null;
        List<Path> nativeLibraries = getNativeLibraries().getFiles().stream()
                .map(file -> file.toPath())
                .toList();
        BuildIdentity identity = BuildIdentityCapture.capture(
                getProjectPath().get(),
                getApplicationId().get(),
                getVersionCode().get(),
                getVersionName().get(),
                getVariant().get(),
                getMinSdk().get(),
                getCompileSdk().get(),
                getTargetSdk().get(),
                provenance,
                schema,
                mapping,
                nativeLibraries,
                optionalPath(getCrashpadSourceLock()),
                getCrashpadPatchFiles().getFiles().stream().map(file -> file.toPath()).toList(),
                optionalPath(getRustLock()),
                optionalPath(getDependencyVerification()),
                optionalPath(getDependencyLock()));
        Files.createDirectories(getOutputFile().get().getAsFile().toPath().getParent());
        Files.writeString(getOutputFile().get().getAsFile().toPath(), BuildIdentityCapture.toJson(identity),
                StandardCharsets.UTF_8);
        Files.writeString(getSymbolCatalogFile().get().getAsFile().toPath(),
                BuildIdentityCapture.symbolCatalog(identity), StandardCharsets.UTF_8);
    }

    private static Path optionalPath(RegularFileProperty property) {
        return property.isPresent() ? property.get().getAsFile().toPath() : null;
    }
}
