package dev.tracebox.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.GradleException;
import org.junit.Test;

/** Fast host tests for the full build tuple and bounded provenance inputs. */
public final class BuildIdentityUnitTest {
    @Test
    public void fullIdentityIncludesEveryPinnedProvenanceInput() throws Exception {
        Path root = Files.createTempDirectory("tracebox-build-identity");
        Path mapping = write(root, "mapping.txt", "dev.tracebox.Original -> a:\n"
                + "    void method() -> b\n");
        Path sourceLock = write(root, "source-lock.json", "{\"revision\":\"abc\"}\n");
        Path series = write(root, "series", "0001.patch\n");
        Path patch = write(root, "0001.patch", "patch bytes\n");
        Path rustLock = write(root, "Cargo.lock", "[[package]]\nname=\"tracebox\"\n");
        Path verification = write(root, "verification-metadata.xml", "<verification-metadata/>\n");
        Path dependencyLock = write(root, "gradle.lockfile",
                "org.example:offline:1=releaseRuntimeClasspath\n");

        BuildIdentity first = BuildIdentityCapture.capture(
                ":app",
                "dev.tracebox.fixture",
                7,
                "1.2.3",
                "release",
                23,
                37,
                37,
                ":app;version=1.2.3;gradle=9.6.1",
                "schema".getBytes(StandardCharsets.UTF_8),
                mapping,
                List.of(),
                sourceLock,
                List.of(series, patch),
                rustLock,
                verification,
                dependencyLock);

        assertEquals("dev.tracebox.fixture", first.applicationId());
        assertEquals(7, first.versionCode());
        assertEquals(23, first.minSdk());
        assertNotNull(first.crashpadSourceSha256());
        assertNotNull(first.crashpadPatchSetSha256());
        assertNotNull(first.rustLockSha256());
        assertNotNull(first.dependencyVerificationSha256());
        assertNotNull(first.dependencyLockSha256());
        assertTrue(BuildIdentityCapture.toJson(first).contains("\"applicationId\": \"dev.tracebox.fixture\""));
        assertTrue(BuildIdentityCapture.symbolCatalog(first)
                .startsWith("# tracebox-symbol-catalog-v2\nbuild\t" + first.buildId()));

        Files.writeString(rustLock, "[[package]]\nname=\"changed\"\n", StandardCharsets.UTF_8);
        BuildIdentity changed = BuildIdentityCapture.capture(
                ":app",
                "dev.tracebox.fixture",
                7,
                "1.2.3",
                "release",
                23,
                37,
                37,
                ":app;version=1.2.3;gradle=9.6.1",
                "schema".getBytes(StandardCharsets.UTF_8),
                mapping,
                List.of(),
                sourceLock,
                List.of(series, patch),
                rustLock,
                verification,
                dependencyLock);
        assertNotEquals(first.buildId(), changed.buildId());
    }

    @Test
    public void releaseConfigurationMatchingIsExact() {
        assertTrue(ReleaseConformanceTask.containsConfiguration(
                "debugRuntimeClasspath,releaseRuntimeClasspath", "releaseRuntimeClasspath"));
        assertFalse(ReleaseConformanceTask.containsConfiguration(
                "debugRuntimeClasspath,otherReleaseRuntimeClasspath", "releaseRuntimeClasspath"));
    }

    @Test
    public void releaseIdentityMemberMatchingRejectsSpoofsAndDuplicates() {
        ReleaseConformanceTask.requireExactJsonMember(
                "{\n  \"applicationId\": \"dev.tracebox.fixture\"\n}\n",
                "applicationId",
                "\"dev.tracebox.fixture\"");

        assertIdentityMemberRejected(
                "{\n  \"note\": \"applicationId: dev.tracebox.fixture\"\n}\n",
                "applicationId",
                "\"dev.tracebox.fixture\"");
        assertIdentityMemberRejected(
                "{\n"
                        + "  \"applicationId\": \"dev.tracebox.fixture\",\n"
                        + "  \"applicationId\": \"dev.tracebox.fixture\"\n"
                        + "}\n",
                "applicationId",
                "\"dev.tracebox.fixture\"");
        assertIdentityMemberRejected(
                "{\n  \"applicationId\": \"dev.tracebox.other\"\n}\n",
                "applicationId",
                "\"dev.tracebox.fixture\"");
    }

    @Test
    public void schemaInputUsesRelativePathSensitivity() throws Exception {
        PathSensitive annotation = CaptureBuildIdentityTask.class
                .getMethod("getSchemaFile")
                .getAnnotation(PathSensitive.class);

        assertNotNull(annotation);
        assertEquals(PathSensitivity.RELATIVE, annotation.value());
    }

    @Test
    public void symbolCatalogUsesRuntimeModuleBasenamesAcrossAbis() {
        BuildIdentity identity = new BuildIdentity(
                ":app",
                "dev.tracebox.fixture",
                7,
                "1.2.3",
                "release",
                23,
                37,
                37,
                "build-id",
                "schema-id",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(
                        new ElfBuildId(
                                "lib/arm64-v8a/libtracebox_crashpad.so",
                                "arm-identity",
                                "sha256",
                                "arm64-v8a",
                                List.of(new ElfSymbol(7, "arm_symbol"))),
                        new ElfBuildId(
                                "lib\\x86_64\\libtracebox_crashpad.so",
                                "x86-identity",
                                "sha256",
                                "x86_64",
                                List.of(new ElfSymbol(9, "x86_symbol")))),
                List.of());

        String catalog = BuildIdentityCapture.symbolCatalog(identity);
        assertTrue(catalog.contains(
                "native\tlibtracebox_crashpad.so\tarm-identity\tarm64-v8a\t7\tarm_symbol"));
        assertTrue(catalog.contains(
                "native\tlibtracebox_crashpad.so\tx86-identity\tx86_64\t9\tx86_symbol"));
        assertFalse(catalog.contains("native\tlib/"));
        assertFalse(catalog.contains("native\tlib\\"));
    }

    @Test
    public void provenanceHashRejectsOversizedFileBeforeReading() throws Exception {
        Path file = Files.createTempFile("tracebox-oversized-provenance", ".bin");
        try (RandomAccessFile output = new RandomAccessFile(file.toFile(), "rw")) {
            output.setLength(64L * 1024L * 1024L + 1);
        }
        try {
            BuildIdentityCapture.hashProvenanceFile(file);
            fail("oversized provenance file was accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exceeds"));
        }
    }

    private static Path write(Path root, String name, String contents) throws IOException {
        Path file = root.resolve(name);
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }

    private static void assertIdentityMemberRejected(
            String json,
            String member,
            String canonicalValue) {
        try {
            ReleaseConformanceTask.requireExactJsonMember(json, member, canonicalValue);
            fail("invalid build identity member was accepted");
        } catch (GradleException expected) {
            assertTrue(expected.getMessage().contains(member));
        }
    }
}
