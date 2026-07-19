package dev.tracebox.export.ui

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.export.DeterministicZip
import dev.tracebox.export.InternalIdentity
import dev.tracebox.export.PackagePipelineResult
import dev.tracebox.export.SegmentSource
import dev.tracebox.export.SnapshotPreparer
import dev.tracebox.export.SourceRecord
import dev.tracebox.export.StandardPackagePipeline
import dev.tracebox.export.StandardSnapshotRequest
import dev.tracebox.export.ui.testing.ApprovalTestBypass
import dev.tracebox.storage.UidAccounting
import dev.tracebox.storage.UidBucket
import dev.tracebox.storage.UidQuota
import java.io.ByteArrayOutputStream
import java.nio.file.Files
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

    private fun request(payload: ByteArray = ByteArray(12_000) { 7 }): StandardSnapshotRequest {
        val segment = identity(2)
        return StandardSnapshotRequest(
            7,
            mapOf(segment to 10),
            listOf(SegmentSource(identity(1), segment, 3, listOf(
                SourceRecord(0, GeneratedEventId.BREADCRUMB, payload, 100, dev.tracebox.export.PackagePrivacyClass.C1),
            ))),
        )
    }

    private fun pipeline(request: StandardSnapshotRequest, limit: Long = 1_000_000): PackagePipelineResult =
        StandardPackagePipeline(
            SnapshotPreparer(accounting(limit), Path.of("build", "phase4-workflow", System.nanoTime().toString())),
            DeterministicZip(),
        ).finalize(request)

    private fun approved(request: StandardSnapshotRequest = request()): ApprovedPackage {
        val finalized = assertIs<PackagePipelineResult.Ready>(pipeline(request))
        return ApprovalTestBypass.confirm(DisclosureRenderer.rendered(finalized.packageBytes))
    }

    @Test fun disclosure_decodes_exact_finalized_bytes_and_restoration_requires_a_fresh_confirmation() {
        val finalized = assertIs<PackagePipelineResult.Ready>(pipeline(request()))
        val exact = finalized.packageBytes.exactBytes()
        val disclosure = assertIs<DisclosureDecodeResult.Decoded>(DisclosureRenderer.render(finalized.packageBytes))
        assertContentEquals(finalized.packageBytes.plaintextSha256(), disclosure.facts.plaintextDigest)
        assertEquals(1, disclosure.facts.includedCount)
        assertEquals(emptyList(), disclosure.facts.rawC2Artifacts)
        assertContentEquals(exact, finalized.packageBytes.exactBytes())

        val original = TraceboxDisclosureActivity.DisclosureActivityStateMachine()
        original.restore(finalized.packageBytes)
        assertTrue(original.confirmFreshGesture() != null)
        val restored = TraceboxDisclosureActivity.DisclosureActivityStateMachine()
        restored.restore(finalized.packageBytes)
        assertNull(restored.approvalForTestOnly())
        assertTrue(restored.confirmFreshGesture() != null)
    }

    @Test fun failed_actual_pipeline_has_no_approved_or_shareable_receipt_path() {
        val failed = assertIs<PackagePipelineResult.Failed>(pipeline(request(), limit = 1))
        val receipt = ReceiptFactory.fromPipeline(failed)
        assertIs<GenerationResult.Failed>(receipt.generation)
        assertNull(receipt.approvedPlaintextDigest)
        assertNull(receipt.outputDigest)
        assertEquals(ShareHandoffState.NOT_STARTED, receipt.handoff)
    }

    @Test fun staging_lease_expires_and_the_provider_scope_is_only_the_named_staging_directory() {
        var now = 100L
        val root = Path.of("build", "phase4-workflow", "tracebox-export-staging")
        Files.createDirectories(root)
        val manager = StagingLeaseManager(root) { now }
        val lease = manager.stage(approved(), ttlMillis = 50)
        assertTrue(Files.exists(lease.path))
        now = 150
        assertEquals(listOf(lease.path), manager.cleanupExpired())
        assertTrue(!Files.exists(lease.path))
        assertFailsWith<IllegalArgumentException> {
            StagingLeaseManager(Path.of("build", "phase4-workflow", "broad-files")) { now }
        }
        assertEquals("tracebox-export-staging", StagingLeaseManager.STAGING_DIRECTORY)
        assertEquals(
            setOf("NOT_STARTED", "CHOOSER_OPENED", "TARGET_SELECTED", "DELIVERY_UNKNOWN"),
            ShareHandoffState.entries.map { it.name }.toSet(),
        )
    }

    @Test fun cancelled_saf_copy_is_a_typed_partial_warning_never_a_complete_or_delivery_result() {
        val approved = approved()
        var cancellation = false
        val result = SafPackageSaver().copyFinalized(
            approved,
            { ByteArrayOutputStream() },
            { cancellation },
            { cancellation = true },
        )
        val partial = assertIs<SaveResult.PartialCopyWarning>(result)
        assertTrue(partial.cancelled)
        assertTrue(partial.bytesWritten > 0)
        val receipt = ReceiptFactory.approved(approved).copy(save = result, cancellationObserved = true)
        assertTrue(receipt.save !is SaveResult.Complete)
        assertEquals(ShareHandoffState.NOT_STARTED, receipt.handoff)
    }

    @Test fun approval_pins_materialized_bytes_and_rejects_regeneration_after_source_changes() {
        val mutablePayload = byteArrayOf(1, 2, 3)
        val source = request(mutablePayload)
        val approved = approved(source)
        val pinned = approved.exactBytes()
        mutablePayload[0] = 99
        val changed = assertIs<PackagePipelineResult.Ready>(pipeline(source)).packageBytes.exactBytes()

        assertContentEquals(pinned, approved.exactBytes())
        assertIs<GenerationResult.ApprovalMismatch>(ReceiptFactory.regenerate(approved, changed))
        assertIs<GenerationResult.Finalized>(ReceiptFactory.regenerate(approved, pinned))
    }
}
