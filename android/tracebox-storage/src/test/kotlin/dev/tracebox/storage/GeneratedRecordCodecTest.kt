package dev.tracebox.storage

import dev.tracebox.api.generated.GeneratedAnrCandidate
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.api.generated.GeneratedManagedCrash
import dev.tracebox.api.generated.GeneratedOsExit
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.api.generated.GeneratedRustPanic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GeneratedRecordCodecTest {
    @Test
    fun capture_records_round_trip_without_free_form_values() {
        val values = listOf<GeneratedRecord>(
            GeneratedManagedCrash(0x1020u, 2u, 17u, 1u),
            GeneratedRustPanic(3u, 4u, 0x5060u, 2u),
            GeneratedAnrCandidate(6_000u, 3u, 64u, 1u, 4u),
            GeneratedOsExit(6, 0, 100, 3u, 1u),
        )

        values.forEach { value ->
            val decoded = GeneratedRecordCodec.decode(
                value.eventId.stableId,
                GeneratedRecordCodec.encode(value),
            )
            assertEquals(value.eventId, decoded.eventId)
            assertEquals(GeneratedRecordCodec.encode(value).toList(), GeneratedRecordCodec.encode(decoded).toList())
        }
    }

    @Test
    fun os_exit_internal_source_identity_is_stripped_before_decode() {
        val value = GeneratedOsExit(6, 1, 200, 2u, 1u)
        val internalPayload = ByteArray(PersistedSegmentIdentity.ID_SIZE) { 0x5a } +
            GeneratedRecordCodec.encode(value)

        val decoded = assertIs<GeneratedOsExit>(
            GeneratedRecordCodec.decode(value.eventId.stableId, internalPayload),
        )

        assertEquals(value.reason, decoded.reason)
        assertEquals(value.status, decoded.status)
        assertEquals(value.importance, decoded.importance)
        assertEquals(value.link_confidence, decoded.link_confidence)
        assertEquals(value.artifact_state, decoded.artifact_state)
    }

    @Test
    fun direct_boot_emergency_internal_source_identity_is_stripped_before_decode() {
        val value = GeneratedEmergencyRecord(
            slot_sequence = 17uL,
            policy_epoch = 9uL,
            signal_number = 11,
            signal_code = 2,
            process_role = 7u,
            thread_role = 3u,
            flags = 5uL,
        )
        val internalPayload = ByteArray(PersistedSegmentIdentity.ID_SIZE) { 0x4b } +
            GeneratedRecordCodec.encode(value)

        val decoded = assertIs<GeneratedEmergencyRecord>(
            GeneratedRecordCodec.decode(value.eventId.stableId, internalPayload),
        )

        assertEquals(
            GeneratedRecordCodec.encode(value).toList(),
            GeneratedRecordCodec.encode(decoded).toList(),
        )
    }
}
