package dev.tracebox.gradle;

import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.Variant;

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
            task.setDescription("Captures schema, build, R8, and ELF provenance for Tracebox.");
            task.getSchemaFile().fileProvider(project.provider(
                    () -> project.getRootProject().file(extension.getSchemaFile())));
            task.getProjectPath().convention(project.getPath());
            task.getVariant().convention(project.getName());
            task.getProjectVersion().convention(project.provider(() -> String.valueOf(project.getVersion())));
            task.getR8MappingFile().fileProvider(project.provider(() -> extension.getR8MappingFile() == null
                    ? null
                    : project.file(extension.getR8MappingFile())));
            task.getNativeLibraries().from(project.provider(() -> extension.getNativeLibrariesDirectory() == null
                    ? null
                    : project.file(extension.getNativeLibrariesDirectory())));
            task.getOutputFile().convention(project.getLayout().getBuildDirectory()
                    .file("tracebox/build-identity.json"));
        });
        project.getPluginManager().withPlugin("com.android.application",
                ignored -> registerAndroidVariantTasks(project));
        project.getPluginManager().withPlugin("com.android.library",
                ignored -> registerAndroidVariantTasks(project));
    }

    @SuppressWarnings("unchecked")
    private static void registerAndroidVariantTasks(Project project) {
        AndroidComponentsExtension<?, ?, Variant> components =
                (AndroidComponentsExtension<?, ?, Variant>) project.getExtensions()
                        .getByType(AndroidComponentsExtension.class);
        components.onVariants(components.selector().all(), variant -> {
            String variantName = variant.getName();
            project.getTasks().register("captureTraceboxBuildIdentity" + capitalize(variantName),
                    CaptureBuildIdentityTask.class, task -> {
                        task.setGroup("tracebox");
                        task.setDescription("Captures schema, build, R8, and ELF provenance for "
                                + variantName + ".");
                        task.getSchemaFile().fileProvider(project.provider(
                                () -> project.getRootProject().file("schema/events.json")));
                        task.getProjectPath().convention(project.getPath());
                        task.getVariant().convention(variantName);
                        task.getProjectVersion().convention(
                                project.provider(() -> String.valueOf(project.getVersion())));
                        task.getR8MappingFile().fileProvider(variant.getArtifacts().get(
                                SingleArtifact.OBFUSCATION_MAPPING_FILE.INSTANCE)
                                .map(location -> location.getAsFile()));
                        task.getNativeLibraries().from(variant.getArtifacts().get(
                                SingleArtifact.MERGED_NATIVE_LIBS.INSTANCE));
                        task.getOutputFile().convention(project.getLayout().getBuildDirectory()
                                .file("tracebox/" + variantName + "-build-identity.json"));
                    });
        });
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
