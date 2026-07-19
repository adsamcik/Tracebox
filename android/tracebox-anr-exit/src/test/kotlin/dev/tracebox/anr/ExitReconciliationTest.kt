package dev.tracebox.anr

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExitReconciliationTest {
    private val exit = SyntheticApplicationExitInfo(
        packageName = "dev.tracebox.app",
        processName = "dev.tracebox.app:worker",
        definingUid = 10_001,
        timestampMillis = 1_000,
        reason = 6,
        status = 9,
        importance = 100,
        pid = 42,
        processStateSummary = byteArrayOf(7, 8),
        artifactKind = ExitArtifactKind.ANR_TRACE,
    )

    @Test fun exact_source_key_is_durable_across_ledger_reconstruction_and_exhaustion_never_evicts() {
        val path = Files.createTempDirectory("tracebox-exit").resolve("tombstones")
        val ledger = ExitTombstoneLedger(path, maxEntries = 1, maxBytes = 128)
        val key = ExitSourceKey.derive(exit)
        val secondKey = ExitSourceKey.derive(exit.copy(timestampMillis = 1_001))

        assertEquals(ExitImportResult.IMPORTED, ledger.record(key))
        assertEquals(ExitImportResult.ALREADY_IMPORTED, ledger.record(key))
        assertEquals(ExitImportResult.DISABLED_EXHAUSTED, ledger.record(secondKey))
        assertTrue(ledger.imported(key))
        assertEquals(1, ledger.entryCount())

        val restarted = ExitTombstoneLedger(path, maxEntries = 1, maxBytes = 128)
        assertTrue(restarted.imported(key))
        assertEquals(ExitImportResult.ALREADY_IMPORTED, restarted.record(key))
        assertEquals(ExitImportResult.DISABLED_EXHAUSTED, restarted.record(secondKey))
        assertFalse(restarted.imported(secondKey))
    }

    @Test fun source_key_distinguishes_every_documented_exit_identity_field() {
        val original = ExitSourceKey.derive(exit)

        assertNotEquals(original, ExitSourceKey.derive(exit.copy(packageName = "dev.tracebox.other")))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(processName = "dev.tracebox.app:other")))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(definingUid = 10_002)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(timestampMillis = 1_001)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(reason = 7)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(status = 10)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(importance = 101)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(pid = 43)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(processStateSummary = byteArrayOf(8, 7))))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(artifactKind = ExitArtifactKind.NATIVE_TOMBSTONE)))
    }

    @Test fun linker_preserves_explicit_confidence_without_turning_watchdog_candidate_into_confirmation() {
        val exact = LocalExitEvidence(exit.processName, 1001, exit.reason, 99, byteArrayOf(7, 8))
        val probable = exact.copy(processInstanceToken = null)
        val possible = exact.copy(timestampMillis = 500_000, reason = 8, pid = exit.pid, processInstanceToken = null)

        assertEquals(ExitLinkConfidence.EXACT, ExitLinker.link(exit, exact))
        assertEquals(ExitLinkConfidence.PROBABLE, ExitLinker.link(exit, probable))
        assertEquals(ExitLinkConfidence.POSSIBLE, ExitLinker.link(exit, possible))
        assertEquals(ExitLinkConfidence.UNMATCHED, ExitLinker.link(exit, null))
    }
}
