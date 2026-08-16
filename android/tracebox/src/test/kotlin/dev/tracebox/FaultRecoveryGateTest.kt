package dev.tracebox

import dev.tracebox.storage.UidWideQuotaCoordinator
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaultRecoveryGateTest {
    @Test
    fun handler_death_callbacks_coalesce_until_serialized_recovery_completes() {
        val gate = CoalescingRecoveryGate()

        assertTrue(gate.request())
        repeat(16) { assertFalse(gate.request()) }
        assertTrue(gate.isPending())

        gate.complete()

        assertFalse(gate.isPending())
        assertTrue(gate.request())
    }

    @Test
    fun storage_pressure_requires_an_explicit_bounded_recovery_attempt() {
        val gate = StoragePressureRecoveryGate()

        assertTrue(gate.acceptsWrites())
        gate.pressure()
        assertFalse(gate.acceptsWrites())
        assertTrue(gate.beginRecovery())
        assertFalse(gate.beginRecovery())
        assertFalse(gate.completeRecovery(success = false))
        assertTrue(gate.beginRecovery())
        assertTrue(gate.completeRecovery(success = true))
        assertTrue(gate.acceptsWrites())
    }

    @Test
    fun pressure_during_recovery_prevents_a_stale_success_from_reopening_writes() {
        val gate = StoragePressureRecoveryGate()
        gate.pressure()
        assertTrue(gate.beginRecovery())

        gate.pressure()

        assertFalse(gate.completeRecovery(success = true))
        assertFalse(gate.acceptsWrites())
        assertTrue(gate.beginRecovery())
    }

    @Test
    fun only_physical_io_and_unavailable_quota_ledgers_trip_storage_pressure() {
        assertTrue(isStoragePressureFailure(IOException("disk full")))
        assertTrue(
            isStoragePressureFailure(
                UidWideQuotaCoordinator.UidQuotaLedgerException.Unavailable(IOException("disk full")),
            ),
        )
        assertFalse(
            isStoragePressureFailure(UidWideQuotaCoordinator.UidQuotaLedgerException.MetadataExhausted),
        )
        assertFalse(isStoragePressureFailure(IllegalStateException("ordinary failure")))
    }
}
