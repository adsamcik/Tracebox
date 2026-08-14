package dev.tracebox.storage

import dev.tracebox.api.Crc32c
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A named writer or subsystem that must stop before any owned data is removed.
 *
 * The callback must be bounded and idempotent because every retry invokes it again.
 */
class StorageQuiesceParticipant(
    val id: String,
    private val action: () -> Boolean,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}")))
    }

    internal fun quiesce(): Boolean = action()
}

/**
 * Commits the durable global policy epoch that rejects new capture.
 *
 * The callback must be bounded and idempotent because every retry invokes it again.
 */
fun interface StorageDeletionDenyCommit {
    fun commit(): Boolean
}

/** Rechecks the durable global deny after the UID-wide mutation barrier is acquired. */
fun interface StorageDeletionDenyVerification {
    fun isCommitted(): Boolean
}

/** Durable phases of the UID-wide storage deletion transaction. */
enum class StorageTreeDeletionState {
    REQUESTED,
    DENY_COMMITTED,
    QUIESCED,
    STORES_MARKED_INELIGIBLE,
    DELETING,
    COMPLETE,
    PENDING_FAILURE,
}

enum class StorageTreeDeletionFailureReason {
    JOURNAL_QUOTA_REJECTED,
    MARKER_QUOTA_REJECTED,
    DENY_COMMIT_REJECTED,
    QUIESCE_REJECTED,
    OWNERSHIP_PARTIAL,
    ROOT_NOT_CLAIMED,
    ROOT_SCAN_LIMIT,
    UNSAFE_PATH,
    DELETE_FAILED,
    BATCH_LIMIT,
    IO,
}

data class StorageTreeDeletionFailure(
    val reason: StorageTreeDeletionFailureReason,
    val participantId: String? = null,
    val rootId: String? = null,
    val relativePath: String? = null,
    val ownershipReason: StorageOwnershipFailureReason? = null,
)

sealed interface StorageTreeDeletionReport {
    val state: StorageTreeDeletionState
    val deletedFiles: Int

    data class Complete(
        override val deletedFiles: Int,
        val releasedReservations: Int,
        val journalGeneration: Long,
    ) : StorageTreeDeletionReport {
        override val state: StorageTreeDeletionState = StorageTreeDeletionState.COMPLETE
    }

    data class Pending(
        override val state: StorageTreeDeletionState,
        override val deletedFiles: Int,
        val releasedReservations: Int,
        val remainingFiles: Int?,
        val failures: List<StorageTreeDeletionFailure>,
    ) : StorageTreeDeletionReport
}

data class StorageDeletionBoundary(
    val state: StorageTreeDeletionState,
    val rootId: String? = null,
    val relativePath: String? = null,
)

/** Test/process-death seam. Returning false simulates death immediately after the boundary. */
fun interface StorageTreeDeletionCrashInjector {
    fun after(boundary: StorageDeletionBoundary): Boolean
}

class StorageTreeDeletionInterrupted(
    val boundary: StorageDeletionBoundary,
) : IllegalStateException("injected storage deletion interruption at $boundary")

/**
 * Bounded, crash-resumable deletion across all roots owned by [UidWideStorageReconciler].
 *
 * Each invocation independently commits the global deny epoch and obtains every quiesce
 * acknowledgement. A partial ownership scan blocks data deletion. Claimed roots are permanently
 * marked ineligible before any data file is removed, so restarted or late writers can fail closed
 * through [TraceboxOwnedStorageRoot.isEligible].
 */
class JournaledStorageTreeDeletion(
    private val ownership: UidWideStorageReconciler,
    transactionId: String,
    private val denyCommit: StorageDeletionDenyCommit,
    private val denyVerification: StorageDeletionDenyVerification,
    quiesceParticipants: List<StorageQuiesceParticipant>,
    private val maxDeletesPerRun: Int = DEFAULT_MAX_DELETES_PER_RUN,
    private val journalSlotBytes: Int = DEFAULT_JOURNAL_SLOT_BYTES,
) {
    private val participants = quiesceParticipants.toList()
    private val journalA: Path
    private val journalB: Path

    init {
        require(transactionId.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}")))
        require(participants.isNotEmpty() && participants.size <= MAX_PARTICIPANTS)
        require(participants.map(StorageQuiesceParticipant::id).distinct().size == participants.size)
        require(maxDeletesPerRun in 1..MAX_DELETES_PER_RUN)
        require(journalSlotBytes in MIN_JOURNAL_SLOT_BYTES..MAX_JOURNAL_SLOT_BYTES)
        journalA = ownership.controlPath("delete-${transactionId}-a")
        journalB = ownership.controlPath("delete-${transactionId}-b")
    }

    /**
     * Starts or resumes deletion. Pending reports are deliberately retryable; every retry
     * re-quiesces and reconciles rather than trusting an acknowledgement from an older process.
     */
    fun deleteAll(injector: StorageTreeDeletionCrashInjector? = null): StorageTreeDeletionReport {
        val progress = DeletionProgress()
        return try {
            if (!reserveJournals()) {
                return pending(
                    state = StorageTreeDeletionState.PENDING_FAILURE,
                    failures = listOf(failure(StorageTreeDeletionFailureReason.JOURNAL_QUOTA_REJECTED)),
                )
            }

            transition(StorageTreeDeletionState.REQUESTED, injector)
            if (!runCatching { denyCommit.commit() }.getOrDefault(false)) {
                transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
                return pending(
                    StorageTreeDeletionState.PENDING_FAILURE,
                    failures = listOf(failure(StorageTreeDeletionFailureReason.DENY_COMMIT_REJECTED)),
                )
            }
            transition(StorageTreeDeletionState.DENY_COMMITTED, injector)
            val quiesceFailures = participants.mapNotNull { participant ->
                val accepted = runCatching { participant.quiesce() }.getOrDefault(false)
                if (accepted) null else StorageTreeDeletionFailure(
                    reason = StorageTreeDeletionFailureReason.QUIESCE_REJECTED,
                    participantId = participant.id,
                )
            }
            if (quiesceFailures.isNotEmpty()) {
                transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
                return pending(StorageTreeDeletionState.PENDING_FAILURE, failures = quiesceFailures)
            }
            transition(StorageTreeDeletionState.QUIESCED, injector)

            ownership.withStorageMutationBarrier {
                if (!runCatching { denyVerification.isCommitted() }.getOrDefault(false)) {
                    transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
                    pending(
                        StorageTreeDeletionState.PENDING_FAILURE,
                        failures = listOf(failure(StorageTreeDeletionFailureReason.DENY_COMMIT_REJECTED)),
                    )
                } else {
                    deleteUnderMutationBarrier(injector, progress)
                }
            }
        } catch (interrupted: StorageTreeDeletionInterrupted) {
            throw interrupted
        } catch (_: JournalReservationRejected) {
            pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                progress.deleted,
                progress.released,
                failures = listOf(failure(StorageTreeDeletionFailureReason.JOURNAL_QUOTA_REJECTED)),
            )
        } catch (_: IOException) {
            pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                progress.deleted,
                progress.released,
                failures = listOf(failure(StorageTreeDeletionFailureReason.IO)),
            )
        } catch (_: UncheckedIOException) {
            pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                progress.deleted,
                progress.released,
                failures = listOf(failure(StorageTreeDeletionFailureReason.IO)),
            )
        } catch (_: UidWideQuotaCoordinator.UidQuotaLedgerException) {
            pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                progress.deleted,
                progress.released,
                failures = listOf(failure(StorageTreeDeletionFailureReason.IO)),
            )
        } catch (_: StorageMutationBarrierException) {
            pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                progress.deleted,
                progress.released,
                failures = listOf(failure(StorageTreeDeletionFailureReason.IO)),
            )
        }
    }

    private fun deleteUnderMutationBarrier(
        injector: StorageTreeDeletionCrashInjector?,
        progress: DeletionProgress,
    ): StorageTreeDeletionReport {
        val rootFailures = markRootsIneligible()
        if (rootFailures.isNotEmpty()) {
            transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
            return pending(StorageTreeDeletionState.PENDING_FAILURE, failures = rootFailures)
        }
        transition(StorageTreeDeletionState.STORES_MARKED_INELIGIBLE, injector)

        val before = ownership.reconcile()
        if (before is StorageOwnershipReport.Partial) {
            transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
            return pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                failures = ownershipFailures(before),
            )
        }
        transition(StorageTreeDeletionState.DELETING, injector)

        val initialScan = scanCandidates()
        if (initialScan.failures.isNotEmpty()) {
            transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
            return pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                remainingFiles = initialScan.paths.size,
                failures = initialScan.failures,
            )
        }

        for (candidate in initialScan.paths.take(maxDeletesPerRun)) {
            try {
                if (Files.deleteIfExists(candidate.path)) {
                    progress.deleted++
                    val boundary = StorageDeletionBoundary(
                        StorageTreeDeletionState.DELETING,
                        candidate.root.id,
                        candidate.relative,
                    )
                    if (injector?.after(boundary) == false) {
                        throw StorageTreeDeletionInterrupted(boundary)
                    }
                    if (ownership.releaseReservation(ownership.accountingKey(candidate.root, candidate.path))) {
                        progress.released++
                    }
                }
            } catch (_: IOException) {
                transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
                return pending(
                    StorageTreeDeletionState.PENDING_FAILURE,
                    progress.deleted,
                    progress.released,
                    initialScan.paths.size - progress.deleted,
                    listOf(
                        StorageTreeDeletionFailure(
                            StorageTreeDeletionFailureReason.DELETE_FAILED,
                            rootId = candidate.root.id,
                            relativePath = candidate.relative,
                        ),
                    ),
                )
            }
        }

        val after = ownership.reconcile()
        if (after is StorageOwnershipReport.Partial) {
            transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
            return pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                progress.deleted,
                progress.released,
                failures = ownershipFailures(after),
            )
        }
        val remaining = scanCandidates()
        if (remaining.failures.isNotEmpty()) {
            transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
            return pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                progress.deleted,
                progress.released,
                remaining.paths.size,
                remaining.failures,
            )
        }
        if (remaining.paths.isNotEmpty()) {
            transition(StorageTreeDeletionState.PENDING_FAILURE, injector)
            return pending(
                StorageTreeDeletionState.PENDING_FAILURE,
                progress.deleted,
                progress.released,
                remaining.paths.size,
                listOf(failure(StorageTreeDeletionFailureReason.BATCH_LIMIT)),
            )
        }

        val finalJournal = transition(StorageTreeDeletionState.COMPLETE, injector)
        return StorageTreeDeletionReport.Complete(
            progress.deleted,
            progress.released,
            finalJournal.generation,
        )
    }

    fun currentState(): StorageTreeDeletionState? =
        runCatching {
            ownership.withStorageMutationBarrier { loadJournal().latest?.state }
        }.getOrNull()

    private fun reserveJournals(): Boolean {
        ownership.prepareControlRoot()
        return ownership.ensureControlReservation(journalA, journalSlotBytes.toLong()) &&
            ownership.ensureControlReservation(journalB, journalSlotBytes.toLong())
    }

    private fun markRootsIneligible(): List<StorageTreeDeletionFailure> {
        val failures = mutableListOf<StorageTreeDeletionFailure>()
        for (root in ownership.roots) {
            if (!TraceboxOwnedStorageRoot.isClaimed(root.path)) {
                failures += StorageTreeDeletionFailure(
                    StorageTreeDeletionFailureReason.ROOT_NOT_CLAIMED,
                    rootId = root.id,
                )
                continue
            }
            val marker = root.path.resolve(TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE)
            if (TraceboxOwnedStorageRoot.isIneligible(root.path) &&
                !TraceboxOwnedStorageRoot.hasAmbiguousIneligibleMarkers(root.path)
            ) {
                continue
            }
            val key = ownership.accountingKey(root, marker)
            if (!ownership.ensureControlReservation(key, MARKER_RESERVATION_BYTES)) {
                failures += StorageTreeDeletionFailure(
                    StorageTreeDeletionFailureReason.MARKER_QUOTA_REJECTED,
                    rootId = root.id,
                    relativePath = TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE,
                )
                continue
            }
            try {
                TraceboxOwnedStorageRoot.markIneligible(root.path)
            } catch (_: IOException) {
                failures += StorageTreeDeletionFailure(
                    StorageTreeDeletionFailureReason.IO,
                    rootId = root.id,
                    relativePath = TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE,
                )
            } catch (_: RuntimeException) {
                failures += StorageTreeDeletionFailure(
                    StorageTreeDeletionFailureReason.UNSAFE_PATH,
                    rootId = root.id,
                    relativePath = TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE,
                )
            }
        }
        return failures
    }

    private fun scanCandidates(): CandidateScan {
        val paths = mutableListOf<Candidate>()
        val failures = mutableListOf<StorageTreeDeletionFailure>()
        for (root in ownership.roots) {
            if (!TraceboxOwnedStorageRoot.isClaimed(root.path)) {
                failures += StorageTreeDeletionFailure(
                    StorageTreeDeletionFailureReason.ROOT_NOT_CLAIMED,
                    rootId = root.id,
                )
                continue
            }
            var visited = 0
            var files = 0
            Files.walk(root.path, root.maxDepth + 1).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    val path = iterator.next()
                    if (path == root.path) continue
                    visited++
                    val relative = root.relative(path)
                    if (visited > root.maxFiles * MAX_VISITED_MULTIPLIER) {
                        failures += StorageTreeDeletionFailure(
                            StorageTreeDeletionFailureReason.ROOT_SCAN_LIMIT,
                            rootId = root.id,
                            relativePath = relative,
                        )
                        break
                    }
                    if (root.path.relativize(path).nameCount > root.maxDepth) {
                        failures += StorageTreeDeletionFailure(
                            StorageTreeDeletionFailureReason.ROOT_SCAN_LIMIT,
                            rootId = root.id,
                            relativePath = relative,
                        )
                        break
                    }
                    if (isControlOrPreserved(root, relative)) continue
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                    if (Files.isSymbolicLink(path) ||
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        failures += StorageTreeDeletionFailure(
                            StorageTreeDeletionFailureReason.UNSAFE_PATH,
                            rootId = root.id,
                            relativePath = relative,
                        )
                        continue
                    }
                    files++
                    if (files > root.maxFiles) {
                        failures += StorageTreeDeletionFailure(
                            StorageTreeDeletionFailureReason.ROOT_SCAN_LIMIT,
                            rootId = root.id,
                            relativePath = relative,
                        )
                        break
                    }
                    paths += Candidate(root, relative, path)
                }
            }
        }
        return CandidateScan(
            paths.sortedWith(compareBy<Candidate>({ it.root.id }, { it.relative })),
            failures,
        )
    }

    private fun isControlOrPreserved(root: OwnedStorageRoot, relative: String): Boolean {
        if (TraceboxOwnedStorageRoot.isInternalMarker(relative)) return true
        if (relative == UidWideStorageMutationBarrier.LOCK_FILE_NAME) return true
        if (root.preservedRelativePaths.any { relative == it || relative.startsWith("$it/") }) return true
        if (root.path != ownership.accountingRoot) return false
        return relative == CONTROL_DIRECTORY ||
            relative.startsWith("$CONTROL_DIRECTORY/") ||
            relative in QUOTA_COORDINATOR_FILES
    }

    private fun ownershipFailures(report: StorageOwnershipReport.Partial): List<StorageTreeDeletionFailure> =
        if (report.failures.isEmpty()) {
            listOf(failure(StorageTreeDeletionFailureReason.OWNERSHIP_PARTIAL))
        } else {
            report.failures.map {
                StorageTreeDeletionFailure(
                    StorageTreeDeletionFailureReason.OWNERSHIP_PARTIAL,
                    rootId = it.rootId,
                    relativePath = it.relativePath,
                    ownershipReason = it.reason,
                )
            }
        }

    private fun transition(
        state: StorageTreeDeletionState,
        injector: StorageTreeDeletionCrashInjector?,
    ): Journal = ownership.withStorageMutationBarrier {
        val loaded = loadJournal()
        val target = if (loaded.latestPath == journalA) journalB else journalA
        if (!ownership.ensureControlReservation(target, journalSlotBytes.toLong())) {
            throw JournalReservationRejected
        }
        val journal = Journal((loaded.latest?.generation ?: 0L) + 1L, state)
        persistJournal(journal, target)
        val boundary = StorageDeletionBoundary(state)
        if (injector?.after(boundary) == false) throw StorageTreeDeletionInterrupted(boundary)
        journal
    }

    private fun loadJournal(): JournalLoad {
        val first = readJournal(journalA)
        val second = readJournal(journalB)
        val latest = listOfNotNull(first?.let { journalA to it }, second?.let { journalB to it })
            .maxByOrNull { it.second.generation }
        return JournalLoad(latest?.first, latest?.second)
    }

    private fun readJournal(path: Path): Journal? {
        val encoded = readBoundedJournalFile(path, journalSlotBytes) ?: return null
        if (encoded.size < Int.SIZE_BYTES) return null
        val contentSize = encoded.size - Int.SIZE_BYTES
        val expected = ByteBuffer.wrap(encoded, contentSize, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        if (Crc32c.value(encoded, 0, contentSize) != expected) return null
        val fields = encoded.copyOf(contentSize).toString(Charsets.US_ASCII).trim().split('|')
        if (fields.size != 3 || fields[0] != JOURNAL_MAGIC) return null
        val generation = fields[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val state = runCatching { StorageTreeDeletionState.valueOf(fields[2]) }.getOrNull() ?: return null
        return Journal(generation, state)
    }

    private fun persistJournal(journal: Journal, path: Path) {
        val content = "$JOURNAL_MAGIC|${journal.generation}|${journal.state.name}\n"
            .toByteArray(Charsets.US_ASCII)
        check(content.size + Int.SIZE_BYTES <= journalSlotBytes)
        val encoded = ByteBuffer.allocate(content.size + Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(content)
            .putInt(Crc32c.value(content))
            .array()
        if (Files.isSymbolicLink(path)) throw IOException("symbolic-link deletion journal is forbidden")
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(encoded)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        if (readJournal(path) != journal) throw IOException("storage deletion journal verification failed")
    }

    private fun pending(
        state: StorageTreeDeletionState,
        deleted: Int = 0,
        released: Int = 0,
        remainingFiles: Int? = null,
        failures: List<StorageTreeDeletionFailure>,
    ): StorageTreeDeletionReport.Pending =
        StorageTreeDeletionReport.Pending(state, deleted, released, remainingFiles, failures)

    private fun failure(reason: StorageTreeDeletionFailureReason): StorageTreeDeletionFailure =
        StorageTreeDeletionFailure(reason)

    private data class Candidate(val root: OwnedStorageRoot, val relative: String, val path: Path)
    private data class CandidateScan(
        val paths: List<Candidate>,
        val failures: List<StorageTreeDeletionFailure>,
    )
    private data class Journal(val generation: Long, val state: StorageTreeDeletionState)
    private data class JournalLoad(val latestPath: Path?, val latest: Journal?)
    private data class DeletionProgress(var deleted: Int = 0, var released: Int = 0)
    private data object JournalReservationRejected : IllegalStateException()

    private companion object {
        const val JOURNAL_MAGIC = "tracebox-storage-delete-v1"
        const val CONTROL_DIRECTORY = ".tracebox-control"
        const val DEFAULT_MAX_DELETES_PER_RUN = 128
        const val MAX_DELETES_PER_RUN = 1_024
        const val DEFAULT_JOURNAL_SLOT_BYTES = 1_024
        const val MIN_JOURNAL_SLOT_BYTES = 256
        const val MAX_JOURNAL_SLOT_BYTES = 4_096
        const val MAX_PARTICIPANTS = 32
        const val MAX_VISITED_MULTIPLIER = 2
        const val MARKER_RESERVATION_BYTES = 64L
        val QUOTA_COORDINATOR_FILES = setOf(
            ".tracebox-uid-quota.lock",
            UidWideStorageMutationBarrier.LOCK_FILE_NAME,
            "tracebox-uid-quota-v1",
            "tracebox-uid-quota-v1.new",
        )
    }
}

private fun readBoundedJournalFile(path: Path, maxBytes: Int): ByteArray? {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
    FileChannel.open(
        path,
        StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS,
    ).use { channel ->
        val size = channel.size()
        if (size < 0L || size > maxBytes.toLong()) return null
        val buffer = ByteBuffer.allocate(size.toInt())
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0) return null
        }
        if (channel.size() != size) return null
        return buffer.array()
    }
}
