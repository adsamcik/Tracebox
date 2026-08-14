package dev.tracebox.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Enforces the immutable release manifest and resolved-lock offline contract for one Android variant. */
@CacheableTask
public abstract class ReleaseConformanceTask extends DefaultTask {
    private static final List<String> FORBIDDEN_DEPENDENCY_TOKENS = List.of(
            "okhttp", "retrofit", "ktor-client", "netty", "volley", "cronet",
            "httpclient", "httpcore", "play-services-cronet");

    /** Public AGP merged manifest for the checked variant. */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMergedManifest();

    /** Resolved, committed dependency lockfiles that define the Android runtime closure. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getDependencyLockfiles();

    /** Gradle dependency verification metadata required for a reproducible release closure. */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getVerificationMetadata();

    /** Full build identity emitted by the matching capture task. */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getBuildIdentityFile();

    /** Observed application ID from the public AGP application variant. */
    @Input
    public abstract Property<String> getApplicationId();

    /** Certified minimum SDK. */
    @Input
    public abstract Property<Integer> getExpectedMinSdk();

    /** Certified compile SDK, recorded in the generated report. */
    @Input
    public abstract Property<Integer> getExpectedCompileSdk();

    /** Certified target SDK. */
    @Input
    public abstract Property<Integer> getExpectedTargetSdk();

    /** Exact applying-project runtime configuration represented by the supplied lockfile. */
    @Input
    public abstract Property<String> getRuntimeConfigurationName();

    /** Variant label recorded in the deterministic conformance report. */
    @Input
    public abstract Property<String> getVariantName();

    /** Generated machine-readable release conformance report. */
    @OutputFile
    public abstract RegularFileProperty getReportFile();

    /** Validates the merged manifest, lock closure, and verification metadata before release publication. */
    @TaskAction
    public void verify() throws IOException {
        String manifest = Files.readString(getMergedManifest().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        int minSdk = manifestSdk(manifest, "minSdkVersion");
        int targetSdk = manifestSdk(manifest, "targetSdkVersion");
        if (minSdk != getExpectedMinSdk().get()) {
            throw new GradleException("Tracebox release minSdk must be " + getExpectedMinSdk().get()
                    + ", found " + minSdk + " in " + getMergedManifest().get().getAsFile());
        }
        if (targetSdk != getExpectedTargetSdk().get()) {
            throw new GradleException("Tracebox release targetSdk must be " + getExpectedTargetSdk().get()
                    + ", found " + targetSdk + " in " + getMergedManifest().get().getAsFile());
        }
        if (manifest.contains("android.permission.INTERNET")
                || manifest.contains("android.permission.ACCESS_NETWORK_STATE")) {
            throw new GradleException("Tracebox release manifest declares a forbidden network permission");
        }

        List<Path> lockfiles = getDependencyLockfiles().getFiles().stream()
                .map(file -> file.toPath())
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        if (lockfiles.isEmpty()) {
            throw new GradleException("Tracebox release conformance requires committed Gradle lockfiles");
        }
        if (lockfiles.size() != 1) {
            throw new GradleException("Build identity binds exactly one applying-project Gradle lockfile, found "
                    + lockfiles.size());
        }
        int lockedRuntimeCoordinates = 0;
        boolean emptyLock = false;
        for (Path lockfile : lockfiles) {
            for (String line : Files.readAllLines(lockfile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("=", 2);
                if (parts.length != 2) {
                    continue;
                }
                boolean belongsToRuntime = containsConfiguration(
                        parts[1], getRuntimeConfigurationName().get());
                if (parts[0].equals("empty")) {
                    emptyLock |= belongsToRuntime;
                    continue;
                }
                if (!belongsToRuntime) {
                    continue;
                }
                lockedRuntimeCoordinates++;
                String coordinate = parts[0].toLowerCase(java.util.Locale.ROOT);
                for (String forbidden : FORBIDDEN_DEPENDENCY_TOKENS) {
                    if (coordinate.contains(forbidden)) {
                        throw new GradleException("Forbidden network-capable dependency token '" + forbidden
                                + "' in locked release runtime closure: " + lockfile);
                    }
                }
            }
        }
        if (lockedRuntimeCoordinates == 0 && !emptyLock) {
            throw new GradleException("Applying-project lockfiles do not contain configuration "
                    + getRuntimeConfigurationName().get());
        }
        String verification = Files.readString(
                getVerificationMetadata().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        if (!verification.contains("<verification-metadata")) {
            throw new GradleException("Gradle verification metadata is malformed");
        }
        String buildIdentity = Files.readString(
                getBuildIdentityFile().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        requireExactJsonMember(
                buildIdentity, "applicationId", "\"" + escape(getApplicationId().get()) + "\"");
        requireExactJsonMember(buildIdentity, "minSdk", Integer.toString(getExpectedMinSdk().get()));
        requireExactJsonMember(buildIdentity, "compileSdk", Integer.toString(getExpectedCompileSdk().get()));
        requireExactJsonMember(buildIdentity, "targetSdk", Integer.toString(getExpectedTargetSdk().get()));
        String lockSha256 = deterministicLockHash(lockfiles);
        String identityLockSha256 = BuildIdentityCapture.hashProvenanceFile(lockfiles.get(0));
        String verificationSha256 = BuildIdentityCapture.hashProvenanceFile(
                getVerificationMetadata().get().getAsFile().toPath());
        requireExactJsonMember(
                buildIdentity, "dependencyLockSha256", "\"" + identityLockSha256 + "\"");
        requireExactJsonMember(
                buildIdentity, "dependencyVerificationSha256", "\"" + verificationSha256 + "\"");
        String buildIdentitySha256 = BuildIdentityCapture.hashProvenanceFile(
                getBuildIdentityFile().get().getAsFile().toPath());

        Path report = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\n"
                + "  \"variant\": \"" + escape(getVariantName().get()) + "\",\n"
                + "  \"applicationId\": \"" + escape(getApplicationId().get()) + "\",\n"
                + "  \"minSdk\": " + minSdk + ",\n"
                + "  \"compileSdk\": " + getExpectedCompileSdk().get() + ",\n"
                + "  \"targetSdk\": " + targetSdk + ",\n"
                + "  \"dependencyLockfiles\": " + lockfiles.size() + ",\n"
                + "  \"lockedRuntimeCoordinates\": " + lockedRuntimeCoordinates + ",\n"
                + "  \"dependencyLockSha256\": \"" + lockSha256 + "\",\n"
                + "  \"dependencyVerificationSha256\": \"" + verificationSha256 + "\",\n"
                + "  \"buildIdentitySha256\": \"" + buildIdentitySha256 + "\",\n"
                + "  \"networkPermissions\": false,\n"
                + "  \"result\": \"PASS\"\n"
                + "}\n", StandardCharsets.UTF_8);
    }

    private static int manifestSdk(String manifest, String attribute) {
        Pattern pattern = Pattern.compile("android:" + Pattern.quote(attribute) + "\\s*=\\s*\"(\\d+)\"");
        Matcher match = pattern.matcher(manifest);
        if (!match.find()) {
            throw new GradleException("Merged manifest is missing android:" + attribute);
        }
        return Integer.parseInt(match.group(1));
    }

    static boolean containsConfiguration(String configurations, String expected) {
        for (String configuration : configurations.split(",")) {
            if (configuration.trim().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    static void requireExactJsonMember(String json, String member, String canonicalValue) {
        Pattern memberPattern = Pattern.compile(
                "(?m)^\\s*\"" + Pattern.quote(member) + "\"\\s*:");
        Matcher members = memberPattern.matcher(json);
        int memberCount = 0;
        while (members.find()) {
            memberCount++;
        }
        Pattern exactPattern = Pattern.compile(
                "(?m)^\\s*\"" + Pattern.quote(member) + "\"\\s*:\\s*"
                        + Pattern.quote(canonicalValue) + "\\s*,?\\s*$");
        Matcher exact = exactPattern.matcher(json);
        int exactCount = 0;
        while (exact.find()) {
            exactCount++;
        }
        if (memberCount != 1 || exactCount != 1) {
            throw new GradleException("Build identity member '" + member
                    + "' is missing, duplicated, or does not match the release inputs");
        }
    }

    static String deterministicLockHash(List<Path> lockfiles) throws IOException {
        StringBuilder hashes = new StringBuilder("tracebox-dependency-locks-v1\n");
        for (Path lockfile : lockfiles.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            String name = lockfile.getFileName().toString();
            hashes.append(name.length()).append(':').append(name).append(':')
                    .append(BuildIdentityCapture.hashProvenanceFile(lockfile)).append('\n');
        }
        return BuildIdentityCapture.sha256Hex(hashes.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
