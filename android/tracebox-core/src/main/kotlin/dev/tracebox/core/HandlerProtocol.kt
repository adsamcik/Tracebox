package dev.tracebox.core

import dev.tracebox.api.Crc32c
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Versioned registration sent before a client receives the handler's Crashpad transport.
 *
 * Array inputs are copied so a caller cannot mutate an authenticated registration after it has
 * been validated or queued for transport.
 */
class HandlerRegistrationMessage(
    val participantId: String,
    val processRole: Int,
    processInstanceId: ByteArray,
    val acceptedPolicyEpoch: Long,
    schemaFingerprint: ByteArray,
    val coexistencePolicy: CrashCoexistencePolicy,
) {
    private val storedProcessInstanceId: ByteArray = processInstanceId.copyOf()
    private val storedSchemaFingerprint: ByteArray = schemaFingerprint.copyOf()
    val processInstanceId: ByteArray get() = storedProcessInstanceId.copyOf()
    val schemaFingerprint: ByteArray get() = storedSchemaFingerprint.copyOf()

    init {
        val participantBytes = participantId.toByteArray(StandardCharsets.UTF_8)
        require(participantId.isNotBlank())
        require(participantBytes.size <= HandlerProtocol.MAX_PARTICIPANT_ID_BYTES)
        require(processRole >= 0)
        require(storedProcessInstanceId.size == HandlerProtocol.IDENTITY_BYTES)
        require(acceptedPolicyEpoch >= 0)
        require(storedSchemaFingerprint.size == HandlerProtocol.IDENTITY_BYTES)
    }

    override fun equals(other: Any?): Boolean =
        other is HandlerRegistrationMessage &&
            participantId == other.participantId &&
            processRole == other.processRole &&
            storedProcessInstanceId.contentEquals(other.storedProcessInstanceId) &&
            acceptedPolicyEpoch == other.acceptedPolicyEpoch &&
            storedSchemaFingerprint.contentEquals(other.storedSchemaFingerprint) &&
            coexistencePolicy == other.coexistencePolicy

    override fun hashCode(): Int {
        var result = participantId.hashCode()
        result = 31 * result + processRole
        result = 31 * result + storedProcessInstanceId.contentHashCode()
        result = 31 * result + acceptedPolicyEpoch.hashCode()
        result = 31 * result + storedSchemaFingerprint.contentHashCode()
        return 31 * result + coexistencePolicy.hashCode()
    }
}

/** Ordered policy commands accepted by the bounded handler transport. */
enum class HandlerPolicyPhase(val wireCode: Int) {
    STAGE(1),
    BARRIER(2),
    COMMIT(3),
    ABORT(4);

    companion object {
        internal fun fromWireCode(value: Int): HandlerPolicyPhase? = entries.firstOrNull { it.wireCode == value }
    }
}

/** A target policy is carried on every command; acknowledgements never infer mutable state. */
data class HandlerPolicyMessage(
    val phase: HandlerPolicyPhase,
    val target: PolicySnapshot,
) {
    init {
        require(target.epoch > 0)
    }
}

enum class HandlerProtocolError {
    TRUNCATED,
    TOO_LARGE,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    WRONG_MESSAGE_TYPE,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    INVALID_FIELD,
}

sealed interface HandlerProtocolDecodeResult<out T> {
    data class Decoded<T>(val message: T) : HandlerProtocolDecodeResult<T>
    data class Rejected(val reason: HandlerProtocolError) : HandlerProtocolDecodeResult<Nothing>
}

/**
 * Fixed-endian, length-delimited handler protocol. Decoding validates the complete envelope before
 * allocating participant text and rejects trailing bytes, unknown versions, and invalid UTF-8.
 */
object HandlerProtocol {
    const val VERSION = 1
    const val IDENTITY_BYTES = 32
    const val MAX_PARTICIPANT_ID_BYTES = 96
    const val MAX_MESSAGE_BYTES = 256

    private const val MAGIC = 0x54424850
    private const val REGISTRATION_TYPE = 1
    private const val POLICY_TYPE = 2
    private const val HEADER_BYTES = 16
    private const val REGISTRATION_FIXED_PAYLOAD_BYTES =
        Short.SIZE_BYTES + Short.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES +
            IDENTITY_BYTES + IDENTITY_BYTES
    private const val POLICY_PAYLOAD_BYTES =
        Int.SIZE_BYTES + Long.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES

    fun encodeRegistration(message: HandlerRegistrationMessage): ByteArray {
        val participant = message.participantId.toByteArray(StandardCharsets.UTF_8)
        val size = HEADER_BYTES + REGISTRATION_FIXED_PAYLOAD_BYTES + participant.size
        check(size <= MAX_MESSAGE_BYTES)
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        writeHeader(buffer, REGISTRATION_TYPE, size)
        buffer.putShort(participant.size.toShort())
        buffer.putShort(coexistenceWireCode(message.coexistencePolicy).toShort())
        buffer.putInt(message.processRole)
        buffer.putLong(message.acceptedPolicyEpoch)
        buffer.put(message.processInstanceId)
        buffer.put(message.schemaFingerprint)
        buffer.put(participant)
        writePayloadChecksum(bytes)
        return bytes
    }

    fun decodeRegistration(bytes: ByteArray): HandlerProtocolDecodeResult<HandlerRegistrationMessage> {
        val payload = when (val envelope = decodeEnvelope(bytes, REGISTRATION_TYPE)) {
            is EnvelopeResult.Accepted -> envelope.payload
            is EnvelopeResult.Rejected -> return HandlerProtocolDecodeResult.Rejected(envelope.reason)
        }
        if (payload.remaining() < REGISTRATION_FIXED_PAYLOAD_BYTES) {
            return HandlerProtocolDecodeResult.Rejected(HandlerProtocolError.TRUNCATED)
        }
        val participantLength = payload.short.toInt() and 0xffff
        val coexistence = coexistenceFromWireCode(payload.short.toInt() and 0xffff)
            ?: return HandlerProtocolDecodeResult.Rejected(HandlerProtocolError.INVALID_FIELD)
        val processRole = payload.int
        val epoch = payload.long
        val processInstance = ByteArray(IDENTITY_BYTES).also(payload::get)
        val schema = ByteArray(IDENTITY_BYTES).also(payload::get)
        if (participantLength !in 1..MAX_PARTICIPANT_ID_BYTES ||
            participantLength != payload.remaining() ||
            processRole < 0 ||
            epoch < 0
        ) {
            return HandlerProtocolDecodeResult.Rejected(HandlerProtocolError.INVALID_FIELD)
        }
        val participantBytes = ByteArray(participantLength).also(payload::get)
        val participant = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(participantBytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return HandlerProtocolDecodeResult.Rejected(HandlerProtocolError.INVALID_FIELD)
        }
        return try {
            HandlerProtocolDecodeResult.Decoded(
                HandlerRegistrationMessage(participant, processRole, processInstance, epoch, schema, coexistence),
            )
        } catch (_: IllegalArgumentException) {
            HandlerProtocolDecodeResult.Rejected(HandlerProtocolError.INVALID_FIELD)
        }
    }

    fun encodePolicy(message: HandlerPolicyMessage): ByteArray {
        val size = HEADER_BYTES + POLICY_PAYLOAD_BYTES
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        writeHeader(buffer, POLICY_TYPE, size)
        buffer.putInt(message.phase.wireCode)
        buffer.putLong(message.target.epoch)
        buffer.putLong(message.target.denyMask)
        buffer.putInt(if (message.target.disabled) 1 else 0)
        writePayloadChecksum(bytes)
        return bytes
    }

    fun decodePolicy(bytes: ByteArray): HandlerProtocolDecodeResult<HandlerPolicyMessage> {
        val payload = when (val envelope = decodeEnvelope(bytes, POLICY_TYPE)) {
            is EnvelopeResult.Accepted -> envelope.payload
            is EnvelopeResult.Rejected -> return HandlerProtocolDecodeResult.Rejected(envelope.reason)
        }
        if (payload.remaining() != POLICY_PAYLOAD_BYTES) {
            return HandlerProtocolDecodeResult.Rejected(HandlerProtocolError.SIZE_MISMATCH)
        }
        val phase = HandlerPolicyPhase.fromWireCode(payload.int)
            ?: return HandlerProtocolDecodeResult.Rejected(HandlerProtocolError.INVALID_FIELD)
        val epoch = payload.long
        val denyMask = payload.long
        val disabledValue = payload.int
        if (epoch <= 0 || disabledValue !in 0..1) {
            return HandlerProtocolDecodeResult.Rejected(HandlerProtocolError.INVALID_FIELD)
        }
        return HandlerProtocolDecodeResult.Decoded(
            HandlerPolicyMessage(phase, PolicySnapshot(epoch, denyMask, disabledValue == 1)),
        )
    }

    private fun decodeEnvelope(bytes: ByteArray, expectedType: Int): EnvelopeResult {
        if (bytes.size < HEADER_BYTES) return EnvelopeResult.Rejected(HandlerProtocolError.TRUNCATED)
        if (bytes.size > MAX_MESSAGE_BYTES) return EnvelopeResult.Rejected(HandlerProtocolError.TOO_LARGE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MAGIC) return EnvelopeResult.Rejected(HandlerProtocolError.BAD_MAGIC)
        val version = buffer.short.toInt() and 0xffff
        if (version != VERSION) return EnvelopeResult.Rejected(HandlerProtocolError.UNSUPPORTED_VERSION)
        val type = buffer.short.toInt() and 0xffff
        if (type != expectedType) return EnvelopeResult.Rejected(HandlerProtocolError.WRONG_MESSAGE_TYPE)
        val declaredSize = buffer.int
        if (declaredSize != bytes.size) return EnvelopeResult.Rejected(HandlerProtocolError.SIZE_MISMATCH)
        val expectedChecksum = buffer.int
        val actualChecksum = Crc32c.value(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
        if (expectedChecksum != actualChecksum) {
            return EnvelopeResult.Rejected(HandlerProtocolError.CHECKSUM_MISMATCH)
        }
        return EnvelopeResult.Accepted(
            ByteBuffer.wrap(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN),
        )
    }

    private fun writeHeader(buffer: ByteBuffer, type: Int, size: Int) {
        buffer.putInt(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(type.toShort())
        buffer.putInt(size)
        buffer.putInt(0)
    }

    private fun writePayloadChecksum(bytes: ByteArray) {
        val checksum = Crc32c.value(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, checksum)
    }

    private fun coexistenceWireCode(value: CrashCoexistencePolicy): Int = when (value) {
        CrashCoexistencePolicy.EXCLUSIVE -> 1
        CrashCoexistencePolicy.BEST_EFFORT_CHAIN -> 2
        CrashCoexistencePolicy.DISABLE_ON_CONFLICT -> 3
    }

    private fun coexistenceFromWireCode(value: Int): CrashCoexistencePolicy? = when (value) {
        1 -> CrashCoexistencePolicy.EXCLUSIVE
        2 -> CrashCoexistencePolicy.BEST_EFFORT_CHAIN
        3 -> CrashCoexistencePolicy.DISABLE_ON_CONFLICT
        else -> null
    }

    private sealed interface EnvelopeResult {
        data class Accepted(val payload: ByteBuffer) : EnvelopeResult
        data class Rejected(val reason: HandlerProtocolError) : EnvelopeResult
    }
}
