package dev.tracebox.storage

import dev.tracebox.api.generated.GeneratedBreadcrumb
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedHandledError
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.api.generated.GeneratedStructuralSummary
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The single binary representation shared by generated-record segment writers and recovery readers. */
object GeneratedRecordCodec {
    private const val SUMMARY_IMPORT_ID_SIZE = PersistedSegmentIdentity.ID_SIZE

    fun encode(value: GeneratedRecord): ByteArray = when (value) {
        is GeneratedStructuralSummary -> ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.stream_count.toInt()).putInt(value.thread_count.toInt())
            putInt(value.module_count.toInt()).putInt(value.exception_code.toInt())
            putShort(value.processor_architecture.toShort())
        }.array()
        is GeneratedEmergencyRecord -> ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(value.slot_sequence.toLong()).putLong(value.policy_epoch.toLong())
            putInt(value.signal_number).putInt(value.signal_code)
            putInt(value.process_role.toInt()).putInt(value.thread_role.toInt()).putLong(value.flags.toLong())
        }.array()
        is GeneratedBreadcrumb -> ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.code.toInt()).putLong(value.monotonic_time_ns.toLong())
        }.array()
        is GeneratedHandledError -> ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.kind.toInt()).putShort(value.frame_count.toShort())
        }.array()
    }

    /**
     * Decodes the actual frame payload. Imported Phase 3 summaries carry their durable summary ID
     * before the schema payload; the ID is consumed here and cannot enter export data.
     */
    fun decode(recordType: Int, payload: ByteArray): GeneratedRecord {
        val event = GeneratedEventId.entries.firstOrNull { it.stableId == recordType }
            ?: throw IllegalArgumentException("unknown generated event type $recordType")
        val body = if (event == GeneratedEventId.STRUCTURALSUMMARY && payload.size == SUMMARY_IMPORT_ID_SIZE + 18) {
            payload.copyOfRange(SUMMARY_IMPORT_ID_SIZE, payload.size)
        } else {
            payload
        }
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        return when (event) {
            GeneratedEventId.STRUCTURALSUMMARY -> {
                require(body.size == 18)
                GeneratedStructuralSummary(buffer.int.toUInt(), buffer.int.toUInt(), buffer.int.toUInt(), buffer.int.toUInt(), buffer.short.toUShort())
            }
            GeneratedEventId.EMERGENCYRECORD -> {
                require(body.size == 40)
                GeneratedEmergencyRecord(buffer.long.toULong(), buffer.long.toULong(), buffer.int, buffer.int, buffer.int.toUInt(), buffer.int.toUInt(), buffer.long.toULong())
            }
            GeneratedEventId.BREADCRUMB -> {
                require(body.size == 12)
                GeneratedBreadcrumb(buffer.int.toUInt(), buffer.long.toULong())
            }
            GeneratedEventId.HANDLEDERROR -> {
                require(body.size == 6)
                GeneratedHandledError(buffer.int.toUInt(), buffer.short.toUShort())
            }
        }
    }
}
