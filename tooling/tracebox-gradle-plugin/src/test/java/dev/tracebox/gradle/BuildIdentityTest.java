package dev.tracebox.gradle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.testkit.runner.GradleRunner;

/** Runnable functional test proving that applying the plugin writes a real identity artifact. */
public final class BuildIdentityTest {
    private BuildIdentityTest() {}

    /** Applies the plugin in a fixture project and verifies schema/build provenance was captured. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("..", "..").toAbsolutePath().normalize();
        Path mapping = root.resolve("test-apps/phase0-fixture/build/outputs/mapping/release/mapping.txt");
        Path nativeLibraries = root.resolve("android/tracebox-native/src/main/jniLibs");
        if (!Files.isRegularFile(mapping) || !Files.isDirectory(nativeLibraries)) {
            throw new AssertionError("functional test requires the real Phase 0 release mapping and native libraries");
        }
        BuildIdentity identity = BuildIdentityCapture.capture(":app", "release", "version=7;gradle=9.6.1",
                "schema".getBytes(StandardCharsets.UTF_8), mapping, List.of(nativeLibraries));
        if (identity.buildId().length() != 64 || identity.r8MappingId() == null
                || identity.r8MappingSha256() == null
                || identity.elfBuildIds().stream().noneMatch(elf -> elf.buildId() != null)
                || identity.elfBuildIds().stream().noneMatch(elf -> "sha256".equals(elf.source()))) {
            throw new AssertionError("real R8 mapping or ELF artifact identity was not captured");
        }

        Path fixture = Path.of("build", "identity-capture-functional-test");
        Files.createDirectories(fixture);
        Files.writeString(fixture.resolve("settings.gradle"), "rootProject.name = 'identity-fixture'\n");
        Files.writeString(fixture.resolve("schema.json"), "{\"schema_version\":1}\n");
        Files.writeString(fixture.resolve("build.gradle"), "plugins { id 'dev.tracebox.identity' }\n"
                + "traceboxIdentity {\n"
                + "  schemaFile = 'schema.json'\n"
                + "  r8MappingFile = '" + gradlePath(mapping) + "'\n"
                + "  nativeLibrariesDirectory = '" + gradlePath(nativeLibraries) + "'\n"
                + "}\n");
        GradleRunner.create()
                .withProjectDir(fixture.toFile())
                .withArguments("captureTraceboxBuildIdentity", "--offline")
                .withPluginClasspath()
                .build();
        String catalog = Files.readString(fixture.resolve("build/tracebox/build-identity.json"));
        if (!catalog.contains("\"projectPath\": \":\"")
                || !catalog.contains("\"schemaFingerprint\"")
                || !catalog.contains(identity.r8MappingSha256())
                || !catalog.contains("\"elfBuildIds\": [")) {
            throw new AssertionError("plugin did not capture applying-project R8/ELF provenance");
        }
    }

    private static String gradlePath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
