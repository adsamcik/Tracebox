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
        List<ElfBuildId> elfBuildIds,
        List<R8MappingEntry> r8Mappings) {}

/** One exported ELF symbol, identified by its exact module-relative offset. */
record ElfSymbol(long offset, String name) {}

/** One native library's GNU ELF build ID, fallback hash, ABI, and bounded symbol catalog. */
record ElfBuildId(String path, String buildId, String source, String abi, List<ElfSymbol> symbols) {}

/** One exact residual R8 name mapping. Ambiguous residual names remain separate entries. */
record R8MappingEntry(String obfuscated, String original) {}

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
                captureElfBuildIds(nativeLibraryInputs), captureR8Mappings(r8MappingFile));
    }

    static String toJson(BuildIdentity identity) {
        return "{\n"
                + "  \"projectPath\": \"" + escape(identity.projectPath()) + "\",\n"
                + "  \"variant\": \"" + escape(identity.variant()) + "\",\n"
                + "  \"buildId\": \"" + identity.buildId() + "\",\n"
                + "  \"schemaFingerprint\": \"" + identity.schemaFingerprint() + "\",\n"
                + "  \"r8MappingId\": " + nullableJson(identity.r8MappingId()) + ",\n"
                + "  \"r8MappingSha256\": " + nullableJson(identity.r8MappingSha256()) + ",\n"
                + "  \"elfBuildIds\": [" + elfBuildIdsJson(identity.elfBuildIds()) + "],\n"
                + "  \"symbolCatalogSha256\": \"" + hex(sha256(symbolCatalog(identity)
                        .getBytes(StandardCharsets.UTF_8))) + "\"\n"
                + "}\n";
    }

    /**
     * Creates the deterministic, line-oriented catalog consumed by the offline CLI.
     *
     * Native libraries without a GNU note use the exact SHA-256 fallback already recorded in the
     * identity artifact. A module row establishes a matchable provenance record even when a
     * stripped library exports no symbols; such a row can never resolve an arbitrary offset.
     */
    static String symbolCatalog(BuildIdentity identity) {
        StringBuilder catalog = new StringBuilder("# tracebox-symbol-catalog-v1\n");
        catalog.append("# kind\tmodule\tidentity\tabi\toffset\tsymbol\n");
        for (ElfBuildId elf : identity.elfBuildIds()) {
            if (elf.buildId() == null) {
                continue;
            }
            if (elf.symbols().isEmpty()) {
                catalog.append("native\t").append(escapeTsv(elf.path())).append('\t')
                        .append(escapeTsv(elf.buildId())).append('\t').append(elf.abi())
                        .append("\t0\tidentity-only\n");
            } else {
                for (ElfSymbol symbol : elf.symbols()) {
                    catalog.append("native\t").append(escapeTsv(elf.path())).append('\t')
                            .append(escapeTsv(elf.buildId())).append('\t').append(elf.abi()).append('\t')
                            .append(symbol.offset()).append('\t').append(escapeTsv(symbol.name())).append('\n');
                }
            }
        }
        if (identity.r8MappingId() != null) {
            if (identity.r8Mappings().isEmpty()) {
                catalog.append("r8\t").append(escapeTsv(identity.r8MappingId()))
                        .append("\t<identity>\t<identity>\n");
            } else {
                for (R8MappingEntry mapping : identity.r8Mappings()) {
                    catalog.append("r8\t").append(escapeTsv(identity.r8MappingId())).append('\t')
                            .append(escapeTsv(mapping.obfuscated())).append('\t')
                            .append(escapeTsv(mapping.original())).append('\n');
                }
            }
        }
        return catalog.toString();
    }

    private static List<ElfBuildId> captureElfBuildIds(List<Path> nativeLibraryInputs)
            throws java.io.IOException {
        List<ElfBuildId> identities = new ArrayList<>();
        for (Path input : nativeLibraryInputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> files = Files.walk(input)) {
                    for (Path file : files.filter(candidate -> Files.isRegularFile(candidate)
                                    && candidate.getFileName().toString().endsWith(".so"))
                            .sorted()
                            .toList()) {
                        identities.add(captureElfBuildId(normalizePath(input.relativize(file)), file));
                    }
                }
            } else if (Files.isRegularFile(input) && input.getFileName().toString().endsWith(".so")) {
                identities.add(captureElfBuildId(input.getFileName().toString(), input));
            }
        }
        identities.sort(Comparator.comparing(ElfBuildId::path));
        return List.copyOf(identities);
    }

    private static List<R8MappingEntry> captureR8Mappings(Path mapping) throws java.io.IOException {
        if (mapping == null || !Files.isRegularFile(mapping)) {
            return List.of();
        }
        if (Files.size(mapping) > 64L * 1024L * 1024L) {
            throw new java.io.IOException("R8 mapping exceeds the 64 MiB catalog bound: " + mapping);
        }
        List<R8MappingEntry> entries = new ArrayList<>();
        String originalClass = null;
        String residualClass = null;
        for (String line : Files.readAllLines(mapping, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int arrow = trimmed.lastIndexOf(" -> ");
            if (!Character.isWhitespace(line.charAt(0)) && arrow > 0 && trimmed.endsWith(":")) {
                originalClass = trimmed.substring(0, arrow).trim();
                residualClass = trimmed.substring(arrow + 4, trimmed.length() - 1).trim();
                if (originalClass.isEmpty() || residualClass.isEmpty()) {
                    throw new java.io.IOException("Malformed R8 class mapping: " + line);
                }
                entries.add(new R8MappingEntry(residualClass, originalClass));
                continue;
            }
            if (originalClass == null || residualClass == null || arrow <= 0) {
                continue;
            }
            String residualMember = trimmed.substring(arrow + 4).trim();
            String sourceMember = stripR8LineRanges(trimmed.substring(0, arrow).trim());
            if (residualMember.isEmpty() || sourceMember.isEmpty()) {
                throw new java.io.IOException("Malformed R8 member mapping: " + line);
            }
            int openParenthesis = sourceMember.indexOf('(');
            int separator = sourceMember.lastIndexOf(' ', openParenthesis >= 0 ? openParenthesis : sourceMember.length());
            if (separator < 0 || separator == sourceMember.length() - 1) {
                throw new java.io.IOException("Malformed R8 source member: " + line);
            }
            String originalMember = sourceMember.substring(separator + 1, openParenthesis >= 0 ? openParenthesis : sourceMember.length());
            if (originalMember.isEmpty()) {
                throw new java.io.IOException("Malformed R8 source member name: " + line);
            }
            entries.add(new R8MappingEntry(
                    residualClass + "." + residualMember,
                    originalClass + "." + originalMember));
        }
        entries.sort(Comparator.comparing(R8MappingEntry::obfuscated).thenComparing(R8MappingEntry::original));
        return List.copyOf(entries);
    }

    private static String stripR8LineRanges(String value) {
        String result = value;
        while (result.matches("^\\d+:\\d+:.*")) {
            int thirdColon = result.indexOf(':', result.indexOf(':') + 1);
            result = result.substring(thirdColon + 1);
        }
        return result.replaceFirst(":\\d+:\\d+$", "");
    }

    private static ElfBuildId captureElfBuildId(String path, Path file) throws java.io.IOException {
        ElfMetadata metadata;
        try {
            metadata = inspectElf(Files.readAllBytes(file));
        } catch (IllegalArgumentException error) {
            throw new java.io.IOException("Malformed native ELF input: " + file, error);
        }
        String buildId = metadata.gnuBuildId();
        String source = "gnu";
        if (buildId == null) {
            buildId = "sha256:" + hex(sha256(Files.readAllBytes(file)));
            source = "sha256";
        }
        return new ElfBuildId(path, buildId, source, metadata.abi(), metadata.symbols());
    }

    private record ElfMetadata(String gnuBuildId, String abi, List<ElfSymbol> symbols) {}

    private record ElfSection(int nameOffset, int type, long offset, long size, int link, long entrySize) {}

    private static final class ElfLayout {
        private final byte[] bytes;
        private final ByteBuffer header;
        private final int elfClass;
        private final int machine;
        private final List<ElfSection> sections;

        ElfLayout(byte[] bytes, ByteBuffer header, int elfClass, int machine, List<ElfSection> sections) {
            this.bytes = bytes;
            this.header = header;
            this.elfClass = elfClass;
            this.machine = machine;
            this.sections = sections;
        }

        String stringAt(ElfSection strings, int offset) {
            if (offset < 0 || !strictWithin(bytes, strings.offset() + offset, 1)) {
                throw new IllegalArgumentException("ELF string offset is invalid");
            }
            return readStrictString(
                    bytes,
                    strictOffset(strings.offset() + offset),
                    strictOffset(addExact(strings.offset(), strings.size())));
        }
    }

    private static ElfMetadata inspectElf(byte[] elf) {
        ElfLayout layout = parseElfLayout(elf);
        ElfSection sectionNames = layout.sections.get(sectionNamesIndex(layout));
        String buildId = null;
        for (ElfSection section : layout.sections) {
            if (".note.gnu.build-id".equals(layout.stringAt(sectionNames, section.nameOffset()))) {
                if (buildId != null) {
                    throw new IllegalArgumentException("ELF declares multiple GNU build-id sections");
                }
                buildId = strictGnuBuildId(elf, layout.header.order(), section.offset(), section.size());
            }
        }
        return new ElfMetadata(buildId, abiName(layout.machine), collectElfSymbols(layout));
    }

    private static ElfLayout parseElfLayout(byte[] elf) {
        if (elf.length < 16 || elf[0] != 0x7f || elf[1] != 'E' || elf[2] != 'L' || elf[3] != 'F') {
            throw new IllegalArgumentException("ELF magic is missing");
        }
        int elfClass = unsigned(elf[4]);
        ByteOrder byteOrder = switch (unsigned(elf[5])) {
            case 1 -> ByteOrder.LITTLE_ENDIAN;
            case 2 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IllegalArgumentException("ELF byte order is invalid");
        };
        if (elfClass != 1 && elfClass != 2) {
            throw new IllegalArgumentException("ELF class is invalid");
        }
        int headerSize = elfClass == 1 ? 52 : 64;
        if (elf.length < headerSize) {
            throw new IllegalArgumentException("ELF header is truncated");
        }
        ByteBuffer header = ByteBuffer.wrap(elf).order(byteOrder);
        long sectionOffset = elfClass == 1 ? unsignedInt(header, 32) : unsignedLong(header.getLong(40));
        int sectionEntrySize = unsignedShort(header, elfClass == 1 ? 46 : 58);
        int sectionCount = unsignedShort(header, elfClass == 1 ? 48 : 60);
        int namesIndex = unsignedShort(header, elfClass == 1 ? 50 : 62);
        int minimumSectionEntrySize = elfClass == 1 ? 40 : 64;
        if (sectionEntrySize < minimumSectionEntrySize || sectionCount == 0 || namesIndex >= sectionCount
                || !strictWithin(elf, sectionOffset, multiplyExact(sectionEntrySize, sectionCount))) {
            throw new IllegalArgumentException("ELF section table is invalid");
        }
        List<ElfSection> sections = new ArrayList<>(sectionCount);
        for (int index = 0; index < sectionCount; index++) {
            int offset = strictOffset(sectionOffset + (long) index * sectionEntrySize);
            int nameOffset = header.getInt(offset);
            int type = header.getInt(offset + 4);
            long contentOffset = elfClass == 1 ? unsignedInt(header, offset + 16) : unsignedLong(header.getLong(offset + 24));
            long contentSize = elfClass == 1 ? unsignedInt(header, offset + 20) : unsignedLong(header.getLong(offset + 32));
            long linkValue = elfClass == 1 ? unsignedInt(header, offset + 24) : unsignedInt(header, offset + 40);
            long entrySize = elfClass == 1 ? unsignedInt(header, offset + 36) : unsignedLong(header.getLong(offset + 56));
            boolean fileBacked = type != 8; // SHT_NOBITS occupies memory but has no bytes in the ELF file.
            if (linkValue > Integer.MAX_VALUE
                    || (fileBacked && !strictWithin(elf, contentOffset, contentSize))
                    || (!fileBacked && (contentOffset < 0 || contentOffset > elf.length))) {
                throw new IllegalArgumentException("ELF section range is invalid");
            }
            sections.add(new ElfSection(nameOffset, type, contentOffset, contentSize, (int) linkValue, entrySize));
        }
        ElfSection names = sections.get(namesIndex);
        if (!strictWithin(elf, names.offset(), names.size())) {
            throw new IllegalArgumentException("ELF section-name table is invalid");
        }
        return new ElfLayout(elf, header, elfClass, unsignedShort(header, 18), List.copyOf(sections));
    }

    private static int sectionNamesIndex(ElfLayout layout) {
        return unsignedShort(layout.header, layout.elfClass == 1 ? 50 : 62);
    }

    private static List<ElfSymbol> collectElfSymbols(ElfLayout layout) {
        List<ElfSymbol> symbols = new ArrayList<>();
        for (ElfSection section : layout.sections) {
            if (section.type() != 2 && section.type() != 11) {
                continue;
            }
            int minimumSymbolSize = layout.elfClass == 1 ? 16 : 24;
            if (section.entrySize() < minimumSymbolSize || section.entrySize() == 0
                    || section.size() % section.entrySize() != 0) {
                throw new IllegalArgumentException("ELF symbol table is malformed");
            }
            long count = section.size() / section.entrySize();
            if (count > 65_536 || section.link() < 0 || section.link() >= layout.sections.size()) {
                throw new IllegalArgumentException("ELF symbol table exceeds the hard bound");
            }
            ElfSection strings = layout.sections.get(section.link());
            for (long index = 0; index < count; index++) {
                int entry = strictOffset(section.offset() + index * section.entrySize());
                int nameOffset = layout.header.getInt(entry);
                int typeOffset = layout.elfClass == 1 ? entry + 12 : entry + 4;
                int sectionIndexOffset = layout.elfClass == 1 ? entry + 14 : entry + 6;
                int symbolType = unsigned(layout.bytes[typeOffset]) & 0x0f;
                if (symbolType != 2 && symbolType != 10
                        || unsignedShort(layout.header, sectionIndexOffset) == 0) {
                    continue;
                }
                long offset = layout.elfClass == 1
                        ? unsignedInt(layout.header, entry + 4)
                        : unsignedLong(layout.header.getLong(entry + 8));
                String name = layout.stringAt(strings, nameOffset);
                if (!name.isEmpty()) {
                    symbols.add(new ElfSymbol(offset, name));
                }
            }
        }
        symbols.sort(Comparator.comparingLong(ElfSymbol::offset).thenComparing(ElfSymbol::name));
        return List.copyOf(symbols);
    }

    private static String abiName(int machine) {
        return switch (machine) {
            case 3 -> "x86";
            case 40 -> "armeabi-v7a";
            case 62 -> "x86_64";
            case 183 -> "arm64-v8a";
            default -> "elf-machine-" + machine;
        };
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

    /**
     * Strict in-memory parser used by the fixture corpus. Unlike the catalog fallback path, it
     * distinguishes a valid ELF without a GNU build note from malformed ELF structure.
     */
    static String parseElfBuildIdForTest(byte[] elf) {
        if (elf.length < 16 || elf[0] != 0x7f || elf[1] != 'E' || elf[2] != 'L' || elf[3] != 'F') {
            throw new IllegalArgumentException("ELF magic is missing");
        }
        int elfClass = unsigned(elf[4]);
        ByteOrder byteOrder = switch (unsigned(elf[5])) {
            case 1 -> ByteOrder.LITTLE_ENDIAN;
            case 2 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IllegalArgumentException("ELF byte order is invalid");
        };
        if (elfClass != 1 && elfClass != 2) {
            throw new IllegalArgumentException("ELF class is invalid");
        }
        int headerSize = elfClass == 1 ? 52 : 64;
        if (elf.length < headerSize) {
            throw new IllegalArgumentException("ELF header is truncated");
        }
        ByteBuffer header = ByteBuffer.wrap(elf).order(byteOrder);
        long sectionOffset = elfClass == 1
                ? unsignedInt(header, 32)
                : unsignedLong(header.getLong(40));
        int sectionEntrySize = unsignedShort(header, elfClass == 1 ? 46 : 58);
        int sectionCount = unsignedShort(header, elfClass == 1 ? 48 : 60);
        int sectionNamesIndex = unsignedShort(header, elfClass == 1 ? 50 : 62);
        int minimumSectionEntrySize = elfClass == 1 ? 40 : 64;
        if (sectionEntrySize < minimumSectionEntrySize || sectionCount == 0
                || sectionNamesIndex >= sectionCount
                || !strictWithin(elf, sectionOffset, multiplyExact(sectionEntrySize, sectionCount))) {
            throw new IllegalArgumentException("ELF section table is invalid");
        }
        int namesSection = strictOffset(sectionOffset + (long) sectionNamesIndex * sectionEntrySize);
        long stringsOffset = sectionOffset(header, namesSection, elfClass);
        long stringsSize = sectionSize(header, namesSection, elfClass);
        if (!strictWithin(elf, stringsOffset, stringsSize)) {
            throw new IllegalArgumentException("ELF section-name table is invalid");
        }
        for (int index = 0; index < sectionCount; index++) {
            int section = strictOffset(sectionOffset + (long) index * sectionEntrySize);
            int nameOffset = header.getInt(section);
            if (nameOffset < 0 || !strictWithin(elf, stringsOffset + nameOffset, 1)) {
                throw new IllegalArgumentException("ELF section name is invalid");
            }
            String sectionName = readStrictString(
                    elf,
                    strictOffset(stringsOffset + nameOffset),
                    strictOffset(stringsOffset + stringsSize));
            if (!".note.gnu.build-id".equals(sectionName)) {
                continue;
            }
            long noteOffset = sectionOffset(header, section, elfClass);
            long noteSize = sectionSize(header, section, elfClass);
            if (!strictWithin(elf, noteOffset, noteSize)) {
                throw new IllegalArgumentException("ELF GNU note section is invalid");
            }
            return strictGnuBuildId(elf, byteOrder, noteOffset, noteSize);
        }
        return null;
    }

    private static String strictGnuBuildId(byte[] elf, ByteOrder byteOrder, long noteOffset, long noteSize) {
        ByteBuffer notes = ByteBuffer.wrap(elf).order(byteOrder);
        long cursor = noteOffset;
        long end = addExact(noteOffset, noteSize);
        while (cursor < end) {
            if (end - cursor < 12) {
                throw new IllegalArgumentException("ELF GNU note is truncated");
            }
            int namesz = notes.getInt(strictOffset(cursor));
            int descsz = notes.getInt(strictOffset(cursor + 4));
            int type = notes.getInt(strictOffset(cursor + 8));
            if (namesz < 0 || descsz < 0) {
                throw new IllegalArgumentException("ELF GNU note has an oversized field");
            }
            long nameOffset = addExact(cursor, 12);
            long descriptionOffset = align4Strict(addExact(nameOffset, namesz));
            long next = align4Strict(addExact(descriptionOffset, descsz));
            if (next > end || !strictWithin(elf, nameOffset, namesz)
                    || !strictWithin(elf, descriptionOffset, descsz)) {
                throw new IllegalArgumentException("ELF GNU note exceeds its section");
            }
            if (type == 3 && namesz == 4
                    && elf[strictOffset(nameOffset)] == 'G'
                    && elf[strictOffset(nameOffset + 1)] == 'N'
                    && elf[strictOffset(nameOffset + 2)] == 'U'
                    && elf[strictOffset(nameOffset + 3)] == 0) {
                byte[] buildId = new byte[descsz];
                System.arraycopy(elf, strictOffset(descriptionOffset), buildId, 0, descsz);
                return hex(buildId);
            }
            cursor = next;
        }
        return null;
    }

    private static long unsignedLong(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("ELF offset exceeds supported bounds");
        }
        return value;
    }

    private static long multiplyExact(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("ELF table size overflows", error);
        }
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("ELF offset overflows", error);
        }
    }

    private static long align4Strict(long value) {
        return addExact(value, 3) & ~3L;
    }

    private static boolean strictWithin(byte[] bytes, long offset, long size) {
        return offset >= 0 && size >= 0 && offset <= bytes.length && size <= bytes.length - offset;
    }

    private static int strictOffset(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ELF offset is out of range");
        }
        return (int) value;
    }

    private static String readStrictString(byte[] bytes, int offset, int end) {
        int cursor = offset;
        while (cursor < end && bytes[cursor] != 0) {
            cursor++;
        }
        if (cursor == end) {
            throw new IllegalArgumentException("ELF string is unterminated");
        }
        return new String(bytes, offset, cursor - offset, StandardCharsets.US_ASCII);
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
                    .append(", \"source\": \"").append(identity.source())
                    .append("\", \"abi\": \"").append(identity.abi())
                    .append("\", \"symbolCount\": ").append(identity.symbols().size()).append('}');
        }
        return json.toString();
    }

    private static String nullableJson(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String escapeTsv(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
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
