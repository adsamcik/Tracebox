package dev.tracebox.gradle;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable identity captured for a build before later R8/ELF collection phases. */
public record BuildIdentity(String applicationId, String variant, String buildId, String schemaFingerprint) {}

/** Captures schema/build provenance without adding network or symbol transport behavior. */
final class BuildIdentityCapture {
    private BuildIdentityCapture() {}

    static BuildIdentity capture(String applicationId, String variant, String buildId, byte[] schemaBytes) {
        if (applicationId.isBlank() || variant.isBlank() || buildId.isBlank()) {
            throw new IllegalArgumentException("build identity inputs must be non-empty");
        }
        return new BuildIdentity(applicationId, variant, buildId, hex(sha256(schemaBytes)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
