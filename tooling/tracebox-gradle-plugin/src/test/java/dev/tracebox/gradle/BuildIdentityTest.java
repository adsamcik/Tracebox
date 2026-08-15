package dev.tracebox.gradle;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.gradle.testkit.runner.GradleRunner;

/** Runnable functional test proving that applying the plugin writes a real identity artifact. */
public final class BuildIdentityTest {
    private BuildIdentityTest() {}

    /** Applies the plugin in a fixture project and verifies schema/build provenance was captured. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("..", "..").toAbsolutePath().normalize();
        Path mapping =
                root.resolve(
                        "test-apps/phase0-fixture/build/outputs/mapping/noInternetRelease/mapping.txt");
        Path nativeLibraries = root.resolve("android/tracebox-native/src/main/jniLibs");
        if (!Files.isRegularFile(mapping) || !Files.isDirectory(nativeLibraries)) {
            throw new AssertionError("functional test requires the real Phase 0 release mapping and native libraries");
        }
        List<Path> patchInputs;
        try (Stream<Path> files = Files.list(root.resolve("third_party/crashpad/patches"))) {
            patchInputs = files.filter(Files::isRegularFile).sorted().toList();
        }
        BuildIdentity identity = BuildIdentityCapture.capture(
                ":app",
                "dev.tracebox.phase0",
                1,
                "0.1",
                "release",
                23,
                37,
                37,
                "version=7;gradle=9.6.1",
                "schema".getBytes(StandardCharsets.UTF_8),
                mapping,
                List.of(nativeLibraries),
                root.resolve("third_party/crashpad/source-lock.json"),
                patchInputs,
                root.resolve("Cargo.lock"),
                root.resolve("gradle/verification-metadata.xml"),
                root.resolve("test-apps/phase0-fixture/gradle.lockfile"));
        if (identity.buildId().length() != 64 || identity.r8MappingId() == null
                || identity.r8MappingSha256() == null
                || identity.crashpadSourceSha256() == null
                || identity.crashpadPatchSetSha256() == null
                || identity.rustLockSha256() == null
                || identity.dependencyVerificationSha256() == null
                || identity.dependencyLockSha256() == null
                || identity.elfBuildIds().stream().noneMatch(elf -> elf.buildId() != null)
                || identity.elfBuildIds().stream().noneMatch(elf -> "sha256".equals(elf.source()))) {
            throw new AssertionError("real R8 mapping or ELF artifact identity was not captured");
        }
        String symbolCatalog = BuildIdentityCapture.symbolCatalog(identity);
        if (!symbolCatalog.startsWith("# tracebox-symbol-catalog-v2\nbuild\t" + identity.buildId())
                || identity.elfBuildIds().stream().anyMatch(
                        elf -> !symbolCatalog.contains("native\t"
                                + BuildIdentityCapture.catalogModuleName(elf.path()) + "\t" + elf.buildId()))
                || symbolCatalog.lines()
                        .filter(line -> line.startsWith("native\t"))
                        .map(line -> line.split("\t", 3)[1])
                        .anyMatch(module -> module.contains("/") || module.contains("\\"))
                || identity.elfBuildIds().stream().noneMatch(
                        elf -> elf.symbols().stream().anyMatch(symbol -> symbol.offset() > 0))
                || identity.r8Mappings().isEmpty()
                || symbolCatalog.contains("\t<identity>\t<identity>\n")) {
            throw new AssertionError("native identities were not emitted into the reusable symbol catalog");
        }
        verifyElfParserFixtures();

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
        String emittedSymbols = Files.readString(fixture.resolve("build/tracebox/symbol-catalog.tsv"));
        if (!catalog.contains("\"projectPath\": \":\"")
                || !catalog.contains("\"schemaFingerprint\"")
                || !catalog.contains(identity.r8MappingSha256())
                || !catalog.contains("\"elfBuildIds\": [")
                || !catalog.contains("\"symbolCatalogSha256\"")
                || !catalog.contains("\"applicationId\"")
                || !catalog.contains("\"dependencyVerificationSha256\"")
                || !emittedSymbols.startsWith("# tracebox-symbol-catalog-v2\n")) {
            throw new AssertionError("plugin did not capture applying-project R8/ELF provenance");
        }
        verifyAndroidVariantSchemaConfiguration();
    }

    private static void verifyAndroidVariantSchemaConfiguration() throws Exception {
        Path fixture = Path.of("build", "android-variant-schema-functional-test");
        Files.createDirectories(fixture.resolve("identity"));
        Files.writeString(
                fixture.resolve("settings.gradle"),
                "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\n"
                        + "rootProject.name = 'android-variant-schema-fixture'\n");
        Files.writeString(
                fixture.resolve("identity/custom-events.json"),
                "{\"schema_version\":2,\"events\":[]}\n");
        Files.writeString(
                fixture.resolve("build.gradle"),
                "plugins {\n"
                        + "  id 'com.android.application'\n"
                        + "  id 'dev.tracebox.identity'\n"
                        + "}\n"
                        + "android {\n"
                        + "  namespace = 'dev.tracebox.schemafixture'\n"
                        + "  compileSdk = 37\n"
                        + "  defaultConfig {\n"
                        + "    minSdk = 23\n"
                        + "    targetSdk = 37\n"
                        + "    versionCode = 1\n"
                        + "    versionName = '1.0'\n"
                        + "  }\n"
                        + "}\n"
                        + "traceboxIdentity { schemaFile = 'identity/custom-events.json' }\n"
                        + "tasks.register('assertTraceboxVariantSchemas') {\n"
                        + "  doLast {\n"
                        + "    def expected = rootProject.file('identity/custom-events.json').canonicalFile\n"
                        + "    def captures = tasks.findAll {\n"
                        + "      it.name.startsWith('captureTraceboxBuildIdentity') &&\n"
                        + "          it.name != 'captureTraceboxBuildIdentity'\n"
                        + "    }\n"
                        + "    assert !captures.empty : 'no Android variant identity tasks were registered'\n"
                        + "    captures.each { capture ->\n"
                        + "      assert capture.schemaFile.get().asFile.canonicalFile == expected :\n"
                        + "          \"${capture.name} ignored traceboxIdentity.schemaFile\"\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n");

        GradleRunner.create()
                .withProjectDir(fixture.toFile())
                .withArguments("assertTraceboxVariantSchemas", "--offline")
                .withPluginClasspath()
                .build();
    }

    private static String gradlePath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static void verifyElfParserFixtures() {
        for (int elfClass : List.of(1, 2)) {
            for (ByteOrder order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                if (!"01020304".equals(BuildIdentityCapture.parseElfBuildIdForTest(elfFixture(elfClass, order)))) {
                    throw new AssertionError("ELF " + elfClass + " " + order + " GNU build ID was not parsed");
                }
            }
        }
        byte[] truncated = Arrays.copyOf(elfFixture(1, ByteOrder.LITTLE_ENDIAN), 40);
        assertMalformed(truncated, "truncated ELF");
        byte[] oversizedSectionOffset = elfFixture(2, ByteOrder.LITTLE_ENDIAN);
        ByteBuffer.wrap(oversizedSectionOffset).order(ByteOrder.LITTLE_ENDIAN).putLong(40, Long.MAX_VALUE);
        assertMalformed(oversizedSectionOffset, "oversized section offset");
        byte[] malformedNote = elfFixture(1, ByteOrder.BIG_ENDIAN);
        ByteBuffer.wrap(malformedNote).order(ByteOrder.BIG_ENDIAN).putInt(0xc4, 0x7fff_ffff);
        assertMalformed(malformedNote, "malformed GNU note");
    }

    private static void assertMalformed(byte[] fixture, String description) {
        try {
            BuildIdentityCapture.parseElfBuildIdForTest(fixture);
            throw new AssertionError(description + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Required: malformed ELF input must not be catalogued through a fallback parser path.
        }
    }

    private static byte[] elfFixture(int elfClass, ByteOrder order) {
        int headerSize = elfClass == 1 ? 52 : 64;
        int sectionEntrySize = elfClass == 1 ? 40 : 64;
        int sectionHeaderOffset = 0x100;
        byte[] fixture = new byte[sectionHeaderOffset + 3 * sectionEntrySize];
        ByteBuffer buffer = ByteBuffer.wrap(fixture).order(order);
        fixture[0] = 0x7f;
        fixture[1] = 'E';
        fixture[2] = 'L';
        fixture[3] = 'F';
        fixture[4] = (byte) elfClass;
        fixture[5] = (byte) (order == ByteOrder.LITTLE_ENDIAN ? 1 : 2);
        fixture[6] = 1;
        buffer.putShort(18, (short) 62);
        if (elfClass == 1) {
            buffer.putInt(32, sectionHeaderOffset);
            buffer.putShort(46, (short) sectionEntrySize);
            buffer.putShort(48, (short) 3);
            buffer.putShort(50, (short) 1);
        } else {
            buffer.putLong(40, sectionHeaderOffset);
            buffer.putShort(58, (short) sectionEntrySize);
            buffer.putShort(60, (short) 3);
            buffer.putShort(62, (short) 1);
        }
        byte[] names = "\0.shstrtab\0.note.gnu.build-id\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(names, 0, fixture, 0x80, names.length);
        buffer.putInt(0xc0, 4).putInt(0xc4, 4).putInt(0xc8, 3);
        fixture[0xcc] = 'G';
        fixture[0xcd] = 'N';
        fixture[0xce] = 'U';
        fixture[0xcf] = 0;
        fixture[0xd0] = 1;
        fixture[0xd1] = 2;
        fixture[0xd2] = 3;
        fixture[0xd3] = 4;
        writeSection(buffer, elfClass, sectionHeaderOffset + sectionEntrySize, 1, 3, 0x80, names.length);
        writeSection(buffer, elfClass, sectionHeaderOffset + 2 * sectionEntrySize, 11, 7, 0xc0, 20);
        if (headerSize >= fixture.length) {
            throw new AssertionError("invalid synthetic ELF fixture");
        }
        return fixture;
    }

    private static void writeSection(
            ByteBuffer buffer,
            int elfClass,
            int offset,
            int nameOffset,
            int type,
            int contentOffset,
            int contentSize) {
        buffer.putInt(offset, nameOffset).putInt(offset + 4, type);
        if (elfClass == 1) {
            buffer.putInt(offset + 16, contentOffset).putInt(offset + 20, contentSize);
        } else {
            buffer.putLong(offset + 24, contentOffset).putLong(offset + 32, contentSize);
        }
    }
}
