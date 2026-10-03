package dev.tracebox

import dev.tracebox.core.ControlPage
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.PolicyTaggedRecord
import dev.tracebox.core.RecordPriority
import dev.tracebox.core.WriterPolicyGate
import dev.tracebox.storage.PersistedSegmentIdentity
import dev.tracebox.storage.RoleQuotaLedger
import dev.tracebox.storage.RoleQuotaPolicy
import dev.tracebox.storage.SegmentAppendResult
import dev.tracebox.storage.SegmentException
import dev.tracebox.storage.SegmentHeader
import dev.tracebox.storage.SegmentWriter
import dev.tracebox.storage.StorageMutationEligibility
import dev.tracebox.storage.TraceboxOwnedStorageRoot
import dev.tracebox.storage.UidBucket
import dev.tracebox.storage.UidQuota
import dev.tracebox.storage.UidWideQuotaCoordinator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManagedSecondaryCaptureTest {
    @Test
    fun unchanged_managed_policy_keeps_one_writer_across_one_hundred_polls() {
        val harness = Harness()
        var rotations = 0
        var writer: SegmentWriter? = null
        repeat(100) {
            if (!maintainSecondaryCaptureIfHealthy(false, writer != null, false) {
                    error("Managed capture must not invoke native draining")
                }
            ) {
                writer?.seal()
                rotations++
                writer = harness.create(rotations)
            }
            assertIs<SegmentAppendResult.Appended>(writer!!.append(1, record()))
        }
        writer!!.seal()
        assertEquals(1, rotations)
        assertEquals(100, SegmentWriter.recover(harness.path(1), repair = false).frames.size)
    }

    @Test
    fun native_policy_still_checks_its_participant_and_drains_its_ring() {
        var drains = 0
        assertFalse(maintainSecondaryCaptureIfHealthy(true, true, false) { drains++ })
        assertFalse(maintainSecondaryCaptureIfHealthy(false, false, false) { drains++ })
        assertTrue(maintainSecondaryCaptureIfHealthy(true, true, true) { drains++ })
        assertEquals(1, drains)
    }

    @Test
    fun full_segment_quota_recovers_only_verified_empty_sealed_segments() {
        val harness = Harness()
        val retained = mutableMapOf<Path, ByteArray>()
        for (seed in 1..32) {
            val writer = harness.create(seed, mismatchedName = seed == 4)
            if (seed == 1) assertIs<SegmentAppendResult.Appended>(writer.append(1, record()))
            if (seed != 2) writer.seal()
            val path = harness.path(seed, mismatchedName = seed == 4)
            if (seed == 3) {
                val broken = Files.readAllBytes(path)
                broken[broken.lastIndex] = (broken.last().toInt() xor 1).toByte()
                Files.write(path, broken)
            }
            if (seed <= 4) retained[path] = Files.readAllBytes(path)
        }
        val journal = harness.root.resolve("identity-lifecycle-managed-v1")
        val journalBytes = byteArrayOf(4, 3, 2, 1)
        Files.write(journal, journalBytes)
        assertFailsWith<SegmentException.Quota> { harness.create(33) }

        val recovered = createSegmentWithEmptyRecovery(
            harness.root, harness.quota, StorageMutationEligibility.ALWAYS,
        ) { harness.create(33) }
        assertIs<SegmentAppendResult.Appended>(recovered.append(1, record()))
        retained.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        assertContentEquals(journalBytes, Files.readAllBytes(journal))
        for (seed in 5..32) {
            assertFalse(Files.exists(harness.path(seed)))
            assertFalse(harness.quota.owns(harness.path(seed), UidBucket.ROLE_SEGMENTS, 176L))
        }
    }

    @Test
    fun denied_storage_mutation_does_not_retire_empty_segments() {
        val harness = Harness()
        harness.create(1).seal()
        val bytes = Files.readAllBytes(harness.path(1))
        assertFailsWith<SegmentException.Quota> {
            createSegmentWithEmptyRecovery(harness.root, harness.quota, StorageMutationEligibility { false }) {
                throw SegmentException.Quota
            }
        }
        assertContentEquals(bytes, Files.readAllBytes(harness.path(1)))
        assertTrue(harness.quota.owns(harness.path(1), UidBucket.ROLE_SEGMENTS, 176L))
    }

    private class Harness {
        val root = createTempDirectory("tracebox-managed-secondary")
        val quota = UidWideQuotaCoordinator(
            root,
            UidQuota(UidBucket.entries.associateWith { 8L * 1024 * 1024 }),
            UidBucket.entries.associateWith { if (it == UidBucket.ROLE_SEGMENTS) 32 else 256 },
        )
        private val processId = ByteArray(32) { 77 }
        private val instance = root.resolve("instances").resolve(encode(processId))
        private val segments = instance.resolve("segments")
        private val gate: WriterPolicyGate

        init {
            TraceboxOwnedStorageRoot.claim(root)
            Files.createDirectories(segments)
            Files.write(instance.resolve("process-instance-id"), processId)
            val page = ControlPage(root.resolve("policy-control-v1"))
            page.commit(PolicySnapshot(1, 0))
            gate = WriterPolicyGate(page).also { it.reload() }
        }

        fun path(seed: Int, mismatchedName: Boolean = false): Path =
            segments.resolve("${encode(ByteArray(32) { (seed + if (mismatchedName) 100 else 0).toByte() })}.tbseg")

        fun create(seed: Int, mismatchedName: Boolean = false): SegmentWriter = SegmentWriter.create(
            path(seed, mismatchedName),
            SegmentHeader(PersistedSegmentIdentity(ByteArray(32) { seed.toByte() }, processId), ByteArray(32), 1, 0, 3),
            gate,
            RoleQuotaLedger(RoleQuotaPolicy(mapOf(3 to 8L * 1024 * 1024)), segments),
            quota,
        )
    }

    private companion object {
        fun record() = PolicyTaggedRecord(1, 1, RecordPriority.ORDINARY_EVENT, byteArrayOf(7))
        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
