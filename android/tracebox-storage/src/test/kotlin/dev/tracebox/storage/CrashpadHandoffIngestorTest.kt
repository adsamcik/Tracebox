package dev.tracebox.storage

import dev.tracebox.api.Crc32c
import dev.tracebox.api.generated.GeneratedStructuralSummary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrashpadHandoffIngestorTest {
    @Test
    fun crashpad_import_and_lifecycle_sweeps_never_consume_os_exit_artifacts() {
        val fixture = fixture()
        val committedOsId = ByteArray(32) { (it + 91).toByte() }
        val journalOnlyOsId = ByteArray(32) { (it + 123).toByte() }
        assertTrue(
            fixture.rawStore.preCapture(
                committedOsId,
                ByteArray(32) { 7 },
                3,
                9,
                RawArtifactKind.OS_EXIT_ANR_TRACE,
            ),
        )
        assertTrue(fixture.rawStore.commitRaw(committedOsId, byteArrayOf(1, 2, 3, 4)))
        assertTrue(
            fixture.rawStore.preCapture(
                journalOnlyOsId,
                ByteArray(32) { 8 },
                4,
                10,
                RawArtifactKind.OS_EXIT_ANR_TRACE,
            ),
        )
        var summarized = false

        val import = fixture.ingestor(
            SummaryIdentityDeriver { _, _, _, _ -> ByteArray(32) { 1 } },
            CrashpadMinidumpSummarizer { _, _ ->
                summarized = true
                intArrayOf(1, 1, 1, 1, 1, 1)
            },
            DurableStructuralSummaryAppender { _, _ -> DurableSummaryAppendResult.DURABLE },
        ).ingest()
        val lifecycle = CrashpadClientLifecycleReconciler(
            fixture.root.resolve("missing-clients"),
            fixture.handoff,
            fixture.rawStore,
        ).reconcile(handlerQuiesced = true)

        assertTrue(import.outcomes.isEmpty())
        assertFalse(summarized)
        assertTrue(fixture.rawStore.containsRaw(committedOsId, RawArtifactKind.OS_EXIT_ANR_TRACE))
        assertNotNull(fixture.rawStore.journal(journalOnlyOsId))
        assertEquals(0, lifecycle.journalOnlyOrphansDeleted)
    }

    @Test
    fun imports_only_after_journal_native_summary_rust_identity_and_durable_append() {
        val fixture = fixture()
        val rawId = ByteArray(32) { (it + 1).toByte() }
        val summaryId = ByteArray(32) { (it + 41).toByte() }
        assertTrue(fixture.rawStore.preCapture(rawId, ByteArray(32) { 7 }, 3, 9))
        val handoff = fixture.handoff.resolve("${hex(rawId)}.dmp")
        Files.write(handoff, byteArrayOf(1, 2, 3, 4))
        var summarizedPath: Path? = null
        var derived = false
        var appended: GeneratedStructuralSummary? = null
        val ingestor = fixture.ingestor(
            identityDeriver = SummaryIdentityDeriver { candidateRaw, version, schema, digest ->
                assertContentEquals(rawId, candidateRaw)
                assertEquals(1, version)
                assertContentEquals(ByteArray(32) { 11 }, schema)
                assertEquals(32, digest.size)
                derived = true
                summaryId
            },
            summarizer = CrashpadMinidumpSummarizer { path, maximum ->
                summarizedPath = path
                assertEquals(CrashpadHandoffIngestor.MAXIMUM_MINIDUMP_BYTES, maximum)
                intArrayOf(5, 6, 7, -1, 0x8664, 1)
            },
            appender = DurableStructuralSummaryAppender { id, summary ->
                assertContentEquals(summaryId, id)
                appended = summary
                DurableSummaryAppendResult.DURABLE
            },
        )

        val batch = ingestor.ingest()

        val imported = assertIs<CrashpadHandoffOutcome.Imported>(batch.outcomes.single())
        assertContentEquals(rawId, imported.rawArtifactId)
        assertContentEquals(summaryId, imported.summaryId)
        assertTrue(derived)
        assertEquals(fixture.root.resolve("raw"), summarizedPath?.parent)
        assertEquals(5u, appended?.stream_count)
        assertEquals(6u, appended?.thread_count)
        assertEquals(7u, appended?.module_count)
        assertEquals(UInt.MAX_VALUE, appended?.exception_code)
        assertEquals(0x8664u.toUShort(), appended?.processor_architecture)
        assertFalse(Files.exists(handoff))
        assertFalse(fixture.rawStore.containsRaw(rawId))
        assertEquals(null, fixture.rawStore.journal(rawId))
        assertEquals(1, batch.retiredSpoolRecordsPurged)
        assertTrue(Files.list(fixture.spool).use { it.findAny().isEmpty })
    }

    @Test
    fun append_deferral_retains_every_recoverable_source_and_retry_is_idempotent() {
        val fixture = fixture()
        val rawId = ByteArray(32) { 3 }
        val summaryId = ByteArray(32) { 4 }
        assertTrue(fixture.rawStore.preCapture(rawId, ByteArray(32) { 5 }, 1, 2))
        val handoff = fixture.handoff.resolve("${hex(rawId)}.dmp")
        Files.write(handoff, byteArrayOf(9, 8, 7))
        var appendAttempts = 0
        val identity = SummaryIdentityDeriver { _, _, _, _ -> summaryId }
        val summary = CrashpadMinidumpSummarizer { _, _ -> intArrayOf(1, 2, 3, 4, 5, 1) }

        val deferred = fixture.ingestor(
            identity,
            summary,
            DurableStructuralSummaryAppender { _, _ ->
                appendAttempts++
                DurableSummaryAppendResult.RETRY
            },
        ).ingest()

        assertIs<CrashpadHandoffOutcome.Retained>(deferred.outcomes.single())
        assertFalse(Files.exists(handoff))
        assertTrue(fixture.rawStore.containsRaw(rawId))
        assertNotNull(fixture.rawStore.journal(rawId))
        assertEquals(0, deferred.retiredSpoolRecordsPurged)

        val recovered = fixture.ingestor(
            identity,
            summary,
            DurableStructuralSummaryAppender { id, _ ->
                appendAttempts++
                assertContentEquals(summaryId, id)
                DurableSummaryAppendResult.DURABLE
            },
        ).ingest()

        assertIs<CrashpadHandoffOutcome.Imported>(recovered.outcomes.single())
        assertEquals(2, appendAttempts)
        assertFalse(Files.exists(handoff))
        assertFalse(fixture.rawStore.containsRaw(rawId))
    }

    @Test
    fun recovery_finds_durable_internal_id_after_append_before_spool_retirement() {
        val fixture = fixture()
        val rawId = ByteArray(32) { 16 }
        val summaryId = ByteArray(32) { 17 }
        assertTrue(fixture.rawStore.preCapture(rawId, ByteArray(32) { 18 }, 1, 2))
        val handoff = fixture.handoff.resolve("${hex(rawId)}.dmp")
        Files.write(handoff, byteArrayOf(1, 3, 5, 7))
        val durableIds = mutableSetOf<String>()
        var physicalAppends = 0
        val identity = SummaryIdentityDeriver { _, _, _, _ -> summaryId }
        val summary = CrashpadMinidumpSummarizer { _, _ -> intArrayOf(1, 2, 3, 4, 5, 1) }

        val interrupted = fixture.ingestor(
            identity,
            summary,
            DurableStructuralSummaryAppender { id, _ ->
                physicalAppends++
                durableIds += Base64.getUrlEncoder().withoutPadding().encodeToString(id)
                throw SimulatedProcessDeath
            },
        ).ingest()

        assertIs<CrashpadHandoffOutcome.Retained>(interrupted.outcomes.single())
        assertEquals(1, physicalAppends)
        assertFalse(Files.exists(handoff))
        assertTrue(fixture.rawStore.containsRaw(rawId))

        val recovered = fixture.ingestor(
            identity,
            summary,
            DurableStructuralSummaryAppender { id, _ ->
                val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(id)
                if (encoded in durableIds) {
                    DurableSummaryAppendResult.DURABLE
                } else {
                    physicalAppends++
                    durableIds += encoded
                    DurableSummaryAppendResult.DURABLE
                }
            },
        ).ingest()

        assertIs<CrashpadHandoffOutcome.Imported>(recovered.outcomes.single())
        assertEquals(1, physicalAppends)
        assertFalse(Files.exists(handoff))
        assertFalse(fixture.rawStore.containsRaw(rawId))
    }

    @Test
    fun invalid_names_unbound_bytes_and_invalid_native_results_are_destroyed_without_parsing() {
        val fixture = fixture(maximumBytes = 4)
        var calls = 0
        val summarizer = CrashpadMinidumpSummarizer { _, _ ->
            calls++
            intArrayOf(1, 2, 3, 4, 5, 1)
        }
        val identity = SummaryIdentityDeriver { _, _, _, _ -> ByteArray(32) { 1 } }
        val appender = DurableStructuralSummaryAppender { _, _ -> DurableSummaryAppendResult.DURABLE }

        val badName = fixture.handoff.resolve("NOT-AN-ID.dmp")
        Files.write(badName, byteArrayOf(1))
        val missingJournalId = ByteArray(32) { 8 }
        val missingJournal = fixture.handoff.resolve("${hex(missingJournalId)}.dmp")
        Files.write(missingJournal, byteArrayOf(1))
        val oversizedId = ByteArray(32) { 9 }
        assertTrue(fixture.rawStore.preCapture(oversizedId, ByteArray(32) { 2 }, 1, 1))
        val oversized = fixture.handoff.resolve("${hex(oversizedId)}.dmp")
        Files.write(oversized, ByteArray(5))

        val batch = fixture.ingestor(identity, summarizer, appender).ingest()

        assertEquals(3, batch.outcomes.size)
        assertTrue(batch.outcomes.all { it is CrashpadHandoffOutcome.Destroyed })
        assertEquals(0, calls)
        assertFalse(Files.exists(badName))
        assertFalse(Files.exists(missingJournal))
        assertFalse(Files.exists(oversized))
        assertEquals(null, fixture.rawStore.journal(oversizedId))

        val invalidSummaryId = ByteArray(32) { 10 }
        assertTrue(fixture.rawStore.preCapture(invalidSummaryId, ByteArray(32) { 2 }, 1, 1))
        val invalidSummary = fixture.handoff.resolve("${hex(invalidSummaryId)}.dmp")
        Files.write(invalidSummary, byteArrayOf(1))
        val rejected = fixture.ingestor(
            identity,
            CrashpadMinidumpSummarizer { _, _ -> intArrayOf(1, 2, 3, 4, 5, 0) },
            appender,
        ).ingest()

        assertEquals(
            CrashpadHandoffFailure.INVALID_STRUCTURAL_SUMMARY,
            assertIs<CrashpadHandoffOutcome.Destroyed>(rejected.outcomes.single()).failure,
        )
        assertFalse(Files.exists(invalidSummary))
        assertEquals(null, fixture.rawStore.journal(invalidSummaryId))
    }

    @Test
    fun non_regular_handoff_and_zero_identity_fail_closed_and_batches_are_bounded() {
        val fixture = fixture()
        val directoryId = ByteArray(32) { 12 }
        assertTrue(fixture.rawStore.preCapture(directoryId, ByteArray(32) { 3 }, 1, 1))
        val directoryHandoff = fixture.handoff.resolve("${hex(directoryId)}.dmp")
        Files.createDirectory(directoryHandoff)
        val zero = fixture.handoff.resolve("${hex(ByteArray(32))}.dmp")
        Files.write(zero, byteArrayOf(1))
        Files.write(fixture.handoff.resolve("bad-one.dmp"), byteArrayOf(1))
        val ingestor = fixture.ingestor(
            SummaryIdentityDeriver { _, _, _, _ -> ByteArray(32) { 1 } },
            CrashpadMinidumpSummarizer { _, _ -> error("must not parse") },
            DurableStructuralSummaryAppender { _, _ -> DurableSummaryAppendResult.DURABLE },
        )

        val first = ingestor.ingest(maxFiles = 2)

        assertEquals(2, first.outcomes.size)
        assertTrue(first.truncated)
        assertTrue(first.outcomes.all { it is CrashpadHandoffOutcome.Destroyed })
        val second = ingestor.ingest(maxFiles = 2)
        assertFalse(second.truncated)
        assertEquals(1, second.outcomes.size)
    }

    @Test
    fun valid_precapture_journal_without_raw_survives_bounded_orphan_and_expiry_sweeps() {
        val root = Files.createTempDirectory("tracebox-raw-hardened")
        val store = RawArtifactStore(root, 1_024)
        val liveId = ByteArray(32) { 21 }
        assertTrue(store.preCapture(liveId, ByteArray(32) { 22 }, 1, 1))
        Files.write(root.resolve("not-canonical.tbraw"), byteArrayOf(1))
        Files.write(root.resolve("not-canonical.tbrawjournal"), byteArrayOf(1))
        repeat(3) { Files.write(root.resolve("invalid-$it.tbraw"), byteArrayOf(1)) }

        val firstDeleted = store.deleteUnverifiableOrphans(maxEntries = 2)
        assertTrue(firstDeleted <= 2)
        repeat(4) { store.deleteUnverifiableOrphans(maxEntries = 2) }
        store.expire(nowMillis = Long.MAX_VALUE, ttlMillis = 0, maxEntries = 8)

        assertNotNull(store.journal(liveId))
        assertFalse(Files.exists(root.resolve("not-canonical.tbraw")))
        assertFalse(Files.exists(root.resolve("not-canonical.tbrawjournal")))
        assertTrue(Files.list(root).use { paths ->
            paths.noneMatch { it.fileName.toString().endsWith(".tbraw") }
        })
    }

    @Test
    fun purging_only_durably_retired_summary_releases_uid_wide_spool_quota() {
        val root = Files.createTempDirectory("tracebox-retired-quota")
        val quota = UidQuota(UidBucket.entries.associateWith { bucket ->
            if (bucket == UidBucket.METADATA) 4_096L else 1_024L
        })
        val coordinator = UidWideQuotaCoordinator(
            root,
            quota,
            UidBucket.entries.associateWith { 16 },
        )
        val spool = StructuralSummarySpool(
            root.resolve("spool"),
            coordinator,
            SummaryIdentityDeriver { _, _, _, _ -> ByteArray(32) { 13 } },
        )
        spool.stageStructuralSummary(
            ByteArray(32) { 14 },
            1,
            ByteArray(32) { 15 },
            GeneratedStructuralSummary(1u, 2u, 3u, 4u, 5u),
        )
        assertTrue(coordinator.used(UidBucket.SUMMARY_SPOOL) > 0)

        spool.replay { _, _ -> }

        assertEquals(1, spool.purgeRetired())
        assertEquals(0L, coordinator.used(UidBucket.SUMMARY_SPOOL))
        assertEquals(0, spool.purgeRetired())
    }

    @Test
    fun handoff_adoption_transfers_max_reservation_to_actual_bytes_without_double_charge() {
        val root = Files.createTempDirectory("tracebox-handoff-transfer")
        val handoffRoot = root.resolve("handoff").also(Files::createDirectories)
        val rawRoot = root.resolve("raw").also(Files::createDirectories)
        val quota = UidQuota(UidBucket.entries.associateWith { bucket ->
            when (bucket) {
                UidBucket.RAW_ARTIFACTS -> 16L
                UidBucket.METADATA -> 4_096L
                else -> 1_024L
            }
        })
        val coordinator = UidWideQuotaCoordinator(
            root,
            quota,
            UidBucket.entries.associateWith { 16 },
        )
        val store = RawArtifactStore(rawRoot, 16L, coordinator)
        val rawId = ByteArray(32) { (it + 1).toByte() }
        val source = handoffRoot.resolve("${hex(rawId)}.dmp")
        val destination = rawRoot.resolve(
            "${Base64.getUrlEncoder().withoutPadding().encodeToString(rawId)}.tbraw",
        )
        assertTrue(store.preCapture(rawId, ByteArray(32) { 4 }, 1, 2))
        assertTrue(coordinator.reserve(source, UidBucket.RAW_ARTIFACTS, 16))
        Files.write(source, ByteArray(7) { it.toByte() })

        assertTrue(store.adoptRaw(rawId, source, 7))

        assertFalse(Files.exists(source))
        assertEquals(destination, store.committedRawPath(rawId))
        assertEquals(7L, coordinator.used(UidBucket.RAW_ARTIFACTS))
        assertTrue(coordinator.owns(destination, UidBucket.RAW_ARTIFACTS, 7))
        assertFalse(coordinator.allocations().containsKey(source))
    }

    @Test
    fun adoption_resumes_after_quota_transfer_commits_before_physical_rename() {
        val root = Files.createTempDirectory("tracebox-handoff-transfer-recovery")
        val handoffRoot = root.resolve("handoff").also(Files::createDirectories)
        val rawRoot = root.resolve("raw").also(Files::createDirectories)
        val quota = UidQuota(UidBucket.entries.associateWith { bucket ->
            if (bucket == UidBucket.METADATA) 4_096L else 32L
        })
        val coordinator = UidWideQuotaCoordinator(
            root,
            quota,
            UidBucket.entries.associateWith { 16 },
        )
        val store = RawArtifactStore(rawRoot, 32L, coordinator)
        val rawId = ByteArray(32) { (it + 17).toByte() }
        val source = handoffRoot.resolve("${hex(rawId)}.dmp")
        val destination = rawRoot.resolve(
            "${Base64.getUrlEncoder().withoutPadding().encodeToString(rawId)}.tbraw",
        )
        assertTrue(store.preCapture(rawId, ByteArray(32) { 5 }, 1, 2))
        assertTrue(coordinator.reserve(source, UidBucket.RAW_ARTIFACTS, 32))
        Files.write(source, ByteArray(9) { it.toByte() })
        assertEquals(
            UidReservationTransferResult.TRANSFERRED,
            coordinator.transfer(source, destination, UidBucket.RAW_ARTIFACTS, 9),
        )

        assertTrue(store.adoptRaw(rawId, source, 9))

        assertFalse(Files.exists(source))
        assertEquals(destination, store.committedRawPath(rawId))
        assertEquals(9L, coordinator.used(UidBucket.RAW_ARTIFACTS))
    }

    @Test
    fun lifecycle_terminal_is_crc_bound_and_cleaned_only_after_handoff_is_gone() {
        val fixture = fixture()
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val rawId = ByteArray(32) { 31 }
        val processId = ByteArray(32) { 32 }
        assertTrue(fixture.rawStore.preCapture(rawId, processId, 3, 7))
        val lifecycle = lifecycleRoot.resolve(clientFileName(3u, rawId))
        Files.write(
            lifecycle,
            clientJournal(
                registered = clientRecord(1, 123, 55u, 3u, 7uL, 9uL, processId, rawId, 100uL),
                terminal = clientRecord(2, 123, 55u, 3u, 7uL, 9uL, processId, rawId, 200uL),
            ),
        )
        val handoff = fixture.handoff.resolve("${hex(rawId)}.dmp")
        Files.write(handoff, byteArrayOf(1))
        val reconciler = CrashpadClientLifecycleReconciler(
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        )

        val retained = reconciler.reconcile(handlerQuiesced = true)
        assertEquals(
            CrashpadLifecycleDisposition.RETAINED_FOR_HANDOFF,
            retained.outcomes.single().disposition,
        )
        assertTrue(Files.exists(lifecycle))
        assertNotNull(fixture.rawStore.journal(rawId))

        Files.delete(handoff)
        val cleaned = reconciler.reconcile(handlerQuiesced = true)
        assertEquals(
            CrashpadLifecycleDisposition.CLEANED_TERMINAL,
            cleaned.outcomes.single().disposition,
        )
        assertFalse(Files.exists(lifecycle))
        assertEquals(null, fixture.rawStore.journal(rawId))
    }

    @Test
    fun lifecycle_incomplete_or_invalid_is_preserved_live_and_destroyed_only_when_quiesced() {
        val fixture = fixture()
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val processId = ByteArray(32) { 42 }
        val incompleteRaw = ByteArray(32) { 43 }
        val invalidRaw = ByteArray(32) { 44 }
        val corruptTerminalRaw = ByteArray(32) { 47 }
        assertTrue(fixture.rawStore.preCapture(incompleteRaw, processId, 1, 2))
        assertTrue(fixture.rawStore.preCapture(corruptTerminalRaw, processId, 1, 2))
        val incomplete = lifecycleRoot.resolve(clientFileName(1u, incompleteRaw))
        Files.write(
            incomplete,
            clientJournal(
                clientRecord(1, 88, 9u, 1u, 2uL, 10uL, processId, incompleteRaw, 3uL),
                ByteArray(192),
            ),
        )
        val invalid = lifecycleRoot.resolve(clientFileName(1u, invalidRaw))
        val corrupt = clientJournal(
            clientRecord(1, 89, 9u, 1u, 2uL, 11uL, processId, invalidRaw, 3uL),
            ByteArray(192),
        )
        corrupt[188] = (corrupt[188].toInt() xor 1).toByte()
        Files.write(invalid, corrupt)
        val corruptTerminal = lifecycleRoot.resolve(clientFileName(1u, corruptTerminalRaw))
        val corruptTerminalBytes = clientJournal(
            clientRecord(1, 87, 9u, 1u, 2uL, 9uL, processId, corruptTerminalRaw, 3uL),
            clientRecord(2, 87, 9u, 1u, 2uL, 9uL, processId, corruptTerminalRaw, 4uL),
        )
        corruptTerminalBytes[380] = (corruptTerminalBytes[380].toInt() xor 1).toByte()
        Files.write(corruptTerminal, corruptTerminalBytes)
        val reconciler = CrashpadClientLifecycleReconciler(
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        )

        val live = reconciler.reconcile(handlerQuiesced = false)
        assertEquals(
            setOf(
                CrashpadLifecycleDisposition.RETAINED_ACTIVE,
                CrashpadLifecycleDisposition.INVALID_RETAINED,
            ),
            live.outcomes.map { it.disposition }.toSet(),
        )
        assertTrue(Files.exists(incomplete))
        assertTrue(Files.exists(invalid))
        assertTrue(Files.exists(corruptTerminal))
        assertNotNull(fixture.rawStore.journal(incompleteRaw))
        assertNotNull(fixture.rawStore.journal(corruptTerminalRaw))

        val quiesced = reconciler.reconcile(handlerQuiesced = true)
        assertEquals(
            setOf(
                CrashpadLifecycleDisposition.CLEANED_QUIESCED_INCOMPLETE,
                CrashpadLifecycleDisposition.INVALID_DESTROYED,
            ),
            quiesced.outcomes.map { it.disposition }.toSet(),
        )
        assertFalse(Files.exists(incomplete))
        assertFalse(Files.exists(invalid))
        assertFalse(Files.exists(corruptTerminal))
        assertEquals(null, fixture.rawStore.journal(incompleteRaw))
        assertEquals(null, fixture.rawStore.journal(corruptTerminalRaw))
    }

    @Test
    fun quiesced_single_lease_recovers_pending_dump_before_incomplete_lifecycle_cleanup() {
        val fixture = fixture()
        val pendingRoot = fixture.root.resolve("crashpad-db").resolve("pending")
            .also(Files::createDirectories)
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val processId = ByteArray(32) { 71 }
        val rawId = ByteArray(32) { 72 }
        assertTrue(fixture.rawStore.preCapture(rawId, processId, 1, 9))
        val lifecycle = lifecycleRoot.resolve(clientFileName(1u, rawId))
        Files.write(
            lifecycle,
            clientJournal(
                clientRecord(1, 301, 9u, 1u, 9uL, 41uL, processId, rawId, 1uL),
                ByteArray(192),
            ),
        )
        val reportName = "1cc0a206-73f3-4775-8cc0-0c7d4fec41dd"
        val pending = pendingRoot.resolve("$reportName.dmp")
        val metadata = pendingRoot.resolve("$reportName.meta")
        Files.write(pending, byteArrayOf(1, 2, 3, 4))
        Files.write(metadata, ByteArray(32) { it.toByte() })
        val recoverer = CrashpadPendingHandoffRecoverer(
            pendingRoot,
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        )

        assertEquals(CrashpadPendingRecoveryResult.NONE, recoverer.recover(handlerQuiesced = false))
        assertTrue(Files.exists(pending))
        assertEquals(
            CrashpadPendingRecoveryResult.RECOVERED,
            recoverer.recover(handlerQuiesced = true),
        )
        val handoff = fixture.handoff.resolve("${hex(rawId)}.dmp")
        assertFalse(Files.exists(pending))
        assertFalse(Files.exists(metadata))
        assertTrue(Files.isRegularFile(handoff))
        assertTrue(Files.exists(lifecycle))

        val lifecycleOutcome = CrashpadClientLifecycleReconciler(
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        ).reconcile(handlerQuiesced = true).outcomes.single()
        assertEquals(
            CrashpadLifecycleDisposition.RETAINED_FOR_HANDOFF,
            lifecycleOutcome.disposition,
        )
    }

    @Test
    fun matching_crashpad_lock_is_retained_live_and_retired_before_quiesced_recovery() {
        val fixture = fixture()
        val pendingRoot = fixture.root.resolve("crashpad-db/pending")
            .also(Files::createDirectories)
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val processId = ByteArray(32) { 73 }
        val rawId = ByteArray(32) { 74 }
        assertTrue(fixture.rawStore.preCapture(rawId, processId, 1, 10))
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, rawId)),
            clientJournal(
                clientRecord(1, 302, 9u, 1u, 10uL, 42uL, processId, rawId, 1uL),
                ByteArray(192),
            ),
        )
        val reportName = "1cc0a206-73f3-4775-8cc0-0c7d4fec41dd"
        val pending = pendingRoot.resolve("$reportName.dmp")
        val metadata = pendingRoot.resolve("$reportName.meta")
        val lock = pendingRoot.resolve("$reportName.lock")
        Files.write(pending, byteArrayOf(1, 2, 3, 4))
        Files.write(metadata, ByteArray(32) { it.toByte() })
        Files.write(lock, ByteArray(8) { (it + 1).toByte() })
        val recoverer = CrashpadPendingHandoffRecoverer(
            pendingRoot,
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        )

        assertEquals(CrashpadPendingRecoveryResult.NONE, recoverer.recover(handlerQuiesced = false))
        assertTrue(Files.exists(pending))
        assertTrue(Files.exists(metadata))
        assertTrue(Files.exists(lock))

        assertEquals(
            CrashpadPendingRecoveryResult.RECOVERED,
            recoverer.recover(handlerQuiesced = true),
        )
        assertFalse(Files.exists(pending))
        assertFalse(Files.exists(metadata))
        assertFalse(Files.exists(lock))
        assertTrue(Files.isRegularFile(fixture.handoff.resolve("${hex(rawId)}.dmp")))
    }

    @Test
    fun only_dead_and_handoff_failed_terminal_journals_recover_an_exact_pending_pair() {
        fun runScenario(
            terminalState: Int,
            expected: CrashpadPendingRecoveryResult,
        ) {
            val fixture = fixture()
            val pendingRoot = fixture.root.resolve("crashpad-db/pending")
                .also(Files::createDirectories)
            val lifecycleRoot = fixture.root.resolve("clients")
                .also(Files::createDirectories)
            val processId = ByteArray(32) { (80 + terminalState).toByte() }
            val rawId = ByteArray(32) { (90 + terminalState).toByte() }
            assertTrue(fixture.rawStore.preCapture(rawId, processId, 1, 11))
            val lifecycle = lifecycleRoot.resolve(clientFileName(1u, rawId))
            Files.write(
                lifecycle,
                clientJournal(
                    clientRecord(1, 310 + terminalState, 9u, 1u, 11uL, 43uL, processId, rawId, 1uL),
                    clientRecord(
                        terminalState,
                        310 + terminalState,
                        9u,
                        1u,
                        11uL,
                        43uL,
                        processId,
                        rawId,
                        2uL,
                    ),
                ),
            )
            val reportName = "1cc0a206-73f3-4775-8cc0-0c7d4fec41dd"
            val pending = pendingRoot.resolve("$reportName.dmp")
            val metadata = pendingRoot.resolve("$reportName.meta")
            Files.write(pending, byteArrayOf(5, 6, 7, 8))
            Files.write(metadata, ByteArray(32) { (it + terminalState).toByte() })

            assertEquals(
                expected,
                CrashpadPendingHandoffRecoverer(
                    pendingRoot,
                    lifecycleRoot,
                    fixture.handoff,
                    fixture.rawStore,
                ).recover(handlerQuiesced = true),
            )

            val handoff = fixture.handoff.resolve("${hex(rawId)}.dmp")
            if (expected == CrashpadPendingRecoveryResult.RECOVERED) {
                assertFalse(Files.exists(pending))
                assertFalse(Files.exists(metadata))
                assertTrue(Files.isRegularFile(handoff))
            } else {
                assertTrue(Files.isRegularFile(pending))
                assertTrue(Files.isRegularFile(metadata))
                assertTrue(Files.isRegularFile(lifecycle))
                assertFalse(Files.exists(handoff))
            }
        }

        runScenario(terminalState = 3, expected = CrashpadPendingRecoveryResult.RECOVERED)
        runScenario(terminalState = 5, expected = CrashpadPendingRecoveryResult.RECOVERED)
        runScenario(terminalState = 4, expected = CrashpadPendingRecoveryResult.AMBIGUOUS)
    }

    @Test
    fun quiesced_recovery_retires_only_a_canonical_orphaned_crashpad_sidecar() {
        val fixture = fixture()
        val pendingRoot = fixture.root.resolve("crashpad-db/pending")
            .also(Files::createDirectories)
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val metadata = pendingRoot.resolve(
            "1cc0a206-73f3-4775-8cc0-0c7d4fec41dd.meta",
        )
        Files.write(metadata, ByteArray(32) { it.toByte() })
        val recoverer = CrashpadPendingHandoffRecoverer(
            pendingRoot,
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        )

        assertEquals(CrashpadPendingRecoveryResult.NONE, recoverer.recover(handlerQuiesced = false))
        assertTrue(Files.exists(metadata))
        assertEquals(CrashpadPendingRecoveryResult.NONE, recoverer.recover(handlerQuiesced = true))
        assertFalse(Files.exists(metadata))

        val malformed = pendingRoot.resolve(
            "1cc0a206-73f3-4775-8cc0-0c7d4fec41dd.meta",
        )
        Files.write(malformed, ByteArray(31))
        assertEquals(
            CrashpadPendingRecoveryResult.AMBIGUOUS,
            recoverer.recover(handlerQuiesced = true),
        )
        assertTrue(Files.exists(malformed))
    }

    @Test
    fun retiring_exact_orphaned_crashpad_sidecars_releases_their_metadata_quota() {
        val root = Files.createTempDirectory("tracebox-pending-sidecar-quota")
        val pendingRoot = root.resolve("crashpad-db/pending").also(Files::createDirectories)
        val lifecycleRoot = root.resolve("clients").also(Files::createDirectories)
        val handoffRoot = root.resolve("handoff").also(Files::createDirectories)
        val coordinator = pendingQuotaCoordinator(root)
        val rawStore = RawArtifactStore(root.resolve("raw"), 128L, coordinator)
        val baseline = coordinator.used(UidBucket.METADATA)
        val reportName = "1cc0a206-73f3-4775-8cc0-0c7d4fec41dd"
        val metadata = pendingRoot.resolve("$reportName.meta")
        val lock = pendingRoot.resolve("$reportName.lock")
        Files.write(metadata, ByteArray(32) { it.toByte() })
        Files.write(lock, ByteArray(8) { (it + 1).toByte() })
        assertTrue(coordinator.reserve(metadata, UidBucket.METADATA, 32))
        assertTrue(coordinator.reserve(lock, UidBucket.METADATA, 8))
        assertEquals(baseline + 40, coordinator.used(UidBucket.METADATA))
        val recoverer = CrashpadPendingHandoffRecoverer(
            pendingRoot,
            lifecycleRoot,
            handoffRoot,
            rawStore,
            coordinator,
        )

        assertEquals(CrashpadPendingRecoveryResult.NONE, recoverer.recover(handlerQuiesced = false))
        assertEquals(baseline + 40, coordinator.used(UidBucket.METADATA))
        assertTrue(Files.exists(metadata))
        assertTrue(Files.exists(lock))

        assertEquals(CrashpadPendingRecoveryResult.NONE, recoverer.recover(handlerQuiesced = true))
        assertFalse(Files.exists(metadata))
        assertFalse(Files.exists(lock))
        assertFalse(coordinator.allocations().containsKey(metadata))
        assertFalse(coordinator.allocations().containsKey(lock))
        assertEquals(baseline, coordinator.used(UidBucket.METADATA))
    }

    @Test
    fun pending_recovery_rejects_ambiguous_multiple_dumps_without_mutation() {
        val fixture = fixture()
        val pendingRoot = fixture.root.resolve("crashpad-db").resolve("pending")
            .also(Files::createDirectories)
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val processId = ByteArray(32) { 73 }
        val rawId = ByteArray(32) { 74 }
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, rawId)),
            clientJournal(
                clientRecord(1, 302, 9u, 1u, 9uL, 42uL, processId, rawId, 1uL),
                ByteArray(192),
            ),
        )
        Files.write(pendingRoot.resolve("one.dmp"), byteArrayOf(1))
        Files.write(pendingRoot.resolve("two.dmp"), byteArrayOf(2))

        assertEquals(
            CrashpadPendingRecoveryResult.AMBIGUOUS,
            CrashpadPendingHandoffRecoverer(
                pendingRoot,
                lifecycleRoot,
                fixture.handoff,
                fixture.rawStore,
            ).recover(handlerQuiesced = true),
        )
        assertEquals(2L, Files.list(pendingRoot).use { it.count() })
        assertTrue(Files.list(fixture.handoff).use { it.findAny().isEmpty })
    }

    @Test
    fun pending_recovery_fails_closed_for_invalid_journal_mixed_with_valid_registration() {
        val fixture = fixture()
        val pendingRoot = fixture.root.resolve("crashpad-db/pending")
            .also(Files::createDirectories)
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val processId = ByteArray(32) { 91.toByte() }
        val rawId = ByteArray(32) { 92.toByte() }
        val invalidRawId = ByteArray(32) { 93.toByte() }
        assertTrue(fixture.rawStore.preCapture(rawId, processId, 1, 17))
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, rawId)),
            clientJournal(
                clientRecord(1, 401, 9u, 1u, 17uL, 51uL, processId, rawId, 1uL),
                ByteArray(192),
            ),
        )
        val invalid = clientJournal(
            clientRecord(
                1,
                402,
                9u,
                1u,
                17uL,
                52uL,
                processId,
                invalidRawId,
                1uL,
            ),
            ByteArray(192),
        )
        invalid[188] = (invalid[188].toInt() xor 1).toByte()
        Files.write(lifecycleRoot.resolve(clientFileName(1u, invalidRawId)), invalid)
        val pending = pendingRoot.resolve("single.dmp")
        Files.write(pending, byteArrayOf(1, 2, 3))

        assertEquals(
            CrashpadPendingRecoveryResult.AMBIGUOUS,
            CrashpadPendingHandoffRecoverer(
                pendingRoot,
                lifecycleRoot,
                fixture.handoff,
                fixture.rawStore,
            ).recover(handlerQuiesced = true),
        )
        assertTrue(Files.exists(pending))
        assertTrue(Files.list(fixture.handoff).use { it.findAny().isEmpty })
    }

    @Test
    fun pending_recovery_allows_valid_terminal_journals_beside_the_single_incomplete_lease() {
        val fixture = fixture()
        val pendingRoot = fixture.root.resolve("crashpad-db/pending")
            .also(Files::createDirectories)
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val processId = ByteArray(32) { 94.toByte() }
        val rawId = ByteArray(32) { 95.toByte() }
        val terminalRawId = ByteArray(32) { 96.toByte() }
        assertTrue(fixture.rawStore.preCapture(rawId, processId, 1, 18))
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, rawId)),
            clientJournal(
                clientRecord(1, 403, 9u, 1u, 18uL, 53uL, processId, rawId, 1uL),
                ByteArray(192),
            ),
        )
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, terminalRawId)),
            clientJournal(
                clientRecord(
                    1,
                    404,
                    9u,
                    1u,
                    16uL,
                    50uL,
                    processId,
                    terminalRawId,
                    1uL,
                ),
                clientRecord(
                    2,
                    404,
                    9u,
                    1u,
                    16uL,
                    50uL,
                    processId,
                    terminalRawId,
                    2uL,
                ),
            ),
        )
        Files.write(pendingRoot.resolve("single.dmp"), byteArrayOf(4, 5, 6))

        assertEquals(
            CrashpadPendingRecoveryResult.RECOVERED,
            CrashpadPendingHandoffRecoverer(
                pendingRoot,
                lifecycleRoot,
                fixture.handoff,
                fixture.rawStore,
            ).recover(handlerQuiesced = true),
        )
        assertTrue(Files.exists(fixture.handoff.resolve("${hex(rawId)}.dmp")))
    }

    @Test
    fun pending_recovery_requires_exact_raw_journal_and_native_registered_sequence() {
        fun runScenario(
            rawProcessId: ByteArray,
            pendingSequence: Long,
            rawRole: Int = 1,
            rawEpoch: Long = 19,
        ): CrashpadPendingRecoveryResult {
            val fixture = fixture()
            val pendingRoot = fixture.root.resolve("crashpad-db/pending")
                .also(Files::createDirectories)
            val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
            val lifecycleProcessId = ByteArray(32) { 101.toByte() }
            val rawId = ByteArray(32) { 102.toByte() }
            assertTrue(fixture.rawStore.preCapture(rawId, rawProcessId, rawRole, rawEpoch))
            Files.write(
                lifecycleRoot.resolve(clientFileName(1u, rawId)),
                clientJournal(
                    clientRecord(
                        1,
                        405,
                        9u,
                        1u,
                        19uL,
                        54uL,
                        lifecycleProcessId,
                        rawId,
                        1uL,
                        pendingSequence,
                    ),
                    ByteArray(192),
                ),
            )
            Files.write(pendingRoot.resolve("single.dmp"), byteArrayOf(7, 8, 9))
            return CrashpadPendingHandoffRecoverer(
                pendingRoot,
                lifecycleRoot,
                fixture.handoff,
                fixture.rawStore,
            ).recover(handlerQuiesced = true)
        }

        assertEquals(
            CrashpadPendingRecoveryResult.AMBIGUOUS,
            runScenario(ByteArray(32) { 103.toByte() }, pendingSequence = 0),
        )
        assertEquals(
            CrashpadPendingRecoveryResult.AMBIGUOUS,
            runScenario(ByteArray(32) { 101.toByte() }, pendingSequence = 1),
        )
        assertEquals(
            CrashpadPendingRecoveryResult.AMBIGUOUS,
            runScenario(
                ByteArray(32) { 101.toByte() },
                pendingSequence = 0,
                rawRole = 2,
            ),
        )
        assertEquals(
            CrashpadPendingRecoveryResult.AMBIGUOUS,
            runScenario(
                ByteArray(32) { 101.toByte() },
                pendingSequence = 0,
                rawEpoch = 18,
            ),
        )
    }

    @Test
    fun pending_recovery_rejects_unknown_pending_entries_and_nonempty_handoff() {
        val fixture = fixture()
        val pendingRoot = fixture.root.resolve("crashpad-db/pending")
            .also(Files::createDirectories)
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val processId = ByteArray(32) { 104.toByte() }
        val rawId = ByteArray(32) { 105.toByte() }
        assertTrue(fixture.rawStore.preCapture(rawId, processId, 1, 20))
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, rawId)),
            clientJournal(
                clientRecord(1, 406, 9u, 1u, 20uL, 55uL, processId, rawId, 1uL),
                ByteArray(192),
            ),
        )
        val pending = pendingRoot.resolve("single.dmp")
        Files.write(pending, byteArrayOf(1))
        val unknown = pendingRoot.resolve("unexpected.meta")
        Files.write(unknown, byteArrayOf(2))
        val recoverer = CrashpadPendingHandoffRecoverer(
            pendingRoot,
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        )

        assertEquals(
            CrashpadPendingRecoveryResult.AMBIGUOUS,
            recoverer.recover(handlerQuiesced = true),
        )
        Files.delete(unknown)
        Files.write(fixture.handoff.resolve("${"a".repeat(64)}.dmp"), byteArrayOf(3))
        assertEquals(
            CrashpadPendingRecoveryResult.AMBIGUOUS,
            recoverer.recover(handlerQuiesced = true),
        )
        assertTrue(Files.exists(pending))
    }

    @Test
    fun pending_recovery_preserves_conservative_destination_quota_for_raw_adoption() {
        val root = Files.createTempDirectory("tracebox-pending-destination-quota")
        val pendingRoot = root.resolve("crashpad-db/pending").also(Files::createDirectories)
        val lifecycleRoot = root.resolve("clients").also(Files::createDirectories)
        val handoffRoot = root.resolve("handoff").also(Files::createDirectories)
        val coordinator = pendingQuotaCoordinator(root)
        val rawStore = RawArtifactStore(root.resolve("raw"), 128L, coordinator)
        val processId = ByteArray(32) { 106.toByte() }
        val rawId = ByteArray(32) { 107.toByte() }
        assertTrue(rawStore.preCapture(rawId, processId, 1, 21))
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, rawId)),
            clientJournal(
                clientRecord(1, 407, 9u, 1u, 21uL, 56uL, processId, rawId, 1uL),
                ByteArray(192),
            ),
        )
        val pending = pendingRoot.resolve("single.dmp")
        val destination = handoffRoot.resolve("${hex(rawId)}.dmp")
        Files.write(pending, byteArrayOf(1, 2, 3, 4))
        assertTrue(coordinator.reserve(destination, UidBucket.RAW_ARTIFACTS, 128))

        assertEquals(
            CrashpadPendingRecoveryResult.RECOVERED,
            CrashpadPendingHandoffRecoverer(
                pendingRoot,
                lifecycleRoot,
                handoffRoot,
                rawStore,
                coordinator,
            ).recover(handlerQuiesced = true),
        )
        assertTrue(coordinator.owns(destination, UidBucket.RAW_ARTIFACTS, 128))
        assertTrue(rawStore.adoptRaw(rawId, destination, 4))
        assertEquals(4L, coordinator.used(UidBucket.RAW_ARTIFACTS))
    }

    @Test
    fun pending_recovery_atomically_transfers_and_shrinks_pending_quota() {
        val root = Files.createTempDirectory("tracebox-pending-source-quota")
        val pendingRoot = root.resolve("crashpad-db/pending").also(Files::createDirectories)
        val lifecycleRoot = root.resolve("clients").also(Files::createDirectories)
        val handoffRoot = root.resolve("handoff").also(Files::createDirectories)
        val coordinator = pendingQuotaCoordinator(root)
        val rawStore = RawArtifactStore(root.resolve("raw"), 128L, coordinator)
        val processId = ByteArray(32) { 108.toByte() }
        val rawId = ByteArray(32) { 109.toByte() }
        assertTrue(rawStore.preCapture(rawId, processId, 1, 22))
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, rawId)),
            clientJournal(
                clientRecord(1, 408, 9u, 1u, 22uL, 57uL, processId, rawId, 1uL),
                ByteArray(192),
            ),
        )
        val pending = pendingRoot.resolve("single.dmp")
        val destination = handoffRoot.resolve("${hex(rawId)}.dmp")
        Files.write(pending, byteArrayOf(5, 6, 7, 8))
        assertTrue(coordinator.reserve(pending, UidBucket.RAW_ARTIFACTS, 128))

        assertEquals(
            CrashpadPendingRecoveryResult.RECOVERED,
            CrashpadPendingHandoffRecoverer(
                pendingRoot,
                lifecycleRoot,
                handoffRoot,
                rawStore,
                coordinator,
            ).recover(handlerQuiesced = true),
        )
        assertFalse(coordinator.allocations().containsKey(pending))
        assertTrue(coordinator.owns(destination, UidBucket.RAW_ARTIFACTS, 4))
        assertEquals(4L, coordinator.used(UidBucket.RAW_ARTIFACTS))
    }

    @Test
    fun pending_recovery_rejects_an_underreserved_destination_without_mutation() {
        val root = Files.createTempDirectory("tracebox-pending-underreserved-quota")
        val pendingRoot = root.resolve("crashpad-db/pending").also(Files::createDirectories)
        val lifecycleRoot = root.resolve("clients").also(Files::createDirectories)
        val handoffRoot = root.resolve("handoff").also(Files::createDirectories)
        val coordinator = pendingQuotaCoordinator(root)
        val rawStore = RawArtifactStore(root.resolve("raw"), 128L, coordinator)
        val processId = ByteArray(32) { 110.toByte() }
        val rawId = ByteArray(32) { 111.toByte() }
        assertTrue(rawStore.preCapture(rawId, processId, 1, 23))
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, rawId)),
            clientJournal(
                clientRecord(1, 409, 9u, 1u, 23uL, 58uL, processId, rawId, 1uL),
                ByteArray(192),
            ),
        )
        val pending = pendingRoot.resolve("single.dmp")
        val destination = handoffRoot.resolve("${hex(rawId)}.dmp")
        Files.write(pending, byteArrayOf(1, 2, 3, 4))
        assertTrue(coordinator.reserve(destination, UidBucket.RAW_ARTIFACTS, 3))

        assertEquals(
            CrashpadPendingRecoveryResult.FAILED,
            CrashpadPendingHandoffRecoverer(
                pendingRoot,
                lifecycleRoot,
                handoffRoot,
                rawStore,
                coordinator,
            ).recover(handlerQuiesced = true),
        )
        assertTrue(Files.exists(pending))
        assertFalse(Files.exists(destination))
        assertTrue(coordinator.owns(destination, UidBucket.RAW_ARTIFACTS, 3))
    }

    @Test
    fun lifecycle_never_deletes_an_adopted_raw_file_waiting_for_summary_import() {
        val fixture = fixture()
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val processId = ByteArray(32) { 45 }
        val rawId = ByteArray(32) { 46 }
        assertTrue(fixture.rawStore.preCapture(rawId, processId, 1, 2))
        assertTrue(fixture.rawStore.commitRaw(rawId, byteArrayOf(1, 2, 3)))
        val lifecycle = lifecycleRoot.resolve(clientFileName(1u, rawId))
        Files.write(
            lifecycle,
            clientJournal(
                clientRecord(1, 90, 9u, 1u, 2uL, 12uL, processId, rawId, 3uL),
                clientRecord(2, 90, 9u, 1u, 2uL, 12uL, processId, rawId, 4uL),
            ),
        )

        val outcome = CrashpadClientLifecycleReconciler(
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        ).reconcile(handlerQuiesced = true).outcomes.single()

        assertEquals(CrashpadLifecycleDisposition.RETAINED_FOR_IMPORT, outcome.disposition)
        assertTrue(Files.exists(lifecycle))
        assertTrue(fixture.rawStore.containsRaw(rawId))
        assertNotNull(fixture.rawStore.journal(rawId))
    }

    @Test
    fun lifecycle_cleanup_releases_missing_pre_reserved_handoff_for_terminal_and_quiesced_clients() {
        val root = Files.createTempDirectory("tracebox-lifecycle-reservation")
        val handoffRoot = root.resolve("handoff").also(Files::createDirectories)
        val lifecycleRoot = root.resolve("clients").also(Files::createDirectories)
        val quota = UidQuota(UidBucket.entries.associateWith { bucket ->
            when (bucket) {
                UidBucket.RAW_ARTIFACTS -> 32L
                UidBucket.METADATA -> 4_096L
                else -> 1_024L
            }
        })
        val coordinator = UidWideQuotaCoordinator(
            root,
            quota,
            UidBucket.entries.associateWith { 32 },
        )
        val rawStore = RawArtifactStore(root.resolve("raw"), 32L, coordinator)
        val processId = ByteArray(32) { 51 }
        val terminalRaw = ByteArray(32) { 52 }
        val incompleteRaw = ByteArray(32) { 53 }
        assertTrue(rawStore.preCapture(terminalRaw, processId, 1, 2))
        assertTrue(rawStore.preCapture(incompleteRaw, processId, 1, 2))
        val terminalHandoff = handoffRoot.resolve("${hex(terminalRaw)}.dmp")
        val incompleteHandoff = handoffRoot.resolve("${hex(incompleteRaw)}.dmp")
        assertTrue(coordinator.reserve(terminalHandoff, UidBucket.RAW_ARTIFACTS, 16))
        assertTrue(coordinator.reserve(incompleteHandoff, UidBucket.RAW_ARTIFACTS, 16))
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, terminalRaw)),
            clientJournal(
                clientRecord(1, 101, 7u, 1u, 2uL, 21uL, processId, terminalRaw, 1uL),
                clientRecord(2, 101, 7u, 1u, 2uL, 21uL, processId, terminalRaw, 2uL),
            ),
        )
        Files.write(
            lifecycleRoot.resolve(clientFileName(1u, incompleteRaw)),
            clientJournal(
                clientRecord(1, 102, 7u, 1u, 2uL, 22uL, processId, incompleteRaw, 1uL),
                ByteArray(192),
            ),
        )

        val batch = CrashpadClientLifecycleReconciler(
            lifecycleRoot,
            handoffRoot,
            rawStore,
            coordinator,
        ).reconcile(handlerQuiesced = true)

        assertEquals(
            setOf(
                CrashpadLifecycleDisposition.CLEANED_TERMINAL,
                CrashpadLifecycleDisposition.CLEANED_QUIESCED_INCOMPLETE,
            ),
            batch.outcomes.map { it.disposition }.toSet(),
        )
        assertEquals(0L, coordinator.used(UidBucket.RAW_ARTIFACTS))
        assertFalse(coordinator.allocations().containsKey(terminalHandoff))
        assertFalse(coordinator.allocations().containsKey(incompleteHandoff))
    }

    @Test
    fun lifecycle_filename_is_bound_to_canonical_role_and_raw_identity() {
        val fixture = fixture()
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val processId = ByteArray(32) { 60 }
        val exactRaw = ByteArray(32) { 61 }
        val leadingZeroRaw = ByteArray(32) { 62 }
        val uppercaseRaw = ByteArray(32) { 63 }
        val roleMismatchRaw = ByteArray(32) { 64 }
        val nameRaw = ByteArray(32) { 65 }
        val bodyRaw = ByteArray(32) { 66 }
        val maximumRoleRaw = ByteArray(32) { 67 }
        val outOfRangeRoleRaw = ByteArray(32) { 68 }
        val exactName = clientFileName(7u, exactRaw)
        val maximumRoleName = clientFileName(UInt.MAX_VALUE, maximumRoleRaw)
        val journals = linkedMapOf(
            exactName to clientRecord(1, 201, 5u, 7u, 8uL, 31uL, processId, exactRaw, 1uL),
            maximumRoleName to
                clientRecord(
                    1,
                    206,
                    5u,
                    UInt.MAX_VALUE,
                    8uL,
                    36uL,
                    processId,
                    maximumRoleRaw,
                    1uL,
                ),
            "client-201-31.tbclient" to
                clientRecord(1, 201, 5u, 7u, 8uL, 31uL, processId, exactRaw, 1uL),
            "client-r07-${hex(leadingZeroRaw)}.tbclient" to
                clientRecord(1, 202, 5u, 7u, 8uL, 32uL, processId, leadingZeroRaw, 1uL),
            "client-r7-${hex(uppercaseRaw).uppercase()}.tbclient" to
                clientRecord(1, 203, 5u, 7u, 8uL, 33uL, processId, uppercaseRaw, 1uL),
            "client-r4294967296-${hex(outOfRangeRoleRaw)}.tbclient" to
                clientRecord(
                    1,
                    207,
                    5u,
                    UInt.MAX_VALUE,
                    8uL,
                    37uL,
                    processId,
                    outOfRangeRoleRaw,
                    1uL,
                ),
            clientFileName(8u, roleMismatchRaw) to
                clientRecord(1, 204, 5u, 7u, 8uL, 34uL, processId, roleMismatchRaw, 1uL),
            clientFileName(7u, nameRaw) to
                clientRecord(1, 205, 5u, 7u, 8uL, 35uL, processId, bodyRaw, 1uL),
        )
        journals.forEach { (name, registered) ->
            Files.write(lifecycleRoot.resolve(name), clientJournal(registered, ByteArray(192)))
        }

        val outcomes = CrashpadClientLifecycleReconciler(
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        ).reconcile(handlerQuiesced = false).outcomes.associateBy { it.fileName }

        assertEquals(CrashpadLifecycleDisposition.RETAINED_ACTIVE, outcomes[exactName]?.disposition)
        assertEquals(
            CrashpadLifecycleDisposition.RETAINED_ACTIVE,
            outcomes[maximumRoleName]?.disposition,
        )
        assertEquals(
            6,
            outcomes.values.count {
                it.disposition == CrashpadLifecycleDisposition.INVALID_RETAINED
            },
        )
    }

    @Test
    fun journal_only_orphans_require_quiescence_and_clean_with_no_lifecycle_directory() {
        val fixture = fixture()
        val missingLifecycleRoot = fixture.root.resolve("missing-clients")
        val orphan = ByteArray(32) { 71 }
        val handoffBound = ByteArray(32) { 72 }
        val processId = ByteArray(32) { 73 }
        assertTrue(fixture.rawStore.preCapture(orphan, processId, 1, 9))
        assertTrue(fixture.rawStore.preCapture(handoffBound, processId, 1, 9))
        val handoff = fixture.handoff.resolve("${hex(handoffBound)}.dmp")
        Files.write(handoff, byteArrayOf(1))
        val reconciler = CrashpadClientLifecycleReconciler(
            missingLifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        )

        val live = reconciler.reconcile(handlerQuiesced = false)
        assertEquals(0, live.journalOnlyOrphansDeleted)
        assertNotNull(fixture.rawStore.journal(orphan))

        val quiesced = reconciler.reconcile(handlerQuiesced = true)
        assertEquals(1, quiesced.journalOnlyOrphansDeleted)
        assertFalse(quiesced.truncated)
        assertEquals(null, fixture.rawStore.journal(orphan))
        assertNotNull(fixture.rawStore.journal(handoffBound))

        Files.delete(handoff)
        val retiredHandoffReservation = reconciler.reconcile(handlerQuiesced = true)
        assertEquals(1, retiredHandoffReservation.journalOnlyOrphansDeleted)
        assertEquals(null, fixture.rawStore.journal(handoffBound))
    }

    @Test
    fun journal_only_quota_is_released_only_after_a_complete_quiesced_reconciliation() {
        val root = Files.createTempDirectory("tracebox-journal-only-quota")
        val coordinator = UidWideQuotaCoordinator(
            root,
            UidQuota(UidBucket.entries.associateWith { bucket ->
                if (bucket == UidBucket.METADATA) 8_192L else 1_024L
            }),
            UidBucket.entries.associateWith { 32 },
        )
        val rawStore = RawArtifactStore(root.resolve("raw"), 1_024L, coordinator)
        val baseline = coordinator.used(UidBucket.METADATA)
        val rawId = ByteArray(32) { 80 }
        assertTrue(rawStore.preCapture(rawId, ByteArray(32) { 81 }, 1, 12))
        val reserved = coordinator.used(UidBucket.METADATA)
        assertTrue(reserved > baseline)
        val reconciler = CrashpadClientLifecycleReconciler(
            root.resolve("missing-clients"),
            root.resolve("missing-handoffs"),
            rawStore,
            coordinator,
        )

        val live = reconciler.reconcile(handlerQuiesced = false)
        assertEquals(0, live.journalOnlyOrphansDeleted)
        assertEquals(reserved, coordinator.used(UidBucket.METADATA))
        assertNotNull(rawStore.journal(rawId))

        val quiesced = reconciler.reconcile(handlerQuiesced = true)
        assertFalse(quiesced.truncated)
        assertEquals(1, quiesced.journalOnlyOrphansDeleted)
        assertEquals(baseline, coordinator.used(UidBucket.METADATA))
        assertEquals(null, rawStore.journal(rawId))
    }

    @Test
    fun journal_only_cleanup_retains_a_valid_file_when_its_exact_quota_owner_is_missing() {
        val root = Files.createTempDirectory("tracebox-journal-only-unowned")
        val coordinator = UidWideQuotaCoordinator(
            root,
            UidQuota(UidBucket.entries.associateWith { bucket ->
                if (bucket == UidBucket.METADATA) 8_192L else 1_024L
            }),
            UidBucket.entries.associateWith { 32 },
        )
        val rawRoot = root.resolve("raw")
        val rawStore = RawArtifactStore(rawRoot, 1_024L, coordinator)
        val rawId = ByteArray(32) { 86 }
        assertTrue(rawStore.preCapture(rawId, ByteArray(32) { 87 }, 1, 14))
        val journalPath = Files.list(rawRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tbrawjournal") }
                .findFirst()
                .orElseThrow { AssertionError("raw pre-capture journal was not created") }
        }
        assertTrue(coordinator.release(journalPath))

        val batch = CrashpadClientLifecycleReconciler(
            root.resolve("missing-clients"),
            root.resolve("missing-handoffs"),
            rawStore,
            coordinator,
        ).reconcile(handlerQuiesced = true)

        assertTrue(batch.truncated)
        assertEquals(0, batch.journalOnlyOrphansDeleted)
        assertNotNull(rawStore.journal(rawId))
        assertTrue(Files.isRegularFile(journalPath))
    }

    @Test
    fun journal_only_sweep_waits_for_complete_unambiguous_lifecycle_and_handoff_scans() {
        val fixture = fixture()
        val lifecycleRoot = fixture.root.resolve("clients").also(Files::createDirectories)
        val orphan = ByteArray(32) { 74 }
        assertTrue(fixture.rawStore.preCapture(orphan, ByteArray(32) { 75 }, 1, 10))
        Files.write(lifecycleRoot.resolve("invalid-a"), byteArrayOf(1))
        Files.write(lifecycleRoot.resolve("invalid-b"), byteArrayOf(1))
        val firstHandoff = fixture.handoff.resolve("${hex(ByteArray(32) { 76 })}.dmp")
        val secondHandoff = fixture.handoff.resolve("${hex(ByteArray(32) { 77 })}.dmp")
        Files.write(firstHandoff, byteArrayOf(1))
        Files.write(secondHandoff, byteArrayOf(1))
        val reconciler = CrashpadClientLifecycleReconciler(
            lifecycleRoot,
            fixture.handoff,
            fixture.rawStore,
        )

        val lifecycleTruncated = reconciler.reconcile(handlerQuiesced = true, maxFiles = 1)
        assertTrue(lifecycleTruncated.truncated)
        assertNotNull(fixture.rawStore.journal(orphan))

        Files.list(lifecycleRoot).use { paths ->
            paths.forEach(Files::delete)
        }
        val handoffTruncated = reconciler.reconcile(handlerQuiesced = true, maxFiles = 1)
        assertTrue(handoffTruncated.truncated)
        assertNotNull(fixture.rawStore.journal(orphan))

        Files.delete(firstHandoff)
        Files.delete(secondHandoff)
        Files.write(fixture.handoff.resolve("ambiguous"), byteArrayOf(1))
        val ambiguous = reconciler.reconcile(handlerQuiesced = true, maxFiles = 1)
        assertTrue(ambiguous.truncated)
        assertNotNull(fixture.rawStore.journal(orphan))

        Files.delete(fixture.handoff.resolve("ambiguous"))
        val complete = reconciler.reconcile(handlerQuiesced = true, maxFiles = 1)
        assertFalse(complete.truncated)
        assertEquals(1, complete.journalOnlyOrphansDeleted)
        assertEquals(null, fixture.rawStore.journal(orphan))
    }

    @Test
    fun journal_only_sweep_reports_truncation_when_protected_ids_expand_the_scan_window() {
        val fixture = fixture()
        val firstOrphan = ByteArray(32) { 82 }
        val secondOrphan = ByteArray(32) { 83 }
        val protectedWithoutJournal = ByteArray(32) { 84 }
        val processId = ByteArray(32) { 85 }
        assertTrue(fixture.rawStore.preCapture(firstOrphan, processId, 1, 13))
        assertTrue(fixture.rawStore.preCapture(secondOrphan, processId, 1, 13))
        Files.write(
            fixture.handoff.resolve("${hex(protectedWithoutJournal)}.dmp"),
            byteArrayOf(1),
        )

        val first = CrashpadClientLifecycleReconciler(
            fixture.root.resolve("missing-clients"),
            fixture.handoff,
            fixture.rawStore,
        ).reconcile(handlerQuiesced = true, maxFiles = 1)

        assertEquals(1, first.journalOnlyOrphansDeleted)
        assertTrue(first.truncated)
        assertEquals(
            1,
            listOf(firstOrphan, secondOrphan).count {
                fixture.rawStore.journal(it) != null
            },
        )
    }

    @Test
    fun failed_journal_only_delete_is_retained_and_retried() {
        val root = Files.createTempDirectory("tracebox-journal-only-retry")
        val coordinator = UidWideQuotaCoordinator(
            root,
            UidQuota(UidBucket.entries.associateWith { bucket ->
                if (bucket == UidBucket.METADATA) 4_096L else 1_024L
            }),
            UidBucket.entries.associateWith { 32 },
        )
        val rawStore = RawArtifactStore(root.resolve("raw"), 1_024L, coordinator)
        val rawId = ByteArray(32) { 78 }
        assertTrue(rawStore.preCapture(rawId, ByteArray(32) { 79 }, 1, 11))
        val barrier = root.resolve(UidWideStorageMutationBarrier.LOCK_FILE_NAME)
        Files.delete(barrier)
        Files.createDirectory(barrier)
        val reconciler = CrashpadClientLifecycleReconciler(
            root.resolve("missing-clients"),
            root.resolve("missing-handoffs"),
            rawStore,
            coordinator,
        )

        val failed = reconciler.reconcile(handlerQuiesced = true)
        assertTrue(failed.truncated)
        assertEquals(0, failed.journalOnlyOrphansDeleted)
        assertNotNull(rawStore.journal(rawId))

        Files.delete(barrier)
        val retried = reconciler.reconcile(handlerQuiesced = true)
        assertFalse(retried.truncated)
        assertEquals(1, retried.journalOnlyOrphansDeleted)
        assertEquals(null, rawStore.journal(rawId))
    }

    private fun fixture(maximumBytes: Int = CrashpadHandoffIngestor.MAXIMUM_MINIDUMP_BYTES): Fixture {
        val root = Files.createTempDirectory("tracebox-handoff")
        return Fixture(
            root,
            root.resolve("handoff").also(Files::createDirectories),
            root.resolve("spool").also(Files::createDirectories),
            RawArtifactStore(root.resolve("raw"), 32L * 1024 * 1024),
            maximumBytes,
        )
    }

    private fun pendingQuotaCoordinator(root: Path): UidWideQuotaCoordinator =
        UidWideQuotaCoordinator(
            root,
            UidQuota(UidBucket.entries.associateWith { bucket ->
                when (bucket) {
                    UidBucket.RAW_ARTIFACTS -> 128L
                    UidBucket.METADATA -> 8_192L
                    else -> 1_024L
                }
            }),
            UidBucket.entries.associateWith { 32 },
        )

    private data class Fixture(
        val root: Path,
        val handoff: Path,
        val spool: Path,
        val rawStore: RawArtifactStore,
        val maximumBytes: Int,
    ) {
        fun ingestor(
            identityDeriver: SummaryIdentityDeriver,
            summarizer: CrashpadMinidumpSummarizer,
            appender: DurableStructuralSummaryAppender,
        ): CrashpadHandoffIngestor = CrashpadHandoffIngestor(
            handoff,
            rawStore,
            spool,
            ByteArray(32) { 11 },
            identityDeriver,
            summarizer,
            appender,
            maximumMinidumpBytes = maximumBytes,
        )
    }

    private fun hex(value: ByteArray): String =
        value.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun clientFileName(role: UInt, rawId: ByteArray): String =
        "client-r$role-${hex(rawId)}.tbclient"

    private fun clientJournal(registered: ByteArray, terminal: ByteArray): ByteArray =
        registered + terminal

    private fun clientRecord(
        state: Int,
        pid: Int,
        uid: UInt,
        role: UInt,
        epoch: ULong,
        sequence: ULong,
        processId: ByteArray,
        rawId: ByteArray,
        monotonic: ULong,
        pendingSequence: Long = 0L,
    ): ByteArray {
        val bytes = ByteArray(192)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x5442434a)
        buffer.putShort(1.toShort())
        buffer.putShort(state.toShort())
        buffer.putInt(192)
        buffer.putInt(pid)
        buffer.putInt(uid.toInt())
        buffer.putInt(role.toInt())
        buffer.putInt(0)
        buffer.putLong(epoch.toLong())
        buffer.putLong(monotonic.toLong())
        buffer.putLong(sequence.toLong())
        buffer.putLong(pendingSequence)
        buffer.put(processId)
        buffer.put(rawId)
        buffer.position(188)
        buffer.putInt(Crc32c.value(bytes, 0, 188))
        return bytes
    }

    private data object SimulatedProcessDeath : IllegalStateException()
}
