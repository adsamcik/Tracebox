package dev.tracebox.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import dev.tracebox.api.DiagnosticContext
import dev.tracebox.api.Diagnostics
import dev.tracebox.api.generated.GeneratedDiagnostics
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedHandledError
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.api.generated.GeneratedStructuralSummary
import dev.tracebox.core.GateAcceptance
import dev.tracebox.core.GateResult
import dev.tracebox.core.JvmCapturePolicy
import dev.tracebox.core.PolicyTaggedRecord
import dev.tracebox.core.RecordPriority
import dev.tracebox.api.Crc32c
import dev.tracebox.core.TraceboxUncaughtExceptionHandler
import dev.tracebox.core.WriterPolicyGate

/** Raw crash bytes may only contribute an ID-free structural summary and are never package eligible. */
enum class RawArtifactDisposition { STRUCTURAL_SUMMARY_ONLY }

/** Producer binding prevents one raw-artifact consumer from parsing or retiring another's bytes. */
enum class RawArtifactKind {
    CRASHPAD_MINIDUMP,
    OS_EXIT_ANR_TRACE,
    OS_EXIT_NATIVE_TOMBSTONE,
}

data class RawArtifactJournal(
    val id: ByteArray,
    val originProcessInstanceId: ByteArray,
    val originRole: Int,
    val acceptedEpoch: Long,
    val disposition: RawArtifactDisposition = RawArtifactDisposition.STRUCTURAL_SUMMARY_ONLY,
    val kind: RawArtifactKind = RawArtifactKind.CRASHPAD_MINIDUMP,
) {
    init {
        require(id.size == 32)
        require(originProcessInstanceId.size == 32)
        require(originRole >= 0)
        require(acceptedEpoch >= 0)
    }
}

data class CommittedRawArtifact(
    val id: ByteArray,
    val path: Path,
    val kind: RawArtifactKind = RawArtifactKind.CRASHPAD_MINIDUMP,
)

data class CommittedRawArtifactBatch(
    val artifacts: List<CommittedRawArtifact>,
    val truncated: Boolean,
)

internal data class JournalOnlyRawArtifactBatch(
    val ids: List<ByteArray>,
    val truncated: Boolean,
)

/** CE handler raw-artifact store with a separate, hard byte budget. */
class RawArtifactStore(
    root: Path,
    private val rawQuotaBytes: Long,
    private val uidQuota: UidWideQuotaCoordinator? = null,
    private val storageEligibility: StorageMutationEligibility = StorageMutationEligibility.ALWAYS,
) {
    private val root = safeStorageRoot(root)
    private val commitLock = Any()
    init { require(rawQuotaBytes >= 0) }

    fun preCapture(
        id: ByteArray,
        originProcessInstanceId: ByteArray,
        originRole: Int,
        acceptedEpoch: Long,
        kind: RawArtifactKind = RawArtifactKind.CRASHPAD_MINIDUMP,
    ): Boolean = synchronized(commitLock) {
        when (
            val guarded = guardedStorageMutation(uidQuota, storageEligibility) mutation@{
                if (hasSymbolicLinkComponent(root)) return@mutation false
                val journal = RawArtifactJournal(
                    id.copyOf(),
                    originProcessInstanceId.copyOf(),
                    originRole,
                    acceptedEpoch,
                    kind = kind,
                )
                val path = journalPath(id)
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    val existing = journal(id) ?: return@mutation false
                    return@mutation existing.id.contentEquals(journal.id) &&
                        existing.originProcessInstanceId.contentEquals(journal.originProcessInstanceId) &&
                        existing.originRole == journal.originRole &&
                        existing.acceptedEpoch == journal.acceptedEpoch &&
                        existing.kind == journal.kind
                }
                Files.createDirectories(root)
                if (hasSymbolicLinkComponent(root) ||
                    !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                ) {
                    return@mutation false
                }
                val bytes =
                    "${encode(journal.id)}|${encode(journal.originProcessInstanceId)}|" +
                        "${journal.originRole}|${journal.acceptedEpoch}|${journal.kind.name}"
                val encoded = bytes.toByteArray(Charsets.US_ASCII)
                if (uidQuota?.reserve(path, UidBucket.METADATA, encoded.size.toLong()) == false) {
                    return@mutation false
                }
                try {
                    forceWrite(path, encoded)
                    true
                } catch (_: java.io.IOException) {
                    runCatching { uidQuota?.release(path) }
                    false
                }
            }
        ) {
            is StorageMutationBarrierResult.Applied -> guarded.value
            StorageMutationBarrierResult.Rejected -> false
        }
    }

    fun commitRaw(id: ByteArray, bytes: ByteArray): Boolean = synchronized(commitLock) {
        when (
            val guarded = guardedStorageMutation(uidQuota, storageEligibility) mutation@{
                if (hasSymbolicLinkComponent(root)) return@mutation false
                val path = rawPath(id)
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && journal(id) != null) {
                    return@mutation true
                }
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                    journal(id) == null ||
                    bytes.size.toLong() > rawQuotaBytes - usedRawBytes().coerceAtMost(rawQuotaBytes) ||
                    uidQuota?.reserve(path, UidBucket.RAW_ARTIFACTS, bytes.size.toLong()) == false
                ) {
                    return@mutation false
                }
                try {
                    forceWrite(path, bytes)
                    true
                } catch (_: java.io.IOException) {
                    runCatching { uidQuota?.release(path) }
                    false
                }
            }
        ) {
            is StorageMutationBarrierResult.Applied -> guarded.value
            StorageMutationBarrierResult.Rejected -> false
        }
    }

    /**
     * Adopts Crashpad's completed same-filesystem handoff with one rename and one quota owner.
     *
     * The durable reservation moves first. A crash before the rename is an idempotent
     * `ALREADY_TRANSFERRED` retry; startup ownership reconciliation can also repair either side.
     * If the native handoff was not yet in the ledger, the destination is reserved exactly once.
     */
    fun adoptRaw(
        id: ByteArray,
        source: Path,
        expectedBytes: Long,
    ): Boolean = synchronized(commitLock) {
        when (
            val guarded = guardedStorageMutation(uidQuota, storageEligibility) mutation@{
                require(id.size == ID_BYTES)
                try {
                    if (expectedBytes !in 1..rawQuotaBytes ||
                        hasSymbolicLinkComponent(root) ||
                        journal(id) == null
                    ) {
                        return@mutation false
                    }
                    val destination = rawPath(id)
                    if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                        return@mutation Files.size(destination) == expectedBytes
                    }
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return@mutation false
                    val normalizedSource = source.toAbsolutePath().normalize()
                    if (normalizedSource == destination ||
                        hasSymbolicLinkComponent(normalizedSource.parent) ||
                        !Files.isRegularFile(normalizedSource, LinkOption.NOFOLLOW_LINKS) ||
                        Files.size(normalizedSource) != expectedBytes
                    ) {
                        return@mutation false
                    }
                    Files.createDirectories(root)
                    if (hasSymbolicLinkComponent(root) ||
                        !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        return@mutation false
                    }

                    val reservation = when {
                        uidQuota == null -> RawAdoptionReservation.NONE
                        else -> when (
                            uidQuota.transfer(
                                normalizedSource,
                                destination,
                                UidBucket.RAW_ARTIFACTS,
                                expectedBytes,
                            )
                        ) {
                            UidReservationTransferResult.TRANSFERRED -> RawAdoptionReservation.TRANSFERRED
                            UidReservationTransferResult.ALREADY_TRANSFERRED ->
                                RawAdoptionReservation.ALREADY_TRANSFERRED
                            UidReservationTransferResult.REJECTED -> {
                                if (uidQuota.allocations().containsKey(normalizedSource) ||
                                    !uidQuota.reserve(destination, UidBucket.RAW_ARTIFACTS, expectedBytes)
                                ) {
                                    return@mutation false
                                }
                                RawAdoptionReservation.RESERVED_DESTINATION
                            }
                        }
                    }

                    try {
                        Files.move(
                            normalizedSource,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) ||
                            Files.size(destination) != expectedBytes
                        ) {
                            rollbackAdoption(normalizedSource, destination, expectedBytes, reservation)
                            return@mutation false
                        }
                        true
                    } catch (_: java.io.IOException) {
                        rollbackAdoption(normalizedSource, destination, expectedBytes, reservation)
                        false
                    } catch (_: UnsupportedOperationException) {
                        rollbackAdoption(normalizedSource, destination, expectedBytes, reservation)
                        false
                    }
                } catch (_: java.io.IOException) {
                    false
                } catch (_: UnsupportedOperationException) {
                    false
                }
            }
        ) {
            is StorageMutationBarrierResult.Applied -> guarded.value
            StorageMutationBarrierResult.Rejected -> false
        }
    }

    /** Enumerates bounded, journal-bound raw files left by an interrupted handoff import. */
    fun committedArtifacts(
        maxArtifacts: Int = DEFAULT_SWEEP_ENTRIES,
        kind: RawArtifactKind? = null,
    ): CommittedRawArtifactBatch {
        require(maxArtifacts in 1..MAX_SWEEP_ENTRIES)
        val artifacts = ArrayList<CommittedRawArtifact>(maxArtifacts)
        var excessArtifact = false
        val complete = scanBounded(MAX_RAW_ACCOUNTING_ENTRIES) { path ->
            val name = path.fileName.toString()
            if (!name.endsWith(RAW_SUFFIX)) return@scanBounded
            val encodedId = name.removeSuffix(RAW_SUFFIX)
            val id = runCatching { decode(encodedId) }.getOrNull() ?: return@scanBounded
            if (id.size != ID_BYTES ||
                encode(id) != encodedId ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                return@scanBounded
            }
            val boundJournal = journal(id) ?: return@scanBounded
            if (kind != null && boundJournal.kind != kind) return@scanBounded
            if (artifacts.size == maxArtifacts) {
                excessArtifact = true
            } else {
                artifacts += CommittedRawArtifact(id, path, boundJournal.kind)
            }
        }
        return CommittedRawArtifactBatch(artifacts, !complete || excessArtifact)
    }

    fun journal(id: ByteArray): RawArtifactJournal? {
        if (hasSymbolicLinkComponent(root)) return null
        val path = journalPath(id)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = readBoundedRegular(path, MAX_JOURNAL_BYTES) ?: return null
        if (bytes.any { it.toInt() !in 0..0x7f }) return null
        val text = bytes.toString(Charsets.US_ASCII)
        if (text.trim() != text) return null
        val parts = text.split('|')
        if (parts.size !in 4..5) return null
        return try {
            val kind = if (parts.size == 4) {
                // Version-1 journals predate typed OS-exit storage and were Crashpad-owned.
                RawArtifactKind.CRASHPAD_MINIDUMP
            } else {
                RawArtifactKind.valueOf(parts[4])
            }
            RawArtifactJournal(
                decode(parts[0]),
                decode(parts[1]),
                parts[2].toInt(),
                parts[3].toLong(),
                kind = kind,
            ).takeIf {
                encode(it.id) == parts[0] && encode(it.originProcessInstanceId) == parts[1]
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun containsRaw(id: ByteArray, kind: RawArtifactKind? = null): Boolean {
        val journal = journal(id) ?: return false
        return (kind == null || journal.kind == kind) &&
            Files.isRegularFile(rawPath(id), LinkOption.NOFOLLOW_LINKS)
    }

    internal fun containsOwned(id: ByteArray): Boolean =
        Files.exists(rawPath(id), LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(journalPath(id), LinkOption.NOFOLLOW_LINKS)

    /**
     * Enumerates valid pre-capture reservations whose raw bytes were never committed. The
     * lifecycle reconciler may retire these only after independently proving the handler and all
     * lifecycle/handoff producers quiescent.
     */
    internal fun journalOnlyArtifacts(
        maxArtifacts: Int = DEFAULT_SWEEP_ENTRIES,
        kind: RawArtifactKind? = null,
    ): JournalOnlyRawArtifactBatch {
        require(maxArtifacts in 1..MAX_SWEEP_ENTRIES)
        val ids = ArrayList<ByteArray>(maxArtifacts)
        var excessArtifact = false
        val complete = scanBounded(MAX_RAW_ACCOUNTING_ENTRIES) { path ->
            val name = path.fileName.toString()
            if (!name.endsWith(JOURNAL_SUFFIX)) return@scanBounded
            val encodedId = name.removeSuffix(JOURNAL_SUFFIX)
            val id = runCatching { decode(encodedId) }.getOrNull() ?: return@scanBounded
            if (id.size != ID_BYTES ||
                encode(id) != encodedId ||
                Files.exists(rawPath(id), LinkOption.NOFOLLOW_LINKS)
            ) {
                return@scanBounded
            }
            val boundJournal = journal(id) ?: return@scanBounded
            if (kind != null && boundJournal.kind != kind) return@scanBounded
            if (ids.size == maxArtifacts) {
                excessArtifact = true
            } else {
                ids += id
            }
        }
        return JournalOnlyRawArtifactBatch(ids, !complete || excessArtifact)
    }

    /**
     * Retires exactly one still-journal-only reservation. A concurrently materialized raw path
     * fails closed and is never deleted by this cleanup route.
     */
    internal fun deleteJournalOnly(
        id: ByteArray,
        expectedKind: RawArtifactKind? = null,
    ): Boolean = synchronized(commitLock) {
        require(id.size == ID_BYTES)
        val journal = journalPath(id)
        try {
            when (
                val guarded = guardedStorageMutation(
                    uidQuota,
                    StorageMutationEligibility.ALWAYS,
                ) {
                    if (Files.exists(rawPath(id), LinkOption.NOFOLLOW_LINKS)) {
                        return@guardedStorageMutation false
                    }
                    val boundJournal = journal(id)
                        ?: return@guardedStorageMutation false
                    if (!boundJournal.id.contentEquals(id) ||
                        expectedKind != null && boundJournal.kind != expectedKind
                    ) {
                        return@guardedStorageMutation false
                    }
                    if (uidQuota != null) {
                        val journalBytes = Files.size(journal)
                        if (!uidQuota.owns(journal, UidBucket.METADATA, journalBytes)) {
                            return@guardedStorageMutation false
                        }
                    }
                    val deleted = Files.deleteIfExists(journal)
                    deleted &&
                        !Files.exists(journal, LinkOption.NOFOLLOW_LINKS) &&
                        uidQuota?.release(journal) != false
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value
                StorageMutationBarrierResult.Rejected -> false
            }
        } catch (_: java.io.IOException) {
            false
        } catch (_: StorageMutationBarrierException) {
            false
        } catch (_: UidWideQuotaCoordinator.UidQuotaLedgerException) {
            false
        }
    }

    /**
     * Returns the canonical committed file only while both raw bytes and their binding journal are
     * regular, non-symlink files. This is the sole path suitable for a native structural parser.
     */
    fun committedRawPath(id: ByteArray, expectedKind: RawArtifactKind? = null): Path? {
        require(id.size == 32)
        val path = rawPath(id)
        return path.takeIf { containsRaw(id, expectedKind) }
    }

    fun deleteOwned(id: ByteArray, expectedKind: RawArtifactKind? = null): Boolean {
        if (expectedKind != null && journal(id)?.kind != expectedKind) return false
        val rawDeleted = deleteOwnedPath(rawPath(id))
        val journalDeleted = deleteOwnedPath(journalPath(id))
        return rawDeleted || journalDeleted
    }

    /**
     * Tracebox-generated raw bytes without a valid lifecycle journal are destroyed, never parsed.
     * Valid journal-only entries are preCapture reservations and remain until lifecycle
     * reconciliation proves their registration terminal.
     */
    fun deleteUnverifiableOrphans(maxEntries: Int = DEFAULT_SWEEP_ENTRIES): Int {
        require(maxEntries in 1..MAX_SWEEP_ENTRIES)
        var deleted = 0
        scanBounded(maxEntries) { path ->
            val name = path.fileName.toString()
            when {
                name.endsWith(RAW_SUFFIX) -> {
                    val id = name.removeSuffix(RAW_SUFFIX)
                    val valid = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        journalByName(id) != null
                    if (!valid && deleteOwnedPath(path)) deleted++
                }
                name.endsWith(JOURNAL_SUFFIX) -> {
                    val id = name.removeSuffix(JOURNAL_SUFFIX)
                    // Preserve every strictly valid journal even when its raw file does not exist.
                    if (journalByName(id) == null && deleteOwnedPath(path)) deleted++
                }
            }
        }
        return deleted
    }

    /** Removes expired raw bytes and their binding journals, then removes any remaining invalid orphan. */
    fun expire(
        nowMillis: Long,
        ttlMillis: Long,
        maxEntries: Int = DEFAULT_SWEEP_ENTRIES,
    ): Int {
        require(nowMillis >= 0)
        require(ttlMillis >= 0)
        require(maxEntries in 1..MAX_SWEEP_ENTRIES)
        var deleted = 0
        scanBounded(maxEntries) { raw ->
            val name = raw.fileName.toString()
            if (!name.endsWith(RAW_SUFFIX)) return@scanBounded
            val id = name.removeSuffix(RAW_SUFFIX)
            val journal = journalByName(id)
            val regular = Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)
            val modified = try {
                Files.getLastModifiedTime(raw, LinkOption.NOFOLLOW_LINKS).toMillis()
            } catch (_: java.io.IOException) {
                return@scanBounded
            }
            if (!regular || journal == null || nowMillis - modified >= ttlMillis) {
                if (deleteOwnedPath(raw)) deleted++
                if (journal != null && regular) deleteOwnedPath(root.resolve("$id$JOURNAL_SUFFIX"))
            }
        }
        deleted += deleteUnverifiableOrphans(maxEntries)
        return deleted
    }

    private fun journalByName(id: String): RawArtifactJournal? =
        if (!isCanonicalId(id)) null else try {
            journal(decode(id))
        } catch (_: IllegalArgumentException) {
            null
        }
    private fun rawPath(id: ByteArray): Path {
        require(id.size == ID_BYTES)
        return root.resolve("${encode(id)}$RAW_SUFFIX")
    }
    private fun journalPath(id: ByteArray): Path {
        require(id.size == ID_BYTES)
        return root.resolve("${encode(id)}$JOURNAL_SUFFIX")
    }
    private fun usedRawBytes(): Long {
        var used = 0L
        val complete = scanBounded(MAX_RAW_ACCOUNTING_ENTRIES) { path ->
            if (!path.fileName.toString().endsWith(RAW_SUFFIX)) return@scanBounded
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                used = rawQuotaBytes
                return@scanBounded
            }
            val size = try {
                Files.size(path)
            } catch (_: java.io.IOException) {
                used = rawQuotaBytes
                return@scanBounded
            }
            used = if (size > Long.MAX_VALUE - used) Long.MAX_VALUE else used + size
        }
        return if (complete) used else rawQuotaBytes
    }
    private fun deleteOwnedPath(path: Path): Boolean {
        return try {
            when (
                val guarded = guardedStorageMutation(
                    uidQuota,
                    StorageMutationEligibility.ALWAYS,
                ) {
                    val deleted = Files.deleteIfExists(path)
                    if (deleted) runCatching { uidQuota?.release(path) }
                    deleted
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value
                StorageMutationBarrierResult.Rejected -> false
            }
        } catch (_: java.io.IOException) {
            false
        } catch (_: StorageMutationBarrierException) {
            false
        }
    }

    private fun rollbackAdoption(
        source: Path,
        destination: Path,
        bytes: Long,
        reservation: RawAdoptionReservation,
    ) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(source, LinkOption.NOFOLLOW_LINKS)
        ) {
            runCatching {
                Files.move(destination, source, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        when (reservation) {
            RawAdoptionReservation.TRANSFERRED -> runCatching {
                uidQuota?.transfer(destination, source, UidBucket.RAW_ARTIFACTS, bytes)
            }

            RawAdoptionReservation.RESERVED_DESTINATION -> runCatching {
                uidQuota?.release(destination)
            }

            RawAdoptionReservation.NONE,
            RawAdoptionReservation.ALREADY_TRANSFERRED,
            -> Unit
        }
    }

    /** Deletes every raw byte and lifecycle journal while releasing their UID-wide ownership. */
    fun deleteAllOwned(maxEntries: Int = MAX_SWEEP_ENTRIES): Int {
        require(maxEntries in 1..MAX_SWEEP_ENTRIES)
        var deleted = 0
        scanBounded(maxEntries) {
            val name = it.fileName.toString()
            if ((name.endsWith(RAW_SUFFIX) || name.endsWith(JOURNAL_SUFFIX)) && deleteOwnedPath(it)) {
                deleted++
            }
        }
        return deleted
    }

    fun remainingOwned(maxEntries: Int = DEFAULT_SWEEP_ENTRIES): List<Path> {
        require(maxEntries in 1..MAX_SWEEP_ENTRIES)
        val remaining = ArrayList<Path>(maxEntries + 1)
        val complete = scanBounded(maxEntries) {
            val name = it.fileName.toString()
            if (name.endsWith(RAW_SUFFIX) || name.endsWith(JOURNAL_SUFFIX)) remaining.add(it)
        }
        if (!complete) remaining.add(root)
        return remaining
    }

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    private fun isCanonicalId(value: String): Boolean {
        val decoded = runCatching { decode(value) }.getOrNull() ?: return false
        return decoded.size == ID_BYTES && encode(decoded) == value
    }

    private fun readBoundedRegular(path: Path, maximumBytes: Long): ByteArray? = try {
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size !in 1..maximumBytes || size > Int.MAX_VALUE) return null
            val bytes = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) return null
            }
            if (channel.size() == size) bytes else null
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    /**
     * Returns true only when the complete directory was inspected. It never materializes an
     * attacker-controlled directory and ignores entries beyond the explicit caller bound.
     */
    private inline fun scanBounded(maxEntries: Int, action: (Path) -> Unit): Boolean {
        if (hasSymbolicLinkComponent(root)) return false
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            Files.list(root).use { paths ->
                val iterator = paths.iterator()
                var inspected = 0
                while (iterator.hasNext()) {
                    if (inspected == maxEntries) return false
                    inspected++
                    action(iterator.next())
                }
            }
            true
        } catch (_: java.io.IOException) {
            false
        }
    }

    private companion object {
        enum class RawAdoptionReservation {
            NONE,
            TRANSFERRED,
            ALREADY_TRANSFERRED,
            RESERVED_DESTINATION,
        }

        const val ID_BYTES = 32
        const val RAW_SUFFIX = ".tbraw"
        const val JOURNAL_SUFFIX = ".tbrawjournal"
        const val MAX_JOURNAL_BYTES = 256L
        const val DEFAULT_SWEEP_ENTRIES = 256
        const val MAX_SWEEP_ENTRIES = 2_048
        const val MAX_RAW_ACCOUNTING_ENTRIES = 2_048
    }
}

/** The only handler capture-start route: it forces a lifecycle journal before capture bytes exist. */
class CrashpadCaptureLifecycle(private val rawStore: RawArtifactStore) {
    fun capture(
        rawArtifactId: ByteArray,
        originProcessInstanceId: ByteArray,
        originRole: Int,
        acceptedPolicyEpoch: Long,
        writeCaptureBytes: () -> ByteArray,
    ): Boolean {
        if (!rawStore.preCapture(rawArtifactId, originProcessInstanceId, originRole, acceptedPolicyEpoch)) return false
        return rawStore.commitRaw(rawArtifactId, writeCaptureBytes())
    }
}

/** R2.8 participant for CE raw artifacts; deletion is only complete after this participant is empty. */
class RawArtifactDeletionParticipant(private val rawStore: RawArtifactStore, private val root: Path) : DeletionParticipant {
    override fun markIneligible() = Unit

    override fun deleteOwned() {
        rawStore.deleteAllOwned()
    }

    override fun remainingOwned(): List<Path> {
        rawStore.deleteUnverifiableOrphans()
        return rawStore.remainingOwned()
    }
}

/** Durable states make spool replay recoverable after every source-retirement boundary. */
private enum class SpoolState { JOURNALED, APPENDED, ACKNOWLEDGED, RETIRED }

/** Rust-backed in production; the result must be journaled before this call returns. */
fun interface SummaryIdentityDeriver {
    fun derive(
        rawArtifactId: ByteArray,
        extractorVersion: Int,
        schemaFingerprint: ByteArray,
        canonicalContentSha256: ByteArray,
    ): ByteArray
}

/**
 * Handler structural-summary spool. Its canonical content excludes IDs; `stage` writes the tuple
 * and deterministic ID before appending, and `replay` retains source until a durable acknowledgement.
 */
class StructuralSummarySpool(
    root: Path,
    private val uidQuota: UidWideQuotaCoordinator? = null,
    private val identityDeriver: SummaryIdentityDeriver = JvmReferenceSummaryIdentityDeriver,
    private val storageEligibility: StorageMutationEligibility = StorageMutationEligibility.ALWAYS,
) {
    private val root = safeStorageRoot(root)
    private fun stage(rawId: ByteArray, extractorVersion: Int, schema: ByteArray, canonicalBody: ByteArray): String {
        require(rawId.size == 32 && schema.size == 32)
        check(!hasSymbolicLinkComponent(root)) {
            "symbolic-link structural-summary root is forbidden"
        }
        val digest = sha256(canonicalBody)
        val idBytes = identityDeriver.derive(rawId, extractorVersion, schema, digest)
        require(idBytes.size == PersistedSegmentIdentity.ID_SIZE)
        val id = encode(idBytes)
        val path = recordPath(id)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root)
            check(!hasSymbolicLinkComponent(root) &&
                Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
            ) {
                "unsafe structural-summary root"
            }
            writeRecord(path, listOf(SpoolState.JOURNALED.name, encode(canonicalBody)).joinToString("|").toByteArray())
        } else {
            val existing = readRecord(path)
                ?: throw IllegalStateException("invalid structural-summary spool record")
            require(existing.body.contentEquals(canonicalBody)) {
                "structural-summary identity is bound to different canonical content"
            }
        }
        return id
    }

    /** Stages a schema-generated summary whose body can later be recovered into ordinary storage. */
    fun stageStructuralSummary(
        rawId: ByteArray,
        extractorVersion: Int,
        schema: ByteArray,
        summary: GeneratedStructuralSummary,
    ): String = stage(rawId, extractorVersion, schema, GeneratedRecordCodec.encode(summary))

    fun replay(
        maxRecords: Int = DEFAULT_REPLAY_RECORDS,
        import: (String, ByteArray) -> Unit,
    ) {
        require(maxRecords in 1..MAX_REPLAY_RECORDS)
        if (hasSymbolicLinkComponent(root)) return
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        forEachCanonicalRecord(maxRecords) { path, id, record ->
            if (record.state == SpoolState.RETIRED) return@forEachCanonicalRecord
            import(id, record.body.copyOf())
            val encodedBody = encode(record.body)
            writeRecord(path, "${SpoolState.ACKNOWLEDGED.name}|$encodedBody".toByteArray())
            writeRecord(path, "${SpoolState.RETIRED.name}|$encodedBody".toByteArray())
        }
    }

    fun replayToTarget(
        importer: TargetSegmentSummaryImporter,
        crashInjector: SummaryImportCrashInjector? = null,
        maxRecords: Int = DEFAULT_REPLAY_RECORDS,
    ) {
        require(maxRecords in 1..MAX_REPLAY_RECORDS)
        if (hasSymbolicLinkComponent(root)) return
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        forEachCanonicalRecord(maxRecords) { path, id, record ->
            if (record.state == SpoolState.RETIRED) return@forEachCanonicalRecord
            importer.import(id, id, record.body.copyOf(), crashInjector)
            val encodedBody = encode(record.body)
            writeRecord(path, "${SpoolState.ACKNOWLEDGED.name}|$encodedBody".toByteArray())
            writeRecord(path, "${SpoolState.RETIRED.name}|$encodedBody".toByteArray())
        }
    }

    fun isRetired(id: String): Boolean =
        isCanonicalSummaryId(id) && readRecord(recordPath(id))?.state == SpoolState.RETIRED

    /**
     * Removes only strictly validated records that already carry a durable RETIRED state. Invalid,
     * active, symlink, and excess entries are preserved for explicit fail-closed reconciliation.
     */
    fun purgeRetired(maxRecords: Int = DEFAULT_PURGE_RECORDS): Int {
        require(maxRecords in 1..MAX_PURGE_RECORDS)
        if (hasSymbolicLinkComponent(root)) return 0
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0
        var inspected = 0
        var deleted = 0
        Files.list(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext() && inspected < maxRecords) {
                val path = iterator.next()
                inspected++
                val id = path.fileName.toString().removeSuffix(".tbsummary")
                if (!path.fileName.toString().endsWith(".tbsummary") ||
                    !isCanonicalSummaryId(id) ||
                    readRecord(path)?.state != SpoolState.RETIRED
                ) {
                    continue
                }
                if (deleteSpoolPath(path)) {
                    deleted++
                }
            }
        }
        return deleted
    }

            /** Writes a durable tombstone before the selected summary can be excluded from replay/export. */
    fun tombstone(id: String): Boolean {
        if (!isCanonicalSummaryId(id)) return false
        val path = recordPath(id)
        val record = readRecord(path) ?: return false
        writeRecord(path, "${SpoolState.RETIRED.name}|${encode(record.body)}".toByteArray())
        return true
    }

    /** Deletes a bounded batch of handler spool bytes during a coordinated deletion transaction. */
    fun deleteAllOwned(maxRecords: Int = MAX_REPLAY_RECORDS): Int {
        require(maxRecords in 1..MAX_REPLAY_RECORDS)
        if (hasSymbolicLinkComponent(root)) return 0
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0
        var inspected = 0
        var deleted = 0
        Files.list(root).use { files ->
            val iterator = files.iterator()
            while (iterator.hasNext() && inspected < maxRecords) {
                val path = iterator.next()
                inspected++
                val name = path.fileName.toString()
                if (!name.endsWith(SUMMARY_SUFFIX)) continue
                // Invalid names/nodes are still safe to unlink from this library-owned directory,
                // but are never parsed or replayed.
                if (deleteSpoolPath(path)) {
                    deleted++
                }
            }
        }
        return deleted
    }

    fun remainingOwned(maxRecords: Int = MAX_REPLAY_RECORDS): List<Path> {
        require(maxRecords in 1..MAX_REPLAY_RECORDS)
        if (hasSymbolicLinkComponent(root)) return listOf(root)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val remaining = ArrayList<Path>(maxRecords + 1)
        var truncated = false
        Files.list(root).use { files ->
            val iterator = files.iterator()
            var inspected = 0
            while (iterator.hasNext() && inspected < maxRecords) {
                val path = iterator.next()
                inspected++
                if (path.fileName.toString().endsWith(SUMMARY_SUFFIX)) remaining.add(path)
            }
            truncated = iterator.hasNext()
        }
        if (truncated) remaining.add(root)
        return remaining
    }

    private fun recordPath(id: String): Path = root.resolve("$id$SUMMARY_SUFFIX")
    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun readRecord(path: Path): SpoolRecord? {
        if (hasSymbolicLinkComponent(root)) return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = readBoundedRecord(path) ?: return null
        if (bytes.any { it.toInt() !in 0..0x7f }) return null
        val fields = bytes.toString(Charsets.US_ASCII).split('|', limit = 2)
        if (fields.size != 2) return null
        val state = runCatching { SpoolState.valueOf(fields[0]) }.getOrNull() ?: return null
        val body = runCatching { decode(fields[1]) }.getOrNull() ?: return null
        if (body.size != STRUCTURAL_SUMMARY_BODY_BYTES || encode(body) != fields[1]) return null
        return SpoolRecord(state, body)
    }

    private fun readBoundedRecord(path: Path): ByteArray? = try {
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size !in 1..MAX_SPOOL_RECORD_BYTES) return null
            val bytes = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) return null
            }
            if (channel.size() == size) bytes else null
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    private fun isCanonicalSummaryId(id: String): Boolean {
        val decoded = runCatching { decode(id) }.getOrNull() ?: return false
        return decoded.size == PersistedSegmentIdentity.ID_SIZE && encode(decoded) == id
    }

    private inline fun forEachCanonicalRecord(
        maxRecords: Int,
        action: (Path, String, SpoolRecord) -> Unit,
    ) {
        Files.list(root).use { paths ->
            val iterator = paths.iterator()
            var inspected = 0
            while (iterator.hasNext() && inspected < maxRecords) {
                val path = iterator.next()
                inspected++
                val name = path.fileName.toString()
                if (!name.endsWith(SUMMARY_SUFFIX)) continue
                val id = name.removeSuffix(SUMMARY_SUFFIX)
                if (!isCanonicalSummaryId(id)) continue
                val record = readRecord(path) ?: continue
                action(path, id, record)
            }
        }
    }

    private fun writeRecord(path: Path, bytes: ByteArray) {
        when (
            guardedStorageMutation(uidQuota, storageEligibility) {
                check(!hasSymbolicLinkComponent(root)) {
                    "symbolic-link structural-summary root is forbidden"
                }
                val exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                if (exists && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw IllegalStateException("structural-summary spool path is not a regular file")
                }
                val priorSize = if (exists) Files.size(path) else null
                if (uidQuota != null) {
                    val reserved = if (priorSize == null) {
                        uidQuota.reserve(path, UidBucket.SUMMARY_SPOOL, bytes.size.toLong())
                    } else {
                        uidQuota.resize(path, UidBucket.SUMMARY_SPOOL, bytes.size.toLong())
                    }
                    if (!reserved) throw SegmentException.Quota
                }
                try {
                    forceWrite(path, bytes)
                } catch (failure: java.io.IOException) {
                    if (priorSize == null) runCatching { uidQuota?.release(path) }
                    else runCatching { uidQuota?.resize(path, UidBucket.SUMMARY_SPOOL, priorSize) }
                    throw failure
                }
            }
        ) {
            is StorageMutationBarrierResult.Applied -> Unit
            StorageMutationBarrierResult.Rejected -> throw SegmentException.StorageIneligible
        }
    }

    private fun deleteSpoolPath(path: Path): Boolean {
        try {
            return when (
                val guarded = guardedStorageMutation(
                    uidQuota,
                    StorageMutationEligibility.ALWAYS,
                ) {
                    val removed = Files.deleteIfExists(path)
                    if (removed) runCatching { uidQuota?.release(path) }
                    removed
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value
                StorageMutationBarrierResult.Rejected -> false
            }
        } catch (_: java.io.IOException) {
            return false
        } catch (_: StorageMutationBarrierException) {
            return false
        }
    }

    /**
     * Host/reference derivation used by storage-only tests. Production installs the Rust JNI
     * deriver explicitly and never uses this implementation.
     */
    private object JvmReferenceSummaryIdentityDeriver : SummaryIdentityDeriver {
        override fun derive(
            rawArtifactId: ByteArray,
            extractorVersion: Int,
            schemaFingerprint: ByteArray,
            canonicalContentSha256: ByteArray,
        ): ByteArray = MessageDigest.getInstance("SHA-256").digest(
            "tracebox-summary-v1".toByteArray() +
                rawArtifactId +
                extractorVersion.toLittleEndian() +
                schemaFingerprint +
                canonicalContentSha256,
        )

        private fun Int.toLittleEndian(): ByteArray =
            byteArrayOf(toByte(), (this shr 8).toByte(), (this shr 16).toByte(), (this shr 24).toByte())
    }

    private data class SpoolRecord(val state: SpoolState, val body: ByteArray)

    private companion object {
        const val STRUCTURAL_SUMMARY_BODY_BYTES = 18
        const val MAX_SPOOL_RECORD_BYTES = 512L
        const val SUMMARY_SUFFIX = ".tbsummary"
        const val DEFAULT_PURGE_RECORDS = 32
        const val MAX_PURGE_RECORDS = 256
        const val DEFAULT_REPLAY_RECORDS = 32
        const val MAX_REPLAY_RECORDS = 256
    }
}

/** R2.8 deletion participant for authoritative handler structural-summary spool files. */
class SummarySpoolDeletionParticipant(
    private val spool: StructuralSummarySpool,
    private val root: Path,
) : DeletionParticipant {
    override fun markIneligible() = Unit

    override fun deleteOwned() {
        spool.deleteAllOwned()
    }

    override fun remainingOwned(): List<Path> = spool.remainingOwned()
}

        /** Location persisted only after the target's forced append and immutable seal. */
        data class SummaryImportAcknowledgement(
            val sourceSpoolId: String,
            val targetSegmentId: ByteArray,
            val offset: Long,
            val summaryId: String,
        ) {
            init { require(targetSegmentId.size == PersistedSegmentIdentity.ID_SIZE) }
        }

        /** Fault injection is intentionally limited to the acknowledgement boundary. */
        fun interface SummaryImportCrashInjector {
            fun afterTargetAppendBeforeAcknowledgement(): Boolean
        }

        /**
         * Imports one summary into a target segment. Recovery finds an already-appended payload by its
         * summary ID, then creates the missing durable acknowledgement before source retirement.
         */
        class TargetSegmentSummaryImporter(
            acknowledgementRoot: Path,
            targetPath: Path,
            private val target: SegmentWriter,
        ) {
            private val acknowledgementRoot = safeStorageRoot(acknowledgementRoot)
            private val targetPath = targetPath.toAbsolutePath().normalize()

            fun import(
                sourceSpoolId: String,
                summaryId: String,
                canonicalBody: ByteArray,
                crashInjector: SummaryImportCrashInjector? = null,
            ): SummaryImportAcknowledgement {
                require(canonicalId(sourceSpoolId) != null) {
                    "source spool ID must be canonical"
                }
                val idBytes = requireNotNull(canonicalId(summaryId)) {
                    "summary ID must be canonical"
                }
                require(canonicalBody.size == STRUCTURAL_SUMMARY_BODY_BYTES) {
                    "structural summary has an unexpected size"
                }
                val existing = acknowledgement(sourceSpoolId)
                if (existing != null) return existing
                val recovered = SegmentWriter.recover(targetPath, repair = false)
                val matching = recovered.frames.firstOrNull {
                    it.recordType == STRUCTURAL_SUMMARY_RECORD_TYPE &&
                        it.payload.size >= PersistedSegmentIdentity.ID_SIZE &&
                        it.payload.copyOfRange(0, PersistedSegmentIdentity.ID_SIZE).contentEquals(idBytes)
                }
                val offset = if (matching != null) {
                    matching.offset
                } else {
                    when (target.append(
                        STRUCTURAL_SUMMARY_RECORD_TYPE,
                        PolicyTaggedRecord(
                            categoryMask = STRUCTURAL_SUMMARY_CATEGORY,
                            acceptedEpoch = recovered.header.policyGeneration,
                            priority = RecordPriority.CRASH_ANR,
                            payload = idBytes + canonicalBody,
                        ),
                    )) {
                        is SegmentAppendResult.Appended -> {
                            target.seal()
                            SegmentWriter.recover(targetPath, repair = false).frames.last().offset
                        }
                        is SegmentAppendResult.Dropped -> throw IllegalStateException("summary import denied by target policy")
                        is SegmentAppendResult.DroppedQuota -> throw SegmentException.Quota
                    }
                }
                if (crashInjector?.afterTargetAppendBeforeAcknowledgement() == false) {
                    throw SummaryImportInterrupted
                }
                val targetId = SegmentWriter.recover(targetPath, repair = false).header.identity.segmentId
                val acknowledgement = SummaryImportAcknowledgement(sourceSpoolId, targetId, offset, summaryId)
                Files.createDirectories(acknowledgementRoot)
                forceWrite(acknowledgementPath(sourceSpoolId), encodeAcknowledgement(acknowledgement).toByteArray())
                return acknowledgement
            }

            fun acknowledgement(sourceSpoolId: String): SummaryImportAcknowledgement? {
                if (canonicalId(sourceSpoolId) == null) return null
                val path = acknowledgementPath(sourceSpoolId)
                val encoded = readBoundedRegularFile(path, MAX_ACKNOWLEDGEMENT_BYTES) ?: return null
                if (encoded.any { it.toInt() !in 0..0x7f }) return null
                val text = encoded.toString(Charsets.US_ASCII)
                if (text.trim() != text) return null
                val fields = text.split('|')
                if (fields.size != 4) return null
                val acknowledgement = try {
                    val targetId = canonicalId(fields[1]) ?: return null
                    if (fields[0] != sourceSpoolId ||
                        canonicalId(fields[0]) == null ||
                        canonicalId(fields[3]) == null
                    ) {
                        return null
                    }
                    SummaryImportAcknowledgement(fields[0], targetId, fields[2].toLong(), fields[3])
                } catch (_: IllegalArgumentException) {
                    return null
                }
                if (acknowledgement.offset < 0L) return null
                val recovered = runCatching {
                    SegmentWriter.recover(targetPath, repair = false)
                }.getOrNull() ?: return null
                if (!recovered.header.identity.segmentId.contentEquals(acknowledgement.targetSegmentId)) {
                    return null
                }
                val summaryId = canonicalId(acknowledgement.summaryId) ?: return null
                val frame = recovered.frames.firstOrNull { it.offset == acknowledgement.offset } ?: return null
                if (frame.recordType != STRUCTURAL_SUMMARY_RECORD_TYPE ||
                    frame.payload.size < PersistedSegmentIdentity.ID_SIZE ||
                    !frame.payload.copyOfRange(0, PersistedSegmentIdentity.ID_SIZE).contentEquals(summaryId)
                ) {
                    return null
                }
                return acknowledgement
            }

            private fun acknowledgementPath(sourceSpoolId: String): Path =
                acknowledgementRoot.resolve("$sourceSpoolId.tbimportack")

            private fun encodeAcknowledgement(value: SummaryImportAcknowledgement): String =
                listOf(value.sourceSpoolId, encode(value.targetSegmentId), value.offset, value.summaryId).joinToString("|")

            private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
            private fun canonicalId(value: String): ByteArray? {
                val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return null
                return decoded.takeIf {
                    it.size == PersistedSegmentIdentity.ID_SIZE && encode(it) == value
                }
            }

            private companion object {
                const val STRUCTURAL_SUMMARY_RECORD_TYPE = 1
                const val STRUCTURAL_SUMMARY_CATEGORY = 1L
                const val STRUCTURAL_SUMMARY_BODY_BYTES = 18
                const val MAX_ACKNOWLEDGEMENT_BYTES = 512L
            }
        }

        /** Simulated process death used only by the crash-recovery import test. */
        data object SummaryImportInterrupted : IllegalStateException()

        sealed interface GeneratedRecordAppendResult {
            data class Appended(val sequence: Long) : GeneratedRecordAppendResult
            data class Dropped(val reason: GateResult) : GeneratedRecordAppendResult
            data class DroppedQuota(val priority: RecordPriority) : GeneratedRecordAppendResult
            data object Ignored : GeneratedRecordAppendResult
        }

        /**
         * The sole generated-record-to-segment adapter. It accepts generated values only, applies the
         * current writer gate at construction and append, and exposes a bounded last result for hooks.
         */
        class GeneratedRecordSegmentAdapter(
            private val writer: SegmentWriter,
            private val policyGate: WriterPolicyGate,
        ) : Diagnostics {
            @Volatile private var latest: GeneratedRecordAppendResult = GeneratedRecordAppendResult.Ignored

            @Synchronized
            override fun eventEnabled(eventId: GeneratedEventId): Boolean {
                val descriptor = descriptor(eventId)
                return when (val accepted = policyGate.accept(descriptor.category, descriptor.priority, byteArrayOf())) {
                    is GateAcceptance.Accepted -> true
                    is GateAcceptance.Rejected -> {
                        latest = GeneratedRecordAppendResult.Dropped(accepted.reason)
                        false
                    }
                }
            }

            @Synchronized
            override fun record(value: GeneratedRecord, context: DiagnosticContext?) {
                recordPayload(value, encode(value))
            }

            /**
             * Persists an exact internal source identity ahead of the generated body. Recovery
             * consumes this prefix before package transformation so it can never be disclosed.
             */
            @Synchronized
            fun recordWithInternalIdentity(
                value: GeneratedRecord,
                internalIdentity: ByteArray,
            ): GeneratedRecordAppendResult {
                require(internalIdentity.size == PersistedSegmentIdentity.ID_SIZE)
                return recordPayload(value, internalIdentity.copyOf() + encode(value))
            }

            private fun recordPayload(
                value: GeneratedRecord,
                payload: ByteArray,
            ): GeneratedRecordAppendResult {
                val descriptor = descriptor(value.eventId)
                latest = when (val accepted = policyGate.accept(descriptor.category, descriptor.priority, payload)) {
                    is GateAcceptance.Rejected -> GeneratedRecordAppendResult.Dropped(accepted.reason)
                    is GateAcceptance.Accepted -> when (
                        val appended = if (descriptor.priority == RecordPriority.CRASH_ANR) {
                            writer.appendCritical(value.eventId.stableId, accepted.record)
                        } else {
                            writer.append(value.eventId.stableId, accepted.record)
                        }
                    ) {
                        is SegmentAppendResult.Appended -> GeneratedRecordAppendResult.Appended(appended.sequence)
                        is SegmentAppendResult.Dropped -> GeneratedRecordAppendResult.Dropped(appended.reason)
                        is SegmentAppendResult.DroppedQuota -> GeneratedRecordAppendResult.DroppedQuota(appended.priority)
                    }
                }
                return latest
            }

            fun latestResult(): GeneratedRecordAppendResult = latest

            private fun descriptor(eventId: GeneratedEventId): Descriptor = when (eventId) {
                GeneratedEventId.STRUCTURALSUMMARY -> Descriptor(1L, RecordPriority.CRASH_ANR)
                GeneratedEventId.EMERGENCYRECORD -> Descriptor(2L, RecordPriority.CRASH_ANR)
                GeneratedEventId.BREADCRUMB -> Descriptor(4L, RecordPriority.BREADCRUMB)
                GeneratedEventId.HANDLEDERROR -> Descriptor(8L, RecordPriority.HANDLED_ERROR)
                GeneratedEventId.MANAGEDCRASH -> Descriptor(16L, RecordPriority.CRASH_ANR)
                GeneratedEventId.RUSTPANIC -> Descriptor(32L, RecordPriority.CRASH_ANR)
                GeneratedEventId.ANRCANDIDATE -> Descriptor(64L, RecordPriority.CRASH_ANR)
                GeneratedEventId.OSEXIT -> Descriptor(128L, RecordPriority.CRASH_ANR)
            }

            private fun encode(value: GeneratedRecord): ByteArray = GeneratedRecordCodec.encode(value)

            private data class Descriptor(val category: Long, val priority: RecordPriority)
        }

        /** Storage-backed installation seam for the bounded JVM wrapper. */
        class JvmCaptureStorageAdapter(private val records: GeneratedRecordSegmentAdapter) {
            fun install(previous: Thread.UncaughtExceptionHandler?, policy: JvmCapturePolicy = JvmCapturePolicy()): Thread.UncaughtExceptionHandler =
                TraceboxUncaughtExceptionHandler(previous, policy) { captured ->
                    val first = captured.causes.firstOrNull()
                    GeneratedDiagnostics.managedCrash(
                        records,
                        primary_exception_code = (first?.type?.hashCode() ?: 0).toUInt(),
                        cause_count = captured.causes.size.toUShort(),
                        frame_count = (first?.frames?.size ?: 0).toUShort(),
                        flags = if (captured.causes.any { it.cycle }) 1u else 0u,
                    )
                }

            fun latestResult(): GeneratedRecordAppendResult = records.latestResult()
        }

        sealed interface EmergencyIngestionResult {
            data class Ingested(val sequence: Long) : EmergencyIngestionResult
            data class Dropped(val reason: GateResult) : EmergencyIngestionResult
            data object InvalidOrIncomplete : EmergencyIngestionResult
        }

        /** Startup-only reader for the Phase 0 slot; it never participates in the signal-handler path. */
        class EmergencyStartupIngestor(
            private val slot: Path,
            private val records: GeneratedRecordSegmentAdapter,
        ) {
            fun ingest(): EmergencyIngestionResult {
                val bytes = readExactStorageFile(slot, EMERGENCY_RECORD_SIZE)
                    ?: return EmergencyIngestionResult.InvalidOrIncomplete
                val record = decodeEmergency(bytes) ?: return EmergencyIngestionResult.InvalidOrIncomplete
                GeneratedDiagnostics.emergencyRecord(
                    records,
                    record.slotSequence.toULong(),
                    record.policyEpoch.toULong(),
                    record.signalNumber,
                    record.signalCode,
                    record.processRole.toUInt(),
                    record.threadRole.toUInt(),
                    record.flags.toULong(),
                )
                return when (val result = records.latestResult()) {
                    is GeneratedRecordAppendResult.Appended -> {
                        forceWrite(slot, ByteArray(EMERGENCY_RECORD_SIZE))
                        EmergencyIngestionResult.Ingested(result.sequence)
                    }
                    is GeneratedRecordAppendResult.Dropped -> EmergencyIngestionResult.Dropped(result.reason)
                    is GeneratedRecordAppendResult.DroppedQuota -> EmergencyIngestionResult.Dropped(GateResult.Denied)
                    GeneratedRecordAppendResult.Ignored -> EmergencyIngestionResult.InvalidOrIncomplete
                }
            }

            private fun decodeEmergency(bytes: ByteArray): EmergencyFields? {
                if (bytes.size != EMERGENCY_RECORD_SIZE ||
                    !bytes.copyOfRange(0, 8).contentEquals("TBEMERG1".toByteArray()) ||
                    readInt(bytes, 8) != 1 || readInt(bytes, 12) != EMERGENCY_RECORD_SIZE ||
                    readLong(bytes, 248) != EMERGENCY_COMPLETION ||
                    Crc32c.value(bytes, 0, 244) != readInt(bytes, 244)
                ) return null
                return EmergencyFields(
                    readLong(bytes, 48), readLong(bytes, 56), readInt(bytes, 80), readInt(bytes, 84),
                    readInt(bytes, 112), readInt(bytes, 116), readLong(bytes, 120),
                )
            }

            private fun readInt(bytes: ByteArray, offset: Int): Int =
                java.nio.ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            private fun readLong(bytes: ByteArray, offset: Int): Long =
                java.nio.ByteBuffer.wrap(bytes, offset, Long.SIZE_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN).long

            private data class EmergencyFields(
                val slotSequence: Long, val policyEpoch: Long, val signalNumber: Int, val signalCode: Int,
                val processRole: Int, val threadRole: Int, val flags: Long,
            )

            private companion object {
                const val EMERGENCY_RECORD_SIZE = 256
                const val EMERGENCY_COMPLETION = 0x5442454d434f4d50L
            }
        }
private fun forceWrite(path: Path, bytes: ByteArray) {
    if (Files.isSymbolicLink(path)) {
        throw java.io.IOException("symbolic-link storage file is forbidden")
    }
    FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
        LinkOption.NOFOLLOW_LINKS,
    ).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
}

internal fun readExactStorageFile(path: Path, expectedBytes: Int): ByteArray? {
    if (expectedBytes <= 0) return null
    return readBoundedRegularFile(path, expectedBytes.toLong())
        ?.takeIf { it.size == expectedBytes }
}

private fun readBoundedRegularFile(path: Path, maximumBytes: Long): ByteArray? {
    if (maximumBytes <= 0L ||
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    ) {
        return null
    }
    return try {
        FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val size = channel.size()
            if (size !in 1..maximumBytes || size > Int.MAX_VALUE) return null
            val bytes = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) return null
            }
            if (channel.size() == size) bytes else null
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }
}
