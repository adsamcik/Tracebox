package dev.tracebox.gradle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.GradleRunner;

/** Runnable functional test proving that applying the plugin writes a real identity artifact. */
public final class BuildIdentityTest {
    private BuildIdentityTest() {}

    /** Applies the plugin in a fixture project and verifies schema/build provenance was captured. */
    public static void main(String[] args) throws Exception {
        BuildIdentity identity = BuildIdentityCapture.capture(
                ":app", "release", "version=7;gradle=9.6.1", "schema".getBytes(StandardCharsets.UTF_8));
        if (identity.buildId().length() != 64 || !BuildIdentityCapture.toJson(identity).contains("\"schemaFingerprint\"")) {
            throw new AssertionError("identity capture formatting changed");
        }

        Path fixture = Path.of("build", "identity-capture-functional-test");
        Files.createDirectories(fixture);
        Files.writeString(fixture.resolve("settings.gradle"), "rootProject.name = 'identity-fixture'\n");
        Files.writeString(fixture.resolve("schema.json"), "{\"schema_version\":1}\n");
        Files.writeString(fixture.resolve("build.gradle"), "plugins { id 'dev.tracebox.identity' }\n"
                + "traceboxIdentity { schemaFile = 'schema.json' }\n");
        GradleRunner.create()
                .withProjectDir(fixture.toFile())
                .withArguments("captureTraceboxBuildIdentity", "--offline")
                .withPluginClasspath()
                .build();
        String catalog = Files.readString(fixture.resolve("build/tracebox/build-identity.json"));
        if (!catalog.contains("\"projectPath\": \":\"")
                || !catalog.contains("\"schemaFingerprint\"")) {
            throw new AssertionError("plugin did not capture applying-project provenance");
        }
    }
}
