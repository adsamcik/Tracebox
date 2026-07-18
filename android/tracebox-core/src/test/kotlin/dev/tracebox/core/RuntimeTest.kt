package dev.tracebox.core

import dev.tracebox.api.Readiness
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeTest {
    private val bootstrap = object : MinimalBootstrap {
        override fun installJvmWrapper() = Unit
        override fun startOrConnectHandler() = Unit
        override fun installEmergencyFallback() = Unit
    }

    @AfterTest fun reset() = TraceboxInstaller.resetForTest()

    @Test fun install_is_volatile_not_durable_and_identical_install_is_idempotent() {
        val configuration = TraceboxConfiguration(ByteArray(32) { 7 }, 1)
        val first = assertIs<InstallResult.Installed>(TraceboxInstaller.install(configuration, bootstrap)).runtime
        assertEquals(Readiness.VOLATILE_CAPTURE, first.readiness.value)
        assertIs<InstallResult.Reused>(
            TraceboxInstaller.install(TraceboxConfiguration(ByteArray(32) { 7 }, 1), bootstrap),
        )
        assertEquals(RuntimeResult.PolicyNotLoaded, first.durableAfterPolicyLoad(false))
        assertEquals(Readiness.VOLATILE_CAPTURE, first.readiness.value)
    }

    @Test fun conflicting_configuration_is_typed() {
        TraceboxInstaller.install(TraceboxConfiguration(ByteArray(32) { 1 }, 1), bootstrap)
        assertIs<InstallResult.ConflictingConfiguration>(
            TraceboxInstaller.install(TraceboxConfiguration(ByteArray(32) { 2 }, 1), bootstrap),
        )
    }

    @Test fun control_page_persists_and_gate_rejects_stale_writers_and_append_time_tightening() {
        val dir = Files.createTempDirectory("tracebox-core")
        val page = ControlPage(dir.resolve("control"))
        page.commit(PolicySnapshot(1, 0))
        val gate = WriterPolicyGate(page)
        assertEquals(GateResult.Reloaded, gate.reload())
        val record = assertIs<GateAcceptance.Accepted>(
            gate.accept(1, RecordPriority.BREADCRUMB, byteArrayOf(1)),
        ).record
        page.commit(PolicySnapshot(2, 1))
        assertEquals(GateResult.StaleRecord, gate.appendAllowed(record))
        assertIs<GateAcceptance.Rejected>(gate.accept(2, RecordPriority.BREADCRUMB, byteArrayOf(2))).also {
            assertEquals(GateResult.StaleWriter, it.reason)
        }
    }

    @Test fun bounded_queue_prioritizes_and_barrier_preserves_allowed_records() {
        val health = HealthCounters()
        val queue = BoundedPolicyQueue(2, health)
        fun record(mask: Long, priority: RecordPriority, epoch: Long = 1) =
            PolicyTaggedRecord(mask, epoch, priority, byteArrayOf())
        repeat(100) { queue.enqueue(record(0, RecordPriority.ORDINARY_EVENT)) }
        assertTrue(queue.size() <= 2)
        queue.enqueue(record(0, RecordPriority.CRASH_ANR))
        queue.enqueue(record(1, RecordPriority.POLICY_HEALTH))
        assertTrue(queue.size() <= 2)
        val dropped = queue.barrier(PolicySnapshot(1, 1))
        assertTrue(dropped >= 1)
        queue.resume()
        assertEquals(RecordPriority.CRASH_ANR, queue.dequeue()?.priority)
    }
}
