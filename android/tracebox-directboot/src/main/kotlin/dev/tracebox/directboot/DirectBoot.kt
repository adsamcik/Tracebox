package dev.tracebox.directboot

import android.content.Context
import android.os.Build
import dev.tracebox.api.Crc32c
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.core.BarrierAck
import dev.tracebox.core.GlobalPolicyCoordinator
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.ProfileUpdateResult
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
import java.util.concurrent.ConcurrentHashMap

/** Canonical names and hard bounds of the device-protected Direct Boot store. */
object DirectBootLayout {
    const val DIRECTORY_NAME = "tracebox-directboot"
    const val RECORDS_FILE_NAME = "tracebox-c0.records"
    const val ACTIVATION_FILE_NAME = "directboot-activation-v1"
    const val ACTIVATION_TEMP_FILE_NAME = "$ACTIVATION_FILE_NAME.new"
    const val ACTIVE_DENY_FILE_NAME = "active-deny-v1"
    const val PENDING_DENY_FILE_NAME = "pending-deny-v1"
    const val FRAME_VERSION = 2
    const val FRAME_SIZE_BYTES = 160
    const val RECORD_CAPACITY = 19
    const val RECORDS_BYTES = FRAME_SIZE_BYTES * RECORD_CAPACITY
    const val ACTIVATION_BYTES = 64

    /**
     * [deviceProtectedNoBackupDirectory] must be
     * `context.createDeviceProtectedStorageContext().noBackupFilesDir`.
     */
    fun fromDeviceProtectedNoBackupDirectory(
        deviceProtectedNoBackupDirectory: Path,
    ): DirectBootPaths {
        require(deviceProtectedNoBackupDirectory.isAbsolute)
        val noBackup = deviceProtectedNoBackupDirectory.toAbsolutePath().normalize()
        val root = noBackup.resolve(DIRECTORY_NAME).normalize()
        require(root.parent == noBackup)
        return DirectBootPaths(
            root = root,
            records = root.resolve(RECORDS_FILE_NAME),
            activation = root.resolve(ACTIVATION_FILE_NAME),
            activationTemp = root.resolve(ACTIVATION_TEMP_FILE_NAME),
            activeDeny = root.resolve(ACTIVE_DENY_FILE_NAME),
            pendingDeny = root.resolve(PENDING_DENY_FILE_NAME),
        )
    }

    /** Derives the canonical root from Android device-protected storage. */
    fun fromDeviceProtectedContext(context: Context): DirectBootPaths {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw IllegalStateException("Android Direct Boot requires API 24 or newer")
        }
        val storageContext = context.createDeviceProtectedStorageContext()
        check(storageContext.isDeviceProtectedStorage) {
            "Direct Boot requires a device-protected Context"
        }
        return fromDeviceProtectedNoBackupDirectory(storageContext.noBackupFilesDir.toPath())
    }
}

/** Fully derived paths; callers cannot substitute an arbitrary records or activation filename. */
class DirectBootPaths internal constructor(
    val root: Path,
    val records: Path,
    val activation: Path,
    val activationTemp: Path,
    val activeDeny: Path,
    val pendingDeny: Path,
)

/** Operation observed by the façade-owned mutation and root-eligibility guard. */
enum class DirectBootMutation {
    SETUP,
    APPEND,
    DRAIN,
    RETIRE,
    DISABLE,
}

/**
 * Exact reservation requested while the façade holds the device-protected mutation barrier.
 *
 * The complete 3,040-byte records file is charged during setup and every later operation reuses
 * that reservation. Metadata is bounded separately by the façade.
 */
data class DirectBootStorageMutationRequest(
    val recordsPath: Path,
    val reservationBytes: Long = DirectBootLayout.RECORDS_BYTES.toLong(),
    val operation: DirectBootMutation = DirectBootMutation.APPEND,
) {
    init {
        require(recordsPath.isAbsolute)
        require(reservationBytes == DirectBootLayout.RECORDS_BYTES.toLong())
    }
}

/**
 * Bridge to the existing-only DE-root mutation lock and UID quota owner.
 *
 * Implementations acquire the lock, recheck that the canonical root is still eligible, ensure
 * the fixed reservation for setup, and invoke [mutation] exactly once only when accepted.
 */
fun interface DirectBootStorageMutationGuard {
    fun mutateIfEligible(
        request: DirectBootStorageMutationRequest,
        mutation: () -> Unit,
    ): Boolean
}

/** Result of setup's explicit durable opt-in. */
enum class DirectBootSetupResult {
    ACTIVATED,
    ALREADY_ACTIVE,
    STORAGE_INELIGIBLE,
    INVALID_STORAGE,
    SCHEMA_MISMATCH,
}

/** Result of making the activation marker fail closed before clearing record slots. */
enum class DirectBootDisableResult {
    DISABLED,
    ALREADY_DISABLED,
    STORAGE_INELIGIBLE,
    INVALID_STORAGE,
}

/** Fail-closed activation state exposed without record bytes. */
enum class DirectBootActivationStatus {
    ACTIVE,
    DISABLED,
    ABSENT,
    INVALID,
}

/** A bounded result which never admits an arbitrary or non-C0 payload. */
enum class DirectBootWriteResult {
    WRITTEN,
    ALREADY_PRESENT,
    DENIED,
    DISABLED,
    NOT_ACTIVATED,
    INVALID_ACTIVATION,
    POLICY_MISMATCH,
    QUOTA_EXHAUSTED,
    STORAGE_INELIGIBLE,
    INVALID_STORAGE,
}

/** Result of scanning and, when necessary, zero-repairing the fixed-size valid prefix. */
data class DirectBootRecovery(
    val recordCount: Int,
    val validBytes: Long,
    val repaired: Boolean,
)

/** Why a bounded drain did or did not expose typed C0 records. */
enum class DirectBootDrainStatus {
    READY,
    NOT_ACTIVATED,
    INVALID_ACTIVATION,
    POLICY_DISABLED,
    POLICY_DENIED,
    STORAGE_INELIGIBLE,
    INVALID_STORAGE,
}

data class DirectBootDrainBatch(
    val status: DirectBootDrainStatus,
    val records: List<DirectBootDrainedRecord>,
    val recovery: DirectBootRecovery?,
)

/** Immutable, content-comparable 32-byte identity assigned from canonical v2 record bytes. */
class DirectBootSourceId internal constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == SOURCE_ID_BYTES)
    }

    fun toByteArray(): ByteArray = value.copyOf()

    val hex: String
        get() {
            val alphabet = "0123456789abcdef"
            return buildString(SOURCE_ID_BYTES * 2) {
                value.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    append(alphabet[unsigned ushr 4])
                    append(alphabet[unsigned and 0xf])
                }
            }
        }

    override fun equals(other: Any?): Boolean =
        other is DirectBootSourceId && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = hex
}

/** Opaque slot/source pair returned only by a successful drain. */
class DirectBootDrainToken internal constructor(
    val slotIndex: Int,
    val sourceId: DirectBootSourceId,
)

/** Complete typed v2 frame supplied to an idempotent CE importer. */
class DirectBootDrainedRecord internal constructor(
    val token: DirectBootDrainToken,
    record: C0DirectBootRecord,
) {
    private val schema = record.schemaFingerprint.copyOf()

    val sourceId: DirectBootSourceId get() = token.sourceId
    val schemaFingerprint: ByteArray get() = schema.copyOf()
    val slotSequence: ULong = record.slotSequence
    val policyEpoch: ULong = record.policyEpoch
    val signalNumber: Int = record.signalNumber
    val signalCode: Int = record.signalCode
    val processRole: UInt = record.processRole
    val threadRole: UInt = record.threadRole
    val flags: ULong = record.flags
    val elapsedMillis: Long = record.elapsedMillis
    val readinessCode: Int = record.readinessCode
    val categoryMask: Long = record.categoryMask

    fun toGeneratedEmergencyRecord(): GeneratedEmergencyRecord =
        GeneratedEmergencyRecord(
            slotSequence,
            policyEpoch,
            signalNumber,
            signalCode,
            processRole,
            threadRole,
            flags,
        )
}

/** Durable-ack lookup. It must return true only after the CE ack has been forced to storage. */
fun interface DirectBootDurableAck {
    fun contains(sourceId: DirectBootSourceId): Boolean
}

enum class DirectBootRetireResult {
    RETIRED,
    ALREADY_RETIRED,
    NOT_DURABLY_ACKNOWLEDGED,
    NOT_TAIL,
    STALE_TOKEN,
    NOT_ACTIVATED,
    INVALID_ACTIVATION,
    STORAGE_INELIGIBLE,
    INVALID_STORAGE,
}

/**
 * Provisioning, drain, and retirement façade for the one canonical device-protected store.
 *
 * [setup] is the explicit opt-in. [openCapture] returns null unless its CRC-protected activation
 * exactly matches this runtime's schema and fixed capacity. Every mutation revalidates activation,
 * policy, root eligibility, and the fixed-size file while the injected guard is held.
 */
class DirectBootManager private constructor(
    private val paths: DirectBootPaths,
    schemaFingerprint: ByteArray,
    private val mutationGuard: DirectBootStorageMutationGuard,
    private val crashInjector: DirectBootPersistenceCrashInjector?,
) {
    private val expectedSchema = schemaFingerprint.copyOf()
    private val mirror = DenyMirror(paths.activeDeny, paths.pendingDeny)
    private val processLock = STORE_LOCKS.computeIfAbsent(paths.root) { Any() }

    init {
        require(expectedSchema.size == SCHEMA_FINGERPRINT_BYTES)
    }

    fun activationStatus(): DirectBootActivationStatus =
        invalidStorageResult(DirectBootActivationStatus.INVALID) {
            when (val activation = readActivation()) {
                ActivationRead.Absent -> DirectBootActivationStatus.ABSENT
                ActivationRead.Invalid -> DirectBootActivationStatus.INVALID
                is ActivationRead.Valid -> when {
                    !activation.value.schemaFingerprint.contentEquals(expectedSchema) ->
                        DirectBootActivationStatus.INVALID
                    activation.value.enabled -> DirectBootActivationStatus.ACTIVE
                    else -> DirectBootActivationStatus.DISABLED
                }
            }
        }

    fun setup(): DirectBootSetupResult =
        invalidStorageResult(DirectBootSetupResult.INVALID_STORAGE) {
            mutate(DirectBootMutation.SETUP, DirectBootSetupResult.STORAGE_INELIGIBLE) {
                synchronized(processLock) {
                    val activation = readActivation()
                    if (activation is ActivationRead.Valid &&
                        !activation.value.schemaFingerprint.contentEquals(expectedSchema)
                    ) {
                        return@synchronized DirectBootSetupResult.SCHEMA_MISMATCH
                    }

                    if (!ensureCanonicalRoot(create = true)) {
                        return@synchronized DirectBootSetupResult.INVALID_STORAGE
                    }

                    if (activation is ActivationRead.Valid && activation.value.enabled) {
                        val scan = scanRecords(repair = true)
                        return@synchronized if (scan is StoreScan.Valid) {
                            DirectBootSetupResult.ALREADY_ACTIVE
                        } else {
                            DirectBootSetupResult.INVALID_STORAGE
                        }
                    }

                    if ((activation == ActivationRead.Absent ||
                            activation == ActivationRead.Invalid) &&
                        existingRecordsContainDataOrAreInvalid()
                    ) {
                        return@synchronized DirectBootSetupResult.INVALID_STORAGE
                    }

                    initializeRecordsInPlace()
                    writeActivation(
                        Activation(enabled = true, schemaFingerprint = expectedSchema),
                        DirectBootPersistenceBoundary.SETUP_ACTIVATION_TEMP_SYNCED,
                        DirectBootPersistenceBoundary.SETUP_ACTIVATION_REPLACED,
                    )
                    DirectBootSetupResult.ACTIVATED
                }
            }
        }

    fun disable(): DirectBootDisableResult =
        invalidStorageResult(DirectBootDisableResult.INVALID_STORAGE) {
            mutate(DirectBootMutation.DISABLE, DirectBootDisableResult.STORAGE_INELIGIBLE) {
                synchronized(processLock) {
                    if (!Files.exists(paths.root, LinkOption.NOFOLLOW_LINKS)) {
                        return@synchronized DirectBootDisableResult.ALREADY_DISABLED
                    }
                    if (!ensureCanonicalRoot(create = false)) {
                        return@synchronized DirectBootDisableResult.INVALID_STORAGE
                    }
                    val hadActivation =
                        Files.exists(paths.activation, LinkOption.NOFOLLOW_LINKS)
                    val hadRecords = Files.exists(paths.records, LinkOption.NOFOLLOW_LINKS)
                    Files.deleteIfExists(paths.activation)
                    Files.deleteIfExists(paths.activationTemp)
                    forceDirectory(paths.root)
                    crashInjector?.after(
                        DirectBootPersistenceBoundary.DISABLE_ACTIVATION_REMOVED,
                    )
                    Files.deleteIfExists(paths.records)
                    forceDirectory(paths.root)
                    crashInjector?.after(
                        DirectBootPersistenceBoundary.DISABLE_RECORDS_REMOVED,
                    )
                    if (!hadActivation && !hadRecords) {
                        DirectBootDisableResult.ALREADY_DISABLED
                    } else {
                        DirectBootDisableResult.DISABLED
                    }
                }
            }
        }

    /**
     * Returns the generated-record-only writer only for a complete active activation.
     *
     * The writer still rechecks activation and policy under the mutation guard for every append.
     */
    fun openCapture(): DirectBootCapture? {
        if (activationStatus() != DirectBootActivationStatus.ACTIVE) return null
        if (!Files.isRegularFile(paths.records, LinkOption.NOFOLLOW_LINKS)) return null
        val size = try {
            Files.size(paths.records)
        } catch (_: java.io.IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        if (size != DirectBootLayout.RECORDS_BYTES.toLong()) return null
        return DirectBootCapture(this)
    }

    /** Enumerates at most [maxRecords] valid prefix frames for CE import. */
    fun drain(maxRecords: Int = DirectBootLayout.RECORD_CAPACITY): DirectBootDrainBatch {
        require(maxRecords in 1..DirectBootLayout.RECORD_CAPACITY)
        return invalidStorageResult(
            DirectBootDrainBatch(DirectBootDrainStatus.INVALID_STORAGE, emptyList(), null),
        ) {
            mutate(
                DirectBootMutation.DRAIN,
                DirectBootDrainBatch(
                    DirectBootDrainStatus.STORAGE_INELIGIBLE,
                    emptyList(),
                    null,
                ),
            ) {
                synchronized(processLock) {
                    when (activationStatus()) {
                        DirectBootActivationStatus.ABSENT ->
                            return@synchronized DirectBootDrainBatch(
                                DirectBootDrainStatus.NOT_ACTIVATED,
                                emptyList(),
                                null,
                            )
                        DirectBootActivationStatus.DISABLED ->
                            return@synchronized DirectBootDrainBatch(
                                DirectBootDrainStatus.POLICY_DISABLED,
                                emptyList(),
                                null,
                            )
                        DirectBootActivationStatus.INVALID ->
                            return@synchronized DirectBootDrainBatch(
                                DirectBootDrainStatus.INVALID_ACTIVATION,
                                emptyList(),
                                null,
                            )
                        DirectBootActivationStatus.ACTIVE -> Unit
                    }
                    val policy = mirror.effective()
                    if (policy == null || policy.disabled) {
                        return@synchronized DirectBootDrainBatch(
                            DirectBootDrainStatus.POLICY_DISABLED,
                            emptyList(),
                            null,
                        )
                    }
                    if ((policy.c0DenyMask and GENERATED_EMERGENCY_CATEGORY) != 0L) {
                        return@synchronized DirectBootDrainBatch(
                            DirectBootDrainStatus.POLICY_DENIED,
                            emptyList(),
                            null,
                        )
                    }
                    when (val scan = scanRecords(repair = true)) {
                        StoreScan.Invalid -> DirectBootDrainBatch(
                            DirectBootDrainStatus.INVALID_STORAGE,
                            emptyList(),
                            null,
                        )
                        is StoreScan.Valid -> DirectBootDrainBatch(
                            DirectBootDrainStatus.READY,
                            scan.records.take(maxRecords).mapIndexed { index, stored ->
                                DirectBootDrainedRecord(
                                    DirectBootDrainToken(index, stored.sourceId),
                                    stored.record,
                                )
                            },
                            scan.recovery,
                        )
                    }
                }
            }
        }
    }

    /**
     * Zeroes exactly one durably acknowledged tail slot.
     *
     * Drains are ordered oldest-first; import and force CE records/acks in that order, then retire
     * tokens newest-first. Tail-only retirement preserves the crash-recoverable valid-prefix
     * invariant without moving records or invalidating their stable IDs.
     */
    fun retireAcknowledged(
        token: DirectBootDrainToken,
        durableAck: DirectBootDurableAck,
    ): DirectBootRetireResult {
        if (!durableAck.contains(token.sourceId)) {
            return DirectBootRetireResult.NOT_DURABLY_ACKNOWLEDGED
        }
        return invalidStorageResult(DirectBootRetireResult.INVALID_STORAGE) {
            mutate(DirectBootMutation.RETIRE, DirectBootRetireResult.STORAGE_INELIGIBLE) {
                synchronized(processLock) {
                    when (activationStatus()) {
                        DirectBootActivationStatus.ABSENT ->
                            return@synchronized DirectBootRetireResult.NOT_ACTIVATED
                        DirectBootActivationStatus.INVALID ->
                            return@synchronized DirectBootRetireResult.INVALID_ACTIVATION
                        DirectBootActivationStatus.ACTIVE,
                        DirectBootActivationStatus.DISABLED,
                        -> Unit
                    }
                    when (val scan = scanRecords(repair = true)) {
                        StoreScan.Invalid -> DirectBootRetireResult.INVALID_STORAGE
                        is StoreScan.Valid -> {
                            if (token.slotIndex >= scan.records.size) {
                                return@synchronized DirectBootRetireResult.ALREADY_RETIRED
                            }
                            val stored = scan.records[token.slotIndex]
                            if (stored.sourceId != token.sourceId) {
                                return@synchronized DirectBootRetireResult.STALE_TOKEN
                            }
                            if (token.slotIndex != scan.records.lastIndex) {
                                return@synchronized DirectBootRetireResult.NOT_TAIL
                            }
                            FileChannel.open(
                                paths.records,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS,
                            ).use { channel ->
                                channel.lock().use {
                                    channel.position(
                                        token.slotIndex.toLong() *
                                            DirectBootLayout.FRAME_SIZE_BYTES,
                                    )
                                    writeFully(channel, ByteBuffer.wrap(ZERO_FRAME))
                                    crashInjector?.after(
                                        DirectBootPersistenceBoundary.RETIRE_SLOT_ZEROED,
                                    )
                                    channel.force(true)
                                    crashInjector?.after(
                                        DirectBootPersistenceBoundary.RETIRE_FORCED,
                                    )
                                }
                            }
                            DirectBootRetireResult.RETIRED
                        }
                    }
                }
            }
        }
    }

    internal fun appendGenerated(record: GeneratedDirectBootRecord): DirectBootWriteResult {
        return invalidStorageResult(DirectBootWriteResult.INVALID_STORAGE) {
            when (activationStatus()) {
                DirectBootActivationStatus.ABSENT ->
                    return@invalidStorageResult DirectBootWriteResult.NOT_ACTIVATED
                DirectBootActivationStatus.DISABLED ->
                    return@invalidStorageResult DirectBootWriteResult.DISABLED
                DirectBootActivationStatus.INVALID ->
                    return@invalidStorageResult DirectBootWriteResult.INVALID_ACTIVATION
                DirectBootActivationStatus.ACTIVE -> Unit
            }
            policyResult(record.c0)?.let { return@invalidStorageResult it }
            mutate(DirectBootMutation.APPEND, DirectBootWriteResult.STORAGE_INELIGIBLE) {
                synchronized(processLock) {
                    when (activationStatus()) {
                        DirectBootActivationStatus.ABSENT ->
                            return@synchronized DirectBootWriteResult.NOT_ACTIVATED
                        DirectBootActivationStatus.DISABLED ->
                            return@synchronized DirectBootWriteResult.DISABLED
                        DirectBootActivationStatus.INVALID ->
                            return@synchronized DirectBootWriteResult.INVALID_ACTIVATION
                        DirectBootActivationStatus.ACTIVE -> Unit
                    }
                    policyResult(record.c0)?.let { return@synchronized it }
                    when (val scan = scanRecords(repair = true)) {
                        StoreScan.Invalid -> DirectBootWriteResult.INVALID_STORAGE
                        is StoreScan.Valid -> {
                            val sourceId = sourceId(record.c0)
                            if (scan.records.any { it.sourceId == sourceId }) {
                                return@synchronized DirectBootWriteResult.ALREADY_PRESENT
                            }
                            if (scan.records.size >= DirectBootLayout.RECORD_CAPACITY) {
                                return@synchronized DirectBootWriteResult.QUOTA_EXHAUSTED
                            }
                            val frame = encodeFrame(record.c0, sourceId)
                            FileChannel.open(
                                paths.records,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS,
                            ).use { channel ->
                                channel.lock().use {
                                    channel.position(
                                        scan.records.size.toLong() *
                                            DirectBootLayout.FRAME_SIZE_BYTES,
                                    )
                                    writeFully(channel, ByteBuffer.wrap(frame))
                                    crashInjector?.after(
                                        DirectBootPersistenceBoundary.APPEND_FRAME_WRITTEN,
                                    )
                                    channel.force(true)
                                    crashInjector?.after(
                                        DirectBootPersistenceBoundary.APPEND_FORCED,
                                    )
                                }
                            }
                            DirectBootWriteResult.WRITTEN
                        }
                    }
                }
            }
        }
    }

    private fun policyResult(record: C0DirectBootRecord): DirectBootWriteResult? {
        if (!record.schemaFingerprint.contentEquals(expectedSchema)) {
            return DirectBootWriteResult.INVALID_ACTIVATION
        }
        val policy = mirror.effective() ?: return DirectBootWriteResult.DISABLED
        if (policy.disabled) return DirectBootWriteResult.DISABLED
        if ((policy.c0DenyMask and record.categoryMask) != 0L) {
            return DirectBootWriteResult.DENIED
        }
        if (record.policyEpoch != policy.epoch.toULong()) {
            return DirectBootWriteResult.POLICY_MISMATCH
        }
        return null
    }

    private fun scanRecords(repair: Boolean): StoreScan {
        if (!Files.isRegularFile(paths.records, LinkOption.NOFOLLOW_LINKS)) {
            return StoreScan.Invalid
        }
        return try {
            FileChannel.open(
                paths.records,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                channel.lock().use {
                    if (channel.size() > DirectBootLayout.RECORDS_BYTES) {
                        return StoreScan.Invalid
                    }
                    var repaired = false
                    if (channel.size() < DirectBootLayout.RECORDS_BYTES) {
                        if (!repair) return StoreScan.Invalid
                        channel.position(channel.size())
                        writeFully(
                            channel,
                            ByteBuffer.wrap(
                                ByteArray((DirectBootLayout.RECORDS_BYTES - channel.size()).toInt()),
                            ),
                        )
                        channel.force(true)
                        repaired = true
                    }

                    val records = ArrayList<StoredRecord>(DirectBootLayout.RECORD_CAPACITY)
                    var repairFrom = -1
                    for (slot in 0 until DirectBootLayout.RECORD_CAPACITY) {
                        val frame = readExactly(
                            channel,
                            slot.toLong() * DirectBootLayout.FRAME_SIZE_BYTES,
                            DirectBootLayout.FRAME_SIZE_BYTES,
                        ) ?: return StoreScan.Invalid
                        if (frame.isAllZero()) {
                            for (suffix in slot + 1 until DirectBootLayout.RECORD_CAPACITY) {
                                val later = readExactly(
                                    channel,
                                    suffix.toLong() * DirectBootLayout.FRAME_SIZE_BYTES,
                                    DirectBootLayout.FRAME_SIZE_BYTES,
                                ) ?: return StoreScan.Invalid
                                if (!later.isAllZero()) {
                                    repairFrom = slot
                                    break
                                }
                            }
                            break
                        }
                        val decoded = decodeFrame(frame)
                        if (decoded == null ||
                            !decoded.record.schemaFingerprint.contentEquals(expectedSchema)
                        ) {
                            repairFrom = slot
                            break
                        }
                        records += decoded
                    }
                    if (repairFrom >= 0) {
                        if (!repair) return StoreScan.Invalid
                        zeroSuffix(channel, repairFrom)
                        channel.force(true)
                        repaired = true
                    }
                    StoreScan.Valid(
                        records,
                        DirectBootRecovery(
                            recordCount = records.size,
                            validBytes =
                                records.size.toLong() * DirectBootLayout.FRAME_SIZE_BYTES,
                            repaired = repaired,
                        ),
                    )
                }
            }
        } catch (_: java.io.IOException) {
            StoreScan.Invalid
        } catch (_: SecurityException) {
            StoreScan.Invalid
        }
    }

    private fun zeroSuffix(channel: FileChannel, startSlot: Int) {
        channel.position(startSlot.toLong() * DirectBootLayout.FRAME_SIZE_BYTES)
        writeFully(
            channel,
            ByteBuffer.wrap(
                ByteArray(
                    (DirectBootLayout.RECORD_CAPACITY - startSlot) *
                        DirectBootLayout.FRAME_SIZE_BYTES,
                ),
            ),
        )
    }

    private fun existingRecordsContainDataOrAreInvalid(): Boolean {
        if (!Files.exists(paths.records, LinkOption.NOFOLLOW_LINKS)) return false
        if (!Files.isRegularFile(paths.records, LinkOption.NOFOLLOW_LINKS)) return true
        val size = try {
            Files.size(paths.records)
        } catch (_: java.io.IOException) {
            return true
        }
        if (size > DirectBootLayout.RECORDS_BYTES) return true
        return try {
            FileChannel.open(
                paths.records,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                var offset = 0L
                val buffer = ByteBuffer.allocate(512)
                while (offset < size) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), size - offset).toInt())
                    channel.position(offset)
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) < 0) return true
                    }
                    if (!buffer.array().copyOf(buffer.limit()).isAllZero()) return true
                    offset += buffer.limit()
                }
                false
            }
        } catch (_: java.io.IOException) {
            true
        }
    }

    private fun ensureCanonicalRoot(create: Boolean): Boolean {
        if (create) Files.createDirectories(paths.root)
        return Files.isDirectory(paths.root, LinkOption.NOFOLLOW_LINKS) &&
            paths.records.parent == paths.root &&
            paths.activation.parent == paths.root &&
            paths.activationTemp.parent == paths.root &&
            paths.activeDeny.parent == paths.root &&
            paths.pendingDeny.parent == paths.root
    }

    /**
     * Initializes the one charged EMERGENCY file in place. No second 3,040-byte file can coexist.
     *
     * This is safe before activation: an interrupted all-zero write remains unavailable and a
     * later setup either completes the zero initialization or rejects non-zero abandoned data.
     */
    private fun initializeRecordsInPlace() {
        if (Files.exists(paths.records, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(paths.records, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw java.io.IOException("Direct Boot records path is not a regular file")
        }
        FileChannel.open(
            paths.records,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            writeFully(channel, ByteBuffer.wrap(ZERO_RECORDS))
            channel.force(true)
        }
        crashInjector?.after(DirectBootPersistenceBoundary.SETUP_RECORDS_INITIALIZED)
        forceDirectory(paths.root)
    }

    private fun readActivation(): ActivationRead {
        if (!Files.exists(paths.activation, LinkOption.NOFOLLOW_LINKS)) {
            return ActivationRead.Absent
        }
        if (!Files.isRegularFile(paths.activation, LinkOption.NOFOLLOW_LINKS)) {
            return ActivationRead.Invalid
        }
        val bytes = try {
            if (Files.size(paths.activation) != ACTIVATION_SIZE.toLong()) {
                return ActivationRead.Invalid
            }
            FileChannel.open(
                paths.activation,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { readExactly(it, 0, ACTIVATION_SIZE) } ?: return ActivationRead.Invalid
        } catch (_: java.io.IOException) {
            return ActivationRead.Invalid
        }
        if (Crc32c.value(bytes, 0, ACTIVATION_CRC_OFFSET) !=
            ByteBuffer.wrap(bytes, ACTIVATION_CRC_OFFSET, Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).int
        ) {
            return ActivationRead.Invalid
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != ACTIVATION_MAGIC || buffer.int != ACTIVATION_VERSION) {
            return ActivationRead.Invalid
        }
        val enabled = when (buffer.int) {
            0 -> false
            1 -> true
            else -> return ActivationRead.Invalid
        }
        if (buffer.int != DirectBootLayout.FRAME_VERSION ||
            buffer.int != DirectBootLayout.FRAME_SIZE_BYTES ||
            buffer.int != DirectBootLayout.RECORD_CAPACITY ||
            buffer.int != DirectBootLayout.RECORDS_BYTES
        ) {
            return ActivationRead.Invalid
        }
        val schema = ByteArray(SCHEMA_FINGERPRINT_BYTES).also(buffer::get)
        return ActivationRead.Valid(Activation(enabled, schema))
    }

    private fun writeActivation(
        activation: Activation,
        tempSynced: DirectBootPersistenceBoundary,
        replaced: DirectBootPersistenceBoundary,
    ) {
        val bytes = ByteBuffer.allocate(ACTIVATION_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(ACTIVATION_MAGIC)
            .putInt(ACTIVATION_VERSION)
            .putInt(if (activation.enabled) 1 else 0)
            .putInt(DirectBootLayout.FRAME_VERSION)
            .putInt(DirectBootLayout.FRAME_SIZE_BYTES)
            .putInt(DirectBootLayout.RECORD_CAPACITY)
            .putInt(DirectBootLayout.RECORDS_BYTES)
            .put(activation.schemaFingerprint)
            .array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(ACTIVATION_CRC_OFFSET, Crc32c.value(bytes, 0, ACTIVATION_CRC_OFFSET))
        val temporary = paths.activationTemp
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            writeFully(channel, ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        crashInjector?.after(tempSynced)
        atomicReplace(temporary, paths.activation)
        crashInjector?.after(replaced)
        forceDirectory(paths.root)
    }

    private fun <T : Any> mutate(
        operation: DirectBootMutation,
        rejected: T,
        mutation: () -> T,
    ): T {
        var invoked = false
        var result: T? = null
        val accepted = mutationGuard.mutateIfEligible(
            DirectBootStorageMutationRequest(
                recordsPath = paths.records,
                operation = operation,
            ),
        ) {
            check(!invoked) { "Direct Boot mutation guard invoked a mutation more than once" }
            invoked = true
            result = mutation()
        }
        if (!accepted) {
            check(!invoked) { "Rejected Direct Boot guard invoked the mutation" }
            return rejected
        }
        check(invoked) { "Accepted Direct Boot guard did not invoke the mutation" }
        return checkNotNull(result)
    }

    companion object {
        private const val ACTIVATION_MAGIC = 0x41444254
        private const val ACTIVATION_VERSION = 1
        private const val ACTIVATION_SIZE = DirectBootLayout.ACTIVATION_BYTES
        private const val ACTIVATION_CRC_OFFSET = ACTIVATION_SIZE - Int.SIZE_BYTES
        private const val FRAME_MAGIC = 0x32424454
        private const val FRAME_PAYLOAD_SIZE = 124
        private const val SCHEMA_FINGERPRINT_BYTES = 32
        private const val SOURCE_MATERIAL_SIZE = FRAME_PAYLOAD_SIZE - SOURCE_ID_BYTES
        private const val FRAME_CRC_OFFSET =
            DirectBootLayout.FRAME_SIZE_BYTES - Int.SIZE_BYTES
        private const val FRAME_RESERVED_OFFSET = 140
        private val GENERATED_EMERGENCY_CATEGORY = GENERATED_DIRECT_BOOT_CATEGORIES.single()
        private val ZERO_FRAME = ByteArray(DirectBootLayout.FRAME_SIZE_BYTES)
        private val ZERO_RECORDS = ByteArray(DirectBootLayout.RECORDS_BYTES)
        private val STORE_LOCKS = ConcurrentHashMap<Path, Any>()

        fun fromDeviceProtectedContext(
            context: Context,
            schemaFingerprint: ByteArray,
            mutationGuard: DirectBootStorageMutationGuard,
        ): DirectBootManager = DirectBootManager(
            DirectBootLayout.fromDeviceProtectedContext(context),
            schemaFingerprint,
            mutationGuard,
            null,
        )

        /**
         * Host-test and façade seam. Production passes the no-backup directory obtained from a
         * device-protected Context; the canonical child root and filenames are always derived.
         */
        fun fromDeviceProtectedNoBackupDirectory(
            deviceProtectedNoBackupDirectory: Path,
            schemaFingerprint: ByteArray,
            mutationGuard: DirectBootStorageMutationGuard,
        ): DirectBootManager = DirectBootManager(
            DirectBootLayout.fromDeviceProtectedNoBackupDirectory(
                deviceProtectedNoBackupDirectory,
            ),
            schemaFingerprint,
            mutationGuard,
            null,
        )

        internal fun forTest(
            deviceProtectedNoBackupDirectory: Path,
            schemaFingerprint: ByteArray,
            mutationGuard: DirectBootStorageMutationGuard,
            crashInjector: DirectBootPersistenceCrashInjector?,
        ): DirectBootManager = DirectBootManager(
            DirectBootLayout.fromDeviceProtectedNoBackupDirectory(
                deviceProtectedNoBackupDirectory,
            ),
            schemaFingerprint,
            mutationGuard,
            crashInjector,
        )
    }

    private data class Activation(
        val enabled: Boolean,
        val schemaFingerprint: ByteArray,
    )

    private sealed interface ActivationRead {
        data object Absent : ActivationRead
        data object Invalid : ActivationRead
        data class Valid(val value: Activation) : ActivationRead
    }

    private sealed interface StoreScan {
        data object Invalid : StoreScan
        data class Valid(
            val records: List<StoredRecord>,
            val recovery: DirectBootRecovery,
        ) : StoreScan
    }

    private data class StoredRecord(
        val sourceId: DirectBootSourceId,
        val record: C0DirectBootRecord,
    )

    private fun sourceId(record: C0DirectBootRecord): DirectBootSourceId {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(SOURCE_ID_DOMAIN)
        digest.update(sourceMaterial(record))
        return DirectBootSourceId(digest.digest())
    }

    private fun sourceMaterial(record: C0DirectBootRecord): ByteArray =
        ByteBuffer.allocate(SOURCE_MATERIAL_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(record.schemaFingerprint)
            .putLong(record.slotSequence.toLong())
            .putLong(record.policyEpoch.toLong())
            .putInt(record.signalNumber)
            .putInt(record.signalCode)
            .putInt(record.processRole.toInt())
            .putInt(record.threadRole.toInt())
            .putLong(record.flags.toLong())
            .putLong(record.elapsedMillis)
            .putInt(record.readinessCode)
            .putLong(record.categoryMask)
            .array()

    private fun encodeFrame(
        record: C0DirectBootRecord,
        sourceId: DirectBootSourceId,
    ): ByteArray {
        val bytes = ByteBuffer.allocate(DirectBootLayout.FRAME_SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(FRAME_MAGIC)
            .putInt(DirectBootLayout.FRAME_VERSION)
            .putInt(FRAME_PAYLOAD_SIZE)
            .putInt(0)
            .put(record.schemaFingerprint)
            .put(sourceId.toByteArray())
            .putLong(record.slotSequence.toLong())
            .putLong(record.policyEpoch.toLong())
            .putInt(record.signalNumber)
            .putInt(record.signalCode)
            .putInt(record.processRole.toInt())
            .putInt(record.threadRole.toInt())
            .putLong(record.flags.toLong())
            .putLong(record.elapsedMillis)
            .putInt(record.readinessCode)
            .putLong(record.categoryMask)
            .array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(FRAME_CRC_OFFSET, Crc32c.value(bytes, 0, FRAME_CRC_OFFSET))
        return bytes
    }

    private fun decodeFrame(bytes: ByteArray): StoredRecord? {
        if (bytes.size != DirectBootLayout.FRAME_SIZE_BYTES ||
            Crc32c.value(bytes, 0, FRAME_CRC_OFFSET) !=
            ByteBuffer.wrap(bytes, FRAME_CRC_OFFSET, Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).int
        ) {
            return null
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != FRAME_MAGIC ||
            buffer.int != DirectBootLayout.FRAME_VERSION ||
            buffer.int != FRAME_PAYLOAD_SIZE ||
            buffer.int != 0
        ) {
            return null
        }
        return try {
            val schemaFingerprint = ByteArray(SCHEMA_FINGERPRINT_BYTES).also(buffer::get)
            val persistedSource = DirectBootSourceId(
                ByteArray(SOURCE_ID_BYTES).also(buffer::get),
            )
            val record = C0DirectBootRecord(
                schemaFingerprint = schemaFingerprint,
                slotSequence = buffer.long.toULong(),
                policyEpoch = buffer.long.toULong(),
                signalNumber = buffer.int,
                signalCode = buffer.int,
                processRole = buffer.int.toUInt(),
                threadRole = buffer.int.toUInt(),
                flags = buffer.long.toULong(),
                elapsedMillis = buffer.long,
                readinessCode = buffer.int,
                categoryMask = buffer.long,
            )
            if (bytes.copyOfRange(FRAME_RESERVED_OFFSET, FRAME_CRC_OFFSET).any { it != 0.toByte() }) {
                return null
            }
            if (persistedSource != sourceId(record)) return null
            StoredRecord(persistedSource, record)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Writer obtainable only from [DirectBootManager.openCapture]. */
class DirectBootCapture internal constructor(
    private val manager: DirectBootManager,
) {
    /** The sole production write accepts only the schema-generated C0 wrapper. */
    fun appendGenerated(record: GeneratedDirectBootRecord): DirectBootWriteResult =
        manager.appendGenerated(record)
}

private val SOURCE_ID_DOMAIN = "Tracebox/DirectBoot/v2/source-id\u0000".toByteArray(Charsets.UTF_8)
private const val SOURCE_ID_BYTES = 32

private inline fun <T> invalidStorageResult(invalid: T, operation: () -> T): T =
    try {
        operation()
    } catch (_: java.io.IOException) {
        invalid
    } catch (_: SecurityException) {
        invalid
    } catch (_: UnsupportedOperationException) {
        invalid
    }

/** Host fault-injection seam around each durable Direct Boot storage boundary. */
internal fun interface DirectBootPersistenceCrashInjector {
    fun after(boundary: DirectBootPersistenceBoundary)
}

internal enum class DirectBootPersistenceBoundary {
    SETUP_RECORDS_INITIALIZED,
    SETUP_ACTIVATION_TEMP_SYNCED,
    SETUP_ACTIVATION_REPLACED,
    DISABLE_ACTIVATION_REMOVED,
    DISABLE_RECORDS_REMOVED,
    APPEND_FRAME_WRITTEN,
    APPEND_FORCED,
    RETIRE_SLOT_ZEROED,
    RETIRE_FORCED,
}

private fun readExactly(channel: FileChannel, offset: Long, length: Int): ByteArray? {
    val result = ByteBuffer.allocate(length)
    channel.position(offset)
    while (result.hasRemaining()) {
        if (channel.read(result) < 0) return null
    }
    return result.array()
}

private fun writeFully(channel: FileChannel, source: ByteBuffer) {
    while (source.hasRemaining()) channel.write(source)
}

private fun ByteArray.isAllZero(): Boolean = all { it == 0.toByte() }

private fun atomicReplace(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Best-effort directory sync; some host file systems do not permit directory channels. */
private fun forceDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: java.io.IOException) {
        // The file itself was forced and replacement was atomic where supported.
    } catch (_: UnsupportedOperationException) {
        // Directory fsync is not available from every desugared/host file-system provider.
    } catch (_: SecurityException) {
        // App-private storage can still deny directory handles on some platform releases.
    }
}

/** Active/pending deny state. `disabled` and masks are combined conservatively on ambiguity. */
data class DenyState(val epoch: Long, val disabled: Boolean, val c0DenyMask: Long) {
    init {
        require(epoch >= 0)
    }
}

/** Fail-closed DE mirror. Absent, corrupt, and newer-version mirrors return null. */
class DenyMirror private constructor(
    activePath: Path,
    pendingPath: Path,
    private val replacementCrashInjector: DenyMirrorReplacementCrashInjector?,
) {
    private val activePath = activePath.toAbsolutePath().normalize()
    private val pendingPath = pendingPath.toAbsolutePath().normalize()
    private val lock = Any()

    constructor(activePath: Path, pendingPath: Path) : this(activePath, pendingPath, null)

    init {
        require(this.activePath != this.pendingPath)
    }

    fun active(): DenyState? = synchronized(lock) { read(activePath) }
    fun pending(): DenyState? = synchronized(lock) { read(pendingPath) }
    fun effective(): DenyState? = synchronized(lock) {
        val active = active()
        val pending = pending()
        if ((Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) && active == null) ||
            (Files.exists(pendingPath, LinkOption.NOFOLLOW_LINKS) && pending == null)
        ) {
            return null
        }
        if (active == null && pending == null) return null
        return combine(active, pending)
    }

    fun writePending(state: DenyState) = synchronized(lock) { write(pendingPath, state) }
    fun promotePending() = synchronized(lock) {
        val pending = pending() ?: return
        write(activePath, pending)
        Files.deleteIfExists(pendingPath)
        forceDirectory(pendingPath.parent)
    }
    fun clearPending() = synchronized(lock) {
        Files.deleteIfExists(pendingPath)
        forceDirectory(pendingPath.parent)
    }

    fun reconcile(ce: DenyState): DenyState = synchronized(lock) {
        val active = active()
        val pending = pending()
        val corrupt =
            (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) && active == null) ||
                (Files.exists(pendingPath, LinkOption.NOFOLLOW_LINKS) && pending == null)
        val result = if (corrupt) {
            DenyState(
                epoch = maxOf(active?.epoch ?: 0, pending?.epoch ?: 0, ce.epoch),
                disabled = true,
                c0DenyMask = Long.MAX_VALUE,
            )
        } else {
            combine(combine(active, pending), ce)!!
        }
        write(activePath, result)
        clearPending()
        return result
    }

    private fun read(path: Path): DenyState? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            return null
        }
        val bytes = try {
            if (Files.size(path) != SIZE.toLong()) return null
            FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { readExactly(it, 0, SIZE) } ?: return null
        } catch (_: java.io.IOException) {
            return null
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC || b.int != VERSION) return null
        val epoch = b.long
        val disabledValue = b.int
        if (epoch < 0 || disabledValue !in 0..1) return null
        val disabled = disabledValue == 1
        val mask = b.long
        if (b.int != crc(bytes, 0, SIZE - Int.SIZE_BYTES)) return null
        return DenyState(epoch, disabled, mask)
    }

    private fun write(path: Path, state: DenyState) {
        Files.createDirectories(path.parent)
        val temporaryPath = path.resolveSibling("${path.fileName}.new")
        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putInt(VERSION).putLong(state.epoch).putInt(if (state.disabled) 1 else 0).putLong(state.c0DenyMask)
        b.putInt(crc(b.array(), 0, SIZE - Int.SIZE_BYTES)).flip()
        FileChannel.open(
            temporaryPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use {
            writeFully(it, b)
            it.force(true)
        }
        replacementCrashInjector?.after(path, DenyMirrorReplacementBoundary.TEMP_SYNCED)
        try {
            Files.move(
                temporaryPath,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING)
        }
        replacementCrashInjector?.after(path, DenyMirrorReplacementBoundary.REPLACED)
        forceDirectory(path.parent)
    }

    private fun combine(first: DenyState?, second: DenyState?): DenyState? = when {
        first == null -> second
        second == null -> first
        else -> DenyState(maxOf(first.epoch, second.epoch), first.disabled || second.disabled, first.c0DenyMask or second.c0DenyMask)
    }

    private fun crc(bytes: ByteArray, offset: Int, length: Int): Int =
        Crc32c.value(bytes, offset, length)

    companion object {
        internal fun withCrashInjector(
            activePath: Path,
            pendingPath: Path,
            crashInjector: DenyMirrorReplacementCrashInjector,
        ): DenyMirror = DenyMirror(activePath, pendingPath, crashInjector)

        private const val MAGIC = 0x5442444d
        private const val VERSION = 1
        private const val SIZE = 32
    }
}

/** Host fault-injection seam immediately around an atomic mirror replacement. */
internal fun interface DenyMirrorReplacementCrashInjector {
    fun after(target: Path, boundary: DenyMirrorReplacementBoundary)
}

internal enum class DenyMirrorReplacementBoundary { TEMP_SYNCED, REPLACED }

/** Crash injection seam around each ordered persistence boundary. */
fun interface DirectBootCrashInjector { fun after(boundary: DirectBootBoundary) }
enum class DirectBootBoundary { PENDING_SYNCED, CE_COMMITTED, PENDING_PROMOTED }

/** Two-phase policy mirror coordinator; CE persistence is supplied by the future global coordinator. */
class DirectBootPolicyCoordinator(
    private val mirror: DenyMirror,
    private val commitCe: (DenyState) -> Unit,
) {
    fun tighten(target: DenyState, injector: DirectBootCrashInjector? = null) {
        mirror.writePending(target)
        injector?.after(DirectBootBoundary.PENDING_SYNCED)
        commitCe(target)
        injector?.after(DirectBootBoundary.CE_COMMITTED)
        mirror.promotePending()
        injector?.after(DirectBootBoundary.PENDING_PROMOTED)
    }

    fun loosen(target: DenyState, injector: DirectBootCrashInjector? = null) {
        commitCe(target)
        injector?.after(DirectBootBoundary.CE_COMMITTED)
        mirror.writePending(target)
        mirror.promotePending()
        injector?.after(DirectBootBoundary.PENDING_PROMOTED)
    }
}

/** Exact result of a CE/global barrier before a DE mirror may become permissive. */
enum class DirectBootGlobalTransitionResult { SUCCESS, PARTIAL, FAILED }

/**
 * Production CE/DE wiring: a tightening writes the fail-closed DE pending mirror before entering
 * the handler-owned global barrier. A loosening never changes DE until that barrier succeeds.
 */
class HandlerCoordinatedDirectBootPolicyCoordinator(
    private val mirror: DenyMirror,
    private val global: GlobalPolicyCoordinator,
    private val handlerBarrier: () -> BarrierAck,
) {
    fun tighten(target: DenyState): DirectBootGlobalTransitionResult {
        mirror.writePending(target)
        return when (global.updateProfile(target.toPolicySnapshot(), handlerBarrier)) {
            is ProfileUpdateResult.Success -> {
                mirror.promotePending()
                DirectBootGlobalTransitionResult.SUCCESS
            }
            is ProfileUpdateResult.Partial -> DirectBootGlobalTransitionResult.PARTIAL
            is ProfileUpdateResult.Failed -> DirectBootGlobalTransitionResult.FAILED
        }
    }

    fun loosen(target: DenyState): DirectBootGlobalTransitionResult {
        return when (global.updateProfile(target.toPolicySnapshot(), handlerBarrier)) {
            is ProfileUpdateResult.Success -> {
                mirror.writePending(target)
                mirror.promotePending()
                DirectBootGlobalTransitionResult.SUCCESS
            }
            is ProfileUpdateResult.Partial -> DirectBootGlobalTransitionResult.PARTIAL
            is ProfileUpdateResult.Failed -> DirectBootGlobalTransitionResult.FAILED
        }
    }

    private fun DenyState.toPolicySnapshot(): PolicySnapshot =
        PolicySnapshot(epoch, c0DenyMask, disabled)
}
