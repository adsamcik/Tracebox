package dev.tracebox.core

import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

/** The only events permitted to initiate handler reconnection; ordinary polling is forbidden. */
enum class HandlerConnectTrigger { INSTALL, LIFECYCLE, CAPTURE, NONE }

sealed interface HandlerConnectionResult {
    data class Connected(val policy: PolicySnapshot) : HandlerConnectionResult
    data object NotTriggered : HandlerConnectionResult
    data object Unavailable : HandlerConnectionResult
}

/**
 * Bounded handler connection state. A service/binder bridge owns transport; this object supplies
 * the mandatory readiness semantics independently of its Android transport.
 */
class HandlerConnection(
    private val runtime: TraceboxRuntime,
    private val connector: () -> HandlerConnectionResult,
) {
    fun connect(trigger: HandlerConnectTrigger): HandlerConnectionResult {
        if (trigger == HandlerConnectTrigger.NONE) return HandlerConnectionResult.NotTriggered
        return when (val result = connector()) {
            is HandlerConnectionResult.Connected -> result
            HandlerConnectionResult.NotTriggered, HandlerConnectionResult.Unavailable -> {
                runtime.degrade()
                HandlerConnectionResult.Unavailable
            }
        }
    }

    fun onHandlerDeath() {
        runtime.degrade()
    }
}

enum class ParticipantState { LIVE, UNVERIFIED }
enum class BarrierAck { Acknowledged, Missing, Rejected }
enum class LeaseProbeResult { ProvenDead, StillLive, Ambiguous, NotUnverified }

data class ParticipantCensusEntry(
    val participantId: String,
    val bootSession: ByteArray,
    val processRole: Int,
    val processInstanceId: ByteArray,
    val leasePath: Path,
    val lastAcknowledgedEpoch: Long,
    val state: ParticipantState,
) {
    init {
        require(bootSession.isNotEmpty())
        require(processInstanceId.size == 32)
    }
}

sealed interface ProfileUpdateResult {
    data class Success(val epoch: Long) : ProfileUpdateResult
    data class Partial(val locallyAppliedEpoch: Long, val unacknowledged: Set<String>) : ProfileUpdateResult
    data class Failed(val reason: CoordinatorFailure) : ProfileUpdateResult
}

enum class CoordinatorFailure { STALE_EPOCH, INVALID_EPOCH, IO }

/** Native fatal-signal coexistence policy selected during handler bootstrap. */
enum class CrashCoexistencePolicy { EXCLUSIVE, BEST_EFFORT_CHAIN, DISABLE_ON_CONFLICT }

/**
 * One fault receives exactly one primary/fallback result. `DegradedNativeDisabled` is deliberately
 * non-certifying: it preserves JVM/exit paths but never claims native capture coverage.
 */
enum class CrashDispatchResult {
    PrimaryCrashpad,
    PrimaryCrashpadThenPrior,
    DegradedNativeDisabled,
}

/** Mirrors the native best-effort chain: invoke the prior action once, then force default death if it returns. */
data class NativeSignalDispatchPlan(
    val result: CrashDispatchResult,
    val preservePreviousAction: Boolean,
    val invokePreviousHandlerExactlyOnce: Boolean,
    val forceDefaultTerminationIfPreviousReturns: Boolean,
)

class CrashDispatchStateMachine(private val policy: CrashCoexistencePolicy) {
    fun dispatch(priorHandlerDetected: Boolean): CrashDispatchResult = when (policy) {
        CrashCoexistencePolicy.EXCLUSIVE -> CrashDispatchResult.PrimaryCrashpad
        CrashCoexistencePolicy.BEST_EFFORT_CHAIN ->
            if (priorHandlerDetected) CrashDispatchResult.PrimaryCrashpadThenPrior else CrashDispatchResult.PrimaryCrashpad
        CrashCoexistencePolicy.DISABLE_ON_CONFLICT ->
            if (priorHandlerDetected) CrashDispatchResult.DegradedNativeDisabled else CrashDispatchResult.PrimaryCrashpad
    }

    fun nativePlan(priorHandlerDetected: Boolean): NativeSignalDispatchPlan {
        val result = dispatch(priorHandlerDetected)
        return NativeSignalDispatchPlan(
            result = result,
            preservePreviousAction = priorHandlerDetected && policy == CrashCoexistencePolicy.BEST_EFFORT_CHAIN,
            invokePreviousHandlerExactlyOnce = result == CrashDispatchResult.PrimaryCrashpadThenPrior,
            forceDefaultTerminationIfPreviousReturns = result == CrashDispatchResult.PrimaryCrashpadThenPrior,
        )
    }
}

private data class Participant(
    var entry: ParticipantCensusEntry,
    val barrier: (PolicySnapshot) -> BarrierAck,
    var lease: InstanceLease? = null,
)

/**
 * Handler-owned package policy coordinator. Its census is durable before registration returns and
 * an existing census is conservative after restart: entries are unverified, never presumed dead.
 */
class GlobalPolicyCoordinator(
    private val root: Path,
    private val controlPage: ControlPage,
    private val bootSession: ByteArray,
) {
    private val lock = Any()
    private val censusPath = root.resolve("participant-census-v1")
    private val censusTemporaryPath = root.resolve("participant-census-v1.new")
    private val leasesPath = root.resolve("leases")
    private val participants = linkedMapOf<String, Participant>()
    private var transitionTarget: PolicySnapshot? = null

    init {
        require(bootSession.isNotEmpty())
        Files.createDirectories(root)
        loadCensus()
    }

    /** Registers while holding the same transition mutex used by [updateProfile]. */
    fun register(
        participantId: String,
        processRole: Int,
        processInstanceId: ByteArray,
        barrier: () -> BarrierAck,
    ): Closeable = registerTargetAware(participantId, processRole, processInstanceId) { _ -> barrier() }

    /**
     * Target-aware registration. The supplied barrier must apply exactly the snapshot it receives
     * before acknowledging; it must not infer a target from a separately mutable coordinator.
     */
    fun registerTargetAware(
        participantId: String,
        processRole: Int,
        processInstanceId: ByteArray,
        barrier: (PolicySnapshot) -> BarrierAck,
    ): Closeable = synchronized(lock) {
        check(transitionTarget == null) { "participant registration attempted from inside a policy transition" }
        require(processInstanceId.size == 32)
        require(participantId.isNotBlank() && !participantId.contains('|'))
        val leasePath = leasesPath.resolve("${Base64.getUrlEncoder().withoutPadding().encodeToString(processInstanceId)}.lease")
        val lease = InstanceLease.open(leasePath) ?: throw IllegalStateException("exclusive process lease unavailable")
        val entry = ParticipantCensusEntry(
            participantId,
            bootSession.copyOf(),
            processRole,
            processInstanceId.copyOf(),
            leasePath,
            controlPage.committed().epoch,
            ParticipantState.LIVE,
        )
        val previous = participants.put(participantId, Participant(entry, barrier, lease))
        try {
            persistCensus()
        } catch (failure: IOException) {
            participants.remove(participantId)
            if (previous != null) participants[participantId] = previous
            lease.close()
            throw failure
        }
        previous?.lease?.close()
        Closeable {
            synchronized(lock) {
                val removed = participants.remove(participantId)
                if (removed != null) {
                    try {
                        persistCensus()
                    } catch (failure: IOException) {
                        participants[participantId] = removed
                        throw failure
                    }
                    removed.lease?.close()
                }
            }

        }
    }

    fun participants(): List<ParticipantCensusEntry> = synchronized(lock) {
        participants.values.map { it.entry.copy(bootSession = it.entry.bootSession.copyOf(), processInstanceId = it.entry.processInstanceId.copyOf()) }
    }

    /**
     * Non-blocking acquisition is the only death proof. Failure is intentionally ambiguous:
     * updates must retain an UNVERIFIED member instead of guessing it is gone.
     */
    fun probeUnverified(participantId: String): LeaseProbeResult = synchronized(lock) {
        val participant = participants[participantId] ?: return LeaseProbeResult.NotUnverified
        if (participant.entry.state != ParticipantState.UNVERIFIED) return LeaseProbeResult.NotUnverified
        return when (InstanceLease.probe(participant.entry.leasePath)) {
            LeaseProbeResult.ProvenDead -> {
                participants.remove(participantId)
                persistCensus()
                LeaseProbeResult.ProvenDead
            }
            LeaseProbeResult.StillLive -> LeaseProbeResult.StillLive
            LeaseProbeResult.Ambiguous -> LeaseProbeResult.Ambiguous
            LeaseProbeResult.NotUnverified -> LeaseProbeResult.NotUnverified
        }
    }

    /**
     * Commits the control page only after every live writer and handler crossed the barrier.
     * A partial result never claims package-wide policy success and leaves the prior page active.
     */
    fun updateProfile(target: PolicySnapshot, handlerBarrier: () -> BarrierAck): ProfileUpdateResult = synchronized(lock) {
        updateProfileLocked(target) { handlerBarrier() }
    }

    /** Target-aware global barrier used by transports that carry the complete immutable snapshot. */
    fun updateProfileTargetAware(
        target: PolicySnapshot,
        handlerBarrier: (PolicySnapshot) -> BarrierAck,
    ): ProfileUpdateResult = synchronized(lock) {
        updateProfileLocked(target, handlerBarrier)
    }

    private fun updateProfileLocked(
        target: PolicySnapshot,
        handlerBarrier: (PolicySnapshot) -> BarrierAck,
    ): ProfileUpdateResult {
        val current = try {
            controlPage.committed()
        } catch (_: PolicyPageException) {
            return ProfileUpdateResult.Failed(CoordinatorFailure.IO)
        }
        if (target.epoch <= current.epoch) return ProfileUpdateResult.Failed(CoordinatorFailure.INVALID_EPOCH)

        transitionTarget = target
        try {
            val missing = linkedSetOf<String>()
            participants.values.forEach { participant ->
                if (participant.entry.state != ParticipantState.LIVE ||
                    acknowledge(participant.barrier, target) != BarrierAck.Acknowledged
                ) {
                    missing += participant.entry.participantId
                }
            }
            if (acknowledge(handlerBarrier, target) != BarrierAck.Acknowledged) missing += "handler"
            if (missing.isNotEmpty()) return ProfileUpdateResult.Partial(current.epoch, missing)

            return try {
                controlPage.commit(target)
                participants.values.forEach {
                    it.entry = it.entry.copy(lastAcknowledgedEpoch = target.epoch)
                }
                persistCensus()
                ProfileUpdateResult.Success(target.epoch)
            } catch (_: IOException) {
                ProfileUpdateResult.Failed(CoordinatorFailure.IO)
            }
        } finally {
            transitionTarget = null
        }
    }

    private fun acknowledge(
        barrier: (PolicySnapshot) -> BarrierAck,
        target: PolicySnapshot,
    ): BarrierAck = try {
        barrier(target)
    } catch (_: Throwable) {
        BarrierAck.Rejected
    }

    private fun loadCensus() {
        if (!Files.exists(censusPath)) return
        val loaded = try {
            Files.readAllLines(censusPath, StandardCharsets.UTF_8)
        } catch (_: IOException) {
            return
        }
        loaded.filter(String::isNotBlank).forEach { line ->
            val fields = line.split('|')
            if (fields.size != 7) return@forEach
            try {
                val savedBoot = decode(fields[1])
                if (!savedBoot.contentEquals(bootSession)) return@forEach
                val entry = ParticipantCensusEntry(
                    fields[0],
                    savedBoot,
                    fields[2].toInt(),
                    decode(fields[3]),
                    Path.of(fields[4]),
                    fields[5].toLong(),
                    ParticipantState.UNVERIFIED,
                )
                participants[entry.participantId] = Participant(entry, { _ -> BarrierAck.Missing })
            } catch (_: IllegalArgumentException) {
                // A malformed census entry is not allowed to regain durability; ignore it.
            }
        }
        persistCensus()
    }

    private fun persistCensus() {
        val lines = participants.values.joinToString("\n", postfix = if (participants.isEmpty()) "" else "\n") { participant ->
            val entry = participant.entry
            listOf(
                entry.participantId,
                encode(entry.bootSession),
                entry.processRole.toString(),
                encode(entry.processInstanceId),
                entry.leasePath.toString(),
                entry.lastAcknowledgedEpoch.toString(),
                entry.state.name,
            ).joinToString("|")
        }
        Files.createDirectories(censusPath.parent)
        Files.writeString(
            censusTemporaryPath,
            lines,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        FileChannel.open(censusTemporaryPath, StandardOpenOption.WRITE).use { it.force(true) }
        try {
            Files.move(
                censusTemporaryPath,
                censusPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(censusTemporaryPath, censusPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}

private class InstanceLease private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : Closeable {
    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        fun open(path: Path): InstanceLease? = try {
            Files.createDirectories(path.parent)
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                null
            } else {
                InstanceLease(channel, lock)
            }
        } catch (_: IOException) {
            null
        }

        fun probe(path: Path): LeaseProbeResult = try {
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                LeaseProbeResult.StillLive
            } else {
                lock.release()
                channel.close()
                LeaseProbeResult.ProvenDead
            }
        } catch (_: IOException) {
            LeaseProbeResult.Ambiguous
        }
    }
}

/** C1 structural JVM exception representation; diagnostic messages remain opt-in. */
data class JvmCrashCause(val type: String, val message: String?, val frames: List<JvmCrashFrame>, val cycle: Boolean)
data class JvmCrashFrame(val declaringClass: String, val method: String, val line: Int)
data class JvmCrashRecord(val causes: List<JvmCrashCause>)
data class JvmCapturePolicy(
    val includeMessage: Boolean = false,
    val maxCauses: Int = 8,
    val maxFramesPerCause: Int = 64,
    val maxClassNameUtf8Bytes: Int = 256,
    val maxMethodNameUtf8Bytes: Int = 128,
    val maxMessageUtf8Bytes: Int = 256,
) {
    init {
        require(maxCauses in 1..16)
        require(maxFramesPerCause in 1..128)
        require(maxClassNameUtf8Bytes in 1..1024)
        require(maxMethodNameUtf8Bytes in 1..512)
        require(maxMessageUtf8Bytes in 1..1024)
    }
}

/** Invokes the prior process handler exactly once even when Tracebox capture throws. */
class TraceboxUncaughtExceptionHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val policy: JvmCapturePolicy,
    private val record: (JvmCrashRecord) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val handling = ThreadLocal.withInitial { false }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (handling.get() != true) {
            handling.set(true)
            try {
                record(capture(throwable))
            } catch (_: Throwable) {
                // Failure capture must not obstruct the application's installed termination path.
            } finally {
                handling.set(false)
            }
        }
        previous?.uncaughtException(thread, throwable)
    }

    private fun capture(root: Throwable): JvmCrashRecord {
        val seen = java.util.IdentityHashMap<Throwable, Unit>()
        val causes = ArrayList<JvmCrashCause>(policy.maxCauses)
        var current: Throwable? = root
        while (current != null && causes.size < policy.maxCauses) {
            val cycle = seen.put(current, Unit) != null
            val frames = current.stackTrace.take(policy.maxFramesPerCause).map {
                JvmCrashFrame(
                    truncateUtf8(it.className, policy.maxClassNameUtf8Bytes),
                    truncateUtf8(it.methodName, policy.maxMethodNameUtf8Bytes),
                    it.lineNumber,
                )
            }
            causes += JvmCrashCause(
                truncateUtf8(current.javaClass.name, policy.maxClassNameUtf8Bytes),
                if (policy.includeMessage) current.message?.let { truncateUtf8(it, policy.maxMessageUtf8Bytes) } else null,
                frames,
                cycle,
            )
            if (cycle) break
            current = current.cause
        }
        return JvmCrashRecord(causes)
    }

    private fun truncateUtf8(value: String, maximumBytes: Int): String {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        if (encoded.size <= maximumBytes) return value
        var length = maximumBytes
        while (length > 0 && (encoded[length].toInt() and 0xc0) == 0x80) {
            length--
        }
        return String(encoded, 0, length, StandardCharsets.UTF_8)
    }
}
