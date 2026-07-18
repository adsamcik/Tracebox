package dev.tracebox.gradle;

import java.nio.charset.StandardCharsets;

/** Runnable identity capture contract test without an external test dependency. */
public final class BuildIdentityTest {
    private BuildIdentityTest() {}

    /** Verifies deterministic schema hashing and preserved build metadata. */
    public static void main(String[] args) {
        BuildIdentity identity = BuildIdentityCapture.capture(
                "dev.example", "release", "build-7", "schema".getBytes(StandardCharsets.UTF_8));
        if (!identity.applicationId().equals("dev.example")
                || !identity.variant().equals("release")
                || !identity.buildId().equals("build-7")
                || !identity.schemaFingerprint().equals(
                        "df0ad6e43880f09c90ebf95f19110178aba6890df0010ebda7485029e2b543b4")) {
            throw new AssertionError("identity capture changed");
        }
    }
}
