package dev.tracebox.storage

import dev.tracebox.core.ControlPage
import dev.tracebox.core.GateResult
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.PolicyTaggedRecord
import dev.tracebox.core.RecordPriority
import dev.tracebox.core.WriterPolicyGate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SegmentStoreTest {
    private fun directory(): Path = Path.of("build", "phase2-tests", UUID.randomUUID().toString()).also(Files::createDirectories)
    private fun header(seed: Byte, role: Int = 1) = SegmentHeader(
        PersistedSegmentIdentity(ByteArray(32) { seed }, ByteArray(32) { (seed + 1).toByte() }),
        ByteArray(32) { 9 },
        1,
        0,
        role,
    )

    private data class WriterHarness(val writer: SegmentWriter, val page: ControlPage)

    private fun writer(path: Path, header: SegmentHeader, quota: Long = 1_000_000): WriterHarness {
        val page = ControlPage(path.resolveSibling("${path.fileName}.control"))
        page.commit(PolicySnapshot(1, 0))
        val gate = WriterPolicyGate(page)
        assertEquals(GateResult.Reloaded, gate.reload())
        return WriterHarness(
            SegmentWriter.create(path, header, gate, RoleQuotaLedger(RoleQuotaPolicy(mapOf(header.processRole to quota)), path.parent)),
            page,
        )
    }

    private fun record(
        payload: ByteArray,
        category: Long = 1,
        epoch: Long = 1,
        priority: RecordPriority = RecordPriority.BREADCRUMB,
    ) = PolicyTaggedRecord(category, epoch, priority, payload)

    @Test fun segment_recovers_valid_prefix_at_every_tail_boundary_and_is_immutable_when_sealed() {
        val dir = directory()
        val source = dir.resolve("source.tbseg")
        writer(source, header(1)).writer.use {
            assertIs<SegmentAppendResult.Appended>(it.append(3, record(byteArrayOf(1, 2))))
            assertIs<SegmentAppendResult.Appended>(it.append(4, record(byteArrayOf(3))))
            it.seal()
            assertFailsWith<SegmentException.Sealed> { it.append(3, record(byteArrayOf())) }
        }
        val bytes = Files.readAllBytes(source)
        for (cut in bytes.indices) {
            val candidate = dir.resolve("cut-$cut.tbseg")
            Files.write(candidate, bytes.copyOf(cut))
            if (cut < 124) {
                assertFailsWith<SegmentException.InvalidHeader> { SegmentWriter.recover(candidate) }
            } else {
                val recovered = SegmentWriter.recover(candidate)
                assertTrue(recovered.frames.size in 0..2)
                assertTrue(recovered.frames.zipWithNext().all { it.second.sequence == it.first.sequence + 1 })
            }
        }
        assertEquals(2, SegmentWriter.recover(source).frames.size)
    }

    @Test fun writer_directly_drops_stale_policy_tagged_record_without_writing_frame() {
        val path = directory().resolve("stale.tbseg")
        val harness = writer(path, header(2))
        val accepted = record(byteArrayOf(1, 2, 3), category = 1, epoch = 1)
        harness.page.commit(PolicySnapshot(2, 1))
        assertEquals(SegmentAppendResult.Dropped(GateResult.StaleRecord), harness.writer.append(3, accepted))
        assertTrue(SegmentWriter.recover(path).frames.isEmpty())
    }

    @Test fun unsealed_frame_with_physical_size_of_current_seal_is_recovered_as_frame() {
        val path = directory().resolve("fifty-two-byte-frame.tbseg")
        writer(path, header(3)).writer.use {
            assertIs<SegmentAppendResult.Appended>(it.append(3, record(ByteArray(32) { 7 })))
        }
        val recovered = SegmentWriter.recover(path)
        assertFalse(recovered.sealed)
        assertFalse(recovered.corruptionDetected)
        assertEquals(176, Files.size(path)) // 124-byte header + (4 length + 12 body + 32 payload + 4 CRC)
        assertEquals(32, recovered.frames.single().payload.size)
        assertEquals(0, recovered.frames.single().sequence)
    }

    @Test fun sealing_at_role_quota_boundary_charges_the_seal_without_exceeding_quota() {
        val quota = 196L // 124-byte header + 20-byte empty frame + 52-byte seal
        val path = directory().resolve("quota-boundary.tbseg")
        writer(path, header(9), quota).writer.use {
            assertIs<SegmentAppendResult.Appended>(it.append(3, record(byteArrayOf())))
            it.seal()
        }
        val recovered = SegmentWriter.recover(path)
        assertTrue(recovered.sealed)
        assertFalse(recovered.corruptionDetected)
        assertEquals(1, recovered.frames.size)
        assertEquals(quota, Files.size(path))
    }

    @Test fun corrupt_length_crc_and_sequence_are_quarantined_to_affected_segment() {
        val dir = directory()
        val good = dir.resolve("good.tbseg")
        val bad = dir.resolve("bad.tbseg")
        listOf(good, bad).forEachIndexed { index, path ->
            writer(path, header((index + 3).toByte())).writer.use {
                assertIs<SegmentAppendResult.Appended>(it.append(3, record(byteArrayOf(1, 2, 3))))
            }
        }
        val bytes = Files.readAllBytes(bad)
        bytes[124] = 0x7f
        Files.write(bad, bytes)
        val recovered = SegmentWriter.recover(bad)
        assertTrue(recovered.corruptionDetected)
        assertEquals(0, recovered.frames.size)
        assertEquals(1, SegmentWriter.recover(good).frames.size)
    }

    @Test fun new_process_instances_have_independent_sequence_domains() {
        val dir = directory()
        writer(dir.resolve("one.tbseg"), header(4)).writer.use {
            assertIs<SegmentAppendResult.Appended>(it.append(3, record(byteArrayOf())))
        }
        writer(dir.resolve("two.tbseg"), header(5)).writer.use {
            assertIs<SegmentAppendResult.Appended>(it.append(3, record(byteArrayOf())))
        }
        assertEquals(0, SegmentWriter.recover(dir.resolve("one.tbseg")).frames.single().sequence)
        assertEquals(0, SegmentWriter.recover(dir.resolve("two.tbseg")).frames.single().sequence)
    }

    @Test fun role_quota_is_reconstructed_for_a_brand_new_process_instance() {
        val dir = directory()
        val quota = 441L
        val first = writer(dir.resolve("first.tbseg"), header(6), quota)
        assertIs<SegmentAppendResult.Appended>(first.writer.append(3, record(ByteArray(100))))
        first.writer.close()

        val second = writer(dir.resolve("second.tbseg"), header(7), quota)
        assertIs<SegmentAppendResult.Appended>(second.writer.append(3, record(byteArrayOf(1))))
        assertEquals(
            SegmentAppendResult.DroppedQuota(RecordPriority.BREADCRUMB),
            second.writer.append(3, record(byteArrayOf(2))),
        )
    }

    @Test fun role_quota_policy_keeps_unknown_roles_at_zero_without_explicit_fallback() {
        val policy = RoleQuotaPolicy(mapOf(1 to 100))
        assertEquals(QuotaDecision.Allowed, policy.allow(1, 0, 80, RecordPriority.BREADCRUMB))
        assertTrue(policy.allow(1, 80, 30, RecordPriority.HANDLED_ERROR) is QuotaDecision.Dropped)
        assertTrue(policy.allow(99, 0, 1, RecordPriority.ORDINARY_EVENT) is QuotaDecision.Dropped)
        assertEquals(QuotaDecision.Allowed, RoleQuotaPolicy(mapOf(1 to 100), fallbackRole = 1).allow(99, 0, 1, RecordPriority.ORDINARY_EVENT))
    }

    @Test fun corrupt_or_stale_index_falls_back_to_authoritative_segment_scan() {
        val dir = directory()
        writer(dir.resolve("one.tbseg"), header(8)).writer.use {
            assertIs<SegmentAppendResult.Appended>(it.append(3, record(byteArrayOf())))
        }
        val budget = UidAccounting(UidQuota(mapOf(UidBucket.METADATA to 1024L)), mapOf(UidBucket.METADATA to 1))
        val indexPath = dir.resolve("segments.tbidx")
        val index = SegmentMetadataIndex(indexPath, budget)
        val direct = index.plan(dir)
        assertTrue(index.rebuild(dir))
        Files.writeString(indexPath, "missing.tbseg,999")
        assertEquals(direct, index.plan(dir))
        Files.writeString(indexPath, "one.tbseg,not-a-count")
        assertEquals(direct, index.plan(dir))
    }

    @Test fun deletion_journal_resumes_after_each_transition_and_never_completes_with_accessible_data() {
        DeletionState.entries.filter { it != DeletionState.COMPLETE && it != DeletionState.PENDING_FAILURE }.forEach { stop ->
            val dir = directory()
            Files.write(dir.resolve("victim.tbseg"), byteArrayOf(1))
            val hooks = object : DeletionHooks {
                override fun commitDisabledEpoch() = true
                override fun quiesceWriters() = true
                override fun invalidateApprovalsAndSnapshotKeys() = Unit
                override fun closeActiveStores() = Unit
            }
            val engine = DeletionEngine(dir, dir.resolve("delete.journal"), hooks)
            assertFailsWith<DeletionInterrupted> { engine.deleteAll(DeletionCrashInjector { state -> state != stop }) }
            assertEquals(stop, engine.current())
            assertEquals(DeletionState.COMPLETE, engine.retry())
            assertFalse(Files.exists(dir.resolve("victim.tbseg")))
        }
    }

    @Test fun deletion_reports_pending_failure_and_retries_boundedly() {
        val dir = directory()
        Files.write(dir.resolve("victim.tbseg"), byteArrayOf(1))
        var permit = false
        val engine = DeletionEngine(dir, dir.resolve("delete.journal"), object : DeletionHooks {
            override fun commitDisabledEpoch() = permit
            override fun quiesceWriters() = true
            override fun invalidateApprovalsAndSnapshotKeys() = Unit
            override fun closeActiveStores() = Unit
        }, maxRetries = 1)
        assertEquals(DeletionState.PENDING_FAILURE, engine.deleteAll())
        permit = true
        assertEquals(DeletionState.COMPLETE, engine.retry())
    }
}
