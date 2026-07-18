package dev.tracebox.core

import dev.tracebox.api.Readiness
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Immutable generated installation configuration. */
class TraceboxConfiguration(
    val generatedSchemaFingerprint: ByteArray,
    val processRole: Int,
    val providerFallback: Boolean = false,
) {
    init {
        require(generatedSchemaFingerprint.size == 32) { "schema fingerprint must be 32 bytes" }
    }

    fun withProviderFallback(): TraceboxConfiguration =
        TraceboxConfiguration(generatedSchemaFingerprint.copyOf(), processRole, true)

    override fun equals(other: Any?): Boolean =
        other is TraceboxConfiguration &&
            processRole == other.processRole &&
            providerFallback == other.providerFallback &&
            generatedSchemaFingerprint.contentEquals(other.generatedSchemaFingerprint)

    override fun hashCode(): Int =
        31 * (31 * generatedSchemaFingerprint.contentHashCode() + processRole) + providerFallback.hashCode()
}

/** Typed install outcomes; conflicting configuration never silently replaces a live runtime. */
sealed interface InstallResult {
    data class Installed(val runtime: TraceboxRuntime) : InstallResult
    data class Reused(val runtime: TraceboxRuntime) : InstallResult
    data class ConflictingConfiguration(val installed: TraceboxConfiguration) : InstallResult
}

/** Minimal capability hooks permitted during provider fallback. */
interface MinimalBootstrap {
    fun installJvmWrapper()
    fun startOrConnectHandler()
    fun installEmergencyFallback()
}

/** Runtime lifecycle state machine. It contains no blocking storage operation. */
class TraceboxRuntime internal constructor(
    val configuration: TraceboxConfiguration,
) : Closeable {
    private val mutableReadiness = MutableStateFlow(Readiness.VOLATILE_CAPTURE)

    /** Observable capability state. `VOLATILE_CAPTURE` is deliberately not durable recording. */
    val readiness: StateFlow<Readiness> = mutableReadiness

    /** Marks durable only after a current committed policy was loaded by the writer. */
    fun durableAfterPolicyLoad(policyLoaded: Boolean): RuntimeResult {
        if (mutableReadiness.value == Readiness.CLOSED) return RuntimeResult.Closed
        if (!policyLoaded) return RuntimeResult.PolicyNotLoaded
        mutableReadiness.value = Readiness.DURABLE
        return RuntimeResult.Changed
    }

    /** Retains volatile capture while declaring a durable capability unavailable. */
    fun degrade(): RuntimeResult {
        if (mutableReadiness.value == Readiness.CLOSED) return RuntimeResult.Closed
        mutableReadiness.value = Readiness.DEGRADED
        return RuntimeResult.Changed
    }

    override fun close() {
        mutableReadiness.value = Readiness.CLOSED
    }
}

/** Typed lifecycle transition results. */
enum class RuntimeResult { Changed, PolicyNotLoaded, Closed }

/** Primary attachBaseContext-style install entry point plus constrained provider fallback. */
object TraceboxInstaller {
    private val installed = AtomicReference<TraceboxRuntime?>(null)

    /**
     * Installs volatile handlers only. Disk-backed writers must separately call
     * [TraceboxRuntime.durableAfterPolicyLoad] off the main thread.
     */
    fun install(configuration: TraceboxConfiguration, bootstrap: MinimalBootstrap): InstallResult {
        val existing = installed.get()
        if (existing != null) {
            return if (existing.configuration == configuration) InstallResult.Reused(existing)
            else InstallResult.ConflictingConfiguration(existing.configuration)
        }
        bootstrap.installJvmWrapper()
        bootstrap.startOrConnectHandler()
        bootstrap.installEmergencyFallback()
        val runtime = TraceboxRuntime(configuration)
        return if (installed.compareAndSet(null, runtime)) InstallResult.Installed(runtime)
        else install(configuration, bootstrap)
    }

    /**
     * Provider fallback is intentionally limited to immutable configuration and
     * minimal bootstrap; it cannot choose a privacy profile or create durable stores.
     */
    fun installFromProvider(configuration: TraceboxConfiguration, bootstrap: MinimalBootstrap): InstallResult =
        install(configuration.withProviderFallback(), bootstrap)

    internal fun resetForTest() {
        installed.getAndSet(null)?.close()
    }
}

/** Persisted and mappable handler control-page contents. */
data class PolicySnapshot(val epoch: Long, val denyMask: Long, val disabled: Boolean = false) {
    fun permits(categoryMask: Long): Boolean = !disabled && (denyMask and categoryMask) == 0L
}

/** Future handler IPC/control-page integration supplies the committed snapshot through this seam. */
fun interface CommittedPolicyProvider {
    fun committed(): PolicySnapshot
}

/** A fixed 32-byte persisted control page; later handler code can map this same file read-only. */
class ControlPage(private val path: Path) : CommittedPolicyProvider {
    override fun committed(): PolicySnapshot {
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
                if (channel.read(buffer) != SIZE) throw PolicyPageException.Corrupt
                buffer.flip()
                if (buffer.int != MAGIC || buffer.int != VERSION) throw PolicyPageException.Corrupt
                val epoch = buffer.long
                val mask = buffer.long
                val disabled = buffer.int != 0
                val expected = buffer.int
                val actual = crc(buffer.array(), 0, SIZE - Int.SIZE_BYTES)
                if (expected != actual) throw PolicyPageException.Corrupt
                return PolicySnapshot(epoch, mask, disabled)
            }
        } catch (_: java.io.IOException) {
            throw PolicyPageException.Unavailable
        }
    }

    /** Persists and forces a committed page. Handler ownership is enforced by module topology in Phase 3. */
    fun commit(snapshot: PolicySnapshot) {
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC).putInt(VERSION).putLong(snapshot.epoch).putLong(snapshot.denyMask)
        buffer.putInt(if (snapshot.disabled) 1 else 0)
        buffer.putInt(crc(buffer.array(), 0, SIZE - Int.SIZE_BYTES))
        buffer.flip()
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
            it.write(buffer)
            it.force(true)
        }
    }

    private fun crc(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = java.util.zip.CRC32C()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }

    private companion object {
        const val MAGIC = 0x54424350
        const val VERSION = 1
        const val SIZE = 32
    }
}

/** Policy control-page failures are typed and fail writers closed. */
sealed class PolicyPageException : IllegalStateException() {
    data object Corrupt : PolicyPageException()
    data object Unavailable : PolicyPageException()
}

/** A locally accepted record is always epoch-tagged before it enters a durable queue. */
data class PolicyTaggedRecord(
    val categoryMask: Long,
    val acceptedEpoch: Long,
    val priority: RecordPriority,
    val payload: ByteArray,
)

/** Explicit fixed priority ordering for bounded retention and queues. */
enum class RecordPriority(val rank: Int) {
    ORDINARY_EVENT(0), BREADCRUMB(1), HANDLED_ERROR(2), POLICY_HEALTH(3), CRASH_ANR(4)
}

/** Local gate for the Phase 2 writer contract. */
class WriterPolicyGate(private val provider: CommittedPolicyProvider) {
    private var loaded: PolicySnapshot? = null

    /** Reloads a committed handler snapshot; only this permits a stale writer to accept again. */
    fun reload(): GateResult {
        return try {
            loaded = provider.committed()
            GateResult.Reloaded
        } catch (_: PolicyPageException) {
            loaded = null
            GateResult.ControlUnavailable
        }
    }

    /** Checks current committed epoch/mask before enqueue. */
    fun accept(categoryMask: Long, priority: RecordPriority, payload: ByteArray): GateAcceptance {
        val local = loaded ?: return GateAcceptance.Rejected(GateResult.PolicyNotLoaded)
        val committed = try {
            provider.committed()
        } catch (_: PolicyPageException) {
            return GateAcceptance.Rejected(GateResult.ControlUnavailable)
        }
        if (committed.epoch != local.epoch) return GateAcceptance.Rejected(GateResult.StaleWriter)
        if (!local.permits(categoryMask)) return GateAcceptance.Rejected(GateResult.Denied)
        return GateAcceptance.Accepted(PolicyTaggedRecord(categoryMask, local.epoch, priority, payload.copyOf()))
    }

    /** Revalidates directly before append, dropping stale or newly denied data. */
    fun appendAllowed(record: PolicyTaggedRecord): GateResult {
        val current = try {
            provider.committed()
        } catch (_: PolicyPageException) {
            return GateResult.ControlUnavailable
        }
        return when {
            current.epoch != record.acceptedEpoch -> GateResult.StaleRecord
            !current.permits(record.categoryMask) -> GateResult.Denied
            else -> GateResult.Allowed
        }
    }
}

/** Gate outcomes are data, never exceptions used as normal recording control flow. */
enum class GateResult { Reloaded, Allowed, PolicyNotLoaded, StaleWriter, StaleRecord, Denied, ControlUnavailable }
sealed interface GateAcceptance {
    data class Accepted(val record: PolicyTaggedRecord) : GateAcceptance
    data class Rejected(val reason: GateResult) : GateAcceptance
}

/** Recursion-free health counters emitted by callers as generated health records when permitted. */
enum class HealthCode { QUEUE_DROPPED, POLICY_STALE, POLICY_DENIED, BARRIER_PURGED }
class HealthCounters {
    private val values = LongArray(HealthCode.entries.size)
    fun increment(code: HealthCode) { values[code.ordinal]++ }
    fun count(code: HealthCode): Long = values[code.ordinal]
}

/** Fixed-capacity priority queue; it never allocates beyond [capacity]. */
class BoundedPolicyQueue(private val capacity: Int, private val health: HealthCounters) {
    init { require(capacity > 0) }
    private val entries = ArrayList<PolicyTaggedRecord>(capacity)
    private var paused = false

    fun enqueue(record: PolicyTaggedRecord): QueueResult {
        if (paused) return QueueResult.Paused
        if (entries.size < capacity) {
            entries += record
            return QueueResult.Enqueued
        }
        val victim = entries.withIndex().minByOrNull { it.value.priority.rank }!!
        if (record.priority.rank <= victim.value.priority.rank) {
            health.increment(HealthCode.QUEUE_DROPPED)
            return QueueResult.Dropped
        }
        entries.removeAt(victim.index)
        entries += record
        health.increment(HealthCode.QUEUE_DROPPED)
        return QueueResult.EvictedLowerPriority
    }

    fun dequeue(): PolicyTaggedRecord? = if (paused || entries.isEmpty()) null
    else entries.removeAt(entries.indices.maxBy { entries[it].priority.rank })

    /**
     * Local half of the later cross-process barrier: dequeue pauses, records not
     * permitted under the new snapshot are purged, then callers reload and resume.
     */
    fun barrier(snapshot: PolicySnapshot): Int {
        paused = true
        val before = entries.size
        entries.removeAll { it.acceptedEpoch != snapshot.epoch || !snapshot.permits(it.categoryMask) }
        val dropped = before - entries.size
        repeat(dropped) { health.increment(HealthCode.BARRIER_PURGED) }
        return dropped
    }

    fun resume() { paused = false }
    fun size(): Int = entries.size
}

enum class QueueResult { Enqueued, EvictedLowerPriority, Dropped, Paused }
