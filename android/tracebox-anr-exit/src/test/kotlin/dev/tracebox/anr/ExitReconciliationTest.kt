package dev.tracebox.anr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExitReconciliationTest {
    private val exit = SyntheticApplicationExitInfo("dev.tracebox.app", 1000, 6, 42, byteArrayOf(7, 8))

    @Test fun exact_source_key_is_idempotent_and_exhaustion_never_evicts() {
        val ledger = ExitTombstoneLedger(maxEntries = 1, maxBytes = 128)
        val key = ExitSourceKey.derive(exit)

        assertEquals(ExitImportResult.IMPORTED, ledger.record(key))
        assertEquals(ExitImportResult.ALREADY_IMPORTED, ledger.record(key))
        assertEquals(
            ExitImportResult.DISABLED_EXHAUSTED,
            ledger.record(ExitSourceKey.derive(exit.copy(timestampMillis = 1001))),
        )
        assertTrue(ledger.imported(key))
        assertEquals(1, ledger.entryCount())
        assertFalse(ledger.imported(ExitSourceKey.derive(exit.copy(timestampMillis = 1001))))
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
