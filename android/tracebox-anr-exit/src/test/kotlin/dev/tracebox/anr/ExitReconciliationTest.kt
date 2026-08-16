package dev.tracebox.anr

import dev.tracebox.api.Crc32c
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExitReconciliationTest {
    private val exit = SyntheticApplicationExitInfo(
        packageName = "dev.tracebox.app",
        processName = "dev.tracebox.app:worker",
        definingUid = 10_001,
        timestampMillis = 1_000,
        reason = 6,
        status = 9,
        importance = 100,
        pid = 42,
        processStateSummary = byteArrayOf(7, 8),
        artifactKind = ExitArtifactKind.ANR_TRACE,
    )

    @Test fun exact_source_key_is_durable_across_ledger_reconstruction_and_exhaustion_never_evicts() {
        val path = Files.createTempDirectory("tracebox-exit").resolve("tombstones")
        val ledger = ExitTombstoneLedger(path, maxEntries = 1, maxBytes = 128)
        val key = ExitSourceKey.derive(exit)
        val secondKey = ExitSourceKey.derive(exit.copy(timestampMillis = 1_001))

        assertEquals(ExitImportResult.IMPORTED, ledger.record(key))
        assertEquals(ExitImportResult.ALREADY_IMPORTED, ledger.record(key))
        assertEquals(ExitImportResult.DISABLED_EXHAUSTED, ledger.record(secondKey))
        assertTrue(ledger.imported(key))
        assertEquals(1, ledger.entryCount())

        val restarted = ExitTombstoneLedger(path, maxEntries = 1, maxBytes = 128)
        assertTrue(restarted.imported(key))
        assertEquals(ExitImportResult.ALREADY_IMPORTED, restarted.record(key))
        assertEquals(ExitImportResult.DISABLED_EXHAUSTED, restarted.record(secondKey))
        assertFalse(restarted.imported(secondKey))
    }

    @Test fun tombstone_ledger_rejects_oversized_and_malformed_persisted_state() {
        val oversized = Files.createTempDirectory("tracebox-exit-oversized").resolve("tombstones")
        Files.write(oversized, ByteArray(64 * 1_024 + 1))
        assertFailsWith<IllegalStateException> {
            ExitTombstoneLedger(oversized, maxEntries = 1, maxBytes = 128)
        }

        val malformed = Files.createTempDirectory("tracebox-exit-malformed").resolve("tombstones")
        Files.writeString(
            malformed,
            "tracebox-exit-tombstones-v1|enabled\nnot-a-source-key\n",
            Charsets.US_ASCII,
        )
        assertFailsWith<IllegalStateException> {
            ExitTombstoneLedger(malformed, maxEntries = 1, maxBytes = 128)
        }
    }

    @Test fun source_key_distinguishes_every_documented_exit_identity_field() {
        val original = ExitSourceKey.derive(exit)

        assertNotEquals(original, ExitSourceKey.derive(exit.copy(packageName = "dev.tracebox.other")))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(processName = "dev.tracebox.app:other")))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(definingUid = 10_002)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(timestampMillis = 1_001)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(reason = 7)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(status = 10)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(importance = 101)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(pid = 43)))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(processStateSummary = byteArrayOf(8, 7))))
        assertNotEquals(original, ExitSourceKey.derive(exit.copy(artifactKind = ExitArtifactKind.NATIVE_TOMBSTONE)))
    }

    @Test fun linker_preserves_explicit_confidence_without_turning_watchdog_candidate_into_confirmation() {
        val exact = LocalExitEvidence(exit.processName, 1001, exit.reason, 99, byteArrayOf(7, 8))
        val probable = exact.copy(processInstanceToken = null)
        val possible = exact.copy(timestampMillis = 500_000, reason = 8, pid = exit.pid, processInstanceToken = null)

        assertEquals(ExitLinkConfidence.EXACT, ExitLinker.link(exit, exact))
        assertEquals(ExitLinkConfidence.PROBABLE, ExitLinker.link(exit, probable))
        assertEquals(ExitLinkConfidence.POSSIBLE, ExitLinker.link(exit, possible))
        assertEquals(ExitLinkConfidence.UNMATCHED, ExitLinker.link(exit, null))
    }

    @Test fun documented_android_exit_fields_map_without_using_pid_as_an_identity() {
        val mapped = ApplicationExitInfoMapper.map(
            "dev.tracebox.fixture",
            AndroidExitInfoFields(
                processName = "dev.tracebox.fixture:worker",
                packageUid = 12_345,
                timestampMillis = 99,
                reason = 6,
                status = 7,
                importance = 100,
                pid = 42,
                processStateSummary = byteArrayOf(1, 2),
                artifactKind = ExitArtifactKind.ANR_TRACE,
            ),
        )

        assertEquals("dev.tracebox.fixture:worker", mapped?.processName)
        assertEquals(42, mapped?.pid)
        assertEquals(ExitArtifactKind.ANR_TRACE, mapped?.artifactKind)
    }

    @Test fun repeated_low_memory_exits_from_multiple_processes_remain_distinct_metadata_sources() {
        fun lowMemory(processName: String, timestampMillis: Long, pid: Int) =
            requireNotNull(
                ApplicationExitInfoMapper.map(
                    "dev.tracebox.fixture",
                    AndroidExitInfoFields(
                        processName = processName,
                        packageUid = 12_345,
                        timestampMillis = timestampMillis,
                        reason = 3,
                        status = 0,
                        importance = 400,
                        pid = pid,
                        processStateSummary = null,
                        artifactKind = ExitArtifactKind.NONE,
                    ),
                ),
            )

        val firstMain = lowMemory("dev.tracebox.fixture", 100, 41)
        val secondMain = lowMemory("dev.tracebox.fixture", 200, 42)
        val worker = lowMemory("dev.tracebox.fixture:worker", 200, 43)
        val keys = listOf(firstMain, secondMain, worker).map(ExitSourceKey::derive)

        assertEquals(3, keys.toSet().size)
        assertEquals(ExitArtifactKind.NONE, firstMain.artifactKind)
        assertEquals("dev.tracebox.fixture:worker", worker.processName)
    }

    @Test fun metadata_only_exit_cannot_claim_raw_artifact_provenance() {
        assertFailsWith<IllegalArgumentException> {
            ExitRawArtifactProvenance(
                artifactKind = ExitArtifactKind.NONE,
                rawArtifactId = ByteArray(32) { 1 },
                acquisitionEpoch = 1,
                originProcessInstanceId = ByteArray(32) { 2 },
                originRole = 1,
            )
        }
    }

    @Test fun capture_time_policy_token_v2_round_trips_every_bound_field() {
        val processIdentity = ByteArray(32) { (it * 7).toByte() }

        listOf(false, true).forEach { rawAllowed ->
            val token = ExitPolicyToken(
                epoch = 42,
                rawArtifactAllowed = rawAllowed,
                processInstanceId = processIdentity,
                processRole = 17,
            )
            val encoded = token.encode()
            val decoded = assertNotNull(ExitPolicyToken.decode(encoded))

            assertEquals(ExitPolicyToken.ENCODED_SIZE, encoded.size)
            assertEquals(42, decoded.epoch)
            assertEquals(rawAllowed, decoded.rawArtifactAllowed)
            assertContentEquals(processIdentity, decoded.processInstanceId)
            assertEquals(17, decoded.processRole)
        }
    }

    @Test fun legacy_v1_policy_token_decodes_for_linkage_without_a_trusted_role() {
        val processIdentity = ByteArray(32) { (255 - it).toByte() }
        val decoded = assertNotNull(
            ExitPolicyToken.decode(
                encodeLegacyPolicyToken(
                    epoch = 41,
                    rawArtifactAllowed = true,
                    processInstanceId = processIdentity,
                ),
            ),
        )

        assertEquals(41, decoded.epoch)
        assertTrue(decoded.rawArtifactAllowed)
        assertContentEquals(processIdentity, decoded.processInstanceId)
        assertNull(decoded.processRole)
        assertFailsWith<IllegalArgumentException> { decoded.encode() }
    }

    @Test fun policy_token_decode_rejects_malformed_v2_semantics_crc_and_size() {
        val valid = ExitPolicyToken(
            epoch = 42,
            rawArtifactAllowed = true,
            processInstanceId = ByteArray(32) { it.toByte() },
            processRole = 17,
        ).encode()

        assertNull(ExitPolicyToken.decode(rewriteLong(valid, offset = 8, value = -1)))
        assertNull(ExitPolicyToken.decode(rewriteInt(valid, offset = 16, value = 2)))
        assertNull(ExitPolicyToken.decode(rewriteInt(valid, offset = 52, value = -1)))

        val corruptCrc = valid.copyOf().also { bytes ->
            val last = bytes.lastIndex
            bytes[last] = (bytes[last].toInt() xor 0x40).toByte()
        }
        assertNull(ExitPolicyToken.decode(corruptCrc))
        assertNull(ExitPolicyToken.decode(valid.copyOf(valid.size - 1)))
        assertNull(ExitPolicyToken.decode(valid.copyOf(valid.size + 1)))

        val v2WithLegacySize = valid.copyOf(ExitPolicyToken.LEGACY_ENCODED_SIZE)
        rewriteCrc(v2WithLegacySize)
        assertNull(ExitPolicyToken.decode(v2WithLegacySize))
        assertFailsWith<IllegalArgumentException> {
            ExitPolicyToken(42, true, ByteArray(32), processRole = -1)
        }
    }

    @Test fun source_key_exposes_only_its_exact_32_byte_internal_identity() {
        val key = ExitSourceKey.derive(exit)
        assertEquals(32, key.bytes().size)
        assertEquals(key, ExitSourceKey(key.encoded))
    }

    @Test fun raw_exit_stream_reader_is_bounded_and_fail_closed() {
        val payload = ByteArray(128) { it.toByte() }
        val (accepted, acceptedState) = BoundedExitStreamReader.read(ByteArrayInputStream(payload), 128)
        assertEquals(ExitRawReadState.AVAILABLE, acceptedState)
        assertContentEquals(payload, accepted)

        val (oversized, oversizedState) = BoundedExitStreamReader.read(ByteArrayInputStream(payload), 127)
        assertNull(oversized)
        assertEquals(ExitRawReadState.OVERSIZED, oversizedState)

        val failing = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("injected")
        }
        val (failed, failedState) = BoundedExitStreamReader.read(failing, 128)
        assertNull(failed)
        assertEquals(ExitRawReadState.READ_FAILED, failedState)
    }

    @Test fun import_journal_resumes_every_append_boundary_and_is_crc_bounded() {
        val root = Files.createTempDirectory("tracebox-exit-import")
        val journal = ExitImportJournal(
            root,
            maxEntries = 2,
            maxBytes = ExitImportJournal.ENTRY_BYTES * 2,
        )
        val key = ExitSourceKey.derive(exit)
        val entry = ExitImportEntry(
            key,
            ExitImportStage.PREPARED,
            exit.reason,
            exit.status,
            exit.importance,
            ExitLinkConfidence.EXACT,
            ExitRawReadState.AVAILABLE,
            rawProvenance(),
        )

        assertTrue(journal.prepare(entry))
        assertEquals(entry, journal.read(key))
        assertTrue(journal.markAppended(key))
        assertEquals(ExitImportStage.APPENDED, journal.read(key)?.stage)

        val restarted = ExitImportJournal(
            root,
            maxEntries = 2,
            maxBytes = ExitImportJournal.ENTRY_BYTES * 2,
        )
        assertEquals(ExitImportStage.APPENDED, restarted.pending().single().stage)
        assertTrue(restarted.complete(key))
        assertTrue(restarted.pending().isEmpty())

        assertTrue(journal.prepare(entry))
        val path = Files.list(root).use { it.findFirst().orElseThrow() }
        val corrupt = Files.readAllBytes(path)
        corrupt[16] = (corrupt[16].toInt() xor 1).toByte()
        Files.write(path, corrupt)
        assertFailsWith<IllegalStateException> { journal.read(key) }
        assertFailsWith<IllegalStateException> { journal.pending() }
    }

    @Test fun import_journal_rejects_truncated_and_oversized_entries_before_decode() {
        val root = Files.createTempDirectory("tracebox-exit-import-bounds")
        val journal = ExitImportJournal(
            root,
            maxEntries = 1,
            maxBytes = ExitImportJournal.ENTRY_BYTES,
        )
        val key = ExitSourceKey.derive(exit)
        val path = root.resolve("${key.encoded}.tbexitjournal")

        Files.write(path, ByteArray(ExitImportJournal.ENTRY_BYTES - 1))
        assertFailsWith<IllegalStateException> { journal.read(key) }
        assertFailsWith<IllegalStateException> { journal.pending() }

        Files.write(path, ByteArray(1 * 1_024 * 1_024))
        assertFailsWith<IllegalStateException> { journal.read(key) }
        assertFailsWith<IllegalStateException> { journal.pending() }
    }

    @Test fun import_journal_refuses_to_evict_exact_inflight_entries_at_limits() {
        val root = Files.createTempDirectory("tracebox-exit-import-limit")
        val journal = ExitImportJournal(
            root,
            maxEntries = 1,
            maxBytes = ExitImportJournal.ENTRY_BYTES,
        )
        val first = ExitImportEntry(
            ExitSourceKey.derive(exit),
            ExitImportStage.PREPARED,
            exit.reason,
            exit.status,
            exit.importance,
            ExitLinkConfidence.UNMATCHED,
            ExitRawReadState.NONE,
        )
        val second = first.copy(sourceKey = ExitSourceKey.derive(exit.copy(timestampMillis = 2_000)))

        assertTrue(journal.prepare(first))
        assertFalse(journal.prepare(second))
        assertNotNull(journal.read(first.sourceKey))
        assertNull(journal.read(second.sourceKey))
        assertTrue(journal.deleteAllOwned())
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun import_journal_recovers_forced_temporary_and_rejects_excess_or_unknown_entries() {
        val root = Files.createTempDirectory("tracebox-exit-import-recovery")
        val journal = ExitImportJournal(
            root,
            maxEntries = 2,
            maxBytes = ExitImportJournal.ENTRY_BYTES * 2,
        )
        val first = ExitImportEntry(
            ExitSourceKey.derive(exit),
            ExitImportStage.PREPARED,
            exit.reason,
            exit.status,
            exit.importance,
            ExitLinkConfidence.EXACT,
            ExitRawReadState.NONE,
        )
        assertTrue(journal.prepare(first))
        val target = root.resolve("${first.sourceKey.encoded}.tbexitjournal")
        val temporary = target.resolveSibling("${target.fileName}.new")
        Files.move(target, temporary, StandardCopyOption.REPLACE_EXISTING)

        assertEquals(first, journal.pending().single())
        assertTrue(Files.exists(target))
        assertFalse(Files.exists(temporary))

        Files.write(root.resolve("unknown"), byteArrayOf(1))
        assertFailsWith<IllegalStateException> { journal.pending() }
        Files.delete(root.resolve("unknown"))

        val second = first.copy(
            sourceKey = ExitSourceKey.derive(exit.copy(timestampMillis = 2_000)),
        )
        assertTrue(journal.prepare(second))
        val boundedToOne = ExitImportJournal(
            root,
            maxEntries = 1,
            maxBytes = ExitImportJournal.ENTRY_BYTES,
        )
        assertFailsWith<IllegalStateException> { boundedToOne.pending() }
    }

    @Test fun import_journal_rejects_symlinked_entries_without_following_them() {
        val root = Files.createTempDirectory("tracebox-exit-import-symlink")
        val outside = Files.createTempFile("tracebox-exit-outside", ".bin")
        val key = ExitSourceKey.derive(exit)
        val linked = root.resolve("${key.encoded}.tbexitjournal")
        val created = runCatching {
            Files.createSymbolicLink(linked, outside)
        }.isSuccess
        if (!created) return

        val journal = ExitImportJournal(
            root,
            maxEntries = 1,
            maxBytes = ExitImportJournal.ENTRY_BYTES,
        )
        assertFailsWith<IllegalStateException> { journal.pending() }
        assertContentEquals(ByteArray(0), Files.readAllBytes(outside))
    }

    @Test fun terminalizer_closes_prepare_tombstone_and_append_crash_windows_exactly_once() {
        val root = Files.createTempDirectory("tracebox-exit-terminalizer")
        val journal = ExitImportJournal(
            root.resolve("journal"),
            maxEntries = 3,
            maxBytes = ExitImportJournal.ENTRY_BYTES * 3,
        )
        val ledger = ExitTombstoneLedger(root.resolve("tombstones"), maxEntries = 3, maxBytes = 256)
        val durable = linkedSetOf<ExitSourceKey>()
        var physicalAppends = 0

        fun entry(timestamp: Long): ExitImportEntry = ExitImportEntry(
            ExitSourceKey.derive(exit.copy(timestampMillis = timestamp)),
            ExitImportStage.PREPARED,
            exit.reason,
            exit.status,
            exit.importance,
            ExitLinkConfidence.EXACT,
            ExitRawReadState.NONE,
        )

        fun recover(candidate: ExitImportEntry): ExitImportTerminalization =
            ExitImportTerminalizer.terminalize(
                candidate,
                recordTombstone = ledger::record,
                containsRecord = { it in durable },
                appendRecord = {
                    physicalAppends++
                    durable += it.sourceKey
                    true
                },
                markAppended = journal::markAppended,
                complete = journal::complete,
                retireRaw = { true },
            )

        // Crash after PREPARED, before the source tombstone.
        val beforeTombstone = entry(10_000)
        assertTrue(journal.prepare(beforeTombstone))
        assertEquals(ExitImportTerminalization.COMPLETED, recover(journal.pending().single()))
        assertEquals(1, physicalAppends)
        assertTrue(ledger.imported(beforeTombstone.sourceKey))
        assertTrue(journal.pending().isEmpty())
        assertEquals(1, physicalAppends)

        // Crash after the source tombstone, before the generated append.
        val afterTombstone = entry(20_000)
        assertTrue(journal.prepare(afterTombstone))
        assertEquals(ExitImportResult.IMPORTED, ledger.record(afterTombstone.sourceKey))
        assertEquals(ExitImportTerminalization.COMPLETED, recover(journal.pending().single()))
        assertEquals(2, physicalAppends)

        // Crash after the durable append, before APPENDED/cleanup.
        val afterAppend = entry(30_000)
        assertTrue(journal.prepare(afterAppend))
        assertEquals(ExitImportResult.IMPORTED, ledger.record(afterAppend.sourceKey))
        durable += afterAppend.sourceKey
        assertEquals(ExitImportTerminalization.COMPLETED, recover(journal.pending().single()))
        assertEquals(2, physicalAppends)
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun tombstone_delete_does_not_clear_memory_when_unsafe_storage_blocks_deletion() {
        val root = Files.createTempDirectory("tracebox-exit-ledger-delete")
        val path = root.resolve("tombstones")
        val ledger = ExitTombstoneLedger(path, maxEntries = 2, maxBytes = 128)
        val key = ExitSourceKey.derive(exit)
        assertEquals(ExitImportResult.IMPORTED, ledger.record(key))
        Files.delete(path)
        val outside = Files.createTempFile("tracebox-exit-ledger-outside", ".txt")
        val linked = runCatching { Files.createSymbolicLink(path, outside) }.isSuccess
        if (!linked) return

        assertFalse(ledger.deleteAllOwned())
        assertTrue(ledger.imported(key))
        assertTrue(Files.exists(outside))
    }

    @Test
    fun android_user_path_detection_starts_at_the_package_directory() {
        assertEquals(
            3,
            androidPrivateStoragePackageIndex(
                listOf("data", "user", "0", "dev.tracebox.app", "no_backup", "tracebox"),
            ),
        )
        assertNull(
            androidPrivateStoragePackageIndex(
                listOf("tmp", "data", "user", "0", "dev.tracebox.app", "tracebox"),
            ),
        )
    }

    @Test
    fun exit_guard_ignores_only_the_prefix_above_its_boundary_and_rejects_descendant_symlinks() {
        val journalRoot = Path.of(
            "build",
            "exit-reconciliation-tests",
            "data",
            "user",
            "0",
            "dev.tracebox.app",
        ).toAbsolutePath()
            .resolve("no_backup")
            .resolve("tracebox")
            .resolve("exit-import")
        val packageBoundary = journalRoot.parent.parent.parent
        val platformAlias = packageBoundary.parent
        assertFalse(simulatedSymbolicLinkCheck(journalRoot, packageBoundary, setOf(platformAlias)))
        assertTrue(simulatedSymbolicLinkCheck(journalRoot, packageBoundary, setOf(packageBoundary)))
        assertTrue(simulatedSymbolicLinkCheck(journalRoot, packageBoundary, setOf(journalRoot)))

        val base = Files.createTempDirectory("tracebox-exit-generic-symlink")
        val outside = base.resolve("outside").also(Files::createDirectories)
        val genericRoot = base.resolve("generic-journal")
        if (runCatching { Files.createSymbolicLink(genericRoot, outside) }.isFailure) return
        val journal = ExitImportJournal(
            genericRoot,
            maxEntries = 1,
            maxBytes = ExitImportJournal.ENTRY_BYTES,
        )
        assertFailsWith<IllegalStateException> { journal.pending() }
    }

    private fun simulatedSymbolicLinkCheck(
        path: Path,
        firstGuardedComponent: Path,
        symbolicLinks: Set<Path>,
    ): Boolean = hasSymbolicLinkComponentAtOrBelow(
        path = path,
        firstGuardedComponent = firstGuardedComponent,
        exists = { true },
        isSymbolicLink = { it in symbolicLinks },
    )

    private fun rawProvenance(): ExitRawArtifactProvenance =
        ExitRawArtifactProvenance(
            ExitArtifactKind.ANR_TRACE,
            ByteArray(32) { (it + 1).toByte() },
            acquisitionEpoch = 42,
            originProcessInstanceId = ByteArray(32) { (it + 33).toByte() },
            originRole = 7,
        )

    private fun encodeLegacyPolicyToken(
        epoch: Long,
        rawArtifactAllowed: Boolean,
        processInstanceId: ByteArray,
    ): ByteArray {
        require(processInstanceId.size == 32)
        val bytes = ByteBuffer.allocate(ExitPolicyToken.LEGACY_ENCODED_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x54584245)
            .putInt(1)
            .putLong(epoch)
            .putInt(if (rawArtifactAllowed) 1 else 0)
            .put(processInstanceId)
            .array()
        rewriteCrc(bytes)
        return bytes
    }

    private fun rewriteLong(bytes: ByteArray, offset: Int, value: Long): ByteArray =
        bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value)
            rewriteCrc(it)
        }

    private fun rewriteInt(bytes: ByteArray, offset: Int, value: Int): ByteArray =
        bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            rewriteCrc(it)
        }

    private fun rewriteCrc(bytes: ByteArray) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(
            bytes.size - Int.SIZE_BYTES,
            Crc32c.value(bytes, 0, bytes.size - Int.SIZE_BYTES),
        )
    }
}
