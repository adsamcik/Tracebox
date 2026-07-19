package dev.tracebox.storage

import dev.tracebox.core.ControlPage
import dev.tracebox.core.GateResult
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.RecordPriority
import dev.tracebox.core.WriterPolicyGate
import dev.tracebox.api.generated.GeneratedStructuralSummary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.zip.CRC32C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FailureCaptureStoreTest {
    private fun directory(): Path = Path.of("build", "phase3-tests", UUID.randomUUID().toString()).also(Files::createDirectories)

    private data class WriterHarness(val writer: SegmentWriter, val page: ControlPage)

    private fun writer(path: Path, denyMask: Long = 0): WriterHarness {
        val page = ControlPage(path.resolveSibling("${path.fileName}.control"))
        page.commit(PolicySnapshot(1, denyMask))
        val gate = WriterPolicyGate(page)
        assertEquals(GateResult.Reloaded, gate.reload())
        val header = SegmentHeader(
            PersistedSegmentIdentity(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            ByteArray(32) { 3 },
            1,
            0,
            1,
        )
        return WriterHarness(
            SegmentWriter.create(path, header, gate, RoleQuotaLedger(RoleQuotaPolicy(mapOf(1 to 1_000_000)), path.parent)),
            page,
        )
    }

    @Test fun raw_capture_requires_durable_journal_and_orphans_are_deleted() {
        val root = Files.createTempDirectory("tracebox-raw")
        val store = RawArtifactStore(root, rawQuotaBytes = 128)
        val id = ByteArray(32) { 9 }

        assertTrue(store.preCapture(id, ByteArray(32) { 8 }, originRole = 3, acceptedEpoch = 4))
        assertTrue(store.commitRaw(id, byteArrayOf(1, 2, 3)))
        assertEquals(RawArtifactDisposition.STRUCTURAL_SUMMARY_ONLY, store.journal(id)!!.disposition)
        Files.write(root.resolve("orphan.tbraw"), byteArrayOf(7))
        store.deleteUnverifiableOrphans()
        assertFalse(Files.exists(root.resolve("orphan.tbraw")))
    }

    @Test fun lifecycle_journal_is_forced_before_capture_bytes_and_participates_in_deletion() {
        val root = Files.createTempDirectory("tracebox-raw-lifecycle")
        val store = RawArtifactStore(root, rawQuotaBytes = 128)
        val id = ByteArray(32) { 9 }
        var observedJournalBeforeBytes = false
        val lifecycle = CrashpadCaptureLifecycle(store)

        assertTrue(lifecycle.capture(id, ByteArray(32) { 7 }, 3, 4) {
            observedJournalBeforeBytes = store.journal(id)?.originProcessInstanceId?.contentEquals(ByteArray(32) { 7 }) == true
            byteArrayOf(1, 2, 3)
        })
        assertTrue(observedJournalBeforeBytes)

        Files.write(root.resolve("orphan.tbraw"), byteArrayOf(7))
        val deletionRoot = Files.createTempDirectory("tracebox-delete")
        val engine = DeletionEngine(
            deletionRoot,
            deletionRoot.resolve("delete.journal"),
            object : DeletionHooks {
                override fun commitDisabledEpoch() = true
                override fun quiesceWriters() = true
                override fun invalidateApprovalsAndSnapshotKeys() = Unit
                override fun closeActiveStores() = Unit
            },
            participants = listOf(RawArtifactDeletionParticipant(store, root)),
        )

        assertEquals(DeletionState.COMPLETE, engine.deleteAll())
        assertFalse(Files.exists(root.resolve("${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id)}.tbraw")))
        assertFalse(Files.exists(root.resolve("orphan.tbraw")))
    }

    @Test fun summary_replay_is_idempotent_at_all_retirement_boundaries() {
        val root = Files.createTempDirectory("tracebox-spool")
        val spool = StructuralSummarySpool(root)
        val rawId = ByteArray(32) { 3 }
        val summary = GeneratedStructuralSummary(1u, 2u, 3u, 4u, 5u)
        val id = spool.stageStructuralSummary(rawId, 1, ByteArray(32) { 4 }, summary)
        val imported = mutableSetOf<String>()

        spool.replay { summaryId, _ -> imported += summaryId }
        spool.replay { summaryId, _ -> imported += summaryId }

        assertEquals(1, imported.size)
        assertTrue(spool.isRetired(id))
    }

    @Test fun structural_summary_staging_serializes_only_generated_summary_values() {
        val spool = StructuralSummarySpool(directory())
        val summary = GeneratedStructuralSummary(1u, 2u, 3u, 4u, 5u)
        val id = spool.stageStructuralSummary(ByteArray(32) { 3 }, 1, ByteArray(32) { 4 }, summary)
        var imported: ByteArray? = null

        spool.replay { _, body -> imported = body }

        assertTrue(GeneratedRecordCodec.encode(summary).contentEquals(imported!!))
        assertTrue(spool.isRetired(id))
    }

    @Test fun summary_import_recovers_after_target_append_before_acknowledgement_without_duplicate() {
        val root = directory()
        val spool = StructuralSummarySpool(root.resolve("spool"))
        val id = spool.stageStructuralSummary(
            ByteArray(32) { 3 },
            1,
            ByteArray(32) { 4 },
            GeneratedStructuralSummary(1u, 2u, 3u, 4u, 5u),
        )
        val targetPath = root.resolve("target.tbseg")
        val harness = writer(targetPath)
        val importer = TargetSegmentSummaryImporter(root.resolve("acks"), targetPath, harness.writer)

        assertIs<SummaryImportInterrupted>(
            kotlin.runCatching {
                spool.replayToTarget(importer, SummaryImportCrashInjector { false })
            }.exceptionOrNull(),
        )
        assertEquals(1, SegmentWriter.recover(targetPath, repair = false).frames.size)
        assertFalse(spool.isRetired(id))

        spool.replayToTarget(importer)

        val recovered = SegmentWriter.recover(targetPath, repair = false)
        assertEquals(1, recovered.frames.size)
        assertTrue(recovered.sealed)
        val acknowledgement = importer.acknowledgement(id)!!
        assertEquals(id, acknowledgement.summaryId)
        assertEquals(recovered.frames.single().offset, acknowledgement.offset)
        assertTrue(spool.isRetired(id))
    }

    @Test fun startup_ingests_valid_emergency_slot_as_generated_record_and_resets_it() {
        val root = directory()
        val harness = writer(root.resolve("emergency.tbseg"))
        val adapter = GeneratedRecordSegmentAdapter(harness.writer, WriterPolicyGate(harness.page).also {
            assertEquals(GateResult.Reloaded, it.reload())
        })
        val slot = root.resolve("emergency.slot")
        Files.write(slot, validEmergencySlot())

        assertIs<EmergencyIngestionResult.Ingested>(EmergencyStartupIngestor(slot, adapter).ingest())

        val frame = SegmentWriter.recover(root.resolve("emergency.tbseg"), repair = false).frames.single()
        assertEquals(2, frame.recordType)
        assertEquals(40, frame.payload.size)
        assertEquals(7L, ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN).long)
        assertTrue(Files.readAllBytes(slot).all { it == 0.toByte() })
    }

    @Test fun startup_ignores_incomplete_emergency_slot_without_fabricating_record() {
        val root = directory()
        val harness = writer(root.resolve("invalid-emergency.tbseg"))
        val adapter = GeneratedRecordSegmentAdapter(harness.writer, WriterPolicyGate(harness.page).also {
            assertEquals(GateResult.Reloaded, it.reload())
        })
        val slot = root.resolve("invalid-emergency.slot")
        Files.write(slot, validEmergencySlot().copyOf(128))

        assertEquals(EmergencyIngestionResult.InvalidOrIncomplete, EmergencyStartupIngestor(slot, adapter).ingest())
        assertTrue(SegmentWriter.recover(root.resolve("invalid-emergency.tbseg"), repair = false).frames.isEmpty())
    }

    @Test fun uncaught_thread_exception_reaches_segment_or_reports_typed_policy_drop() {
        val root = directory()
        val harness = writer(root.resolve("jvm.tbseg"))
        val adapter = GeneratedRecordSegmentAdapter(harness.writer, WriterPolicyGate(harness.page).also {
            assertEquals(GateResult.Reloaded, it.reload())
        })
        val previousCalls = java.util.concurrent.atomic.AtomicInteger()
        val handler = JvmCaptureStorageAdapter(adapter).install(Thread.UncaughtExceptionHandler { _, _ -> previousCalls.incrementAndGet() })

        Thread({ throw IllegalStateException("not retained") }, "failure-capture-test").apply {
            uncaughtExceptionHandler = handler
            start()
            join()
        }

        assertEquals(1, previousCalls.get())
        assertIs<GeneratedRecordAppendResult.Appended>(adapter.latestResult())
        val frame = SegmentWriter.recover(root.resolve("jvm.tbseg"), repair = false).frames.single()
        assertEquals(4, frame.recordType)
        assertEquals(6, frame.payload.size)
    }

    @Test fun uncaught_thread_exception_reports_typed_drop_when_policy_denies_handled_errors() {
        val root = directory()
        val harness = writer(root.resolve("jvm-denied.tbseg"), denyMask = 8)
        val adapter = GeneratedRecordSegmentAdapter(harness.writer, WriterPolicyGate(harness.page).also {
            assertEquals(GateResult.Reloaded, it.reload())
        })
        val handler = JvmCaptureStorageAdapter(adapter).install(null)

        Thread({ throw IllegalStateException() }, "failure-capture-denied-test").apply {
            uncaughtExceptionHandler = handler
            start()
            join()
        }

        assertEquals(GeneratedRecordAppendResult.Dropped(GateResult.Denied), adapter.latestResult())
        assertTrue(SegmentWriter.recover(root.resolve("jvm-denied.tbseg"), repair = false).frames.isEmpty())
    }

    @Test fun concurrent_raw_commits_never_exceed_the_hard_quota() {
        repeat(100) { iteration ->
            val root = Files.createTempDirectory("tracebox-raw-race")
            val store = RawArtifactStore(root, rawQuotaBytes = 16)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val results = BooleanArray(2)
            val ids = listOf(ByteArray(32) { 1 }, ByteArray(32) { 2 })
            ids.forEach { id -> assertTrue(store.preCapture(id, ByteArray(32) { 8 }, 3, 4)) }
            val threads = ids.mapIndexed { index, id ->
                Thread {
                    ready.countDown()
                    start.await()
                    results[index] = store.commitRaw(id, ByteArray(16) { index.toByte() })
                }
            }
            threads.forEach(Thread::start)
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS), "iteration $iteration")
            start.countDown()
            threads.forEach { it.join(5_000) }

            assertEquals(1, results.count { it }, "iteration $iteration")
            assertTrue(Files.list(root).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".tbraw") }.mapToLong(Files::size).sum() <= 16
            })
        }
    }

    private fun validEmergencySlot(): ByteArray {
        val bytes = ByteArray(256)
        "TBEMERG1".encodeToByteArray().copyInto(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(8, 1).putInt(12, 256)
        buffer.putLong(48, 7).putLong(56, 11).putLong(64, 13)
        buffer.putInt(80, 6).putInt(84, 1)
        buffer.putLong(88, 17).putLong(96, 19).putLong(104, 23)
        buffer.putInt(112, 2).putInt(116, 3).putLong(120, 5)
        buffer.putInt(244, CRC32C().also { it.update(bytes, 0, 244) }.value.toInt())
        buffer.putLong(248, 0x5442454d434f4d50L)
        return bytes
    }
}
