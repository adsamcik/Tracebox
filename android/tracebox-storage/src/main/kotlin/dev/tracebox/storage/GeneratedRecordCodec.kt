package dev.tracebox.storage

import dev.tracebox.api.generated.GeneratedBreadcrumb
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedHandledError
import dev.tracebox.api.generated.GeneratedManagedCrash
import dev.tracebox.api.generated.GeneratedAnrCandidate
import dev.tracebox.api.generated.GeneratedOsExit
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.api.generated.GeneratedRustPanic
import dev.tracebox.api.generated.GeneratedStructuralSummary
import dev.tracebox.api.generated.GeneratedLogRecord
import dev.tracebox.api.generated.GeneratedExceptionRecord
import dev.tracebox.api.generated.GeneratedAnrTrace
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The single binary representation shared by generated-record segment writers and recovery readers. */
object GeneratedRecordCodec {
    private const val INTERNAL_IMPORT_ID_SIZE = PersistedSegmentIdentity.ID_SIZE

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
        is GeneratedManagedCrash -> ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.primary_exception_code.toInt())
            putShort(value.cause_count.toShort()).putShort(value.frame_count.toShort())
            putInt(value.flags.toInt())
        }.array()
        is GeneratedRustPanic -> ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.payload_class.toInt()).putInt(value.thread_role.toInt())
            putInt(value.location_code.toInt()).putInt(value.flags.toInt())
        }.array()
        is GeneratedAnrCandidate -> ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.elapsed_millis.toInt())
            putShort(value.sample_count.toShort()).putShort(value.frame_count.toShort())
            putInt(value.nonfatal_result.toInt()).putInt(value.flags.toInt())
        }.array()
        is GeneratedOsExit -> ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.reason).putInt(value.status).putInt(value.importance)
            putInt(value.link_confidence.toInt()).putInt(value.artifact_state.toInt())
        }.array()
        is GeneratedLogRecord -> {
            val message = boundedUtf8(value.rendered_message, MAX_LOG_MESSAGE_BYTES)
            ByteBuffer.allocate(42 + message.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(value.level.toInt()).putInt(value.category.toInt())
                putLong(value.template_fingerprint.toLong())
                putUtf8(message)
                putInt(value.privacy_flags.toInt())
                putLong(value.monotonic_time_ns.toLong()).putLong(value.duration_ns.toLong())
                putInt(value.outcome.toInt())
            }.array()
        }
        is GeneratedExceptionRecord -> {
            val type = boundedUtf8(value.exception_type, MAX_EXCEPTION_TYPE_BYTES)
            val stack = boundedUtf8(value.stack_trace, MAX_STACK_TRACE_BYTES)
            ByteBuffer.allocate(26 + type.size + stack.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(value.kind.toInt()).putUtf8(type).putShort(value.frame_count.toShort())
                putLong(value.stack_fingerprint.toLong()).putUtf8(stack)
                putLong(value.monotonic_time_ns.toLong())
            }.array()
        }
        is GeneratedAnrTrace -> {
            val stack = boundedUtf8(value.stack_trace, MAX_STACK_TRACE_BYTES)
            ByteBuffer.allocate(16 + stack.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(value.elapsed_millis.toInt()).putShort(value.frame_count.toShort())
                putLong(value.stack_fingerprint.toLong()).putUtf8(stack)
            }.array()
        }
    }

    /**
     * Decodes the actual frame payload. Idempotently imported summaries, OS exits, and Direct Boot
     * emergency records carry a durable internal source ID before the schema payload. The ID is
     * consumed here and cannot enter export data.
     */
    fun decode(recordType: Int, payload: ByteArray): GeneratedRecord {
        val event = GeneratedEventId.entries.firstOrNull { it.stableId == recordType }
            ?: throw IllegalArgumentException("unknown generated event type $recordType")
        val expectedBodySize = when (event) {
            GeneratedEventId.STRUCTURALSUMMARY -> 18
            GeneratedEventId.EMERGENCYRECORD -> 40
            GeneratedEventId.BREADCRUMB -> 12
            GeneratedEventId.HANDLEDERROR -> 6
            GeneratedEventId.MANAGEDCRASH -> 12
            GeneratedEventId.RUSTPANIC -> 16
            GeneratedEventId.ANRCANDIDATE -> 16
            GeneratedEventId.OSEXIT -> 20
            GeneratedEventId.LOGRECORD,
            GeneratedEventId.EXCEPTIONRECORD,
            GeneratedEventId.ANRTRACE -> -1
        }
        val body = if (
            event in setOf(
                GeneratedEventId.STRUCTURALSUMMARY,
                GeneratedEventId.EMERGENCYRECORD,
                GeneratedEventId.OSEXIT,
            ) &&
            payload.size == INTERNAL_IMPORT_ID_SIZE + expectedBodySize
        ) {
            payload.copyOfRange(INTERNAL_IMPORT_ID_SIZE, payload.size)
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
            GeneratedEventId.MANAGEDCRASH -> {
                require(body.size == 12)
                GeneratedManagedCrash(
                    buffer.int.toUInt(),
                    buffer.short.toUShort(),
                    buffer.short.toUShort(),
                    buffer.int.toUInt(),
                )
            }
            GeneratedEventId.RUSTPANIC -> {
                require(body.size == 16)
                GeneratedRustPanic(
                    buffer.int.toUInt(),
                    buffer.int.toUInt(),
                    buffer.int.toUInt(),
                    buffer.int.toUInt(),
                )
            }
            GeneratedEventId.ANRCANDIDATE -> {
                require(body.size == 16)
                GeneratedAnrCandidate(
                    buffer.int.toUInt(),
                    buffer.short.toUShort(),
                    buffer.short.toUShort(),
                    buffer.int.toUInt(),
                    buffer.int.toUInt(),
                )
            }
            GeneratedEventId.OSEXIT -> {
                require(body.size == 20)
                GeneratedOsExit(
                    buffer.int,
                    buffer.int,
                    buffer.int,
                    buffer.int.toUInt(),
                    buffer.int.toUInt(),
                )
            }
            GeneratedEventId.LOGRECORD -> {
                require(body.size >= 42)
                GeneratedLogRecord(
                    buffer.int.toUInt(),
                    buffer.int.toUInt(),
                    buffer.long.toULong(),
                    buffer.readUtf8(MAX_LOG_MESSAGE_BYTES),
                    buffer.int.toUInt(),
                    buffer.long.toULong(),
                    buffer.long.toULong(),
                    buffer.int.toUInt(),
                ).also { require(!buffer.hasRemaining()) }
            }
            GeneratedEventId.EXCEPTIONRECORD -> {
                require(body.size >= 26)
                GeneratedExceptionRecord(
                    buffer.int.toUInt(),
                    buffer.readUtf8(MAX_EXCEPTION_TYPE_BYTES),
                    buffer.short.toUShort(),
                    buffer.long.toULong(),
                    buffer.readUtf8(MAX_STACK_TRACE_BYTES),
                    buffer.long.toULong(),
                ).also { require(!buffer.hasRemaining()) }
            }
            GeneratedEventId.ANRTRACE -> {
                require(body.size >= 16)
                GeneratedAnrTrace(
                    buffer.int.toUInt(),
                    buffer.short.toUShort(),
                    buffer.long.toULong(),
                    buffer.readUtf8(MAX_STACK_TRACE_BYTES),
                ).also { require(!buffer.hasRemaining()) }
            }
        }
    }

    private fun boundedUtf8(value: String, maximumBytes: Int): ByteArray =
        value.toByteArray(Charsets.UTF_8).also { require(it.size <= maximumBytes) }

    private fun ByteBuffer.putUtf8(value: ByteArray): ByteBuffer {
        require(value.size <= UShort.MAX_VALUE.toInt())
        putShort(value.size.toShort())
        return put(value)
    }

    private fun ByteBuffer.readUtf8(maximumBytes: Int): String {
        require(remaining() >= UShort.SIZE_BYTES)
        val size = short.toUShort().toInt()
        require(size <= maximumBytes && remaining() >= size)
        val value = ByteArray(size)
        get(value)
        return value.toString(Charsets.UTF_8)
    }

    private const val MAX_LOG_MESSAGE_BYTES = 1_024
    private const val MAX_EXCEPTION_TYPE_BYTES = 256
    private const val MAX_STACK_TRACE_BYTES = 2_048
}
