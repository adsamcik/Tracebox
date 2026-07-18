package dev.tracebox.directboot

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
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
        val record = C0DirectBootRecord(ByteArray(32), 1, 2, 3, 4, 5)
        assertEquals(DirectBootWriteResult.DISABLED, store.append(record))
        mirror.writePending(DenyState(1, false, 0))
        mirror.promotePending()
        assertEquals(DirectBootWriteResult.REJECTED_NON_C0, store.appendClassified(PrivacyClass.C1, record))
        assertEquals(DirectBootWriteResult.WRITTEN, store.append(record))
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

    private class CrashBoundary : RuntimeException()
}
