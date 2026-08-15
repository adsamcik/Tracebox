package dev.tracebox.gradle;

import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.api.variant.Variant;
import com.android.build.api.variant.VariantOutput;

import java.io.File;
import java.util.List;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

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
            task.getApplicationId().convention(project.getPath());
            task.getVersionCode().convention(0);
            task.getVersionName().convention(project.provider(() -> String.valueOf(project.getVersion())));
            task.getVariant().convention(project.getName());
            task.getProjectVersion().convention(project.provider(() -> String.valueOf(project.getVersion())));
            task.getGradleVersion().convention(project.getGradle().getGradleVersion());
            task.getMinSdk().convention(0);
            task.getCompileSdk().convention(0);
            task.getTargetSdk().convention(0);
            task.getR8MappingFile().fileProvider(project.provider(() -> extension.getR8MappingFile() == null
                    ? null
                    : project.file(extension.getR8MappingFile())));
            task.getNativeLibraries().from(project.provider(() -> extension.getNativeLibrariesDirectory() == null
                    ? null
                    : project.file(extension.getNativeLibrariesDirectory())));
            configureProvenanceInputs(project, task);
            task.getOutputFile().convention(project.getLayout().getBuildDirectory()
                    .file("tracebox/build-identity.json"));
            task.getSymbolCatalogFile().convention(project.getLayout().getBuildDirectory()
                    .file("tracebox/symbol-catalog.tsv"));
        });
        project.getPluginManager().withPlugin("com.android.application",
                ignored -> registerAndroidVariantTasks(project, extension));
        project.getPluginManager().withPlugin("com.android.library",
                ignored -> registerAndroidVariantTasks(project, extension));
    }

    @SuppressWarnings("unchecked")
    private static void registerAndroidVariantTasks(
            Project project,
            TraceboxIdentityExtension extension) {
        AndroidComponentsExtension<?, ?, Variant> components =
                (AndroidComponentsExtension<?, ?, Variant>) project.getExtensions()
                        .getByType(AndroidComponentsExtension.class);
        components.onVariants(components.selector().all(), variant -> {
            String variantName = variant.getName();
            Integer publicCompileSdk = variant.getCompileSdk().getApiLevel();
            int observedCompileSdk = publicCompileSdk == null ? 0 : publicCompileSdk;
            TaskProvider<CaptureBuildIdentityTask> capture = project.getTasks().register(
                    "captureTraceboxBuildIdentity" + capitalize(variantName),
                    CaptureBuildIdentityTask.class, task -> {
                        task.setGroup("tracebox");
                        task.setDescription("Captures schema, build, R8, and ELF provenance for "
                                + variantName + ".");
                        task.getSchemaFile().fileProvider(project.provider(
                                () -> project.getRootProject().file(extension.getSchemaFile())));
                        task.getProjectPath().convention(project.getPath());
                        task.getApplicationId().convention(variant.getNamespace());
                        task.getVersionCode().convention(0);
                        task.getVersionName().convention(
                                project.provider(() -> String.valueOf(project.getVersion())));
                        task.getVariant().convention(variantName);
                        task.getProjectVersion().convention(
                                project.provider(() -> String.valueOf(project.getVersion())));
                        task.getGradleVersion().convention(project.getGradle().getGradleVersion());
                        task.getMinSdk().convention(variant.getMinSdk().getApiLevel());
                        task.getCompileSdk().convention(observedCompileSdk);
                        task.getTargetSdk().convention(variant.getTargetSdkVersion().getApiLevel());
                        if (variant instanceof ApplicationVariant applicationVariant) {
                            task.getApplicationId().set(applicationVariant.getApplicationId());
                            List<VariantOutput> outputs = applicationVariant.getOutputs();
                            if (!outputs.isEmpty()) {
                                VariantOutput output = outputs.get(0);
                                task.getVersionCode().set(output.getVersionCode().orElse(0));
                                task.getVersionName().set(output.getVersionName().orElse(
                                        project.provider(() -> String.valueOf(project.getVersion()))));
                            }
                        }
                        task.getR8MappingFile().fileProvider(variant.getArtifacts().get(
                                SingleArtifact.OBFUSCATION_MAPPING_FILE.INSTANCE)
                                .map(location -> location.getAsFile()));
                        task.getNativeLibraries().from(variant.getArtifacts().get(
                                SingleArtifact.MERGED_NATIVE_LIBS.INSTANCE));
                        configureProvenanceInputs(project, task);
                        task.getOutputFile().convention(project.getLayout().getBuildDirectory()
                                .file("tracebox/" + variantName + "-build-identity.json"));
                        task.getSymbolCatalogFile().convention(project.getLayout().getBuildDirectory()
                                .file("tracebox/" + variantName + "-symbol-catalog.tsv"));
                    });
            if (variantName.toLowerCase(java.util.Locale.ROOT).contains("release")) {
                project.getTasks().register(
                        "verifyTraceboxReleaseConformance" + capitalize(variantName),
                        ReleaseConformanceTask.class,
                        task -> {
                            task.setGroup("verification");
                            task.setDescription("Verifies the Tracebox release manifest and dependency closure for "
                                    + variantName + ".");
                            task.getMergedManifest().fileProvider(variant.getArtifacts().get(
                                    SingleArtifact.MERGED_MANIFEST.INSTANCE)
                                    .map(location -> location.getAsFile()));
                            task.getDependencyLockfiles().from(project.file("gradle.lockfile"));
                            task.getVerificationMetadata().fileValue(project.getRootProject()
                                    .file("gradle/verification-metadata.xml"));
                            task.getBuildIdentityFile().set(capture.flatMap(
                                    CaptureBuildIdentityTask::getOutputFile));
                            task.getApplicationId().convention(variant.getNamespace());
                            task.getExpectedMinSdk().convention(variant.getMinSdk().getApiLevel());
                            task.getExpectedCompileSdk().convention(observedCompileSdk);
                            task.getExpectedTargetSdk().convention(variant.getTargetSdkVersion().getApiLevel());
                            task.getRuntimeConfigurationName().convention(
                                    variant.getRuntimeConfiguration().getName());
                            if (variant instanceof ApplicationVariant applicationVariant) {
                                task.getApplicationId().set(applicationVariant.getApplicationId());
                            }
                            task.getVariantName().convention(variantName);
                            task.getReportFile().convention(project.getLayout().getBuildDirectory().file(
                                    "tracebox/" + variantName + "-release-conformance.json"));
                            task.dependsOn(capture);
                        });
            }
        });
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void configureProvenanceInputs(Project project, CaptureBuildIdentityTask task) {
        Project root = project.getRootProject();
        task.getCrashpadSourceLock().fileProvider(project.provider(
                () -> existingFile(root.file("third_party/crashpad/source-lock.json"))));
        task.getCrashpadPatchFiles().from(root.fileTree(
                root.file("third_party/crashpad/patches"),
                tree -> tree.include("series", "*.patch")));
        task.getRustLock().fileProvider(project.provider(
                () -> existingFile(root.file("Cargo.lock"))));
        task.getDependencyVerification().fileProvider(project.provider(
                () -> existingFile(root.file("gradle/verification-metadata.xml"))));
        task.getDependencyLock().fileProvider(project.provider(
                () -> existingFile(project.file("gradle.lockfile"))));
    }

    private static File existingFile(File file) {
        return file.isFile() ? file : null;
    }
}
