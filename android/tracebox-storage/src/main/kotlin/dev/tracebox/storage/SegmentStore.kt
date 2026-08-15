package dev.tracebox.storage

import dev.tracebox.core.RecordPriority
import dev.tracebox.core.GateResult
import dev.tracebox.core.PolicyTaggedRecord
import dev.tracebox.core.WriterPolicyGate
import dev.tracebox.api.Crc32c
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

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
    data object StorageIneligible : SegmentException("storage mutation denied by policy or root eligibility")
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
    private val uidQuota: UidWideQuotaCoordinator?,
    private val storageEligibility: StorageMutationEligibility,
) : AutoCloseable {
    /** Revalidates policy and charges the durable role budget before appending one forced frame. */
    fun append(recordType: Int, record: PolicyTaggedRecord): SegmentAppendResult =
        appendInternal(recordType, record, criticalCapture = false)

    /**
     * Crash/ANR capture may synchronously force one bounded frame even when the failing thread is
     * Android's main thread. This is never used for ordinary recording.
     */
    fun appendCritical(recordType: Int, record: PolicyTaggedRecord): SegmentAppendResult =
        appendInternal(recordType, record, criticalCapture = true)

    private fun appendInternal(
        recordType: Int,
        record: PolicyTaggedRecord,
        criticalCapture: Boolean,
    ): SegmentAppendResult {
        if (!criticalCapture) DiskIoGuard.assertNotMainThread()
        if (record.payload.size > MAX_PAYLOAD) throw SegmentException.FrameTooLarge
        return when (
            val guarded = guardedStorageMutation(uidQuota, storageEligibility) {
                if (sealed) throw SegmentException.Sealed
                val frameBytes = Int.SIZE_BYTES + FRAME_FIXED_BYTES + record.payload.size + Int.SIZE_BYTES
                // Section 11.3's hard bound requires every byte to fit. Keep space for the mandatory
                // seal before extending a live segment; seal() then charges those exact bytes atomically.
                if (uidQuota?.grow(path, UidBucket.ROLE_SEGMENTS, frameBytes.toLong()) == false) {
                    return@guardedStorageMutation SegmentAppendResult.DroppedQuota(record.priority)
                }
                try {
                    val result = quotaLedger.appendAtomically(
                        header.processRole,
                        path,
                        frameBytes.toLong(),
                        record.priority,
                        reservedTailBytes = SEAL_SIZE.toLong(),
                    ) {
                        val gateResult = policyGate.appendAllowed(record)
                        if (gateResult != GateResult.Allowed) {
                            return@appendAtomically SegmentAppendResult.Dropped(gateResult)
                        }
                        val sequence = nextSequence
                        val bodyLength = FRAME_FIXED_BYTES + record.payload.size
                        val body = ByteBuffer.allocate(bodyLength).order(ByteOrder.LITTLE_ENDIAN)
                        body.putInt(recordType).putLong(sequence).put(record.payload)
                        val prefix = ByteBuffer.allocate(Int.SIZE_BYTES)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(bodyLength)
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
                    if (result !is SegmentAppendResult.Appended) {
                        uidQuota?.resize(path, UidBucket.ROLE_SEGMENTS, Files.size(path) + SEAL_SIZE)
                    }
                    result
                } catch (failure: java.io.IOException) {
                    uidQuota?.resize(path, UidBucket.ROLE_SEGMENTS, Files.size(path) + SEAL_SIZE)
                    throw failure
                }
            }
        ) {
            is StorageMutationBarrierResult.Applied -> guarded.value
            StorageMutationBarrierResult.Rejected -> SegmentAppendResult.Dropped(GateResult.Denied)
        }
    }

    /** Writes the immutable seal. Further append attempts are rejected. */
    fun seal() {
        DiskIoGuard.assertNotMainThread()
        when (
            val guarded = guardedStorageMutation(uidQuota, storageEligibility) {
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
        ) {
            is StorageMutationBarrierResult.Applied -> Unit
            StorageMutationBarrierResult.Rejected -> throw SegmentException.StorageIneligible
        }
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
            uidQuota: UidWideQuotaCoordinator? = null,
            storageEligibility: StorageMutationEligibility = StorageMutationEligibility.ALWAYS,
        ): SegmentWriter {
            DiskIoGuard.assertNotMainThread()
            return when (
                val guarded = guardedStorageMutation(uidQuota, storageEligibility) {
                    val roleLockPath = quotaLedger.lockPathFor(path)
                    val roleLockAlreadyReserved =
                        uidQuota?.owns(roleLockPath, UidBucket.METADATA, 0L) != false
                    if (!roleLockAlreadyReserved &&
                        uidQuota.reserve(roleLockPath, UidBucket.METADATA, 0L) == false
                    ) {
                        throw SegmentException.Quota
                    }
                    if (uidQuota?.reserve(
                            path,
                            UidBucket.ROLE_SEGMENTS,
                            HEADER_SIZE.toLong() + SEAL_SIZE,
                        ) == false
                    ) {
                        if (!roleLockAlreadyReserved &&
                            !Files.exists(roleLockPath, LinkOption.NOFOLLOW_LINKS)
                        ) {
                            uidQuota.release(roleLockPath)
                        }
                        throw SegmentException.Quota
                    }
                    val created = try {
                        quotaLedger.createAtomically(
                            header.processRole,
                            path,
                            HEADER_SIZE.toLong(),
                            RecordPriority.ORDINARY_EVENT,
                            reservedTailBytes = SEAL_SIZE.toLong(),
                        ) {
                            val bytes = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                            bytes.putInt(MAGIC).putInt(VERSION)
                            bytes.put(header.identity.segmentId)
                                .put(header.identity.processInstanceId)
                                .put(header.schemaFingerprint)
                            bytes.putLong(header.policyGeneration).putInt(header.flags).putInt(header.processRole)
                            bytes.putInt(crc(bytes.array(), 0, HEADER_SIZE - Int.SIZE_BYTES)).flip()
                            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                                it.write(bytes)
                                it.force(true)
                            }
                        }
                    } catch (failure: java.io.IOException) {
                        uidQuota?.release(path)
                        if (!roleLockAlreadyReserved &&
                            !Files.exists(roleLockPath, LinkOption.NOFOLLOW_LINKS)
                        ) {
                            uidQuota.release(roleLockPath)
                        }
                        throw failure
                    }
                    if (!created) {
                        uidQuota?.release(path)
                        throw SegmentException.Quota
                    }
                    SegmentWriter(
                        path,
                        header,
                        0,
                        false,
                        policyGate,
                        quotaLedger,
                        uidQuota,
                        storageEligibility,
                    )
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value
                StorageMutationBarrierResult.Rejected -> throw SegmentException.StorageIneligible
            }
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
            Crc32c.value(bytes, offset, length)
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
enum class UidBucket {
    ROLE_SEGMENTS,
    RAW_ARTIFACTS,
    SUMMARY_SPOOL,
    SUMMARY_STAGING,
    SNAPSHOTS,
    COMPACTION,
    EMERGENCY,
    METADATA,
}
data class UidQuota(val limits: Map<UidBucket, Long>) {
    fun hardBound(): Long = limits.values.sum()
}

/** Immutable defensive view of one durable UID-wide reservation. */
internal data class UidAllocation(val bucket: UidBucket, val bytes: Long)

/** Result of moving one durable reservation without temporarily charging both path names. */
internal enum class UidReservationTransferResult {
    TRANSFERRED,
    ALREADY_TRANSFERRED,
    REJECTED,
}

/** UID accounting with byte and file count bounds for every bucket. */
class UidAccounting(private val quota: UidQuota, private val maxFiles: Map<UidBucket, Int>) {
    private val lock = Any()
    private val allocations = linkedMapOf<Path, Pair<UidBucket, Long>>()
    fun reserve(path: Path, bucket: UidBucket, bytes: Long): Boolean = synchronized(lock) {
        if (bytes < 0 || path in allocations) return@synchronized false
        val used = allocations.filterValues { it.first == bucket }.values.sumOf { it.second }
        val count = allocations.values.count { it.first == bucket }
        if (used + bytes > (quota.limits[bucket] ?: 0) || count >= (maxFiles[bucket] ?: 0)) return@synchronized false
        allocations[path] = bucket to bytes
        true
    }
    fun release(path: Path) = synchronized(lock) { allocations.remove(path) }
    fun used(bucket: UidBucket): Long = synchronized(lock) {
        allocations.filterValues { it.first == bucket }.values.sumOf { it.second }
    }
}

/**
 * Durable UID-wide ownership ledger. Every library-owned byte is reserved to one bucket before
 * its path is created, and the ledger itself occupies a fixed metadata reserve so its atomic
 * replacement file cannot silently escape the hard bound.
 */
class UidWideQuotaCoordinator(
    root: Path,
    private val quota: UidQuota,
    private val maxFiles: Map<UidBucket, Int>,
    mutationBarrierRoot: Path = root,
) {
    private val root = safeStorageRoot(root)
    private val mutationBarrierRoot = safeStorageRoot(mutationBarrierRoot)
    private val storageMutationBarrier = UidWideStorageMutationBarrier(this.mutationBarrierRoot)
    private val lockPath = this.root.resolve(".tracebox-uid-quota.lock")
    private val processLedgerLock = uidQuotaProcessLock(lockPath)
    private val ledgerPath = this.root.resolve("tracebox-uid-quota-v1")
    private val temporaryLedgerPath = this.root.resolve("tracebox-uid-quota-v1.new")
    private val coordinatorMetadataReserve =
        maxOf(
            MIN_COORDINATOR_METADATA_RESERVE,
            minOf(
                MAX_COORDINATOR_METADATA_RESERVE,
                (quota.limits[UidBucket.METADATA] ?: 0) / 2,
            ),
        )

    init {
        require((quota.limits[UidBucket.METADATA] ?: 0) >= coordinatorMetadataReserve)
        require((maxFiles[UidBucket.METADATA] ?: 0) >= COORDINATOR_METADATA_FILES)
    }

    /**
     * Runs one complete reserve/physical-mutation/finalization transaction under the UID-wide
     * cross-process barrier. This is intentionally separate from the quota ledger lock so [block]
     * may call reserve, resize, transfer, and release without re-entering a file lock.
     */
    fun <T> withStorageMutation(block: () -> T): T =
        storageMutationBarrier.withExclusiveMutation(block)

    /**
     * Acquires the UID-wide barrier, rechecks durable policy/root eligibility, and only then runs
     * one complete physical mutation. An exception from [eligibility] fails closed as [Rejected].
     */
    fun <T> mutateStorageIfEligible(
        eligibility: StorageMutationEligibility,
        block: () -> T,
    ): StorageMutationBarrierResult<T> =
        storageMutationBarrier.mutateIfEligible(eligibility, block)

    /** Reserves a new path before the caller creates or writes it. */
    fun reserve(path: Path, bucket: UidBucket, bytes: Long): Boolean = locked { allocations ->
        val owned = ownedPath(path)
        if (bytes < 0 || owned in allocations || !canFit(allocations, bucket, bytes, additionalFiles = 1)) {
            return@locked false
        }
        allocations[owned] = UidAllocation(bucket, bytes)
        true
    }

    /**
     * Atomically grows a previously-owned path. Callers use this before extending an append-only
     * segment or replacing a raw/spool file, then compensate with [resize] if the physical write
     * fails.
     */
    fun grow(path: Path, bucket: UidBucket, additionalBytes: Long): Boolean = locked { allocations ->
        val owned = ownedPath(path)
        val allocation = allocations[owned] ?: return@locked false
        if (allocation.bucket != bucket || additionalBytes < 0 ||
            !canFit(allocations, bucket, additionalBytes, additionalFiles = 0)
        ) {
            return@locked false
        }
        val grown = try {
            Math.addExact(allocation.bytes, additionalBytes)
        } catch (_: ArithmeticException) {
            return@locked false
        }
        allocations[owned] = allocation.copy(bytes = grown)
        true
    }

    /** Replaces an existing reservation size after a failed or shortened write. */
    fun resize(path: Path, bucket: UidBucket, bytes: Long): Boolean = locked { allocations ->
        val owned = ownedPath(path)
        val allocation = allocations[owned] ?: return@locked false
        if (allocation.bucket != bucket || bytes < 0) return@locked false
        val delta = bytes - allocation.bytes
        if (delta > 0 && !canFit(allocations, bucket, delta, additionalFiles = 0)) return@locked false
        allocations[owned] = allocation.copy(bytes = bytes)
        true
    }

    /** Removes a path from the hard ledger only after its owning caller removed or retired it. */
    fun release(path: Path): Boolean = locked { allocations ->
        allocations.remove(ownedPath(path)) != null
    }

    /**
     * Atomically transfers ownership before a same-filesystem rename, shrinking a conservative
     * source ceiling to the destination's exact completed size.
     *
     * [ALREADY_TRANSFERRED] is the crash-recovery state where the ledger commit survived but the
     * physical rename did not. No state accepts simultaneous source and destination reservations.
     */
    internal fun transfer(
        source: Path,
        destination: Path,
        bucket: UidBucket,
        destinationBytes: Long,
    ): UidReservationTransferResult = locked { allocations ->
        if (destinationBytes < 0L) return@locked UidReservationTransferResult.REJECTED
        val ownedSource = ownedPath(source)
        val ownedDestination = ownedPath(destination)
        if (ownedSource == ownedDestination) {
            return@locked if (allocations[ownedSource] == UidAllocation(bucket, destinationBytes)) {
                UidReservationTransferResult.ALREADY_TRANSFERRED
            } else {
                UidReservationTransferResult.REJECTED
            }
        }
        val sourceAllocation = allocations[ownedSource]
        val destinationAllocation = allocations[ownedDestination]
        when {
            sourceAllocation != null &&
                sourceAllocation.bucket == bucket &&
                sourceAllocation.bytes >= destinationBytes &&
                destinationAllocation == null -> {
                allocations.remove(ownedSource)
                allocations[ownedDestination] = UidAllocation(bucket, destinationBytes)
                UidReservationTransferResult.TRANSFERRED
            }

            sourceAllocation == null &&
                destinationAllocation == UidAllocation(bucket, destinationBytes) ->
                UidReservationTransferResult.ALREADY_TRANSFERRED

            else -> UidReservationTransferResult.REJECTED
        }
    }

    /** Returns the durable bucket usage including the coordinator's fixed metadata reserve. */
    fun used(bucket: UidBucket): Long = locked { allocations ->
        checkedBucketBytes(allocations, bucket)
    }

    /** Verifies an exact durable reservation without changing strict duplicate-reserve behavior. */
    fun owns(path: Path, bucket: UidBucket, bytes: Long): Boolean = locked { allocations ->
        bytes >= 0L && allocations[ownedPath(path)] == UidAllocation(bucket, bytes)
    }

    /** Defensive snapshot used by scoped startup reconciliation; callers cannot mutate the ledger. */
    internal fun allocations(): Map<Path, UidAllocation> = locked { allocations -> allocations.toMap() }

    private fun canFit(
        allocations: Map<Path, UidAllocation>,
        bucket: UidBucket,
        bytes: Long,
        additionalFiles: Int,
    ): Boolean {
        if (bytes < 0L || additionalFiles < 0) return false
        val used = try {
            checkedBucketBytes(allocations, bucket)
        } catch (_: ArithmeticException) {
            return false
        }
        val files = allocations.values.count { it.bucket == bucket } + reservedMetadataFiles(bucket)
        val byteLimit = quota.limits[bucket] ?: 0L
        val fileLimit = maxFiles[bucket] ?: 0
        return used <= byteLimit &&
            files <= fileLimit &&
            bytes <= byteLimit - used &&
            additionalFiles <= fileLimit - files
    }

    private fun checkedBucketBytes(
        allocations: Map<Path, UidAllocation>,
        bucket: UidBucket,
    ): Long {
        var used = reservedMetadataBytes(bucket)
        allocations.values.asSequence()
            .filter { it.bucket == bucket }
            .forEach { used = Math.addExact(used, it.bytes) }
        return used
    }

    private fun reservedMetadataBytes(bucket: UidBucket): Long =
        if (bucket == UidBucket.METADATA) coordinatorMetadataReserve else 0

    private fun reservedMetadataFiles(bucket: UidBucket): Int =
        if (bucket == UidBucket.METADATA) COORDINATOR_METADATA_FILES else 0

    private fun ownedPath(path: Path): Path {
        val owned = path.toAbsolutePath().normalize()
        require(owned.startsWith(root)) { "UID-wide quota path must remain inside its root" }
        return owned
    }

    private fun <T> locked(block: (MutableMap<Path, UidAllocation>) -> T): T {
        processLedgerLock.lock()
        try {
            try {
                if (hasSymbolicLinkComponent(root)) {
                    throw java.io.IOException("symbolic-link UID quota root is forbidden")
                }
                Files.createDirectories(root)
                if (hasSymbolicLinkComponent(root) ||
                    !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                ) {
                    throw java.io.IOException("unsafe UID quota root")
                }
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    channel.lock().use {
                        val allocations = readLedger()
                        val before = allocations.toMap()
                        val result = block(allocations)
                        if (allocations != before) persistLedger(allocations)
                        return result
                    }
                }
            } catch (failure: java.io.IOException) {
                throw UidQuotaLedgerException.Unavailable(failure)
            }
        } finally {
            processLedgerLock.unlock()
        }
    }

    private fun readLedger(): MutableMap<Path, UidAllocation> {
        val primaryExists = Files.exists(ledgerPath, LinkOption.NOFOLLOW_LINKS)
        val pendingExists = Files.exists(temporaryLedgerPath, LinkOption.NOFOLLOW_LINKS)
        val primary = if (primaryExists) {
            try {
                readLedgerFile(ledgerPath)
            } catch (_: UidQuotaLedgerException.Corrupt) {
                null
            }
        } else {
            null
        }
        val pending = if (pendingExists) {
            try {
                readLedgerFile(temporaryLedgerPath)
            } catch (_: UidQuotaLedgerException.Corrupt) {
                null
            }
        } else {
            null
        }
        if (pending != null) {
            try {
                promotePendingLedger()
            } catch (failure: java.io.IOException) {
                throw UidQuotaLedgerException.Unavailable(failure)
            }
            return pending
        }
        if (primary != null) return primary
        if (primaryExists || pendingExists) throw UidQuotaLedgerException.Corrupt
        return linkedMapOf()
    }

    private fun readLedgerFile(path: Path): MutableMap<Path, UidAllocation> {
        val encoded = try {
            readBoundedLedgerFile(path)
        } catch (failure: java.io.IOException) {
            throw UidQuotaLedgerException.Unavailable(failure)
        } ?: throw UidQuotaLedgerException.Corrupt
        if (encoded.any { it.toInt() !in 0..0x7f }) throw UidQuotaLedgerException.Corrupt
        val lines = encoded.toString(Charsets.US_ASCII).lines()
        if (lines.firstOrNull() != LEDGER_MAGIC) throw UidQuotaLedgerException.Corrupt
        val allocations = linkedMapOf<Path, UidAllocation>()
        lines.drop(1).filter(String::isNotBlank).forEach { line ->
            if (allocations.size == MAX_LEDGER_ENTRIES) throw UidQuotaLedgerException.Corrupt
            val fields = line.split('|')
            if (fields.size != 3) throw UidQuotaLedgerException.Corrupt
            val bucket = try {
                UidBucket.valueOf(fields[0])
            } catch (_: IllegalArgumentException) {
                throw UidQuotaLedgerException.Corrupt
            }
            val bytes = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: throw UidQuotaLedgerException.Corrupt
            val relativeBytes = try {
                Base64.getUrlDecoder().decode(fields[2])
            } catch (_: IllegalArgumentException) {
                throw UidQuotaLedgerException.Corrupt
            }
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(relativeBytes) != fields[2]) {
                throw UidQuotaLedgerException.Corrupt
            }
            val relative = relativeBytes.toString(Charsets.UTF_8)
            if (!relative.toByteArray(Charsets.UTF_8).contentEquals(relativeBytes) ||
                relative.isBlank()
            ) {
                throw UidQuotaLedgerException.Corrupt
            }
            val path = root.resolve(relative).normalize()
            if (Path.of(relative).isAbsolute ||
                !path.startsWith(root) ||
                path == root ||
                root.relativize(path).toString() != relative ||
                allocations.put(path, UidAllocation(bucket, bytes)) != null
            ) {
                throw UidQuotaLedgerException.Corrupt
            }
        }
        UidBucket.entries.forEach { bucket ->
            val used = try {
                checkedBucketBytes(allocations, bucket)
            } catch (_: ArithmeticException) {
                throw UidQuotaLedgerException.Corrupt
            }
            val files = allocations.values.count { it.bucket == bucket } + reservedMetadataFiles(bucket)
            if (used > (quota.limits[bucket] ?: 0L) ||
                files > (maxFiles[bucket] ?: 0)
            ) {
                throw UidQuotaLedgerException.Corrupt
            }
        }
        return allocations
    }

    private fun persistLedger(allocations: Map<Path, UidAllocation>) {
        val contents = buildString {
            append(LEDGER_MAGIC).append('\n')
            allocations.toSortedMap(compareBy(Path::toString)).forEach { (path, allocation) ->
                val relative = root.relativize(path).toString()
                append(allocation.bucket.name).append('|').append(allocation.bytes).append('|')
                append(Base64.getUrlEncoder().withoutPadding().encodeToString(relative.toByteArray(Charsets.UTF_8))).append('\n')
            }
        }
        val bytes = contents.toByteArray(Charsets.US_ASCII)
        if (bytes.size.toLong() > coordinatorMetadataReserve / 2) throw UidQuotaLedgerException.MetadataExhausted
        try {
            FileChannel.open(
                temporaryLedgerPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            promotePendingLedger()
        } catch (error: java.io.IOException) {
            throw UidQuotaLedgerException.Unavailable(error)
        }
    }

    private fun promotePendingLedger() {
        try {
            Files.move(
                temporaryLedgerPath,
                ledgerPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryLedgerPath,
                ledgerPath,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun readBoundedLedgerFile(path: Path): ByteArray? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val size = channel.size()
            if (size !in 1..coordinatorMetadataReserve / 2L ||
                size > Int.MAX_VALUE
            ) {
                return null
            }
            val bytes = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) return null
            }
            if (channel.size() == size) bytes else null
        }
    }

    sealed class UidQuotaLedgerException : IllegalStateException() {
        data object Corrupt : UidQuotaLedgerException()
        data object MetadataExhausted : UidQuotaLedgerException()
        data class Unavailable(val ioError: java.io.IOException) : UidQuotaLedgerException()
    }

    private companion object {
        const val LEDGER_MAGIC = "tracebox-uid-quota-v1"
        const val MIN_COORDINATOR_METADATA_RESERVE = 1_024L
        const val MAX_COORDINATOR_METADATA_RESERVE = 128L * 1_024
        // Ledger, pending replacement, quota lock, and storage-mutation lock.
        const val COORDINATOR_METADATA_FILES = 4
        const val MAX_LEDGER_ENTRIES = 16_384
    }
}

private val UID_QUOTA_PROCESS_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()

private fun uidQuotaProcessLock(lockPath: Path): ReentrantLock =
    UID_QUOTA_PROCESS_LOCKS.computeIfAbsent(lockPath.toAbsolutePath().normalize()) {
        ReentrantLock(true)
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

    internal fun lockPathFor(segment: Path): Path =
        (root ?: segment.parent).resolve(".tracebox-role-quota.lock")

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
        val lock = lockPathFor(segment)
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
        val lock = lockPathFor(segment)
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
