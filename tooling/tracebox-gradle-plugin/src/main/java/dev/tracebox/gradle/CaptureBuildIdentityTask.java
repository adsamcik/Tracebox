package dev.tracebox.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Captures schema and Gradle project provenance as a generated build-identity artifact. */
public abstract class CaptureBuildIdentityTask extends DefaultTask {
    /** The authoritative schema captured by this build. */
    @InputFile
    public abstract RegularFileProperty getSchemaFile();

    /** The applying Gradle project's captured path. */
    @Input
    public abstract Property<String> getProjectPath();

    /** The applying Gradle project's captured name, used as the Phase 1 variant label. */
    @Input
    public abstract Property<String> getVariant();

    /** The applying Gradle project's captured version. */
    @Input
    public abstract Property<String> getProjectVersion();

    /** The generated identity/catalog artifact. */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Reads the schema and writes deterministic provenance from the actual applying project. */
    @TaskAction
    public void capture() throws IOException {
        byte[] schema = Files.readAllBytes(getSchemaFile().get().getAsFile().toPath());
        String provenance = getProjectPath().get() + ";version=" + getProjectVersion().get()
                + ";gradle=" + getProject().getGradle().getGradleVersion();
        BuildIdentity identity = BuildIdentityCapture.capture(
                getProjectPath().get(), getVariant().get(), provenance, schema);
        Files.createDirectories(getOutputFile().get().getAsFile().toPath().getParent());
        Files.writeString(getOutputFile().get().getAsFile().toPath(), BuildIdentityCapture.toJson(identity),
                StandardCharsets.UTF_8);
    }
}
