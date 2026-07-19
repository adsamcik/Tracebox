package dev.tracebox.storage

import dev.tracebox.core.RecordPriority
import dev.tracebox.core.GateResult
import dev.tracebox.core.PolicyTaggedRecord
import dev.tracebox.core.WriterPolicyGate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.CRC32C

/** IDs supplied by the Phase 1 persist-before-use identity allocator. */
data class PersistedSegmentIdentity(val segmentId: ByteArray, val processInstanceId: ByteArray) {
    init {
        require(segmentId.size == ID_SIZE && processInstanceId.size == ID_SIZE)
    }

    companion object { const val ID_SIZE = 32 }
}

/** Segment format or filesystem failure. No malformed data becomes a record. */
sealed class SegmentException(message: String) : IllegalStateException(message) {
    data object InvalidHeader : SegmentException("invalid segment header")
    data object FrameTooLarge : SegmentException("frame exceeds hard bound")
    data object Sealed : SegmentException("segment is sealed")
    data object Sequence : SegmentException("non-monotonic sequence")
    data object Quota : SegmentException("role quota exhausted")
}

/** A recovered valid prefix; corrupt tails are quarantined to this file only. */
data class RecoveredSegment(
    val header: SegmentHeader,
    val frames: List<SegmentFrame>,
    val sealed: Boolean,
    val validBytes: Long,
    val corruptionDetected: Boolean,
)

data class SegmentHeader(
    val identity: PersistedSegmentIdentity,
    val schemaFingerprint: ByteArray,
    val policyGeneration: Long,
    val flags: Int,
    /** Stable process role, persisted so quota recovery can charge only this role's segments. */
    val processRole: Int = 1,
) {
    init { require(schemaFingerprint.size == PersistedSegmentIdentity.ID_SIZE) }
}

data class SegmentFrame(
    val recordType: Int,
    val sequence: Long,
    val payload: ByteArray,
    /** Immutable byte offset of this frame's length prefix in its containing segment. */
    val offset: Long = -1,
)

/** Durable append either writes exactly one frame or reports why it was safely dropped. */
sealed interface SegmentAppendResult {
    data class Appended(val sequence: Long) : SegmentAppendResult
    data class Dropped(val reason: GateResult) : SegmentAppendResult
    data class DroppedQuota(val priority: RecordPriority) : SegmentAppendResult
}

/**
 * Append-only ordinary segment. Caller-provided IDs must already have been made durable
 * by Phase 1; this class intentionally never generates identities itself.
 */
class SegmentWriter private constructor(
    private val path: Path,
    private val header: SegmentHeader,
    private var nextSequence: Long,
    private var sealed: Boolean,
    private val policyGate: WriterPolicyGate,
    private val quotaLedger: RoleQuotaLedger,
) : AutoCloseable {
    /** Revalidates policy and charges the durable role budget before appending one forced frame. */
    fun append(recordType: Int, record: PolicyTaggedRecord): SegmentAppendResult {
        DiskIoGuard.assertNotMainThread()
        if (sealed) throw SegmentException.Sealed
        if (record.payload.size > MAX_PAYLOAD) throw SegmentException.FrameTooLarge
        val frameBytes = Int.SIZE_BYTES + FRAME_FIXED_BYTES + record.payload.size + Int.SIZE_BYTES
        // Section 11.3's hard bound requires every byte to fit. Keep space for the mandatory
        // seal before extending a live segment; seal() then charges those exact bytes atomically.
        return quotaLedger.appendAtomically(
            header.processRole,
            path,
            frameBytes.toLong(),
            record.priority,
            reservedTailBytes = SEAL_SIZE.toLong(),
        ) {
            val gateResult = policyGate.appendAllowed(record)
            if (gateResult != GateResult.Allowed) return@appendAtomically SegmentAppendResult.Dropped(gateResult)
            val sequence = nextSequence
            val bodyLength = FRAME_FIXED_BYTES + record.payload.size
            val body = ByteBuffer.allocate(bodyLength).order(ByteOrder.LITTLE_ENDIAN)
            body.putInt(recordType).putLong(sequence).put(record.payload)
            val prefix = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(bodyLength)
            prefix.flip()
            FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                channel.write(prefix)
                channel.write(ByteBuffer.wrap(body.array()))
                val crcBuffer = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                crcBuffer.putInt(crc(body.array())).flip()
                channel.write(crcBuffer)
                channel.force(true)
            }
            nextSequence++
            SegmentAppendResult.Appended(sequence)
        }
    }

    /** Writes the immutable seal. Further append attempts are rejected. */
    fun seal() {
        DiskIoGuard.assertNotMainThread()
        if (sealed) throw SegmentException.Sealed
        val written = quotaLedger.appendAtomically(
            header.processRole,
            path,
            SEAL_SIZE.toLong(),
            RecordPriority.ORDINARY_EVENT,
        ) {
            val prefix = recover(path, repair = false)
            val digest = sha256(Files.readAllBytes(path).copyOfRange(HEADER_SIZE, prefix.validBytes.toInt()))
            val seal = ByteBuffer.allocate(SEAL_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            seal.putInt(SEAL_MAGIC).putLong(nextSequence - 1).putLong(prefix.frames.size.toLong()).put(digest).flip()
            FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use {
                it.write(seal)
                it.force(true)
            }
            SegmentAppendResult.Appended(nextSequence)
        }
        if (written is SegmentAppendResult.DroppedQuota) throw SegmentException.Quota
        sealed = true
    }

    override fun close() = Unit

    companion object {
        const val MAX_PAYLOAD = 64 * 1024
        private const val MAGIC = 0x53425854 // TBXS, little endian
        private const val VERSION = 1
        private const val HEADER_SIZE = 124
        private const val FRAME_FIXED_BYTES = Int.SIZE_BYTES + Long.SIZE_BYTES
        private const val SEAL_MAGIC = 0x53424c54 // TLBS, never a valid bounded frame length
        private const val SEAL_SIZE = Int.SIZE_BYTES + Long.SIZE_BYTES + Long.SIZE_BYTES + 32

        /** Creates and syncs the identity-bearing header before any frame can be appended. */
        fun create(
            path: Path,
            header: SegmentHeader,
            policyGate: WriterPolicyGate,
            quotaLedger: RoleQuotaLedger,
        ): SegmentWriter {
            DiskIoGuard.assertNotMainThread()
            val created = quotaLedger.createAtomically(
                header.processRole,
                path,
                HEADER_SIZE.toLong(),
                RecordPriority.ORDINARY_EVENT,
                reservedTailBytes = SEAL_SIZE.toLong(),
            ) {
                val bytes = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                bytes.putInt(MAGIC).putInt(VERSION)
                bytes.put(header.identity.segmentId).put(header.identity.processInstanceId).put(header.schemaFingerprint)
                bytes.putLong(header.policyGeneration).putInt(header.flags).putInt(header.processRole)
                bytes.putInt(crc(bytes.array(), 0, HEADER_SIZE - Int.SIZE_BYTES)).flip()
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                    it.write(bytes)
                    it.force(true)
                }
            }
            if (!created) throw SegmentException.Quota
            return SegmentWriter(path, header, 0, false, policyGate, quotaLedger)
        }

        /** Reads a valid prefix and optionally truncates only the damaged tail. */
        fun recover(path: Path, repair: Boolean = true): RecoveredSegment {
            DiskIoGuard.assertNotMainThread()
            FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                val headerBytes = readExactly(channel, HEADER_SIZE) ?: throw SegmentException.InvalidHeader
                val header = decodeHeader(headerBytes)
                var offset = HEADER_SIZE.toLong()
                var expectedSequence = 0L
                val frames = ArrayList<SegmentFrame>()
                var sealed = false
                var corrupt = false
                while (offset < channel.size()) {
                    val remaining = channel.size() - offset
                    if (remaining == SEAL_SIZE.toLong() && isSeal(channel)) {
                        val seal = readExactly(channel, SEAL_SIZE) ?: break
                        if (verifySeal(seal, frames, path, offset)) {
                            sealed = true
                            offset += SEAL_SIZE
                        } else corrupt = true
                        break
                    }

                    if (remaining < Int.SIZE_BYTES) { corrupt = true; break }
                    val lengthBuffer = readExactly(channel, Int.SIZE_BYTES) ?: run { corrupt = true; break }
                    val length = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.LITTLE_ENDIAN).int
                    if (length < FRAME_FIXED_BYTES || length > FRAME_FIXED_BYTES + MAX_PAYLOAD) {
                        corrupt = true
                        break
                    }
                    if (remaining < Int.SIZE_BYTES + length + Int.SIZE_BYTES) { corrupt = true; break }
                    val body = readExactly(channel, length) ?: run { corrupt = true; break }
                    val crcBytes = readExactly(channel, Int.SIZE_BYTES) ?: run { corrupt = true; break }
                    val expectedCrc = ByteBuffer.wrap(crcBytes).order(ByteOrder.LITTLE_ENDIAN).int
                    if (crc(body) != expectedCrc) { corrupt = true; break }
                    val frame = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    val type = frame.int
                    val sequence = frame.long
                    if (sequence != expectedSequence) { corrupt = true; break }
                    frames += SegmentFrame(type, sequence, ByteArray(length - FRAME_FIXED_BYTES).also(frame::get), offset)
                    expectedSequence++
                    offset += Int.SIZE_BYTES + length + Int.SIZE_BYTES
                }
                if (corrupt && repair) {
                    channel.truncate(offset)
                    channel.force(true)
                }
                return RecoveredSegment(header, frames, sealed, offset, corrupt)
            }
        }

        private fun decodeHeader(bytes: ByteArray): SegmentHeader {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != MAGIC || buffer.int != VERSION) throw SegmentException.InvalidHeader
            val segment = ByteArray(PersistedSegmentIdentity.ID_SIZE).also(buffer::get)
            val process = ByteArray(PersistedSegmentIdentity.ID_SIZE).also(buffer::get)
            val schema = ByteArray(PersistedSegmentIdentity.ID_SIZE).also(buffer::get)
            val generation = buffer.long
            val flags = buffer.int
            val processRole = buffer.int
            if (buffer.int != crc(bytes, 0, HEADER_SIZE - Int.SIZE_BYTES)) throw SegmentException.InvalidHeader
            return SegmentHeader(PersistedSegmentIdentity(segment, process), schema, generation, flags, processRole)
        }

        private fun verifySeal(seal: ByteArray, frames: List<SegmentFrame>, path: Path, offset: Long): Boolean {
            val buffer = ByteBuffer.wrap(seal).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != SEAL_MAGIC) return false
            val finalSequence = frames.lastOrNull()?.sequence ?: -1L
            if (buffer.long != finalSequence || buffer.long != frames.size.toLong()) return false
            val expected = ByteArray(32).also(buffer::get)
            return expected.contentEquals(sha256(Files.readAllBytes(path).copyOfRange(HEADER_SIZE, offset.toInt())))
        }

        private fun isSeal(channel: FileChannel): Boolean {
            val position = channel.position()
            val marker = readExactly(channel, Int.SIZE_BYTES) ?: return false
            channel.position(position)
            return ByteBuffer.wrap(marker).order(ByteOrder.LITTLE_ENDIAN).int == SEAL_MAGIC
        }

        private fun readExactly(channel: FileChannel, length: Int): ByteArray? {
            val result = ByteBuffer.allocate(length)
            while (result.hasRemaining()) if (channel.read(result) < 0) return null
            return result.array()
        }

        private fun crc(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int =
            CRC32C().also { it.update(bytes, offset, length) }.value.toInt()
        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** Debug/runtime guard for the invariant that ordinary writer disk I/O is never on Android's main thread. */
object DiskIoGuard {
    fun assertNotMainThread() {
        check(Thread.currentThread().name != "main") { "Tracebox ordinary storage I/O on the main thread" }
    }
}

/** One account bucket, so a path may never be charged to more than one hard reserve. */
enum class UidBucket { ROLE_SEGMENTS, RAW_ARTIFACTS, SUMMARY_SPOOL, SNAPSHOTS, COMPACTION, EMERGENCY, METADATA }
data class UidQuota(val limits: Map<UidBucket, Long>) {
    fun hardBound(): Long = limits.values.sum()
}

/** UID accounting with byte and file count bounds for every bucket. */
class UidAccounting(private val quota: UidQuota, private val maxFiles: Map<UidBucket, Int>) {
    private val allocations = linkedMapOf<Path, Pair<UidBucket, Long>>()
    fun reserve(path: Path, bucket: UidBucket, bytes: Long): Boolean {
        if (bytes < 0 || path in allocations) return false
        val used = allocations.filterValues { it.first == bucket }.values.sumOf { it.second }
        val count = allocations.values.count { it.first == bucket }
        if (used + bytes > (quota.limits[bucket] ?: 0) || count >= (maxFiles[bucket] ?: 0)) return false
        allocations[path] = bucket to bytes
        return true
    }
    fun release(path: Path) { allocations.remove(path) }
    fun used(bucket: UidBucket): Long = allocations.filterValues { it.first == bucket }.values.sumOf { it.second }
}

/** Stable process-role quota policy. Unknown roles have zero storage unless fallback is explicit. */
class RoleQuotaPolicy(private val quotas: Map<Int, Long>, private val fallbackRole: Int? = null) {
    fun quotaFor(role: Int): Long = quotas[role] ?: fallbackRole?.let { quotas[it] } ?: 0L
    fun allow(role: Int, existingBytes: Long, requestedBytes: Long, priority: RecordPriority): QuotaDecision =
        if (quotaFor(role) == 0L || existingBytes + requestedBytes > quotaFor(role)) QuotaDecision.Dropped(priority) else QuotaDecision.Allowed
}
sealed interface QuotaDecision {
    data object Allowed : QuotaDecision
    data class Dropped(val priority: RecordPriority) : QuotaDecision
}

/** Stable-role quota ledger; durable use is reconstructed from authoritative segment files. */
class RoleQuotaLedger(
    private val policy: RoleQuotaPolicy,
    private val root: Path? = null,
) {
    private data class Entry(val bytes: Long, val priority: RecordPriority)
    private val records = mutableMapOf<Int, MutableList<Entry>>()

    fun reserve(role: Int, bytes: Long, priority: RecordPriority): QuotaDecision {
        val quota = policy.quotaFor(role)
        if (bytes <= 0 || bytes > quota) return QuotaDecision.Dropped(priority)
        val entries = records.getOrPut(role) { mutableListOf() }
        var used = entries.sumOf { it.bytes }
        while (used + bytes > quota) {
            val victim = entries.minByOrNull { it.priority.rank } ?: return QuotaDecision.Dropped(priority)
            if (victim.priority.rank >= priority.rank) return QuotaDecision.Dropped(priority)
            entries.remove(victim)
            used -= victim.bytes
        }
        entries += Entry(bytes, priority)
        return QuotaDecision.Allowed
    }

    fun used(role: Int): Long = records[role].orEmpty().sumOf { it.bytes }

    /**
     * Holds an inter-process lock while reading the authoritative role total and appending the
     * frame. The segment itself is the durable charge, so recovery cannot grant a fresh budget.
     * A reserved tail is capacity required by the same append but written by a later mandatory
     * operation, such as the immutable segment seal.
     */
    fun appendAtomically(
        role: Int,
        segment: Path,
        bytes: Long,
        priority: RecordPriority,
        reservedTailBytes: Long = 0,
        append: () -> SegmentAppendResult,
    ): SegmentAppendResult {
        val directory = root ?: segment.parent
        Files.createDirectories(directory)
        val lock = directory.resolve(".tracebox-role-quota.lock")
        FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val used = durableUsed(directory, role)
                if (policy.allow(role, used, bytes + reservedTailBytes, priority) !is QuotaDecision.Allowed) {
                    return SegmentAppendResult.DroppedQuota(priority)
                }
                return append()
            }
        }
    }

    fun createAtomically(
        role: Int,
        segment: Path,
        bytes: Long,
        priority: RecordPriority,
        reservedTailBytes: Long = 0,
        create: () -> Unit,
    ): Boolean {
        val directory = root ?: segment.parent
        Files.createDirectories(directory)
        val lock = directory.resolve(".tracebox-role-quota.lock")
        FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (policy.allow(role, durableUsed(directory, role), bytes + reservedTailBytes, priority) !is QuotaDecision.Allowed) {
                    return false
                }
                create()
                return true
            }
        }
    }

    private fun durableUsed(directory: Path, role: Int): Long =
        Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tbseg") }.filter {
                runCatching { SegmentWriter.recover(it, repair = false).header.processRole == role }.getOrDefault(false)
            }.map { Files.size(it) }.toList().sum()
        }
}

/** Non-authoritative index entry: only segment file name and count, never diagnostic values. */
data class SegmentSummary(val fileName: String, val frameCount: Int)

/** Package planning works from segments when this bounded, rebuildable index is absent. */
class SegmentMetadataIndex(private val path: Path, private val accounting: UidAccounting) {
    fun plan(directory: Path): List<SegmentSummary> {
        val direct = scan(directory)
        if (!Files.exists(path)) return direct
        val indexed = runCatching { readIndex() }.getOrNull() ?: return direct
        return if (indexed == direct) indexed else direct
    }
    fun rebuild(directory: Path): Boolean {
        val summaries = scan(directory)
        val bytes = summaries.joinToString("\n") { "${it.fileName},${it.frameCount}" }.toByteArray()
        if (!accounting.reserve(path, UidBucket.METADATA, bytes.size.toLong())) {
            Files.deleteIfExists(path)
            return false
        }
        Files.write(path, bytes)
        return true
    }
    private fun scan(directory: Path): List<SegmentSummary> =
        Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tbseg") }.map {
                SegmentSummary(it.fileName.toString(), SegmentWriter.recover(it).frames.size)
            }.sorted { left, right -> left.fileName.compareTo(right.fileName) }.toList()
        }
    private fun readIndex(): List<SegmentSummary> = Files.readAllLines(path).filter { it.isNotBlank() }.map {
        val parts = it.split(',', limit = 2)
        require(parts.size == 2 && parts[0].endsWith(".tbseg"))
        SegmentSummary(parts[0], parts[1].toInt().also { require(it >= 0) })
    }.also { require(it.map(SegmentSummary::fileName).distinct().size == it.size) }
}

/** Persisted deletion-journal states from the storage deletion protocol. */
enum class DeletionState {
    REQUESTED, DENY_COMMITTED, WRITERS_QUIESCED, STORES_MARKED_INELIGIBLE, DELETING, COMPLETE, PENDING_FAILURE
}

/** Bounded callbacks supplied by future package/handler policy coordinators. */
interface DeletionHooks {
    fun commitDisabledEpoch(): Boolean
    fun quiesceWriters(): Boolean
    fun invalidateApprovalsAndSnapshotKeys()
    fun closeActiveStores()
}

/** A library-owned storage family that participates in the same R2.8 deletion transaction. */
interface DeletionParticipant {
    fun markIneligible()
    fun deleteOwned()
    fun remainingOwned(): List<Path>
}

/** Crash-injection seam called immediately after each durable journal transition. */
fun interface DeletionCrashInjector { fun after(state: DeletionState): Boolean }

/**
 * Crash-recoverable deletion for Phase 2 authoritative ordinary segments and metadata.
 * OS-owned ApplicationExitInfo history is outside this store and is deliberately unaffected.
 */
class DeletionEngine(
    private val root: Path,
    private val journalPath: Path,
    private val hooks: DeletionHooks,
    private val maxRetries: Int = 3,
    private val participants: List<DeletionParticipant> = emptyList(),
) {
    private var attempts = 0

    /** Starts or resumes disabled full deletion; it only reports complete after a fresh scan. */
    fun deleteAll(injector: DeletionCrashInjector? = null): DeletionState {
        if (!Files.exists(journalPath)) transition(DeletionState.REQUESTED, injector)
        return resume(injector)
    }

    /** Bounded explicit/startup retry trigger. */
    fun retry(injector: DeletionCrashInjector? = null): DeletionState {
        if (attempts >= maxRetries) return DeletionState.PENDING_FAILURE
        attempts++
        return resume(injector)
    }

    /** Deletes complete selected segment files without claiming deletion of out-of-scope data. */
    fun deleteWholeSegments(inScope: (Path) -> Boolean): DeletionState {
        Files.list(root).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tbseg") && inScope(it) }.forEach {
                Files.deleteIfExists(it)
            }
        }
        return DeletionState.COMPLETE
    }

    fun current(): DeletionState? {
        if (!Files.exists(journalPath)) return null
        val value = try {
            Files.readString(journalPath).trim()
        } catch (_: java.io.IOException) {
            return null
        }
        return try {
            DeletionState.valueOf(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun resume(injector: DeletionCrashInjector?): DeletionState {
        var state = current() ?: return DeletionState.PENDING_FAILURE
        if (state == DeletionState.PENDING_FAILURE) {
            transition(DeletionState.REQUESTED, injector)
            state = DeletionState.REQUESTED
        }
        while (state != DeletionState.COMPLETE) {
            val next = when (state) {
                DeletionState.REQUESTED -> if (hooks.commitDisabledEpoch()) DeletionState.DENY_COMMITTED else DeletionState.PENDING_FAILURE
                DeletionState.DENY_COMMITTED -> if (hooks.quiesceWriters()) DeletionState.WRITERS_QUIESCED else DeletionState.PENDING_FAILURE
                DeletionState.WRITERS_QUIESCED -> {
                    hooks.invalidateApprovalsAndSnapshotKeys()
                    hooks.closeActiveStores()
                    markIneligible()
                    participants.forEach(DeletionParticipant::markIneligible)
                    DeletionState.STORES_MARKED_INELIGIBLE
                }
                DeletionState.STORES_MARKED_INELIGIBLE -> {
                    deletePhase2Paths()
                    participants.forEach(DeletionParticipant::deleteOwned)
                    DeletionState.DELETING
                }
                DeletionState.DELETING ->
                    if (remainingLibraryOwned().isEmpty() && participants.flatMap(DeletionParticipant::remainingOwned).isEmpty()) {
                        DeletionState.COMPLETE
                    } else {
                        DeletionState.PENDING_FAILURE
                    }
                DeletionState.PENDING_FAILURE, DeletionState.COMPLETE -> state
            }
            transition(next, injector)
            if (next == DeletionState.PENDING_FAILURE || next == DeletionState.COMPLETE) return next
            state = next
        }
        return state
    }

    private fun transition(state: DeletionState, injector: DeletionCrashInjector?) {
        Files.createDirectories(journalPath.parent)
        Files.writeString(journalPath, state.name, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        FileChannel.open(journalPath, StandardOpenOption.WRITE).use { it.force(true) }
        if (injector?.after(state) == false) throw DeletionInterrupted(state)
    }

    private fun markIneligible() {
        Files.writeString(root.resolve(".tracebox-ineligible"), "1", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun deletePhase2Paths() {
        remainingLibraryOwned().forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(root.resolve(".tracebox-ineligible"))
    }

    private fun remainingLibraryOwned(): List<Path> = Files.list(root).use { stream ->
        stream.filter {
            val name = it.fileName.toString()
            name.endsWith(".tbseg") || name.endsWith(".tbidx") || name.endsWith(".tbmeta")
        }.toList()
    }
}

/** Intentional process-death simulation used by fault tests; callers resume from the durable journal. */
class DeletionInterrupted(val state: DeletionState) : IllegalStateException("injected deletion interruption at $state")
