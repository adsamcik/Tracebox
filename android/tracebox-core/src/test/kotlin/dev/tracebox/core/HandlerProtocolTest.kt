package dev.tracebox.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HandlerProtocolTest {
    @Test fun registration_round_trip_is_versioned_bounded_and_defensively_copied() {
        val process = ByteArray(32) { it.toByte() }
        val schema = ByteArray(32) { (it + 32).toByte() }
        val message = HandlerRegistrationMessage(
            participantId = "app:worker",
            processRole = 7,
            processInstanceId = process,
            acceptedPolicyEpoch = 42,
            schemaFingerprint = schema,
            coexistencePolicy = CrashCoexistencePolicy.BEST_EFFORT_CHAIN,
        )
        process.fill(0)
        schema.fill(0)

        val encoded = HandlerProtocol.encodeRegistration(message)
        val decoded = assertIs<HandlerProtocolDecodeResult.Decoded<HandlerRegistrationMessage>>(
            HandlerProtocol.decodeRegistration(encoded),
        ).message

        assertEquals(message, decoded)
        assertContentEquals(ByteArray(32) { it.toByte() }, decoded.processInstanceId)
        val returned = decoded.processInstanceId
        returned.fill(0)
        assertContentEquals(ByteArray(32) { it.toByte() }, decoded.processInstanceId)
    }

    @Test fun policy_round_trip_carries_the_complete_target_on_every_phase() {
        HandlerPolicyPhase.entries.forEach { phase ->
            val expected = HandlerPolicyMessage(phase, PolicySnapshot(9, 0x55, disabled = phase == HandlerPolicyPhase.COMMIT))
            val decoded = assertIs<HandlerProtocolDecodeResult.Decoded<HandlerPolicyMessage>>(
                HandlerProtocol.decodePolicy(HandlerProtocol.encodePolicy(expected)),
            )
            assertEquals(expected, decoded.message)
        }
    }

    @Test fun malformed_wrong_version_wrong_type_size_and_checksum_are_typed_rejections() {
        val valid = HandlerProtocol.encodeRegistration(
            HandlerRegistrationMessage(
                "writer",
                1,
                ByteArray(32) { 1 },
                2,
                ByteArray(32) { 2 },
                CrashCoexistencePolicy.EXCLUSIVE,
            ),
        )

        assertRejected(HandlerProtocolError.TRUNCATED, valid.copyOf(8))
        assertRejected(HandlerProtocolError.TOO_LARGE, ByteArray(HandlerProtocol.MAX_MESSAGE_BYTES + 1))
        assertRejected(HandlerProtocolError.BAD_MAGIC, valid.copyOf().also { it[0] = 0 })
        assertRejected(HandlerProtocolError.UNSUPPORTED_VERSION, valid.copyOf().also { it[4] = 2 })
        assertEquals(
            HandlerProtocolError.WRONG_MESSAGE_TYPE,
            assertIs<HandlerProtocolDecodeResult.Rejected>(HandlerProtocol.decodePolicy(valid)).reason,
        )
        assertRejected(HandlerProtocolError.SIZE_MISMATCH, valid.copyOf().also { it[8] = 1 })
        assertRejected(HandlerProtocolError.CHECKSUM_MISMATCH, valid.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
    }

    private fun assertRejected(expected: HandlerProtocolError, bytes: ByteArray) {
        assertEquals(
            expected,
            assertIs<HandlerProtocolDecodeResult.Rejected>(HandlerProtocol.decodeRegistration(bytes)).reason,
        )
    }
}
