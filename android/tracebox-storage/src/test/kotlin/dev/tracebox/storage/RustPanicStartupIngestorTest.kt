package dev.tracebox.storage

import dev.tracebox.api.Crc32c
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.core.ControlPage
import dev.tracebox.core.GateResult
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.WriterPolicyGate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RustPanicStartupIngestorTest {
    @Test
    fun completed_slot_is_appended_once_and_durably_reset() {
        val root = Files.createTempDirectory("tracebox-rust-panic")
        val page = ControlPage(root.resolve("policy")).also {
            it.commit(PolicySnapshot(7, 0))
        }
        val gate = WriterPolicyGate(page).also { assertEquals(GateResult.Reloaded, it.reload()) }
        val segment = root.resolve("records.tbseg")
        val writer = SegmentWriter.create(
            segment,
            SegmentHeader(
                PersistedSegmentIdentity(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
                ByteArray(32) { 3 },
                7,
                0,
                1,
            ),
            gate,
            RoleQuotaLedger(RoleQuotaPolicy(mapOf(1 to 64 * 1024)), root.resolve("roles")),
        )
        val adapter = GeneratedRecordSegmentAdapter(writer, gate)
        val slot = root.resolve("rust-panic.slot")
        Files.write(slot, validSlot())

        assertIs<RustPanicIngestionResult.Ingested>(
            RustPanicStartupIngestor(slot, adapter).ingest(),
        )
        val frame = SegmentWriter.recover(segment, repair = false).frames.single()
        assertEquals(GeneratedEventId.RUSTPANIC.stableId, frame.recordType)
        assertEquals(16, frame.payload.size)
        assertTrue(Files.readAllBytes(slot).all { it == 0.toByte() })
        assertEquals(
            RustPanicIngestionResult.InvalidOrIncomplete,
            RustPanicStartupIngestor(slot, adapter).ingest(),
        )
    }

    @Test
    fun corrupt_or_incomplete_slot_is_never_fabricated_or_reset() {
        val root = Files.createTempDirectory("tracebox-rust-panic-invalid")
        val page = ControlPage(root.resolve("policy")).also {
            it.commit(PolicySnapshot(7, 0))
        }
        val gate = WriterPolicyGate(page).also { assertEquals(GateResult.Reloaded, it.reload()) }
        val segment = root.resolve("records.tbseg")
        val writer = SegmentWriter.create(
            segment,
            SegmentHeader(
                PersistedSegmentIdentity(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
                ByteArray(32) { 3 },
                7,
                0,
                1,
            ),
            gate,
            RoleQuotaLedger(RoleQuotaPolicy(mapOf(1 to 64 * 1024)), root.resolve("roles")),
        )
        val adapter = GeneratedRecordSegmentAdapter(writer, gate)
        val slot = root.resolve("rust-panic.slot")
        val corrupt = validSlot().also { it[28] = 9 }
        Files.write(slot, corrupt)

        assertEquals(
            RustPanicIngestionResult.InvalidOrIncomplete,
            RustPanicStartupIngestor(slot, adapter).ingest(),
        )
        assertTrue(SegmentWriter.recover(segment, repair = false).frames.isEmpty())
        assertTrue(Files.readAllBytes(slot).contentEquals(corrupt))
    }

    private fun validSlot(): ByteArray {
        val bytes = ByteArray(64)
        "TBRUSTP1".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(8, 1)
            putInt(12, 64)
            putLong(16, 7)
            putInt(24, 3)
            putInt(28, 2)
            putInt(32, 1)
            putInt(36, 19)
            putInt(40, 5)
            putInt(44, 0x1234_5678)
            putInt(52, Crc32c.value(bytes, 0, 52))
            putLong(56, 0x5442_5255_5354_434fL)
        }
        return bytes
    }
}
