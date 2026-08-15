package dev.tracebox.core

import dev.tracebox.api.Crc32c
import dev.tracebox.api.Readiness
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test fun control_page_rejects_a_valid_prefix_with_trailing_bytes() {
        val path = Files.createTempDirectory("tracebox-control-size").resolve("control")
        val page = ControlPage(path)
        page.commit(PolicySnapshot(9, 3))
        Files.write(path, byteArrayOf(0), StandardOpenOption.APPEND)
        assertFailsWith<PolicyPageException.Corrupt> { page.committed() }
    }

    @Test fun control_page_rejects_crc_valid_negative_epoch_and_non_boolean_disabled_value() {
        val root = Files.createTempDirectory("tracebox-control-fields")
        val negativePath = root.resolve("negative")
        ControlPage(negativePath).commit(PolicySnapshot(9, 3))
        rewriteControlField(negativePath, offset = 8) { it.putLong(-1L) }
        assertFailsWith<PolicyPageException.Corrupt> { ControlPage(negativePath).committed() }

        val disabledPath = root.resolve("disabled")
        ControlPage(disabledPath).commit(PolicySnapshot(9, 3))
        rewriteControlField(disabledPath, offset = 24) { it.putInt(2) }
        assertFailsWith<PolicyPageException.Corrupt> { ControlPage(disabledPath).committed() }
    }

    @Test fun barrier_retags_permitted_records_across_a_real_epoch_transition() {
        val health = HealthCounters()
        val queue = BoundedPolicyQueue(3, health)
        fun record(mask: Long, priority: RecordPriority, epoch: Long = 1) =
            PolicyTaggedRecord(mask, epoch, priority, byteArrayOf())
        assertEquals(QueueResult.Enqueued, queue.enqueue(record(0, RecordPriority.BREADCRUMB)))
        assertEquals(QueueResult.Enqueued, queue.enqueue(record(1, RecordPriority.POLICY_HEALTH)))
        assertEquals(QueueResult.Enqueued, queue.enqueue(record(2, RecordPriority.CRASH_ANR)))
        val dropped = queue.barrier(PolicySnapshot(2, 1))
        assertEquals(1, dropped)
        queue.resume()
        val first = queue.dequeue()!!
        val second = queue.dequeue()!!
        assertEquals(setOf(RecordPriority.BREADCRUMB, RecordPriority.CRASH_ANR), setOf(first.priority, second.priority))
        assertEquals(2, first.acceptedEpoch)
        assertEquals(2, second.acceptedEpoch)
    }

    private fun rewriteControlField(
        path: java.nio.file.Path,
        offset: Int,
        write: (ByteBuffer) -> Unit,
    ) {
        val bytes = Files.readAllBytes(path)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(offset)
        write(buffer)
        buffer.putInt(28, Crc32c.value(bytes, 0, 28))
        Files.write(path, bytes)
    }
}
