package dev.tracebox.export

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedBreadcrumb
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.api.generated.GeneratedHandledError
import dev.tracebox.api.generated.GeneratedStructuralSummary
import dev.tracebox.core.ControlPage
import dev.tracebox.core.GateResult
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.PolicyTaggedRecord
import dev.tracebox.core.RecordPriority
import dev.tracebox.core.WriterPolicyGate
import dev.tracebox.storage.GeneratedRecordCodec
import dev.tracebox.storage.PersistedSegmentIdentity
import dev.tracebox.storage.RoleQuotaLedger
import dev.tracebox.storage.RoleQuotaPolicy
import dev.tracebox.storage.SegmentHeader
import dev.tracebox.storage.SegmentWriter
import dev.tracebox.storage.StructuralSummarySpool
import dev.tracebox.storage.TargetSegmentSummaryImporter
import dev.tracebox.storage.UidAccounting
import dev.tracebox.storage.UidBucket
import dev.tracebox.storage.UidQuota
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackagePipelineTest {
    private fun identity(seed: Byte) = InternalIdentity.fromBytes(ByteArray(32) { seed })
    private fun breadcrumb(code: UInt = 7u, time: ULong = 8u) = GeneratedBreadcrumb(code, time)
    private fun handledError(kind: UInt = 9u, frames: UShort = 1u) = GeneratedHandledError(kind, frames)
    private fun sharedCborFixturePath(): Path {
        (System.getProperty("tracebox.sharedCborFixture") ?: System.getenv("TRACEBOX_SHARED_CBOR_FIXTURE"))
            ?.let { return Path.of(it) }
        var directory = Path.of("").toAbsolutePath()
        while (directory.parent != null) {
            val candidate = directory.resolve("tooling/fixtures/canonical-cbor-single-map.fixture")
            if (Files.exists(candidate)) return candidate
            directory = directory.parent
        }
        error("shared CBOR fixture not found")
    }
    private fun packageGoldenPath(): Path {
        var directory = Path.of("").toAbsolutePath()
        while (directory.parent != null) {
            val candidate = directory.resolve("tooling/fixtures/tbdiag-v1-golden.hex")
            if (Files.exists(candidate)) return candidate
            directory = directory.parent
        }
        error("package golden not found")
    }
    private fun accounting(limit: Long = 1_000_000) =
        UidAccounting(UidQuota(mapOf(UidBucket.SNAPSHOTS to limit)), mapOf(UidBucket.SNAPSHOTS to 1))

    private fun request(
        records: List<OrdinarySourceRecord> = listOf(
            OrdinarySourceRecord(0, breadcrumb(), 100, PackagePrivacyClass.C1),
            OrdinarySourceRecord(1, handledError(), 200, PackagePrivacyClass.C1),
        ),
    ): StandardSnapshotRequest {
        val segment = identity(2)
        return StandardSnapshotRequest(
            policyEpoch = 7,
            sequenceCutoffs = mapOf(segment to 10),
            segments = listOf(SegmentSource(identity(1), segment, 3, records)),
        )
    }

    @Test fun snapshot_strips_internal_ids_assigns_local_ids_and_records_corrupt_omission() {
        val snapshot = SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString()))
            .prepare(request(records = listOf(
                OrdinarySourceRecord(0, breadcrumb(), 100, PackagePrivacyClass.C1),
                OrdinarySourceRecord(1, handledError(), 200, PackagePrivacyClass.C1, valid = false),
            )))

        assertEquals(1, snapshot.entries.size)
        assertEquals(1, snapshot.entries.single().recordLocalId)
        assertEquals("records/000001.tbr", snapshot.entries.single().path)
        assertEquals(listOf(SnapshotOmission(1, 1, 1, "corrupt_ordinary_record")), snapshot.omissions)
        assertTrue(!snapshot.entries.single().bytes().asList().windowed(32).any { it.all { byte -> byte == 1.toByte() } })
    }

    @Test fun internal_identity_binary_or_hex_leak_is_rejected_by_scanner() {
        val id = identity(0x5a)
        assertFailsWith<SnapshotFailure.InternalIdentityLeak> {
            RawArtifactIdentityScanner.requireNoKnownIdentityEncoding(id.bytes(), listOf(id))
        }
    }

    @Test fun raw_artifact_identity_scanner_rejects_binary_and_text_encodings() {
        val id = identity(0x5a)
        assertFailsWith<SnapshotFailure.InternalIdentityLeak> {
            RawArtifactIdentityScanner.requireNoKnownIdentityEncoding(id.bytes(), listOf(id))
        }
        val hex = id.bytes().joinToString("") { "%02x".format(it) }.toByteArray()
        assertFailsWith<SnapshotFailure.InternalIdentityLeak> {
            RawArtifactIdentityScanner.requireNoKnownIdentityEncoding(hex, listOf(id))
        }
    }

    @Test fun finalized_zip_byte_count_is_charged_not_entry_bodies() {
        val baseline = assertIs<PackagePipelineResult.Ready>(
            StandardPackagePipeline(
                SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString())),
            ).finalize(request()),
        )
        val entryBodies = baseline.snapshot.totalBytes()
        val exactPackageBytes = baseline.packageBytes.exactBytes().size.toLong()
        assertTrue(exactPackageBytes > entryBodies)

        val insufficient = accounting(exactPackageBytes - 1)
        val failure = StandardPackagePipeline(
            SnapshotPreparer(insufficient, Path.of("build", "phase4", UUID.randomUUID().toString())),
        ).finalize(request())
        assertIs<PackagePipelineResult.Failed>(failure).also {
            assertIs<PackagePipelineFailure.Snapshot>(it.failure).also { snapshot ->
                assertIs<SnapshotFailure.StagingQuota>(snapshot.cause)
            }
        }

        val exact = accounting(exactPackageBytes)
        val ready = assertIs<PackagePipelineResult.Ready>(
            StandardPackagePipeline(
                SnapshotPreparer(exact, Path.of("build", "phase4", UUID.randomUUID().toString())),
            ).finalize(request()),
        )
        assertEquals(ready.packageBytes.exactBytes().size.toLong(), exact.used(UidBucket.SNAPSHOTS))
    }

    @Test fun ordinary_source_record_requires_a_generated_record_not_raw_bytes() {
        val raw = RawArtifactSource.captured(byteArrayOf(0x4d, 0x44), identity(9))
        assertEquals(identity(9), raw.artifactIdentity)
        val record = OrdinarySourceRecord(0, breadcrumb(), 1, PackagePrivacyClass.C1)
        assertEquals(GeneratedEventId.BREADCRUMB, record.eventId)
        // OrdinarySourceRecord(sequence, generated: GeneratedRecord, occurredAtMillis, ...) has no
        // ByteArray overload; RawArtifactSource exposes no bytes property, so raw bytes cannot enter.
    }

    @Test fun recovered_storage_adapter_selects_real_segments_and_imported_phase3_summary() {
        val root = Path.of("build", "phase4-integration", UUID.randomUUID().toString())
        Files.createDirectories(root)
        val segment = root.resolve("ordinary.tbseg")
        val page = ControlPage(root.resolve("control"))
        page.commit(PolicySnapshot(9, 0))
        val gate = WriterPolicyGate(page)
        assertEquals(GateResult.Reloaded, gate.reload())
        val writer = SegmentWriter.create(
            segment,
            SegmentHeader(
                PersistedSegmentIdentity(ByteArray(32) { 2 }, ByteArray(32) { 1 }),
                ByteArray(32) { 3 },
                9,
                0,
                7,
            ),
            gate,
            RoleQuotaLedger(RoleQuotaPolicy(mapOf(7 to 1_000_000)), root),
        )
        val breadcrumb = GeneratedBreadcrumb(42u, 9_000u)
        writer.append(
            breadcrumb.eventId.stableId,
            PolicyTaggedRecord(4L, 9, RecordPriority.BREADCRUMB, GeneratedRecordCodec.encode(breadcrumb)),
        )
        val summary = GeneratedStructuralSummary(1u, 2u, 3u, 4u, 5u)
        val spool = StructuralSummarySpool(root.resolve("spool"))
        spool.stageStructuralSummary(ByteArray(32) { 4 }, 1, ByteArray(32) { 5 }, summary)
        spool.replayToTarget(TargetSegmentSummaryImporter(root.resolve("acknowledgements"), segment, writer))

        val durableTimeMillis = 1_700_000_000_123L
        Files.setLastModifiedTime(segment, FileTime.fromMillis(durableTimeMillis))
        val request = RecoveredSnapshotRequestAdapter().build(9, 1, listOf(segment))
        val source = request.segments.single()
        assertEquals(2, source.records.size)
        assertEquals(GeneratedEventId.BREADCRUMB, source.records[0].eventId)
        assertEquals(GeneratedEventId.STRUCTURALSUMMARY, source.records[1].eventId)
        assertEquals(listOf(durableTimeMillis, durableTimeMillis), source.records.map { it.occurredAtMillis })

        val prepared = SnapshotPreparer(accounting(), root.resolve("staging")).prepare(request)
        assertEquals(2, prepared.entries.size)
        assertEquals(listOf(1, 2), prepared.entries.map { it.recordLocalId })
        assertEquals(listOf(1, 1), prepared.entries.map { it.processLocalId })
        assertEquals(listOf(1, 1), prepared.entries.map { it.segmentLocalId })
        assertContentEquals(GeneratedRecordCodec.encode(breadcrumb), prepared.entries[0].bytes().copyOfRange(32, 44))
        assertContentEquals(GeneratedRecordCodec.encode(summary), prepared.entries[1].bytes().copyOfRange(32, 50))
    }

    @Test fun recovered_storage_adapter_includes_every_uid_process_role_and_counts_processes() {
        val root = Path.of("build", "phase4-processes", UUID.randomUUID().toString())
        Files.createDirectories(root)
        val page = ControlPage(root.resolve("control"))
        page.commit(PolicySnapshot(11, 0))
        val gate = WriterPolicyGate(page)
        assertEquals(GateResult.Reloaded, gate.reload())
        val quota = RoleQuotaLedger(
            RoleQuotaPolicy(mapOf(7 to 1_000_000, 9 to 1_000_000)),
            root,
        )
        val firstPath = root.resolve("first.tbseg")
        val secondPath = root.resolve("second.tbseg")
        val firstWriter = SegmentWriter.create(
            firstPath,
            SegmentHeader(
                PersistedSegmentIdentity(ByteArray(32) { 2 }, ByteArray(32) { 1 }),
                ByteArray(32) { 3 },
                11,
                0,
                7,
            ),
            gate,
            quota,
        )
        val secondWriter = SegmentWriter.create(
            secondPath,
            SegmentHeader(
                PersistedSegmentIdentity(ByteArray(32) { 6 }, ByteArray(32) { 5 }),
                ByteArray(32) { 3 },
                11,
                0,
                9,
            ),
            gate,
            quota,
        )
        val firstRecord = GeneratedBreadcrumb(71u, 1u)
        val secondRecord = GeneratedHandledError(91u, 2u)
        firstWriter.append(
            firstRecord.eventId.stableId,
            PolicyTaggedRecord(
                0L,
                11,
                RecordPriority.BREADCRUMB,
                GeneratedRecordCodec.encode(firstRecord),
            ),
        )
        secondWriter.append(
            secondRecord.eventId.stableId,
            PolicyTaggedRecord(
                0L,
                11,
                RecordPriority.ORDINARY_EVENT,
                GeneratedRecordCodec.encode(secondRecord),
            ),
        )
        val firstTime = 1_700_000_000_100L
        val secondTime = 1_700_000_000_200L
        Files.setLastModifiedTime(firstPath, FileTime.fromMillis(firstTime))
        Files.setLastModifiedTime(secondPath, FileTime.fromMillis(secondTime))

        val request =
            RecoveredSnapshotRequestAdapter().build(11, Long.MAX_VALUE, listOf(secondPath, firstPath))

        assertEquals(setOf(7, 9), request.segments.map { it.processRole }.toSet())
        assertEquals(2, request.segments.map { it.processIdentity }.toSet().size)
        assertEquals(setOf(firstTime, secondTime), request.segments.flatMap { it.records }.map { it.occurredAtMillis }.toSet())
        val prepared = SnapshotPreparer(accounting(), root.resolve("staging")).prepare(request)
        assertEquals(2, prepared.entries.map { it.processLocalId }.toSet().size)
        assertEquals(firstTime..secondTime, prepared.sourceRangeMillis)
    }

    @Test fun standard_planning_selects_a_bounded_deterministic_recent_subset() {
        val process = identity(21)
        val segment = identity(22)
        val records = (0L until 200L).map { sequence ->
            OrdinarySourceRecord(
                sequence = sequence,
                generated = breadcrumb(sequence.toUInt(), sequence.toULong()),
                occurredAtMillis = 10_000L + sequence,
                privacyClass = PackagePrivacyClass.C1,
            )
        }
        val forward = StandardSnapshotRequest(
            policyEpoch = 12,
            sequenceCutoffs = mapOf(segment to Long.MAX_VALUE),
            segments = listOf(SegmentSource(process, segment, 7, records)),
        )
        val reverse = forward.copy(
            segments = listOf(SegmentSource(process, segment, 7, records.reversed())),
        )

        val first = SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString()))
            .prepare(forward)
        val second = SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString()))
            .prepare(reverse)

        assertEquals(SnapshotPreparer.MAX_STANDARD_RECORD_ENTRIES, first.entries.size)
        assertTrue(first.entries.size + 1 <= DeterministicZip.MAX_ENTRIES)
        assertTrue(first.totalBytes() <= SnapshotPreparer.MAX_STANDARD_SELECTED_MATERIALIZED_BYTES)
        val firstSelectedPayloads = first.entries.map { entry ->
            entry.bytes().copyOfRange(32, entry.bytes().size)
        }
        val expectedPayloads = records
            .takeLast(SnapshotPreparer.MAX_STANDARD_RECORD_ENTRIES)
            .map { GeneratedRecordCodec.encode(it.generated) }
        firstSelectedPayloads.zip(expectedPayloads).forEach { (actual, expected) ->
            assertContentEquals(expected, actual)
        }
        assertContentEquals(
            DeterministicZip().materialize(first).exactBytes(),
            DeterministicZip().materialize(second).exactBytes(),
        )
    }

    @Test fun adversarial_ties_have_a_total_order_across_every_input_permutation() {
        val process = identity(31)
        val segment = identity(32)
        val tied = listOf(
            SegmentSource(
                process,
                segment,
                9,
                listOf(
                    OrdinarySourceRecord(
                        4,
                        breadcrumb(7u, 8u),
                        1_000,
                        PackagePrivacyClass.C1,
                        artifactIdentity = identity(41),
                    ),
                ),
            ),
            SegmentSource(
                process,
                segment,
                7,
                listOf(
                    OrdinarySourceRecord(
                        4,
                        breadcrumb(7u, 8u),
                        1_000,
                        PackagePrivacyClass.C0,
                        artifactIdentity = identity(42),
                    ),
                ),
            ),
            SegmentSource(
                process,
                segment,
                8,
                listOf(
                    OrdinarySourceRecord(
                        4,
                        breadcrumb(7u, 8u),
                        1_000,
                        PackagePrivacyClass.C2,
                        valid = false,
                    ),
                ),
            ),
        )
        val permutations = listOf(
            tied,
            tied.reversed(),
            listOf(tied[1], tied[2], tied[0]),
            listOf(tied[2], tied[0], tied[1]),
        )
        val packages = permutations.map { segments ->
            val snapshot = SnapshotPreparer(
                accounting(),
                Path.of("build", "phase4", UUID.randomUUID().toString()),
            ).prepare(
                StandardSnapshotRequest(
                    policyEpoch = 13,
                    sequenceCutoffs = mapOf(segment to 4L),
                    segments = segments,
                ),
            )
            DeterministicZip().materialize(snapshot).exactBytes()
        }

        packages.drop(1).forEach { assertContentEquals(packages.first(), it) }
    }

    @Test fun schema_visibility_is_enforced() {
        assertFailsWith<SnapshotFailure.CorruptInput> {
            SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString())).prepare(
                request(listOf(OrdinarySourceRecord(0, GeneratedEmergencyRecord(0u, 0u, 0, 0, 0u, 0u, 0u), 1, PackagePrivacyClass.C0))),
            )
        }
    }

    @Test fun canonical_cbor_encodes_shared_fixture_for_the_rust_cross_language_test() {
        val fixture = Files.readAllLines(sharedCborFixturePath()).associate {
            val (key, value) = it.split("=", limit = 2)
            key to value
        }
        val encoded = CanonicalCbor.encode(
            CborValue.Map(mapOf(checkNotNull(fixture["key"]) to CborValue.Unsigned(checkNotNull(fixture["unsigned"]).toLong()))),
        )
        (System.getProperty("tracebox.crossLanguageCborOutput") ?: System.getenv("TRACEBOX_CROSS_LANGUAGE_CBOR_OUTPUT"))?.let { output ->
            Path.of(output).also { Files.createDirectories(it.parent) }.let { Files.write(it, encoded) }
        }

        val snapshot = SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString())).prepare(request())
        assertContentEquals(ManifestEncoder.encode(snapshot).bytes(), ManifestEncoder.encode(snapshot).bytes())
    }

    @Test fun canonical_source_order_assigns_identical_package_local_ids() {
        val first = SegmentSource(
            identity(9),
            identity(4),
            1,
            listOf(OrdinarySourceRecord(0, breadcrumb(1u, 1u), 20, PackagePrivacyClass.C1)),
        )
        val second = SegmentSource(
            identity(3),
            identity(2),
            1,
            listOf(OrdinarySourceRecord(0, handledError(2u, 1u), 10, PackagePrivacyClass.C1)),
        )
        val cutoffs = mapOf(first.segmentIdentity to 0L, second.segmentIdentity to 0L)
        val one = SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString()))
            .prepare(StandardSnapshotRequest(5, cutoffs, listOf(first, second)))
        val two = SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString()))
            .prepare(StandardSnapshotRequest(5, cutoffs, listOf(second, first)))
        assertContentEquals(DeterministicZip().materialize(one).exactBytes(), DeterministicZip().materialize(two).exactBytes())
    }

    @Test fun zip_is_byte_identical_and_rejects_malicious_and_boundary_inputs() {
        val snapshot = SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString())).prepare(request())
        val writer = DeterministicZip()
        val first = writer.materialize(snapshot)
        val second = writer.materialize(snapshot)
        assertContentEquals(first.exactBytes(), second.exactBytes())
        assertContentEquals(first.plaintextSha256(), second.plaintextSha256())
        val mutableCopy = first.exactBytes()
        mutableCopy[0] = 0
        assertTrue(first.exactBytes()[0] != 0.toByte())

        assertFailsWith<PackageConstructionFailure.InvalidPath> { writer.write(listOf(ZipEntryInput("../x", byteArrayOf()))) }
        assertFailsWith<PackageConstructionFailure.InvalidPath> { writer.write(listOf(ZipEntryInput("/x", byteArrayOf()))) }
        assertFailsWith<PackageConstructionFailure.InvalidPath> { writer.write(listOf(ZipEntryInput("a\\b", byteArrayOf()))) }
        assertFailsWith<PackageConstructionFailure.DuplicatePath> {
            writer.write(listOf(ZipEntryInput("a", byteArrayOf()), ZipEntryInput("a", byteArrayOf())))
        }

        assertFailsWith<PackageConstructionFailure.NestedArchive> { writer.write(listOf(ZipEntryInput("nested.zip", byteArrayOf()))) }
        assertFailsWith<PackageConstructionFailure.EntryLimit> {
            writer.write((0..128).map { ZipEntryInput("entries/$it", byteArrayOf()) })
        }
        assertFailsWith<PackageConstructionFailure.PlaintextLimit> {
            writer.write(listOf(ZipEntryInput("large", byteArrayOf(), DeterministicZip.HARD_PLAINTEXT_LIMIT + 1)))
        }
        assertFailsWith<PackageConstructionFailure.Zip64> {
            writer.write(listOf(ZipEntryInput("zip64", byteArrayOf(), 0x1_0000_0000L)))
        }
    }

    @Test fun deterministic_v1_package_matches_the_cross_language_byte_golden() {
        val snapshot = SnapshotPreparer(
            accounting(),
            Path.of("build", "phase4", UUID.randomUUID().toString()),
        ).prepare(request())
        val bytes = DeterministicZip().materialize(snapshot).exactBytes()
        (System.getenv("TRACEBOX_PACKAGE_GOLDEN_OUTPUT"))?.let { output ->
            Path.of(output).also { Files.createDirectories(it.parent) }.let { Files.write(it, bytes) }
        }
        val expectedHex = Files.readString(packageGoldenPath()).filterNot(Char::isWhitespace)
        val actualHex = bytes.joinToString("") { byte -> "%02x".format(byte) }

        assertEquals(expectedHex, actualHex)
    }

    @Test fun preview_and_approved_generation_use_the_actual_pipeline_and_have_the_same_digest() {
        fun finalize() = StandardPackagePipeline(
            SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString())),
        ).finalize(request())

        val preview = assertIs<PackagePipelineResult.Ready>(finalize())
        val approvedGeneration = assertIs<PackagePipelineResult.Ready>(finalize())

        assertContentEquals(preview.packageBytes.exactBytes(), approvedGeneration.packageBytes.exactBytes())
        assertContentEquals(preview.packageBytes.plaintextSha256(), approvedGeneration.packageBytes.plaintextSha256())
    }
}
