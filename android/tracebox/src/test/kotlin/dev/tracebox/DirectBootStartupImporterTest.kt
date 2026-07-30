package dev.tracebox

import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.directboot.DirectBootDrainStatus
import dev.tracebox.directboot.DirectBootLayout
import dev.tracebox.directboot.DirectBootRetireResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectBootStartupImporterTest {
    private val fingerprint = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun activation_and_policy_statuses_gate_ce_mutation() {
        val retryStatuses = DirectBootDrainStatus.entries - setOf(
            DirectBootDrainStatus.READY,
            DirectBootDrainStatus.POLICY_DENIED,
        )
        retryStatuses.forEach { status ->
            val source = FakeSource(status, listOf(candidate(1)))
            var sinkCalls = 0
            val report = importer().import(source) {
                sinkCalls += 1
                DirectBootCeDurability.APPENDED_DURABLE
            }

            assertFalse(report.complete, status.name)
            assertEquals(0, sinkCalls, status.name)
            assertTrue(source.retired.isEmpty(), status.name)
        }

        val denied = FakeSource(DirectBootDrainStatus.POLICY_DENIED, listOf(candidate(1)))
        var deniedSinkCalls = 0
        val deniedReport = importer().import(denied) {
            deniedSinkCalls += 1
            DirectBootCeDurability.APPENDED_DURABLE
        }
        assertTrue(deniedReport.complete)
        assertEquals(0, deniedSinkCalls)
        assertTrue(denied.retired.isEmpty())
    }

    @Test
    fun scan_is_fixed_to_capacity_and_oversized_or_schema_mismatched_batches_fail_before_append() {
        val bounded = FakeSource(
            DirectBootDrainStatus.READY,
            (0 until DirectBootLayout.RECORD_CAPACITY).map(::candidate),
        )
        val boundedReport = importer().import(bounded) {
            DirectBootCeDurability.APPENDED_DURABLE
        }
        assertEquals(DirectBootLayout.RECORD_CAPACITY, bounded.requestedMaximum)
        assertTrue(boundedReport.complete)
        assertEquals(DirectBootLayout.RECORD_CAPACITY, boundedReport.retiredRecords)

        val oversized = FakeSource(
            DirectBootDrainStatus.READY,
            (0..DirectBootLayout.RECORD_CAPACITY).map(::candidate),
        )
        var oversizedSinkCalls = 0
        val oversizedReport = importer().import(oversized) {
            oversizedSinkCalls += 1
            DirectBootCeDurability.APPENDED_DURABLE
        }
        assertFalse(oversizedReport.complete)
        assertEquals(0, oversizedSinkCalls)
        assertTrue(oversized.retired.isEmpty())

        val mismatched = FakeSource(
            DirectBootDrainStatus.READY,
            listOf(candidate(1, schema = ByteArray(32))),
        )
        var mismatchedSinkCalls = 0
        val mismatchedReport = importer().import(mismatched) {
            mismatchedSinkCalls += 1
            DirectBootCeDurability.APPENDED_DURABLE
        }
        assertFalse(mismatchedReport.complete)
        assertEquals(0, mismatchedSinkCalls)
        assertTrue(mismatched.retired.isEmpty())
    }

    @Test
    fun already_durable_ce_record_is_not_appended_again_and_can_retire() {
        val source = FakeSource(DirectBootDrainStatus.READY, listOf(candidate(1)))
        val report = importer().import(source) {
            DirectBootCeDurability.ALREADY_DURABLE
        }

        assertTrue(report.complete)
        assertEquals(1, report.durableRecords)
        assertEquals(1, report.retiredRecords)
        assertEquals(listOf(candidate(1).sourceKey), source.retired)
    }

    @Test
    fun ce_quota_failure_preserves_de_prefix_until_durable_retry() {
        val oldest = candidate(1)
        val middle = candidate(2)
        val newest = candidate(3)
        val firstSource = FakeSource(
            DirectBootDrainStatus.READY,
            listOf(oldest, middle, newest),
        )
        val durable = linkedSetOf(oldest.sourceKey, newest.sourceKey)
        val first = importer().import(firstSource) { candidate ->
            if (candidate.sourceKey in durable) {
                DirectBootCeDurability.APPENDED_DURABLE
            } else {
                DirectBootCeDurability.RETRY_REQUIRED
            }
        }

        assertFalse(first.complete)
        assertEquals(listOf(newest.sourceKey), firstSource.retired)

        val retrySource = FakeSource(
            DirectBootDrainStatus.READY,
            listOf(oldest, middle),
        )
        var appendedOnRetry = 0
        val retry = importer().import(retrySource) { candidate ->
            if (durable.add(candidate.sourceKey)) {
                appendedOnRetry += 1
                DirectBootCeDurability.APPENDED_DURABLE
            } else {
                DirectBootCeDurability.ALREADY_DURABLE
            }
        }

        assertTrue(retry.complete)
        assertEquals(1, appendedOnRetry)
        assertEquals(listOf(middle.sourceKey, oldest.sourceKey), retrySource.retired)
    }

    @Test
    fun retirement_failure_reuses_durable_ce_record_during_recovery() {
        val value = candidate(7)
        val firstSource = FakeSource(
            DirectBootDrainStatus.READY,
            listOf(value),
            retireResults = ArrayDeque(listOf(DirectBootRetireResult.STORAGE_INELIGIBLE)),
        )
        val durable = mutableSetOf<String>()
        var appendCount = 0
        val first = importer().import(firstSource) { candidate ->
            if (durable.add(candidate.sourceKey)) {
                appendCount += 1
                DirectBootCeDurability.APPENDED_DURABLE
            } else {
                DirectBootCeDurability.ALREADY_DURABLE
            }
        }
        assertFalse(first.complete)
        assertEquals(1, appendCount)

        val retrySource = FakeSource(DirectBootDrainStatus.READY, listOf(value))
        val retry = importer().import(retrySource) { candidate ->
            if (durable.add(candidate.sourceKey)) {
                appendCount += 1
                DirectBootCeDurability.APPENDED_DURABLE
            } else {
                DirectBootCeDurability.ALREADY_DURABLE
            }
        }
        assertTrue(retry.complete)
        assertEquals(1, appendCount)
        assertEquals(listOf(value.sourceKey), retrySource.retired)
    }

    @Test
    fun process_death_after_ce_force_retries_without_duplicate_append() {
        val value = candidate(9)
        val durable = mutableSetOf<String>()
        var appendCount = 0
        val interruptedSource = FakeSource(DirectBootDrainStatus.READY, listOf(value))

        assertFailsWith<SimulatedProcessDeath> {
            importer().import(interruptedSource) { candidate ->
                durable += candidate.sourceKey
                appendCount += 1
                throw SimulatedProcessDeath
            }
        }
        assertTrue(interruptedSource.retired.isEmpty())

        val retrySource = FakeSource(DirectBootDrainStatus.READY, listOf(value))
        val retry = importer().import(retrySource) { candidate ->
            if (candidate.sourceKey in durable) {
                DirectBootCeDurability.ALREADY_DURABLE
            } else {
                appendCount += 1
                DirectBootCeDurability.APPENDED_DURABLE
            }
        }

        assertTrue(retry.complete)
        assertEquals(1, appendCount)
        assertEquals(listOf(value.sourceKey), retrySource.retired)
    }

    @Test
    fun retirement_acknowledges_only_ce_durable_source_ids() {
        val first = candidate(1)
        val second = candidate(2)
        val source = FakeSource(DirectBootDrainStatus.READY, listOf(first, second))
        val report = importer().import(source) { candidate ->
            if (candidate.sourceKey == second.sourceKey) {
                DirectBootCeDurability.APPENDED_DURABLE
            } else {
                DirectBootCeDurability.RETRY_REQUIRED
            }
        }

        assertFalse(report.complete)
        assertEquals(listOf(second.sourceKey), source.retired)
        assertEquals(listOf(setOf(second.sourceKey)), source.acknowledged)
    }

    private fun importer(): DirectBootStartupImporter =
        DirectBootStartupImporter(fingerprint)

    private fun candidate(
        sequence: Int,
        schema: ByteArray = fingerprint,
    ): DirectBootImportCandidate =
        DirectBootImportCandidate(
            sourceId = ByteArray(32) { index -> (sequence * 37 + index).toByte() },
            schemaFingerprint = schema,
            record = GeneratedEmergencyRecord(
                slot_sequence = sequence.toULong(),
                policy_epoch = 1uL,
                signal_number = 6,
                signal_code = 0,
                process_role = 1u,
                thread_role = 1u,
                flags = 0uL,
            ),
        )

    private class FakeSource(
        private val status: DirectBootDrainStatus,
        private val candidates: List<DirectBootImportCandidate>,
        private val retireResults: ArrayDeque<DirectBootRetireResult> = ArrayDeque(),
    ) : DirectBootImportSource {
        var requestedMaximum: Int? = null
        val retired = mutableListOf<String>()
        val acknowledged = mutableListOf<Set<String>>()

        override fun drain(maximumRecords: Int): DirectBootImportBatch {
            requestedMaximum = maximumRecords
            return DirectBootImportBatch(status, candidates)
        }

        override fun retire(
            candidate: DirectBootImportCandidate,
            durableSourceKeys: Set<String>,
        ): DirectBootRetireResult {
            acknowledged += durableSourceKeys.toSet()
            val result = retireResults.removeFirstOrNull() ?: DirectBootRetireResult.RETIRED
            if (result == DirectBootRetireResult.RETIRED ||
                result == DirectBootRetireResult.ALREADY_RETIRED
            ) {
                retired += candidate.sourceKey
            }
            return result
        }
    }

    private data object SimulatedProcessDeath : RuntimeException()
}
