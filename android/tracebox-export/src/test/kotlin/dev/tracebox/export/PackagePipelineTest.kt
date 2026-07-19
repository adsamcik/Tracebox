package dev.tracebox.export

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.storage.UidAccounting
import dev.tracebox.storage.UidBucket
import dev.tracebox.storage.UidQuota
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackagePipelineTest {
    private fun identity(seed: Byte) = InternalIdentity.fromBytes(ByteArray(32) { seed })
    private fun accounting(limit: Long = 1_000_000) =
        UidAccounting(UidQuota(mapOf(UidBucket.SNAPSHOTS to limit)), mapOf(UidBucket.SNAPSHOTS to 1))

    private fun request(
        records: List<OrdinarySourceRecord> = listOf(
            OrdinarySourceRecord(0, GeneratedEventId.BREADCRUMB, byteArrayOf(7, 8), 100, PackagePrivacyClass.C1),
            OrdinarySourceRecord(1, GeneratedEventId.HANDLEDERROR, byteArrayOf(9), 200, PackagePrivacyClass.C1),
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
                OrdinarySourceRecord(0, GeneratedEventId.BREADCRUMB, byteArrayOf(7), 100, PackagePrivacyClass.C1),
                OrdinarySourceRecord(1, GeneratedEventId.HANDLEDERROR, byteArrayOf(), 200, PackagePrivacyClass.C1, valid = false),
            )))

        assertEquals(1, snapshot.entries.size)
        assertEquals(1, snapshot.entries.single().recordLocalId)
        assertEquals("records/000001.tbr", snapshot.entries.single().path)
        assertEquals(listOf(SnapshotOmission(1, 1, 1, "corrupt_ordinary_record")), snapshot.omissions)
        assertTrue(!snapshot.entries.single().bytes().asList().windowed(32).any { it.all { byte -> byte == 1.toByte() } })
    }

    @Test fun internal_identity_binary_or_hex_leak_fails_snapshot_preparation() {
        val id = identity(0x5a)
        val segment = identity(0x2)
        val request = StandardSnapshotRequest(
            1,
            mapOf(segment to 0),
            listOf(SegmentSource(id, segment, 1, listOf(
                OrdinarySourceRecord(0, GeneratedEventId.BREADCRUMB, id.bytes(), 1, PackagePrivacyClass.C1),
            ))),
        )
        assertFailsWith<SnapshotFailure.InternalIdentityLeak> {
            SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString())).prepare(request)
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

    @Test fun raw_artifact_source_has_no_standard_request_constructor_path() {
        val raw = RawArtifactSource.captured(byteArrayOf(0x4d, 0x44), identity(9))
        assertEquals(2, raw.bytes.size)
        // SegmentSource.records is List<OrdinarySourceRecord>, not List<PackageSourceInput>;
        // therefore passing `raw` here is a Kotlin compile-time type mismatch.
    }

    @Test fun schema_visibility_is_enforced() {
        assertFailsWith<SnapshotFailure.CorruptInput> {
            SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString())).prepare(
                request(listOf(OrdinarySourceRecord(0, GeneratedEventId.EMERGENCYRECORD, byteArrayOf(), 1, PackagePrivacyClass.C0))),
            )
        }
    }

    @Test fun canonical_cbor_has_shared_golden_vector_and_reencodes_identically() {
        val golden = byteArrayOf(0xa1.toByte(), 0x61, 0x61, 0x01)
        assertContentEquals(golden, CanonicalCbor.encode(CborValue.Map(mapOf("a" to CborValue.Unsigned(1)))))

        val snapshot = SnapshotPreparer(accounting(), Path.of("build", "phase4", UUID.randomUUID().toString())).prepare(request())
        assertContentEquals(ManifestEncoder.encode(snapshot).bytes(), ManifestEncoder.encode(snapshot).bytes())
    }

    @Test fun canonical_source_order_assigns_identical_package_local_ids() {
        val first = SegmentSource(
            identity(9),
            identity(4),
            1,
            listOf(OrdinarySourceRecord(0, GeneratedEventId.BREADCRUMB, byteArrayOf(1), 20, PackagePrivacyClass.C1)),
        )
        val second = SegmentSource(
            identity(3),
            identity(2),
            1,
            listOf(OrdinarySourceRecord(0, GeneratedEventId.HANDLEDERROR, byteArrayOf(2), 10, PackagePrivacyClass.C1)),
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
