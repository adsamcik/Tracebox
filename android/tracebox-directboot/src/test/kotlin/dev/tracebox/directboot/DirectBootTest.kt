package dev.tracebox.directboot

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.core.BarrierAck
import dev.tracebox.core.ControlPage
import dev.tracebox.core.GlobalPolicyCoordinator
import dev.tracebox.core.PolicySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectBootTest {
    private fun directory(): Path = Path.of("build", "phase2-tests", UUID.randomUUID().toString()).also(Files::createDirectories)

    @Test fun direct_boot_is_c0_only_and_absent_mirror_fails_closed() {
        val dir = directory()
        val mirror = DenyMirror(dir.resolve("active"), dir.resolve("pending"))
        val store = DirectBootStore(dir.resolve("c0.records"), mirror)
        val record = C0DirectBootRecord(ByteArray(32), 1, 2, 3, 4, 5, 1)
        assertEquals(DirectBootWriteResult.DISABLED, store.append(record))
        mirror.writePending(DenyState(1, false, 0))
        mirror.promotePending()
        assertEquals(DirectBootWriteResult.REJECTED_NON_C0, store.appendClassified(PrivacyClass.C1, record))
        assertEquals(DirectBootWriteResult.WRITTEN, store.append(record))
    }

    @Test fun tightening_denies_only_the_targeted_c0_category() {
        val dir = directory()
        val mirror = DenyMirror(dir.resolve("active"), dir.resolve("pending"))
        val store = DirectBootStore(dir.resolve("c0.records"), mirror)
        mirror.writePending(DenyState(2, false, 1))
        mirror.promotePending()
        assertEquals(
            DirectBootWriteResult.DENIED,
            store.append(C0DirectBootRecord(ByteArray(32), 1, 2, 3, 4, 5, 1)),
        )
        assertEquals(
            DirectBootWriteResult.WRITTEN,
            store.append(C0DirectBootRecord(ByteArray(32), 1, 2, 3, 4, 5, 2)),
        )
    }

    @Test fun generated_emergency_record_is_the_only_production_direct_boot_input() {
        val dir = directory()
        val mirror = DenyMirror(dir.resolve("active"), dir.resolve("pending"))
        mirror.writePending(DenyState(1, false, 0))
        mirror.promotePending()
        val generated = GeneratedDirectBootRecord.fromEmergency(
            ByteArray(32),
            GeneratedEmergencyRecord(7u, 11u, 6, 1, 2u, 3u, 5u),
            elapsedMillis = 13,
            readinessCode = 4,
            categoryMask = 1,
        )

        assertEquals(DirectBootWriteResult.WRITTEN, DirectBootStore(dir.resolve("c0.records"), mirror).appendGenerated(generated))
    }

    @Test fun tightening_is_at_least_as_restrictive_after_every_crash_boundary() {
        DirectBootBoundary.entries.forEach { boundary ->
            val dir = directory()
            val mirror = DenyMirror(dir.resolve("active"), dir.resolve("pending"))
            mirror.writePending(DenyState(1, false, 1))
            mirror.promotePending()
            var ce = DenyState(1, false, 1)
            val coordinator = DirectBootPolicyCoordinator(mirror) { ce = it }
            val target = DenyState(2, true, 3)
            try {
                coordinator.tighten(target, DirectBootCrashInjector { if (it == boundary) throw CrashBoundary() })
            } catch (_: CrashBoundary) {
                val effective = mirror.effective()!!
                assertTrue(effective.disabled)
                assertEquals(3, effective.c0DenyMask)
            }
            assertTrue(ce.epoch <= target.epoch)
        }
    }

    @Test fun loosening_waits_for_ce_and_reconciliation_uses_most_restrictive_state() {
        val dir = directory()
        val mirror = DenyMirror(dir.resolve("active"), dir.resolve("pending"))
        mirror.writePending(DenyState(5, true, 3))
        mirror.promotePending()
        var ceCommitted = false
        DirectBootPolicyCoordinator(mirror) { ceCommitted = true }.loosen(DenyState(6, false, 0))
        assertTrue(ceCommitted)
        mirror.writePending(DenyState(7, false, 0))
        val reconciled = mirror.reconcile(DenyState(6, true, 1))
        assertTrue(reconciled.disabled)
        assertEquals(1, reconciled.c0DenyMask)
        assertNull(mirror.pending())
    }

    @Test fun handler_coordinated_ce_transition_keeps_pending_deny_on_partial_global_barrier() {
        val dir = directory()
        val mirror = DenyMirror(dir.resolve("active"), dir.resolve("pending"))
        mirror.writePending(DenyState(1, false, 0))
        mirror.promotePending()
        val page = ControlPage(dir.resolve("control"))
        page.commit(PolicySnapshot(1, 0))
        val global = GlobalPolicyCoordinator(dir.resolve("coordinator"), page, byteArrayOf(7))
        val coordinated = HandlerCoordinatedDirectBootPolicyCoordinator(mirror, global) { BarrierAck.Missing }

        val result = coordinated.tighten(DenyState(2, true, 3))

        assertEquals(DirectBootGlobalTransitionResult.PARTIAL, result)
        assertTrue(mirror.effective()!!.disabled)
        assertEquals(1L, page.committed().epoch)
    }

    private class CrashBoundary : RuntimeException()
}
