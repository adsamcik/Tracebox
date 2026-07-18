package dev.tracebox.gradle;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Immutable identity captured from an applying Gradle project and its authoritative schema. */
public record BuildIdentity(
        String projectPath,
        String variant,
        String buildId,
        String schemaFingerprint,
        String r8MappingId,
        String r8MappingSha256,
        List<ElfBuildId> elfBuildIds) {}

/** One native library's GNU ELF build ID, or a SHA-256 fallback when it declares no build note. */
record ElfBuildId(String path, String buildId, String source) {}

/** Captures schema/build provenance without adding network or Phase 5 symbol collection behavior. */
final class BuildIdentityCapture {
    private BuildIdentityCapture() {}

    static BuildIdentity capture(String projectPath, String variant, String provenance, byte[] schemaBytes,
            Path r8MappingFile, List<Path> nativeLibraryInputs) throws java.io.IOException {
        if (projectPath.isBlank() || variant.isBlank() || provenance.isBlank()) {
            throw new IllegalArgumentException("captured build provenance must be non-empty");
        }
        String schemaFingerprint = hex(sha256(schemaBytes));
        String buildId = hex(sha256((projectPath + "\n" + variant + "\n" + provenance + "\n"
                + schemaFingerprint).getBytes(StandardCharsets.UTF_8)));
        String mappingHash = r8MappingFile != null && Files.isRegularFile(r8MappingFile)
                ? hex(sha256(Files.readAllBytes(r8MappingFile)))
                : null;
        String mappingId = mappingHash == null ? null : "sha256:" + mappingHash;
        return new BuildIdentity(projectPath, variant, buildId, schemaFingerprint, mappingId, mappingHash,
                captureElfBuildIds(nativeLibraryInputs));
    }

    static String toJson(BuildIdentity identity) {
        return "{\n"
                + "  \"projectPath\": \"" + escape(identity.projectPath()) + "\",\n"
                + "  \"variant\": \"" + escape(identity.variant()) + "\",\n"
                + "  \"buildId\": \"" + identity.buildId() + "\",\n"
                + "  \"schemaFingerprint\": \"" + identity.schemaFingerprint() + "\",\n"
                + "  \"r8MappingId\": " + nullableJson(identity.r8MappingId()) + ",\n"
                + "  \"r8MappingSha256\": " + nullableJson(identity.r8MappingSha256()) + ",\n"
                + "  \"elfBuildIds\": [" + elfBuildIdsJson(identity.elfBuildIds()) + "]\n"
                + "}\n";
    }

    private static List<ElfBuildId> captureElfBuildIds(List<Path> nativeLibraryInputs)
            throws java.io.IOException {
        List<ElfBuildId> identities = new ArrayList<>();
        for (Path input : nativeLibraryInputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> files = Files.walk(input)) {
                    files.filter(file -> Files.isRegularFile(file)
                                    && file.getFileName().toString().endsWith(".so"))
                            .forEach(file -> identities.add(captureElfBuildId(
                                    normalizePath(input.relativize(file)), file)));
                }
            } else if (Files.isRegularFile(input) && input.getFileName().toString().endsWith(".so")) {
                identities.add(captureElfBuildId(input.getFileName().toString(), input));
            }
        }
        identities.sort(Comparator.comparing(ElfBuildId::path));
        return List.copyOf(identities);
    }

    private static ElfBuildId captureElfBuildId(String path, Path file) {
        String buildId = elfBuildId(file);
        if (buildId != null) {
            return new ElfBuildId(path, buildId, "gnu");
        }
        try {
            return new ElfBuildId(path, "sha256:" + hex(sha256(Files.readAllBytes(file))), "sha256");
        } catch (java.io.IOException error) {
            return new ElfBuildId(path, null, "unavailable");
        }
    }

    static String elfBuildId(Path file) {
        try {
            byte[] elf = Files.readAllBytes(file);
            if (elf.length < 64 || elf[0] != 0x7f || elf[1] != 'E' || elf[2] != 'L' || elf[3] != 'F') {
                return null;
            }
            int elfClass = unsigned(elf[4]);
            ByteOrder byteOrder = switch (unsigned(elf[5])) {
                case 1 -> ByteOrder.LITTLE_ENDIAN;
                case 2 -> ByteOrder.BIG_ENDIAN;
                default -> null;
            };
            if ((elfClass != 1 && elfClass != 2) || byteOrder == null) {
                return null;
            }
            ByteBuffer header = ByteBuffer.wrap(elf).order(byteOrder);
            long sectionOffset = elfClass == 1 ? unsignedInt(header, 32) : header.getLong(40);
            int sectionEntrySize = unsignedShort(header, elfClass == 1 ? 46 : 58);
            int sectionCount = unsignedShort(header, elfClass == 1 ? 48 : 60);
            int sectionNamesIndex = unsignedShort(header, elfClass == 1 ? 50 : 62);
            if (sectionOffset < 0 || sectionEntrySize == 0 || sectionCount == 0
                    || sectionNamesIndex >= sectionCount
                    || !within(elf, sectionOffset, (long) sectionEntrySize * sectionCount)) {
                return null;
            }
            int sectionNameOffset = checkedOffset(sectionOffset + (long) sectionNamesIndex * sectionEntrySize);
            long stringsOffset = sectionOffset(header, sectionNameOffset, elfClass);
            long stringsSize = sectionSize(header, sectionNameOffset, elfClass);
            if (!within(elf, stringsOffset, stringsSize)) {
                return null;
            }
            for (int index = 0; index < sectionCount; index++) {
                int section = checkedOffset(sectionOffset + (long) index * sectionEntrySize);
                int nameOffset = header.getInt(section);
                long noteOffset = sectionOffset(header, section, elfClass);
                long noteSize = sectionSize(header, section, elfClass);
                if (nameOffset < 0 || !within(elf, stringsOffset + nameOffset, 1)
                        || !".note.gnu.build-id".equals(readString(elf,
                                checkedOffset(stringsOffset + nameOffset),
                                checkedOffset(stringsOffset + stringsSize)))) {
                    continue;
                }
                return gnuBuildId(elf, byteOrder, noteOffset, noteSize);
            }
        } catch (java.io.IOException | IndexOutOfBoundsException | ArithmeticException ignored) {
            return null;
        }
        return null;
    }

    private static String gnuBuildId(byte[] elf, ByteOrder byteOrder, long noteOffset, long noteSize) {
        if (!within(elf, noteOffset, noteSize)) {
            return null;
        }
        ByteBuffer notes = ByteBuffer.wrap(elf).order(byteOrder);
        long cursor = noteOffset;
        long end = noteOffset + noteSize;
        while (cursor + 12 <= end) {
            int namesz = notes.getInt(checkedOffset(cursor));
            int descsz = notes.getInt(checkedOffset(cursor + 4));
            int type = notes.getInt(checkedOffset(cursor + 8));
            if (namesz < 0 || descsz < 0) {
                return null;
            }
            long nameOffset = cursor + 12;
            long descriptionOffset = align4(nameOffset + namesz);
            long next = align4(descriptionOffset + descsz);
            if (next > end || !within(elf, nameOffset, namesz) || !within(elf, descriptionOffset, descsz)) {
                return null;
            }
            if (type == 3 && namesz == 4 && elf[checkedOffset(nameOffset)] == 'G'
                    && elf[checkedOffset(nameOffset + 1)] == 'N'
                    && elf[checkedOffset(nameOffset + 2)] == 'U'
                    && elf[checkedOffset(nameOffset + 3)] == 0) {
                byte[] buildId = new byte[descsz];
                System.arraycopy(elf, checkedOffset(descriptionOffset), buildId, 0, descsz);
                return hex(buildId);
            }
            cursor = next;
        }
        return null;
    }

    private static long sectionOffset(ByteBuffer header, int section, int elfClass) {
        return elfClass == 1 ? unsignedInt(header, section + 16) : header.getLong(section + 24);
    }

    private static long sectionSize(ByteBuffer header, int section, int elfClass) {
        return elfClass == 1 ? unsignedInt(header, section + 20) : header.getLong(section + 32);
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static int unsignedShort(ByteBuffer bytes, int offset) {
        return Short.toUnsignedInt(bytes.getShort(offset));
    }

    private static long unsignedInt(ByteBuffer bytes, int offset) {
        return Integer.toUnsignedLong(bytes.getInt(offset));
    }

    private static boolean within(byte[] bytes, long offset, long size) {
        return offset >= 0 && size >= 0 && offset <= bytes.length && size <= bytes.length - offset;
    }

    private static int checkedOffset(long value) {
        return Math.toIntExact(value);
    }

    private static long align4(long value) {
        return (value + 3) & ~3L;
    }

    private static String readString(byte[] bytes, int offset, int end) {
        int cursor = offset;
        while (cursor < end && bytes[cursor] != 0) {
            cursor++;
        }
        return new String(bytes, offset, cursor - offset, StandardCharsets.US_ASCII);
    }

    private static String elfBuildIdsJson(List<ElfBuildId> identities) {
        StringBuilder json = new StringBuilder();
        for (ElfBuildId identity : identities) {
            if (!json.isEmpty()) {
                json.append(", ");
            }
            json.append("{\"path\": \"").append(escape(identity.path()))
                    .append("\", \"buildId\": ").append(nullableJson(identity.buildId()))
                    .append(", \"source\": \"").append(identity.source()).append("\"}");
        }
        return json.toString();
    }

    private static String nullableJson(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
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
