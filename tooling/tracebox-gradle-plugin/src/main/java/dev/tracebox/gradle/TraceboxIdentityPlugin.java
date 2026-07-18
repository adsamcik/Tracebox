package dev.tracebox.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Registers a cacheable Phase 1 build-identity/catalog capture task for the applying project. */
public final class TraceboxIdentityPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        TraceboxIdentityExtension extension = project.getExtensions().create(
                "traceboxIdentity", TraceboxIdentityExtension.class);
        project.getTasks().register("captureTraceboxBuildIdentity", CaptureBuildIdentityTask.class, task -> {
            task.setGroup("tracebox");
            task.setDescription("Captures schema and applying-project provenance for Tracebox.");
            task.getSchemaFile().fileProvider(project.provider(
                    () -> project.getRootProject().file(extension.getSchemaFile())));
            task.getProjectPath().convention(project.getPath());
            task.getVariant().convention(project.getName());
            task.getProjectVersion().convention(project.provider(() -> String.valueOf(project.getVersion())));
            task.getOutputFile().convention(project.getLayout().getBuildDirectory()
                    .file("tracebox/build-identity.json"));
        });
    }
}
