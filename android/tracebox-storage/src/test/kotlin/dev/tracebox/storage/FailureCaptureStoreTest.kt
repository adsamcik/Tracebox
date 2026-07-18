package dev.tracebox.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureCaptureStoreTest {
    @Test fun raw_capture_requires_durable_journal_and_orphans_are_deleted() {
        val root = Files.createTempDirectory("tracebox-raw")
        val store = RawArtifactStore(root, rawQuotaBytes = 128)
        val id = ByteArray(32) { 9 }

        assertTrue(store.preCapture(id, ByteArray(32) { 8 }, originRole = 3, acceptedEpoch = 4))
        assertTrue(store.commitRaw(id, byteArrayOf(1, 2, 3)))
        assertEquals(RawArtifactDisposition.STRUCTURAL_SUMMARY_ONLY, store.journal(id)!!.disposition)
        Files.write(root.resolve("orphan.tbraw"), byteArrayOf(7))
        store.deleteUnverifiableOrphans()
        assertFalse(Files.exists(root.resolve("orphan.tbraw")))
    }

    @Test fun lifecycle_journal_is_forced_before_capture_bytes_and_participates_in_deletion() {
        val root = Files.createTempDirectory("tracebox-raw-lifecycle")
        val store = RawArtifactStore(root, rawQuotaBytes = 128)
        val id = ByteArray(32) { 9 }
        var observedJournalBeforeBytes = false
        val lifecycle = CrashpadCaptureLifecycle(store)

        assertTrue(lifecycle.capture(id, ByteArray(32) { 7 }, 3, 4) {
            observedJournalBeforeBytes = store.journal(id)?.originProcessInstanceId?.contentEquals(ByteArray(32) { 7 }) == true
            byteArrayOf(1, 2, 3)
        })
        assertTrue(observedJournalBeforeBytes)

        Files.write(root.resolve("orphan.tbraw"), byteArrayOf(7))
        val deletionRoot = Files.createTempDirectory("tracebox-delete")
        val engine = DeletionEngine(
            deletionRoot,
            deletionRoot.resolve("delete.journal"),
            object : DeletionHooks {
                override fun commitDisabledEpoch() = true
                override fun quiesceWriters() = true
                override fun invalidateApprovalsAndSnapshotKeys() = Unit
                override fun closeActiveStores() = Unit
            },
            participants = listOf(RawArtifactDeletionParticipant(store, root)),
        )

        assertEquals(DeletionState.COMPLETE, engine.deleteAll())
        assertFalse(Files.exists(root.resolve("${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id)}.tbraw")))
        assertFalse(Files.exists(root.resolve("orphan.tbraw")))
    }

    @Test fun summary_replay_is_idempotent_at_all_retirement_boundaries() {
        val root = Files.createTempDirectory("tracebox-spool")
        val spool = StructuralSummarySpool(root)
        val rawId = ByteArray(32) { 3 }
        val body = byteArrayOf(1, 2, 3)
        val id = spool.stage(rawId, 1, ByteArray(32) { 4 }, body)
        val imported = mutableSetOf<String>()

        spool.replay { summaryId, _ -> imported += summaryId }
        spool.replay { summaryId, _ -> imported += summaryId }

        assertEquals(1, imported.size)
        assertTrue(spool.isRetired(id))
    }
}
