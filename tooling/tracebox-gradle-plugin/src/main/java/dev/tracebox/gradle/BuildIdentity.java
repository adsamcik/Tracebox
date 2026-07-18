package dev.tracebox.gradle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable identity captured from an applying Gradle project and its authoritative schema. */
public record BuildIdentity(String projectPath, String variant, String buildId, String schemaFingerprint) {}

/** Captures schema/build provenance without adding network or Phase 5 symbol collection behavior. */
final class BuildIdentityCapture {
    private BuildIdentityCapture() {}

    static BuildIdentity capture(String projectPath, String variant, String provenance, byte[] schemaBytes) {
        if (projectPath.isBlank() || variant.isBlank() || provenance.isBlank()) {
            throw new IllegalArgumentException("captured build provenance must be non-empty");
        }
        String schemaFingerprint = hex(sha256(schemaBytes));
        String buildId = hex(sha256((projectPath + "\n" + variant + "\n" + provenance + "\n"
                + schemaFingerprint).getBytes(StandardCharsets.UTF_8)));
        return new BuildIdentity(projectPath, variant, buildId, schemaFingerprint);
    }

    static String toJson(BuildIdentity identity) {
        return "{\n"
                + "  \"projectPath\": \"" + escape(identity.projectPath()) + "\",\n"
                + "  \"variant\": \"" + escape(identity.variant()) + "\",\n"
                + "  \"buildId\": \"" + identity.buildId() + "\",\n"
                + "  \"schemaFingerprint\": \"" + identity.schemaFingerprint() + "\"\n"
                + "}\n";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
