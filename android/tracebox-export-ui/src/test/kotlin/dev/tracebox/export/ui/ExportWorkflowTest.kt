package dev.tracebox.export.ui

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.export.DeterministicZip
import dev.tracebox.export.InternalIdentity
import dev.tracebox.export.OrdinarySourceRecord
import dev.tracebox.export.PackagePipelineResult
import dev.tracebox.export.PackagePrivacyClass
import dev.tracebox.export.SegmentSource
import dev.tracebox.export.SnapshotPreparer
import dev.tracebox.export.StandardPackagePipeline
import dev.tracebox.export.StandardSnapshotRequest
import dev.tracebox.storage.UidAccounting
import dev.tracebox.storage.UidBucket
import dev.tracebox.storage.UidQuota
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportWorkflowTest {
    private fun identity(seed: Byte) = InternalIdentity.fromBytes(ByteArray(32) { seed })
    private fun accounting(limit: Long = 1_000_000) =
        UidAccounting(UidQuota(mapOf(UidBucket.SNAPSHOTS to limit)), mapOf(UidBucket.SNAPSHOTS to 1))

    private fun request(): StandardSnapshotRequest {
        val segment = identity(2)
        return StandardSnapshotRequest(
            7,
            mapOf(segment to 10),
            listOf(SegmentSource(identity(1), segment, 3, listOf(
                OrdinarySourceRecord(0, GeneratedEventId.BREADCRUMB, byteArrayOf(7, 8), 100, PackagePrivacyClass.C1),
            ))),
        )
    }

    private fun pipeline(request: StandardSnapshotRequest, limit: Long = 1_000_000): PackagePipelineResult =
        StandardPackagePipeline(
            SnapshotPreparer(accounting(limit), Path.of("build", "phase4-workflow", System.nanoTime().toString())),
            DeterministicZip(),
        ).finalize(request)

    @Test fun disclosure_decodes_the_exact_finalized_bytes() {
        val finalized = assertIs<PackagePipelineResult.Ready>(pipeline(request()))
        val disclosure = assertIs<DisclosureDecodeResult.Decoded>(DisclosureRenderer.render(finalized.packageBytes))
        assertContentEquals(finalized.packageBytes.plaintextSha256(), disclosure.facts.plaintextDigest)
        assertEquals(1, disclosure.facts.includedCount)
        assertEquals(emptyList(), disclosure.facts.rawC2Artifacts)
    }

    @Test fun failed_pipeline_receipt_has_no_shareable_fields() {
        val receipt = ReceiptFactory.fromPipeline(assertIs<PackagePipelineResult.Failed>(pipeline(request(), limit = 1)))
        val failed = assertIs<ExportReceipt.GenerationFailed>(receipt)
        assertTrue(failed.cause is dev.tracebox.export.PackagePipelineFailure.Snapshot)
        // ExportReceipt.GenerationFailed has no save or handoff property; Kotlin seals this state.
    }

    @Test fun staging_root_and_handoff_states_are_narrow() {
        val root = Path.of("build", "phase4-workflow", "tracebox-export-staging")
        val manager = StagingLeaseManager(root) { 100L }
        assertTrue(manager.cleanupExpired().isEmpty())
        assertEquals("tracebox-export-staging", StagingLeaseManager.STAGING_DIRECTORY)
        assertEquals(
            setOf("NOT_STARTED", "CHOOSER_OPENED", "TARGET_SELECTED", "DELIVERY_UNKNOWN"),
            ShareHandoffState.entries.map { it.name }.toSet(),
        )
        assertNull(ReceiptFactory.fromPipeline(assertIs<PackagePipelineResult.Ready>(pipeline(request()))) as? ExportReceipt.Approved)
    }

    @Test fun staging_write_without_exact_quota_reservation_is_rejected() {
        val root = Path.of("build", "phase4-workflow", "tracebox-export-staging")
        val manager = StagingLeaseManager(root) { 100L }
        assertFailsWith<IllegalStateException> {
            manager.stageReservedForHostTest(byteArrayOf(1), { false }, {}, ttlMillis = 5)
        }
        assertTrue(!java.nio.file.Files.exists(root) || java.nio.file.Files.list(root).use { !it.findAny().isPresent })
    }

}
