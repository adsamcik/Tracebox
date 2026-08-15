package dev.tracebox.storage

import dev.tracebox.api.Crc32c
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedStructuralSummary
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64

/**
 * Injected production implementation delegates to NativeRuntime.summarizeMinidump. The native
 * implementation independently opens the committed file with O_NOFOLLOW and performs a stable,
 * bounded read before invoking the Rust parser.
 */
fun interface CrashpadMinidumpSummarizer {
    fun summarize(committedRawPath: Path, maximumBytes: Int): IntArray?
}

/**
 * Appends a generated structural summary with [internalSummaryId] as its non-exported identity.
 * DURABLE also covers an idempotent recovery that found this exact ID in an already-forced frame.
 */
fun interface DurableStructuralSummaryAppender {
    fun append(
        internalSummaryId: ByteArray,
        summary: GeneratedStructuralSummary,
    ): DurableSummaryAppendResult
}

enum class DurableSummaryAppendResult {
    DURABLE,
    RETRY,
}

enum class CrashpadHandoffFailure {
    INVALID_NAME,
    NOT_REGULAR,
    EMPTY,
    TOO_LARGE,
    MISSING_OR_INVALID_PRECAPTURE_JOURNAL,
    UNSTABLE_OR_UNREADABLE,
    RAW_QUOTA_EXHAUSTED,
    RAW_ADOPTION_DEFERRED,
    INVALID_STRUCTURAL_SUMMARY,
    SUMMARY_IDENTITY_UNAVAILABLE,
    SUMMARY_APPEND_DEFERRED,
    CLEANUP_DEFERRED,
}

sealed interface CrashpadHandoffOutcome {
    val fileName: String

    data class Imported(
        override val fileName: String,
        val rawArtifactId: ByteArray,
        val summaryId: ByteArray,
    ) : CrashpadHandoffOutcome

    /** Positively invalid or unbound bytes were destroyed without being summarized. */
    data class Destroyed(
        override val fileName: String,
        val failure: CrashpadHandoffFailure,
        val sourceDeleted: Boolean,
    ) : CrashpadHandoffOutcome

    /** Infrastructure or policy prevented a durable import; every recoverable source is retained. */
    data class Retained(
        override val fileName: String,
        val failure: CrashpadHandoffFailure,
    ) : CrashpadHandoffOutcome
}

data class CrashpadHandoffBatch(
    val outcomes: List<CrashpadHandoffOutcome>,
    val truncated: Boolean,
    val retiredSpoolRecordsPurged: Int,
)

/**
 * Startup/coordinator-only bridge from Crashpad's rename handoff into ordinary generated records.
 *
 * The caller supplies the Rust-backed [identityDeriver] explicitly. Raw bytes never become package
 * eligible: they are atomically adopted into the bounded raw store, parsed into five ID-free
 * structural fields, and deleted only after the generated summary is durably appended (or found
 * idempotently).
 */
class CrashpadHandoffIngestor(
    handoffDirectory: Path,
    private val rawStore: RawArtifactStore,
    summarySpoolDirectory: Path,
    schemaFingerprint: ByteArray,
    identityDeriver: SummaryIdentityDeriver,
    private val summarizer: CrashpadMinidumpSummarizer,
    private val appender: DurableStructuralSummaryAppender,
    private val uidQuota: UidWideQuotaCoordinator? = null,
    private val maximumMinidumpBytes: Int = MAXIMUM_MINIDUMP_BYTES,
    private val extractorVersion: Int = EXTRACTOR_VERSION,
    private val storageEligibility: StorageMutationEligibility = StorageMutationEligibility.ALWAYS,
) {
    private val handoffDirectory = safeStorageRoot(handoffDirectory)
    private val schema = schemaFingerprint.copyOf()
    private val spool = StructuralSummarySpool(
        summarySpoolDirectory,
        uidQuota = uidQuota,
        identityDeriver = identityDeriver,
        storageEligibility = storageEligibility,
    )

    init {
        require(schema.size == ID_BYTES)
        require(maximumMinidumpBytes in 1..MAXIMUM_MINIDUMP_BYTES)
        require(extractorVersion >= 0)
    }

    /**
     * Performs bounded recovery. [truncated] requires another coordinator pass; it never authorizes
     * treating uninspected directory entries as valid.
     */
    fun ingest(maxFiles: Int = DEFAULT_BATCH_FILES): CrashpadHandoffBatch {
        require(maxFiles in 1..MAX_BATCH_FILES)
        val committed = rawStore.committedArtifacts(maxFiles, RawArtifactKind.CRASHPAD_MINIDUMP)
        if (committed.artifacts.isNotEmpty() || committed.truncated) {
            val outcomes = committed.artifacts.map { artifact ->
                val fileName = "${hex(artifact.id)}$HANDOFF_SUFFIX"
                ingestCommitted(fileName, artifact.id, artifact.path, handoffDirectory.resolve(fileName))
            }
            return CrashpadHandoffBatch(
                outcomes,
                truncated = committed.truncated || handoffHasEntries(),
                retiredSpoolRecordsPurged =
                    runCatching { spool.purgeRetired(MAX_BATCH_FILES) }.getOrDefault(0),
            )
        }
        if (hasSymbolicLinkComponent(handoffDirectory)) {
            return CrashpadHandoffBatch(emptyList(), truncated = true, retiredSpoolRecordsPurged = 0)
        }
        if (!Files.isDirectory(handoffDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return CrashpadHandoffBatch(emptyList(), truncated = false, retiredSpoolRecordsPurged = 0)
        }

        val paths = ArrayList<Path>(maxFiles)
        var truncated = false
        try {
            Files.list(handoffDirectory).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    if (paths.size == maxFiles) {
                        truncated = true
                        break
                    }
                    paths.add(iterator.next())
                }
            }
        } catch (_: IOException) {
            return CrashpadHandoffBatch(emptyList(), truncated = true, retiredSpoolRecordsPurged = 0)
        } catch (_: UncheckedIOException) {
            return CrashpadHandoffBatch(emptyList(), truncated = true, retiredSpoolRecordsPurged = 0)
        }

        val outcomes = paths.map(::ingestOne)
        val purged = runCatching { spool.purgeRetired(MAX_BATCH_FILES) }.getOrDefault(0)
        return CrashpadHandoffBatch(outcomes, truncated, purged)
    }

    private fun ingestOne(path: Path): CrashpadHandoffOutcome {
        val fileName = path.fileName?.toString().orEmpty()
        val rawId = decodeHandoffName(fileName)
            ?: return destroy(path, fileName, CrashpadHandoffFailure.INVALID_NAME)
        if (rawStore.journal(rawId)?.kind != RawArtifactKind.CRASHPAD_MINIDUMP) {
            return destroy(
                path,
                fileName,
                CrashpadHandoffFailure.MISSING_OR_INVALID_PRECAPTURE_JOURNAL,
            )
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            rawStore.deleteOwned(rawId, RawArtifactKind.CRASHPAD_MINIDUMP)
            return destroy(path, fileName, CrashpadHandoffFailure.NOT_REGULAR)
        }

        val size = try {
            Files.size(path)
        } catch (_: IOException) {
            return CrashpadHandoffOutcome.Retained(
                fileName,
                CrashpadHandoffFailure.UNSTABLE_OR_UNREADABLE,
            )
        }
        if (size == 0L) {
            rawStore.deleteOwned(rawId, RawArtifactKind.CRASHPAD_MINIDUMP)
            return destroy(path, fileName, CrashpadHandoffFailure.EMPTY)
        }
        if (size > maximumMinidumpBytes.toLong()) {
            rawStore.deleteOwned(rawId, RawArtifactKind.CRASHPAD_MINIDUMP)
            return destroy(path, fileName, CrashpadHandoffFailure.TOO_LARGE)
        }

        if (!rawStore.containsRaw(rawId, RawArtifactKind.CRASHPAD_MINIDUMP)) {
            if (!rawStore.adoptRaw(rawId, path, size)) {
                return CrashpadHandoffOutcome.Retained(
                    fileName,
                    CrashpadHandoffFailure.RAW_ADOPTION_DEFERRED,
                )
            }
        }
        val committedPath = rawStore.committedRawPath(rawId, RawArtifactKind.CRASHPAD_MINIDUMP)
            ?: return CrashpadHandoffOutcome.Retained(
                fileName,
                CrashpadHandoffFailure.UNSTABLE_OR_UNREADABLE,
            )
        return ingestCommitted(fileName, rawId, committedPath, path)
    }

    private fun ingestCommitted(
        fileName: String,
        rawId: ByteArray,
        committedPath: Path,
        source: Path?,
    ): CrashpadHandoffOutcome {
        if (rawStore.journal(rawId)?.kind != RawArtifactKind.CRASHPAD_MINIDUMP) {
            return CrashpadHandoffOutcome.Retained(
                fileName,
                CrashpadHandoffFailure.MISSING_OR_INVALID_PRECAPTURE_JOURNAL,
            )
        }
        val summarized = try {
            summarizer.summarize(committedPath, maximumMinidumpBytes)
        } catch (_: Exception) {
            null
        }
        val summary = decodeSummary(summarized)
        if (summary == null) {
            rawStore.deleteOwned(rawId, RawArtifactKind.CRASHPAD_MINIDUMP)
            if (source != null) deleteSource(source)
            return CrashpadHandoffOutcome.Destroyed(
                fileName,
                CrashpadHandoffFailure.INVALID_STRUCTURAL_SUMMARY,
                source == null || !Files.exists(source, LinkOption.NOFOLLOW_LINKS),
            )
        }

        val summaryId = try {
            spool.stageStructuralSummary(rawId, extractorVersion, schema, summary)
        } catch (_: Exception) {
            return CrashpadHandoffOutcome.Retained(
                fileName,
                CrashpadHandoffFailure.SUMMARY_IDENTITY_UNAVAILABLE,
            )
        }
        val appended = try {
            replayStaged()
            spool.isRetired(summaryId)
        } catch (_: Exception) {
            false
        }
        if (!appended) {
            return CrashpadHandoffOutcome.Retained(
                fileName,
                CrashpadHandoffFailure.SUMMARY_APPEND_DEFERRED,
            )
        }

        val decodedSummaryId = decodeSummaryId(summaryId)
            ?: return CrashpadHandoffOutcome.Retained(
                fileName,
                CrashpadHandoffFailure.SUMMARY_IDENTITY_UNAVAILABLE,
            )
        rawStore.deleteOwned(rawId, RawArtifactKind.CRASHPAD_MINIDUMP)
        val sourceDeleted = source == null || deleteSource(source)
        if (!sourceDeleted ||
            rawStore.containsRaw(rawId, RawArtifactKind.CRASHPAD_MINIDUMP) ||
            rawStore.journal(rawId) != null
        ) {
            return CrashpadHandoffOutcome.Retained(
                fileName,
                CrashpadHandoffFailure.CLEANUP_DEFERRED,
            )
        }
        return CrashpadHandoffOutcome.Imported(fileName, rawId, decodedSummaryId)
    }

    private fun replayStaged() {
        spool.replay { summaryId, canonicalBody ->
            val id = decodeSummaryId(summaryId) ?: throw SummaryAppendDeferred
            val record = try {
                GeneratedRecordCodec.decode(GeneratedEventId.STRUCTURALSUMMARY.stableId, canonicalBody)
                    as GeneratedStructuralSummary
            } catch (_: RuntimeException) {
                throw SummaryAppendDeferred
            }
            if (appender.append(id, record) != DurableSummaryAppendResult.DURABLE) {
                throw SummaryAppendDeferred
            }
        }
    }

    private fun destroy(
        path: Path,
        fileName: String,
        failure: CrashpadHandoffFailure,
    ): CrashpadHandoffOutcome.Destroyed =
        CrashpadHandoffOutcome.Destroyed(fileName, failure, deleteSource(path))

    private fun deleteSource(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized.parent != handoffDirectory || hasSymbolicLinkComponent(handoffDirectory)) {
            return false
        }
        return try {
            when (
                val guarded = guardedStorageMutation(
                    uidQuota,
                    StorageMutationEligibility.ALWAYS,
                ) {
                    val deleted = Files.deleteIfExists(path)
                    if (deleted) runCatching { uidQuota?.release(path) }
                    deleted || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value
                StorageMutationBarrierResult.Rejected -> false
            }
        } catch (_: IOException) {
            false
        } catch (_: StorageMutationBarrierException) {
            false
        }
    }

    private fun handoffHasEntries(): Boolean {
        if (hasSymbolicLinkComponent(handoffDirectory)) return true
        if (!Files.isDirectory(handoffDirectory, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.list(handoffDirectory).use { stream ->
                stream.findAny().isPresent
            }
        } catch (_: IOException) {
            true
        } catch (_: UncheckedIOException) {
            true
        }
    }

    private fun decodeSummary(values: IntArray?): GeneratedStructuralSummary? {
        if (values == null || values.size != SUMMARY_FIELD_COUNT ||
            values[0] < 0 || values[1] < 0 || values[2] < 0 ||
            values[4] !in 0..UShort.MAX_VALUE.toInt() ||
            values[5] != VALID_STREAM_PROFILE
        ) {
            return null
        }
        return GeneratedStructuralSummary(
            values[0].toUInt(),
            values[1].toUInt(),
            values[2].toUInt(),
            values[3].toUInt(),
            values[4].toUShort(),
        )
    }

    private fun decodeHandoffName(name: String): ByteArray? {
        if (!HANDOFF_NAME.matches(name)) return null
        val hex = name.removeSuffix(HANDOFF_SUFFIX)
        return ByteArray(ID_BYTES) { index ->
            ((hex[index * 2].digitToInt(16) shl 4) or hex[index * 2 + 1].digitToInt(16)).toByte()
        }.takeUnless { bytes -> bytes.all { it == 0.toByte() } }
    }

    private fun decodeSummaryId(value: String): ByteArray? {
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return null
        return decoded.takeIf {
            it.size == ID_BYTES &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value
        }
    }

    private fun hex(value: ByteArray): String =
        value.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data object SummaryAppendDeferred : IllegalStateException()

    companion object {
        const val MAXIMUM_MINIDUMP_BYTES = 16 * 1024 * 1024
        const val EXTRACTOR_VERSION = 1
        const val DEFAULT_BATCH_FILES = 16
        const val MAX_BATCH_FILES = 256
        private const val ID_BYTES = 32
        private const val SUMMARY_FIELD_COUNT = 6
        private const val VALID_STREAM_PROFILE = 1
        private const val HANDOFF_SUFFIX = ".dmp"
        private val HANDOFF_NAME = Regex("^[0-9a-f]{64}\\.dmp$")
    }
}

enum class CrashpadPendingRecoveryResult {
    NONE,
    RECOVERED,
    AMBIGUOUS,
    FAILED,
}

/**
 * Recovers the sole Crashpad pending dump left by a hard-killed handler.
 *
 * Crashpad does not persist the Tracebox raw identity in its database filename. Attribution is
 * therefore accepted only at the deliberately single-lease boundary: one regular bounded pending
 * dump, its optional exact Crashpad metadata sidecar, and one exact lifecycle registration that is
 * either incomplete or terminal only because the client died before handoff completed. Recovery is
 * permitted only while the handler is positively quiesced. Every other combination remains in
 * place and blocks rearming.
 */
class CrashpadPendingHandoffRecoverer(
    pendingDirectory: Path,
    lifecycleDirectory: Path,
    handoffDirectory: Path,
    private val rawStore: RawArtifactStore,
    private val uidQuota: UidWideQuotaCoordinator? = null,
) {
    private val pendingDirectory = safeStorageRoot(pendingDirectory)
    private val lifecycleDirectory = safeStorageRoot(lifecycleDirectory)
    private val handoffDirectory = safeStorageRoot(handoffDirectory)

    fun recover(handlerQuiesced: Boolean): CrashpadPendingRecoveryResult {
        if (!handlerQuiesced) return CrashpadPendingRecoveryResult.NONE
        return try {
            when (
                val guarded = guardedStorageMutation(
                    uidQuota,
                    StorageMutationEligibility.ALWAYS,
                ) {
                    recoverUnderMutationBarrier()
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value

                StorageMutationBarrierResult.Rejected -> CrashpadPendingRecoveryResult.FAILED
            }
        } catch (_: IOException) {
            CrashpadPendingRecoveryResult.FAILED
        } catch (_: SecurityException) {
            CrashpadPendingRecoveryResult.FAILED
        } catch (_: StorageMutationBarrierException) {
            CrashpadPendingRecoveryResult.FAILED
        }
    }

    private fun recoverUnderMutationBarrier(): CrashpadPendingRecoveryResult {
        if (hasSymbolicLinkComponent(pendingDirectory) ||
            hasSymbolicLinkComponent(lifecycleDirectory) ||
            hasSymbolicLinkComponent(handoffDirectory)
        ) {
            return CrashpadPendingRecoveryResult.AMBIGUOUS
        }
        val pending = when (val audit = auditPendingDirectory()) {
            PendingDirectoryAudit.Empty -> return CrashpadPendingRecoveryResult.NONE
            is PendingDirectoryAudit.MetadataOnly -> {
                if (audit.lock != null &&
                    retireSidecar(audit.lock) != CrashpadPendingRecoveryResult.NONE
                ) {
                    return CrashpadPendingRecoveryResult.FAILED
                }
                return retireSidecar(audit.metadata)
            }

            is PendingDirectoryAudit.Single -> {
                if (audit.report.lock == null) {
                    audit.report
                } else {
                    if (retireSidecar(audit.report.lock) !=
                        CrashpadPendingRecoveryResult.NONE
                    ) {
                        return CrashpadPendingRecoveryResult.FAILED
                    }
                    when (val withoutLock = auditPendingDirectory()) {
                        is PendingDirectoryAudit.Single -> withoutLock.report.takeIf {
                            it.lock == null && samePendingReportWithoutLock(audit.report, it)
                        } ?: return CrashpadPendingRecoveryResult.AMBIGUOUS

                        PendingDirectoryAudit.Empty,
                        is PendingDirectoryAudit.MetadataOnly,
                        PendingDirectoryAudit.Ambiguous,
                        -> return CrashpadPendingRecoveryResult.AMBIGUOUS

                        PendingDirectoryAudit.Failed ->
                            return CrashpadPendingRecoveryResult.FAILED
                    }
                }
            }

            PendingDirectoryAudit.Ambiguous -> return CrashpadPendingRecoveryResult.AMBIGUOUS
            PendingDirectoryAudit.Failed -> return CrashpadPendingRecoveryResult.FAILED
        }
        val registration = when (val audit = auditLifecycleDirectory()) {
            is LifecycleDirectoryAudit.SingleRecoverable -> audit.registration
            LifecycleDirectoryAudit.Ambiguous -> return CrashpadPendingRecoveryResult.AMBIGUOUS
            LifecycleDirectoryAudit.Failed -> return CrashpadPendingRecoveryResult.FAILED
        }
        if (!matchesRawJournal(registration.lifecycle)) {
            return CrashpadPendingRecoveryResult.AMBIGUOUS
        }
        when (handoffDirectoryEmpty()) {
            true -> Unit
            false -> return CrashpadPendingRecoveryResult.AMBIGUOUS
            null -> return CrashpadPendingRecoveryResult.FAILED
        }

        val destination = handoffDirectory.resolve(
            "${hex(registration.lifecycle.rawArtifactId)}.dmp",
        )
        if (destination.parent != handoffDirectory ||
            Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
        ) {
            return CrashpadPendingRecoveryResult.AMBIGUOUS
        }

        try {
            Files.createDirectories(handoffDirectory)
        } catch (_: IOException) {
            return CrashpadPendingRecoveryResult.FAILED
        }
        if (hasSymbolicLinkComponent(handoffDirectory) ||
            !Files.isDirectory(handoffDirectory, LinkOption.NOFOLLOW_LINKS)
        ) {
            return CrashpadPendingRecoveryResult.AMBIGUOUS
        }

        // Creating the destination directory and quota-ledger I/O are mutation points. Repeat the
        // complete census before attribution so a same-UID race cannot replace a dump or journal,
        // add a second lease, or populate handoff storage between the first audit and the rename.
        val confirmedPending = when (val audit = auditPendingDirectory()) {
            is PendingDirectoryAudit.Single -> audit.report
            PendingDirectoryAudit.Empty,
            is PendingDirectoryAudit.MetadataOnly,
            PendingDirectoryAudit.Ambiguous,
            -> return CrashpadPendingRecoveryResult.AMBIGUOUS

            PendingDirectoryAudit.Failed -> return CrashpadPendingRecoveryResult.FAILED
        }
        val confirmedRegistration = when (val audit = auditLifecycleDirectory()) {
            is LifecycleDirectoryAudit.SingleRecoverable -> audit.registration
            LifecycleDirectoryAudit.Ambiguous -> return CrashpadPendingRecoveryResult.AMBIGUOUS
            LifecycleDirectoryAudit.Failed -> return CrashpadPendingRecoveryResult.FAILED
        }
        if (!samePendingReport(pending, confirmedPending) ||
            !sameRegistration(registration, confirmedRegistration) ||
            !matchesRawJournal(confirmedRegistration.lifecycle)
        ) {
            return CrashpadPendingRecoveryResult.AMBIGUOUS
        }
        when (handoffDirectoryEmpty()) {
            true -> Unit
            false -> return CrashpadPendingRecoveryResult.AMBIGUOUS
            null -> return CrashpadPendingRecoveryResult.FAILED
        }

        val reservation = prepareQuotaTransfer(
            confirmedPending.dump.path,
            destination,
            confirmedPending.dump.bytes,
        ) ?: return CrashpadPendingRecoveryResult.FAILED
        val finalPending = when (val audit = auditPendingDirectory()) {
            is PendingDirectoryAudit.Single -> audit.report
            PendingDirectoryAudit.Empty,
            is PendingDirectoryAudit.MetadataOnly,
            PendingDirectoryAudit.Ambiguous,
            -> {
                rollbackPendingMove(
                    confirmedPending.dump.path,
                    destination,
                    confirmedPending.dump.bytes,
                    reservation,
                )
                return CrashpadPendingRecoveryResult.AMBIGUOUS
            }

            PendingDirectoryAudit.Failed -> {
                rollbackPendingMove(
                    confirmedPending.dump.path,
                    destination,
                    confirmedPending.dump.bytes,
                    reservation,
                )
                return CrashpadPendingRecoveryResult.FAILED
            }
        }
        val finalRegistration = when (val audit = auditLifecycleDirectory()) {
            is LifecycleDirectoryAudit.SingleRecoverable -> audit.registration
            LifecycleDirectoryAudit.Ambiguous -> {
                rollbackPendingMove(
                    confirmedPending.dump.path,
                    destination,
                    confirmedPending.dump.bytes,
                    reservation,
                )
                return CrashpadPendingRecoveryResult.AMBIGUOUS
            }

            LifecycleDirectoryAudit.Failed -> {
                rollbackPendingMove(
                    confirmedPending.dump.path,
                    destination,
                    confirmedPending.dump.bytes,
                    reservation,
                )
                return CrashpadPendingRecoveryResult.FAILED
            }
        }
        val finalHandoffEmpty = handoffDirectoryEmpty()
        if (!samePendingReport(confirmedPending, finalPending) ||
            !sameRegistration(confirmedRegistration, finalRegistration) ||
            !matchesRawJournal(finalRegistration.lifecycle) ||
            finalHandoffEmpty != true
        ) {
            rollbackPendingMove(
                confirmedPending.dump.path,
                destination,
                confirmedPending.dump.bytes,
                reservation,
            )
            return if (finalHandoffEmpty == null) {
                CrashpadPendingRecoveryResult.FAILED
            } else {
                CrashpadPendingRecoveryResult.AMBIGUOUS
            }
        }
        var handoffCommitted = false
        return try {
            Files.move(finalPending.dump.path, destination, StandardCopyOption.ATOMIC_MOVE)
            val moved = readPendingDump(destination)
            if (moved == null || !samePendingDumpAfterMove(finalPending.dump, moved)) {
                rollbackPendingMove(
                    finalPending.dump.path,
                    destination,
                    finalPending.dump.bytes,
                    reservation,
                )
                return CrashpadPendingRecoveryResult.FAILED
            }
            forceDirectory(handoffDirectory)
            handoffCommitted = true
            if (finalPending.metadata != null &&
                retireSidecar(finalPending.metadata) !=
                CrashpadPendingRecoveryResult.NONE
            ) {
                // The handoff is already authoritative. Keep it available for ingestion and let a
                // later quiesced startup retry cleanup of the exact orphaned sidecar.
                return CrashpadPendingRecoveryResult.FAILED
            }
            forceDirectory(pendingDirectory)
            CrashpadPendingRecoveryResult.RECOVERED
        } catch (_: IOException) {
            if (!handoffCommitted) {
                rollbackPendingMove(
                    finalPending.dump.path,
                    destination,
                    finalPending.dump.bytes,
                    reservation,
                )
            }
            CrashpadPendingRecoveryResult.FAILED
        } catch (_: UnsupportedOperationException) {
            if (!handoffCommitted) {
                rollbackPendingMove(
                    finalPending.dump.path,
                    destination,
                    finalPending.dump.bytes,
                    reservation,
                )
            }
            CrashpadPendingRecoveryResult.FAILED
        }
    }

    private fun auditPendingDirectory(): PendingDirectoryAudit {
        if (hasSymbolicLinkComponent(pendingDirectory)) {
            return PendingDirectoryAudit.Ambiguous
        }
        if (!Files.exists(pendingDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return PendingDirectoryAudit.Empty
        }
        if (!Files.isDirectory(pendingDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return PendingDirectoryAudit.Ambiguous
        }
        return try {
            Files.list(pendingDirectory).use { stream ->
                val iterator = stream.iterator()
                val entries = ArrayList<Path>(4)
                while (iterator.hasNext() && entries.size < 4) {
                    entries.add(iterator.next())
                }
                if (iterator.hasNext() || entries.size > 3) {
                    return PendingDirectoryAudit.Ambiguous
                }
                if (entries.isEmpty()) return PendingDirectoryAudit.Empty

                val dumps = entries.mapNotNull(::readPendingDump)
                val metadata = entries.mapNotNull(::readPendingMetadata)
                val locks = entries.mapNotNull(::readPendingLock)
                if (dumps.size + metadata.size + locks.size != entries.size ||
                    dumps.size > 1 ||
                    metadata.size > 1 ||
                    locks.size > 1
                ) {
                    return PendingDirectoryAudit.Ambiguous
                }
                if (dumps.size > 1) return PendingDirectoryAudit.Ambiguous
                if (dumps.isEmpty()) {
                    val sidecar = metadata.singleOrNull()
                        ?: return PendingDirectoryAudit.Ambiguous
                    val lock = locks.singleOrNull()
                    if (lock != null && lock.reportName != sidecar.reportName) {
                        return PendingDirectoryAudit.Ambiguous
                    }
                    return PendingDirectoryAudit.MetadataOnly(sidecar, lock)
                }

                val dump = dumps.single()
                val reportName = dumpReportName(dump.path)
                val sidecar = metadata.singleOrNull()
                val lock = locks.singleOrNull()
                if ((sidecar != null && sidecar.reportName != reportName) ||
                    (lock != null && lock.reportName != reportName)
                ) {
                    return PendingDirectoryAudit.Ambiguous
                }
                PendingDirectoryAudit.Single(PendingReport(dump, sidecar, lock))
            }
        } catch (_: IOException) {
            PendingDirectoryAudit.Failed
        } catch (_: UncheckedIOException) {
            PendingDirectoryAudit.Failed
        }
    }

    private fun readPendingDump(path: Path): PendingDump? {
        val normalized = path.toAbsolutePath().normalize()
        if ((normalized.parent != pendingDirectory && normalized.parent != handoffDirectory) ||
            !normalized.fileName.toString().endsWith(".dmp") ||
            hasSymbolicLinkComponent(normalized)
        ) {
            return null
        }
        val attributes = try {
            Files.readAttributes(
                normalized,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            return null
        }
        val bytes = attributes.size()
        return PendingDump(
            normalized,
            bytes,
            attributes.fileKey(),
            attributes.lastModifiedTime().toMillis(),
        ).takeIf {
            attributes.isRegularFile &&
                bytes in 1..CrashpadHandoffIngestor.MAXIMUM_MINIDUMP_BYTES.toLong()
        }
    }

    private fun readPendingMetadata(path: Path): PendingSidecar? =
        readPendingSidecar(
            path,
            CRASHPAD_METADATA_FILE,
            CRASHPAD_METADATA_BYTES.toLong(),
            PendingSidecarKind.METADATA,
        )

    private fun readPendingLock(path: Path): PendingSidecar? =
        readPendingSidecar(
            path,
            CRASHPAD_LOCK_FILE,
            CRASHPAD_LOCK_BYTES.toLong(),
            PendingSidecarKind.LOCK,
        )

    private fun readPendingSidecar(
        path: Path,
        pattern: Regex,
        expectedBytes: Long,
        kind: PendingSidecarKind,
    ): PendingSidecar? {
        val normalized = path.toAbsolutePath().normalize()
        val match = pattern.matchEntire(normalized.fileName?.toString().orEmpty())
            ?: return null
        if (normalized.parent != pendingDirectory || hasSymbolicLinkComponent(normalized)) {
            return null
        }
        val attributes = try {
            Files.readAttributes(
                normalized,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            return null
        }
        return PendingSidecar(
            path = normalized,
            reportName = match.groupValues[1],
            fileKey = attributes.fileKey(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
            kind = kind,
        ).takeIf {
            attributes.isRegularFile &&
                attributes.size() == expectedBytes
        }
    }

    private fun dumpReportName(path: Path): String? =
        CRASHPAD_DUMP_FILE.matchEntire(path.fileName?.toString().orEmpty())
            ?.groupValues
            ?.get(1)

    private fun retireSidecar(
        sidecar: PendingSidecar,
    ): CrashpadPendingRecoveryResult {
        val confirmed = when (sidecar.kind) {
            PendingSidecarKind.METADATA -> readPendingMetadata(sidecar.path)
            PendingSidecarKind.LOCK -> readPendingLock(sidecar.path)
        }
        if (confirmed == null || !samePendingSidecar(sidecar, confirmed)) {
            return CrashpadPendingRecoveryResult.AMBIGUOUS
        }
        return try {
            Files.delete(sidecar.path)
            forceDirectory(pendingDirectory)
            try {
                uidQuota?.release(sidecar.path)
            } catch (_: StorageMutationBarrierException) {
                // The sidecar deletion is authoritative. Quota startup reconciliation repairs a
                // stale ledger entry without putting a deleted ownership record back in place.
            }
            CrashpadPendingRecoveryResult.NONE
        } catch (_: IOException) {
            CrashpadPendingRecoveryResult.FAILED
        } catch (_: UnsupportedOperationException) {
            CrashpadPendingRecoveryResult.FAILED
        }
    }

    private fun auditLifecycleDirectory(): LifecycleDirectoryAudit {
        if (hasSymbolicLinkComponent(lifecycleDirectory) ||
            !Files.isDirectory(lifecycleDirectory, LinkOption.NOFOLLOW_LINKS)
        ) {
            return LifecycleDirectoryAudit.Ambiguous
        }
        return try {
            Files.list(lifecycleDirectory).use { stream ->
                val iterator = stream.iterator()
                var inspected = 0
                var recoverable: LifecycleRegistration? = null
                while (iterator.hasNext()) {
                    if (inspected++ == MAX_LIFECYCLE_FILES) {
                        return LifecycleDirectoryAudit.Ambiguous
                    }
                    when (val decoded = decodeJournal(iterator.next())) {
                        is JournalDecode.Incomplete -> {
                            if (recoverable != null) return LifecycleDirectoryAudit.Ambiguous
                            recoverable = decoded.registration
                        }

                        is JournalDecode.RecoverableTerminal -> {
                            if (recoverable != null) return LifecycleDirectoryAudit.Ambiguous
                            recoverable = decoded.registration
                        }

                        JournalDecode.Complete -> Unit
                        JournalDecode.Invalid -> return LifecycleDirectoryAudit.Ambiguous
                    }
                }
                recoverable?.let(LifecycleDirectoryAudit::SingleRecoverable)
                    ?: LifecycleDirectoryAudit.Ambiguous
            }
        } catch (_: IOException) {
            LifecycleDirectoryAudit.Failed
        } catch (_: UncheckedIOException) {
            LifecycleDirectoryAudit.Failed
        }
    }

    private fun decodeJournal(path: Path): JournalDecode {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized.parent != lifecycleDirectory || hasSymbolicLinkComponent(normalized)) {
            return JournalDecode.Invalid
        }
        val match = CLIENT_FILE_NAME.matchEntire(normalized.fileName?.toString().orEmpty())
            ?: return JournalDecode.Invalid
        val attributes = try {
            Files.readAttributes(
                normalized,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            return JournalDecode.Invalid
        }
        if (!attributes.isRegularFile || attributes.size() != CLIENT_JOURNAL_BYTES.toLong()) {
            return JournalDecode.Invalid
        }
        val bytes = readExact(normalized, CLIENT_JOURNAL_BYTES) ?: return JournalDecode.Invalid
        val registeredBytes = bytes.copyOfRange(0, CLIENT_RECORD_BYTES)
        val registered = decodeRecord(
            registeredBytes,
            CrashpadClientState.REGISTERED,
        ) ?: return JournalDecode.Invalid
        if (registered.pendingSequence != 0L) return JournalDecode.Invalid
        val role = match.groupValues[1].toUIntOrNull()?.takeIf { it != 0u }
            ?: return JournalDecode.Invalid
        val rawId = decodeHexId(match.groupValues[2]) ?: return JournalDecode.Invalid
        if (registered.processRole != role || !registered.rawArtifactId.contentEquals(rawId)) {
            return JournalDecode.Invalid
        }

        val terminalBytes = bytes.copyOfRange(CLIENT_RECORD_BYTES, CLIENT_JOURNAL_BYTES)
        if (terminalBytes.all { it == 0.toByte() }) {
            return JournalDecode.Incomplete(
                LifecycleRegistration(
                    normalized,
                    attributes.fileKey(),
                    attributes.lastModifiedTime().toMillis(),
                    registeredBytes,
                    registered,
                ),
            )
        }
        val terminal = decodeRecord(terminalBytes) ?: return JournalDecode.Invalid
        if (terminal.state == CrashpadClientState.REGISTERED ||
            !sameLifecycleIdentity(registered, terminal)
        ) {
            return JournalDecode.Invalid
        }
        return if (terminal.state == CrashpadClientState.DEAD ||
            terminal.state == CrashpadClientState.HANDOFF_FAILED
        ) {
            JournalDecode.RecoverableTerminal(
                LifecycleRegistration(
                    normalized,
                    attributes.fileKey(),
                    attributes.lastModifiedTime().toMillis(),
                    registeredBytes,
                    registered,
                ),
            )
        } else {
            JournalDecode.Complete
        }
    }

    private fun decodeRecord(
        record: ByteArray,
        requiredState: CrashpadClientState? = null,
    ): CrashpadClientLifecycle? {
        val buffer = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != CLIENT_MAGIC ||
            (buffer.short.toInt() and 0xffff) != CLIENT_VERSION
        ) {
            return null
        }
        val stateValue = buffer.short.toInt() and 0xffff
        val state = CrashpadClientState.entries.firstOrNull { it.wireValue == stateValue }
            ?: return null
        if (requiredState != null && state != requiredState) return null
        if (
            buffer.int != CLIENT_RECORD_BYTES
        ) {
            return null
        }
        val pid = buffer.int
        val uid = buffer.int.toUInt()
        val role = buffer.int.toUInt()
        if (buffer.int != 0) return null
        val epoch = buffer.long.toULong()
        buffer.long
        val sequence = buffer.long.toULong()
        val pendingSequence = buffer.long
        val processId = ByteArray(ID_BYTES).also(buffer::get)
        val rawId = ByteArray(ID_BYTES).also(buffer::get)
        val padding = ByteArray(CLIENT_PADDING_BYTES).also(buffer::get)
        val checksum = buffer.int
        if (pid <= 0 || role == 0u || sequence == 0uL || pendingSequence < 0 ||
            role > Int.MAX_VALUE.toUInt() || epoch > Long.MAX_VALUE.toULong() ||
            processId.all { it == 0.toByte() } || rawId.all { it == 0.toByte() } ||
            padding.any { it != 0.toByte() } ||
            checksum != Crc32c.value(record, 0, CLIENT_CHECKSUM_OFFSET)
        ) {
            return null
        }
        return CrashpadClientLifecycle(
            state,
            pid,
            uid,
            role,
            epoch,
            sequence,
            pendingSequence,
            processId,
            rawId,
        )
    }

    private fun sameLifecycleIdentity(
        registered: CrashpadClientLifecycle,
        terminal: CrashpadClientLifecycle,
    ): Boolean =
        registered.clientPid == terminal.clientPid &&
            registered.clientUid == terminal.clientUid &&
            registered.processRole == terminal.processRole &&
            registered.policyEpoch == terminal.policyEpoch &&
            registered.registrationSequence == terminal.registrationSequence &&
            registered.pendingSequence == terminal.pendingSequence &&
            registered.processId.contentEquals(terminal.processId) &&
            registered.rawArtifactId.contentEquals(terminal.rawArtifactId)

    private fun matchesRawJournal(registration: CrashpadClientLifecycle): Boolean {
        val journal = rawStore.journal(registration.rawArtifactId) ?: return false
        return journal.kind == RawArtifactKind.CRASHPAD_MINIDUMP &&
            !rawStore.containsRaw(registration.rawArtifactId, RawArtifactKind.CRASHPAD_MINIDUMP) &&
            journal.id.contentEquals(registration.rawArtifactId) &&
            journal.originProcessInstanceId.contentEquals(registration.processId) &&
            journal.originRole == registration.processRole.toInt() &&
            journal.acceptedEpoch == registration.policyEpoch.toLong()
    }

    private fun sameRegistration(
        first: LifecycleRegistration,
        second: LifecycleRegistration,
    ): Boolean =
        first.path == second.path &&
            first.lastModifiedMillis == second.lastModifiedMillis &&
            first.fileKey == second.fileKey &&
            first.registeredRecord.contentEquals(second.registeredRecord) &&
            first.lifecycle.state == second.lifecycle.state &&
            sameLifecycleIdentity(first.lifecycle, second.lifecycle)

    private fun samePendingDump(first: PendingDump, second: PendingDump): Boolean =
        first.path == second.path &&
            first.bytes == second.bytes &&
            first.lastModifiedMillis == second.lastModifiedMillis &&
            first.fileKey == second.fileKey

    private fun samePendingReport(first: PendingReport, second: PendingReport): Boolean =
        samePendingDump(first.dump, second.dump) &&
            when {
                first.metadata == null -> second.metadata == null
                second.metadata == null -> false
                else -> samePendingSidecar(first.metadata, second.metadata)
            } &&
            when {
                first.lock == null -> second.lock == null
                second.lock == null -> false
                else -> samePendingSidecar(first.lock, second.lock)
            }

    private fun samePendingReportWithoutLock(
        first: PendingReport,
        second: PendingReport,
    ): Boolean =
        samePendingDump(first.dump, second.dump) &&
            when {
                first.metadata == null -> second.metadata == null
                second.metadata == null -> false
                else -> samePendingSidecar(first.metadata, second.metadata)
            }

    private fun samePendingSidecar(
        first: PendingSidecar,
        second: PendingSidecar,
    ): Boolean =
        first.path == second.path &&
            first.reportName == second.reportName &&
            first.lastModifiedMillis == second.lastModifiedMillis &&
            first.fileKey == second.fileKey &&
            first.kind == second.kind

    private fun samePendingDumpAfterMove(first: PendingDump, second: PendingDump): Boolean =
        first.bytes == second.bytes &&
            first.lastModifiedMillis == second.lastModifiedMillis &&
            first.fileKey == second.fileKey

    private fun handoffDirectoryEmpty(): Boolean? {
        if (hasSymbolicLinkComponent(handoffDirectory)) return false
        if (!Files.exists(handoffDirectory, LinkOption.NOFOLLOW_LINKS)) return true
        if (!Files.isDirectory(handoffDirectory, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.list(handoffDirectory).use { stream -> !stream.iterator().hasNext() }
        } catch (_: IOException) {
            null
        } catch (_: UncheckedIOException) {
            null
        }
    }

    /**
     * Accepts either startup-reconciled ownership on the physical pending path or the original
     * conservative handoff reservation retained by the live process. Never release then reserve:
     * [UidWideQuotaCoordinator.transfer] commits the path change atomically and shrinks only when
     * the pending path currently owns the reservation.
     */
    private fun prepareQuotaTransfer(
        pending: Path,
        destination: Path,
        bytes: Long,
    ): PendingQuotaReservation? {
        val quota = uidQuota ?: return PendingQuotaReservation.NONE
        val allocations = quota.allocations()
        val sourceAllocation = allocations[pending]
        val destinationAllocation = allocations[destination]
        if (sourceAllocation == null &&
            destinationAllocation?.bucket == UidBucket.RAW_ARTIFACTS &&
            destinationAllocation.bytes >= bytes
        ) {
            return PendingQuotaReservation.EXISTING_DESTINATION
        }
        if (destinationAllocation != null) return null
        return when (
            quota.transfer(
                pending,
                destination,
                UidBucket.RAW_ARTIFACTS,
                bytes,
            )
        ) {
            UidReservationTransferResult.TRANSFERRED ->
                PendingQuotaReservation.Transferred(sourceAllocation?.bytes ?: bytes)

            UidReservationTransferResult.ALREADY_TRANSFERRED ->
                PendingQuotaReservation.EXISTING_DESTINATION

            UidReservationTransferResult.REJECTED -> null
        }
    }

    private fun rollbackPendingMove(
        pending: Path,
        destination: Path,
        bytes: Long,
        reservation: PendingQuotaReservation,
    ) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(pending, LinkOption.NOFOLLOW_LINKS)
        ) {
            runCatching {
                Files.move(destination, pending, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        if (reservation is PendingQuotaReservation.Transferred &&
            Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
        ) {
            runCatching {
                val result = uidQuota?.transfer(
                    destination,
                    pending,
                    UidBucket.RAW_ARTIFACTS,
                    bytes,
                )
                if (result == UidReservationTransferResult.TRANSFERRED ||
                    result == UidReservationTransferResult.ALREADY_TRANSFERRED
                ) {
                    uidQuota?.resize(
                        pending,
                        UidBucket.RAW_ARTIFACTS,
                        reservation.originalBytes,
                    )
                }
            }
        }
    }

    private fun decodeHexId(value: String): ByteArray? {
        if (value.length != ID_BYTES * 2 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return null
        }
        return ByteArray(ID_BYTES) { index ->
            ((value[index * 2].digitToInt(16) shl 4) or
                value[index * 2 + 1].digitToInt(16)).toByte()
        }.takeUnless { bytes -> bytes.all { it == 0.toByte() } }
    }

    private fun readExact(path: Path, size: Int): ByteArray? = try {
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != size.toLong()) return null
            val bytes = ByteArray(size)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) return null
            if (channel.size() == size.toLong()) bytes else null
        }
    } catch (_: IOException) {
        null
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Both the atomic move and the file data are already durable on filesystems that do
            // not expose directory fsync through java.nio.
        } catch (_: UnsupportedOperationException) {
            // Host providers may not expose directory channels.
        }
    }

    private fun hex(value: ByteArray): String =
        value.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class PendingDump(
        val path: Path,
        val bytes: Long,
        val fileKey: Any?,
        val lastModifiedMillis: Long,
    )

    private enum class PendingSidecarKind {
        METADATA,
        LOCK,
    }

    private data class PendingSidecar(
        val path: Path,
        val reportName: String,
        val fileKey: Any?,
        val lastModifiedMillis: Long,
        val kind: PendingSidecarKind,
    )

    private data class PendingReport(
        val dump: PendingDump,
        val metadata: PendingSidecar?,
        val lock: PendingSidecar?,
    )

    private sealed interface PendingDirectoryAudit {
        data object Empty : PendingDirectoryAudit
        data class MetadataOnly(
            val metadata: PendingSidecar,
            val lock: PendingSidecar?,
        ) : PendingDirectoryAudit
        data class Single(val report: PendingReport) : PendingDirectoryAudit
        data object Ambiguous : PendingDirectoryAudit
        data object Failed : PendingDirectoryAudit
    }

    private data class LifecycleRegistration(
        val path: Path,
        val fileKey: Any?,
        val lastModifiedMillis: Long,
        val registeredRecord: ByteArray,
        val lifecycle: CrashpadClientLifecycle,
    )

    private sealed interface LifecycleDirectoryAudit {
        data class SingleRecoverable(
            val registration: LifecycleRegistration,
        ) : LifecycleDirectoryAudit

        data object Ambiguous : LifecycleDirectoryAudit
        data object Failed : LifecycleDirectoryAudit
    }

    private sealed interface JournalDecode {
        data class Incomplete(val registration: LifecycleRegistration) : JournalDecode
        data class RecoverableTerminal(val registration: LifecycleRegistration) : JournalDecode
        data object Complete : JournalDecode
        data object Invalid : JournalDecode
    }

    private sealed interface PendingQuotaReservation {
        data object NONE : PendingQuotaReservation
        data object EXISTING_DESTINATION : PendingQuotaReservation
        data class Transferred(val originalBytes: Long) : PendingQuotaReservation
    }

    private companion object {
        const val MAX_LIFECYCLE_FILES = 64
        const val ID_BYTES = 32
        const val CLIENT_MAGIC = 0x5442434a
        const val CLIENT_VERSION = 1
        const val CLIENT_RECORD_BYTES = 192
        const val CLIENT_JOURNAL_BYTES = CLIENT_RECORD_BYTES * 2
        const val CLIENT_PADDING_BYTES = 64
        const val CLIENT_CHECKSUM_OFFSET = CLIENT_RECORD_BYTES - Int.SIZE_BYTES
        const val CRASHPAD_METADATA_BYTES = 32
        const val CRASHPAD_LOCK_BYTES = Long.SIZE_BYTES
        const val CRASHPAD_REPORT_NAME =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val CRASHPAD_DUMP_FILE = Regex("^($CRASHPAD_REPORT_NAME)\\.dmp$")
        val CRASHPAD_METADATA_FILE = Regex("^($CRASHPAD_REPORT_NAME)\\.meta$")
        val CRASHPAD_LOCK_FILE = Regex("^($CRASHPAD_REPORT_NAME)\\.lock$")
        val CLIENT_FILE_NAME =
            Regex("^client-r([1-9][0-9]{0,9})-([0-9a-f]{64})\\.tbclient$")
    }
}

enum class CrashpadClientState(val wireValue: Int) {
    REGISTERED(1),
    CONSUMED(2),
    DEAD(3),
    PROTOCOL_ERROR(4),
    HANDOFF_FAILED(5),
}

data class CrashpadClientLifecycle(
    val state: CrashpadClientState,
    val clientPid: Int,
    val clientUid: UInt,
    val processRole: UInt,
    val policyEpoch: ULong,
    val registrationSequence: ULong,
    val pendingSequence: Long,
    val processId: ByteArray,
    val rawArtifactId: ByteArray,
)

enum class CrashpadLifecycleDisposition {
    RETAINED_ACTIVE,
    RETAINED_FOR_HANDOFF,
    RETAINED_FOR_IMPORT,
    CLEANUP_DEFERRED,
    CLEANED_TERMINAL,
    CLEANED_QUIESCED_INCOMPLETE,
    INVALID_RETAINED,
    INVALID_DESTROYED,
}

data class CrashpadLifecycleOutcome(
    val fileName: String,
    val disposition: CrashpadLifecycleDisposition,
    val lifecycle: CrashpadClientLifecycle? = null,
)

data class CrashpadLifecycleBatch(
    val outcomes: List<CrashpadLifecycleOutcome>,
    val truncated: Boolean,
    val journalOnlyOrphansDeleted: Int = 0,
)

/**
 * Bounded cleanup for the handler's two-record lifecycle journals. Invoke after handoff ingestion.
 * Invalid/incomplete files are retained while the handler may be live and destroyed only after the
 * coordinator has positively quiesced it.
 */
class CrashpadClientLifecycleReconciler(
    lifecycleDirectory: Path,
    handoffDirectory: Path,
    private val rawStore: RawArtifactStore,
    private val uidQuota: UidWideQuotaCoordinator? = null,
) {
    private val lifecycleDirectory = safeStorageRoot(lifecycleDirectory)
    private val handoffDirectory = safeStorageRoot(handoffDirectory)

    fun reconcile(
        handlerQuiesced: Boolean,
        maxFiles: Int = CrashpadHandoffIngestor.DEFAULT_BATCH_FILES,
    ): CrashpadLifecycleBatch {
        require(maxFiles in 1..CrashpadHandoffIngestor.MAX_BATCH_FILES)
        if (hasSymbolicLinkComponent(lifecycleDirectory) ||
            hasSymbolicLinkComponent(handoffDirectory)
        ) {
            return CrashpadLifecycleBatch(emptyList(), truncated = true)
        }
        val paths = ArrayList<Path>(maxFiles)
        var truncated = false
        if (Files.exists(lifecycleDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(lifecycleDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return CrashpadLifecycleBatch(emptyList(), truncated = true)
            }
            try {
                Files.list(lifecycleDirectory).use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        if (paths.size == maxFiles) {
                            truncated = true
                            break
                        }
                        paths.add(iterator.next())
                    }
                }
            } catch (_: IOException) {
                return CrashpadLifecycleBatch(emptyList(), truncated = true)
            } catch (_: UncheckedIOException) {
                return CrashpadLifecycleBatch(emptyList(), truncated = true)
            }
        }
        val outcomes = paths.map { reconcileOne(it, handlerQuiesced) }
        val invalidRetained =
            outcomes.any { it.disposition == CrashpadLifecycleDisposition.INVALID_RETAINED }
        if (!handlerQuiesced) {
            return CrashpadLifecycleBatch(outcomes, truncated)
        }
        if (truncated || invalidRetained) {
            return CrashpadLifecycleBatch(outcomes, truncated = true)
        }
        val lifecycleCleanupDeferred =
            outcomes.any { it.disposition == CrashpadLifecycleDisposition.CLEANUP_DEFERRED }
        if (lifecycleCleanupDeferred) {
            return CrashpadLifecycleBatch(outcomes, truncated = true)
        }

        val lifecycleReferences = outcomes.asSequence()
            .filter {
                it.disposition == CrashpadLifecycleDisposition.RETAINED_ACTIVE ||
                    it.disposition == CrashpadLifecycleDisposition.RETAINED_FOR_HANDOFF ||
                    it.disposition == CrashpadLifecycleDisposition.RETAINED_FOR_IMPORT ||
                    it.disposition == CrashpadLifecycleDisposition.CLEANUP_DEFERRED
            }
            .mapNotNull { it.lifecycle?.rawArtifactId?.let(::hex) }
            .toSet()
        val handoffAudit = auditHandoffReferences(maxFiles)
        if (handoffAudit.truncated || handoffAudit.ambiguous) {
            return CrashpadLifecycleBatch(outcomes, truncated = true)
        }
        val protectedIds = lifecycleReferences + handoffAudit.rawArtifactIds
        val candidates = rawStore.journalOnlyArtifacts(
            maxFiles + protectedIds.size,
            RawArtifactKind.CRASHPAD_MINIDUMP,
        )
        var deleted = 0
        var failed = false
        var attempted = 0
        var deletionBudgetExhausted = false
        candidates.ids.forEach { id ->
            if (hex(id) in protectedIds) return@forEach
            if (attempted == maxFiles) {
                deletionBudgetExhausted = true
                return@forEach
            }
            attempted++
            if (rawStore.deleteJournalOnly(id, RawArtifactKind.CRASHPAD_MINIDUMP)) {
                deleted++
            } else {
                failed = true
            }
        }
        return CrashpadLifecycleBatch(
            outcomes = outcomes,
            truncated = candidates.truncated || failed || deletionBudgetExhausted,
            journalOnlyOrphansDeleted = deleted,
        )
    }

    private fun reconcileOne(path: Path, handlerQuiesced: Boolean): CrashpadLifecycleOutcome {
        val fileName = path.fileName?.toString().orEmpty()
        val fileIdentity = decodeLifecycleFileIdentity(fileName)
        if (fileIdentity == null ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            return invalid(path, fileName, handlerQuiesced)
        }
        val bytes = try {
            if (Files.size(path) != CLIENT_JOURNAL_BYTES.toLong()) {
                return invalid(path, fileName, handlerQuiesced)
            }
            readExact(path, CLIENT_JOURNAL_BYTES) ?: return invalid(path, fileName, handlerQuiesced)
        } catch (_: IOException) {
            return invalid(path, fileName, handlerQuiesced)
        }
        val registered = decodeRecord(bytes, 0, CrashpadClientState.REGISTERED)
            ?: return invalid(path, fileName, handlerQuiesced)
        if (fileIdentity.processRole != registered.processRole ||
            !fileIdentity.rawArtifactId.contentEquals(registered.rawArtifactId)
        ) {
            return invalid(path, fileName, handlerQuiesced)
        }
        val terminalBytes = bytes.copyOfRange(CLIENT_RECORD_BYTES, CLIENT_JOURNAL_BYTES)
        if (terminalBytes.all { it == 0.toByte() }) {
            return reconcileIncomplete(path, fileName, registered, handlerQuiesced)
        }
        val terminal = decodeRecord(bytes, CLIENT_RECORD_BYTES)
            ?.takeIf { sameRegistration(registered, it) }
            ?: return reconcileIncomplete(path, fileName, registered, handlerQuiesced)
        val handoff = handoffPath(terminal.rawArtifactId)
        if (Files.exists(handoff, LinkOption.NOFOLLOW_LINKS)) {
            return CrashpadLifecycleOutcome(
                fileName,
                CrashpadLifecycleDisposition.RETAINED_FOR_HANDOFF,
                terminal,
            )
        }
        if (rawStore.containsRaw(terminal.rawArtifactId, RawArtifactKind.CRASHPAD_MINIDUMP)) {
            releaseMissingHandoffReservation(handoff)
            return CrashpadLifecycleOutcome(
                fileName,
                CrashpadLifecycleDisposition.RETAINED_FOR_IMPORT,
                terminal,
            )
        }
        rawStore.deleteOwned(terminal.rawArtifactId, RawArtifactKind.CRASHPAD_MINIDUMP)
        if (rawStore.containsOwned(terminal.rawArtifactId)) {
            return CrashpadLifecycleOutcome(
                fileName,
                CrashpadLifecycleDisposition.CLEANUP_DEFERRED,
                terminal,
            )
        }
        releaseMissingHandoffReservation(handoff)
        if (!deleteOwned(path)) {
            return CrashpadLifecycleOutcome(
                fileName,
                CrashpadLifecycleDisposition.CLEANUP_DEFERRED,
                terminal,
            )
        }
        return CrashpadLifecycleOutcome(
            fileName,
            CrashpadLifecycleDisposition.CLEANED_TERMINAL,
            terminal,
        )
    }

    private fun reconcileIncomplete(
        path: Path,
        fileName: String,
        registered: CrashpadClientLifecycle,
        handlerQuiesced: Boolean,
    ): CrashpadLifecycleOutcome {
        val handoff = handoffPath(registered.rawArtifactId)
        val handoffExists = Files.exists(handoff, LinkOption.NOFOLLOW_LINKS)
        val importPending =
            rawStore.containsRaw(registered.rawArtifactId, RawArtifactKind.CRASHPAD_MINIDUMP)
        if (!handlerQuiesced || handoffExists || importPending) {
            if (!handoffExists && importPending) releaseMissingHandoffReservation(handoff)
            return CrashpadLifecycleOutcome(
                fileName,
                if (handoffExists) {
                    CrashpadLifecycleDisposition.RETAINED_FOR_HANDOFF
                } else if (importPending) {
                    CrashpadLifecycleDisposition.RETAINED_FOR_IMPORT
                } else {
                    CrashpadLifecycleDisposition.RETAINED_ACTIVE
                },
                registered,
            )
        }
        rawStore.deleteOwned(registered.rawArtifactId, RawArtifactKind.CRASHPAD_MINIDUMP)
        if (rawStore.containsOwned(registered.rawArtifactId)) {
            return CrashpadLifecycleOutcome(
                fileName,
                CrashpadLifecycleDisposition.CLEANUP_DEFERRED,
                registered,
            )
        }
        releaseMissingHandoffReservation(handoff)
        if (!deleteOwned(path)) {
            return CrashpadLifecycleOutcome(
                fileName,
                CrashpadLifecycleDisposition.CLEANUP_DEFERRED,
                registered,
            )
        }
        return CrashpadLifecycleOutcome(
            fileName,
            CrashpadLifecycleDisposition.CLEANED_QUIESCED_INCOMPLETE,
            registered,
        )
    }

    private fun invalid(
        path: Path,
        fileName: String,
        handlerQuiesced: Boolean,
    ): CrashpadLifecycleOutcome {
        if (handlerQuiesced && deleteOwned(path)) {
            return CrashpadLifecycleOutcome(
                fileName,
                CrashpadLifecycleDisposition.INVALID_DESTROYED,
            )
        }
        return CrashpadLifecycleOutcome(
            fileName,
            CrashpadLifecycleDisposition.INVALID_RETAINED,
        )
    }

    private fun decodeRecord(
        bytes: ByteArray,
        offset: Int,
        requiredState: CrashpadClientState? = null,
    ): CrashpadClientLifecycle? {
        val record = bytes.copyOfRange(offset, offset + CLIENT_RECORD_BYTES)
        val buffer = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.int
        val version = buffer.short.toInt() and 0xffff
        if (magic != CLIENT_MAGIC || version != CLIENT_VERSION) return null
        val stateValue = buffer.short.toInt() and 0xffff
        val state = CrashpadClientState.entries.firstOrNull {
            it.wireValue == stateValue
        } ?: return null
        if (requiredState != null && state != requiredState) return null
        if (buffer.int != CLIENT_RECORD_BYTES) return null
        val pid = buffer.int
        val uid = buffer.int.toUInt()
        val role = buffer.int.toUInt()
        if (buffer.int != 0) return null
        val epoch = buffer.long.toULong()
        buffer.long // monotonic timestamp is intentionally allowed to change.
        val registrationSequence = buffer.long.toULong()
        val pendingSequence = buffer.long
        val processId = ByteArray(ID_BYTES).also(buffer::get)
        val rawId = ByteArray(ID_BYTES).also(buffer::get)
        val padding = ByteArray(CLIENT_PADDING_BYTES).also(buffer::get)
        val checksum = buffer.int
        if (pid <= 0 || role == 0u || registrationSequence == 0uL ||
            pendingSequence < 0 || processId.all { it == 0.toByte() } ||
            rawId.all { it == 0.toByte() } || padding.any { it != 0.toByte() } ||
            checksum != Crc32c.value(record, 0, CLIENT_CHECKSUM_OFFSET)
        ) {
            return null
        }
        return CrashpadClientLifecycle(
            state,
            pid,
            uid,
            role,
            epoch,
            registrationSequence,
            pendingSequence,
            processId,
            rawId,
        )
    }

    private fun sameRegistration(
        registered: CrashpadClientLifecycle,
        terminal: CrashpadClientLifecycle,
    ): Boolean =
        terminal.state != CrashpadClientState.REGISTERED &&
            registered.clientPid == terminal.clientPid &&
            registered.clientUid == terminal.clientUid &&
            registered.processRole == terminal.processRole &&
            registered.policyEpoch == terminal.policyEpoch &&
            registered.registrationSequence == terminal.registrationSequence &&
            registered.pendingSequence == terminal.pendingSequence &&
            registered.processId.contentEquals(terminal.processId) &&
            registered.rawArtifactId.contentEquals(terminal.rawArtifactId)

    private fun handoffPath(rawId: ByteArray): Path =
        handoffDirectory.resolve("${hex(rawId)}.dmp")

    private fun hex(value: ByteArray): String =
        value.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun auditHandoffReferences(maxFiles: Int): HandoffReferenceAudit {
        if (!Files.exists(handoffDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return HandoffReferenceAudit(emptySet(), truncated = false, ambiguous = false)
        }
        if (!Files.isDirectory(handoffDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return HandoffReferenceAudit(emptySet(), truncated = false, ambiguous = true)
        }
        val references = linkedSetOf<String>()
        var ambiguous = false
        return try {
            Files.list(handoffDirectory).use { stream ->
                val iterator = stream.iterator()
                var inspected = 0
                while (iterator.hasNext()) {
                    if (inspected == maxFiles) {
                        return HandoffReferenceAudit(references, truncated = true, ambiguous)
                    }
                    inspected++
                    val path = iterator.next()
                    val match = HANDOFF_FILE_NAME.matchEntire(path.fileName?.toString().orEmpty())
                    if (match == null ||
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        ambiguous = true
                    } else {
                        references += match.groupValues[1]
                    }
                }
            }
            HandoffReferenceAudit(references, truncated = false, ambiguous)
        } catch (_: IOException) {
            HandoffReferenceAudit(references, truncated = true, ambiguous = true)
        } catch (_: UncheckedIOException) {
            HandoffReferenceAudit(references, truncated = true, ambiguous = true)
        }
    }

    private fun decodeLifecycleFileIdentity(fileName: String): LifecycleFileIdentity? {
        val match = CLIENT_FILE_NAME.matchEntire(fileName) ?: return null
        val processRole = match.groupValues[1].toUIntOrNull()?.takeIf { it != 0u } ?: return null
        val rawArtifactId = decodeHexId(match.groupValues[2]) ?: return null
        return LifecycleFileIdentity(processRole, rawArtifactId)
    }

    private fun decodeHexId(value: String): ByteArray? {
        if (value.length != ID_BYTES * 2 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return null
        }
        return ByteArray(ID_BYTES) { index ->
            ((value[index * 2].digitToInt(16) shl 4) or
                value[index * 2 + 1].digitToInt(16)).toByte()
        }.takeUnless { bytes -> bytes.all { it == 0.toByte() } }
    }

    private fun releaseMissingHandoffReservation(path: Path) {
        runCatching {
            guardedStorageMutation(uidQuota, StorageMutationEligibility.ALWAYS) {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    uidQuota?.release(path)
                }
            }
        }
    }

    private fun readExact(path: Path, size: Int): ByteArray? = try {
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != size.toLong()) return null
            val bytes = ByteArray(size)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) return null
            }
            if (channel.size() == size.toLong()) bytes else null
        }
    } catch (_: IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    private fun deleteOwned(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized.parent != lifecycleDirectory || hasSymbolicLinkComponent(lifecycleDirectory)) {
            return false
        }
        return try {
            when (
                val guarded = guardedStorageMutation(
                    uidQuota,
                    StorageMutationEligibility.ALWAYS,
                ) {
                    val deleted = Files.deleteIfExists(path)
                    if (deleted) runCatching { uidQuota?.release(path) }
                    deleted || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value
                StorageMutationBarrierResult.Rejected -> false
            }
        } catch (_: IOException) {
            false
        } catch (_: StorageMutationBarrierException) {
            false
        }
    }

    private companion object {
        data class LifecycleFileIdentity(
            val processRole: UInt,
            val rawArtifactId: ByteArray,
        )

        data class HandoffReferenceAudit(
            val rawArtifactIds: Set<String>,
            val truncated: Boolean,
            val ambiguous: Boolean,
        )

        const val ID_BYTES = 32
        const val CLIENT_MAGIC = 0x5442434a
        const val CLIENT_VERSION = 1
        const val CLIENT_RECORD_BYTES = 192
        const val CLIENT_JOURNAL_BYTES = CLIENT_RECORD_BYTES * 2
        const val CLIENT_PADDING_BYTES = 64
        const val CLIENT_CHECKSUM_OFFSET = CLIENT_RECORD_BYTES - Int.SIZE_BYTES
        val CLIENT_FILE_NAME =
            Regex("^client-r([1-9][0-9]{0,9})-([0-9a-f]{64})\\.tbclient$")
        val HANDOFF_FILE_NAME = Regex("^([0-9a-f]{64})\\.dmp$")
    }
}
