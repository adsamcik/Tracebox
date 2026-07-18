package dev.tracebox.core

import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
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

class CrashDispatchStateMachine(private val policy: CrashCoexistencePolicy) {
    fun dispatch(priorHandlerDetected: Boolean): CrashDispatchResult = when (policy) {
        CrashCoexistencePolicy.EXCLUSIVE -> CrashDispatchResult.PrimaryCrashpad
        CrashCoexistencePolicy.BEST_EFFORT_CHAIN ->
            if (priorHandlerDetected) CrashDispatchResult.PrimaryCrashpadThenPrior else CrashDispatchResult.PrimaryCrashpad
        CrashCoexistencePolicy.DISABLE_ON_CONFLICT ->
            if (priorHandlerDetected) CrashDispatchResult.DegradedNativeDisabled else CrashDispatchResult.PrimaryCrashpad
    }
}

private data class Participant(
    var entry: ParticipantCensusEntry,
    val barrier: () -> BarrierAck,
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
    private val leasesPath = root.resolve("leases")
    private val participants = linkedMapOf<String, Participant>()

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
    ): Closeable = synchronized(lock) {
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
        participants.remove(participantId)?.lease?.close()
        participants[participantId] = Participant(entry, barrier, lease)
        persistCensus()
        Closeable {
            synchronized(lock) {
                participants.remove(participantId)?.let {
                    it.lease?.close()
                    persistCensus()
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
        val current = try {
            controlPage.committed()
        } catch (_: PolicyPageException) {
            return ProfileUpdateResult.Failed(CoordinatorFailure.IO)
        }
        if (target.epoch <= current.epoch) return ProfileUpdateResult.Failed(CoordinatorFailure.INVALID_EPOCH)

        val missing = linkedSetOf<String>()
        participants.values.forEach { participant ->
            if (participant.entry.state != ParticipantState.LIVE || participant.barrier() != BarrierAck.Acknowledged) {
                missing += participant.entry.participantId
            }
        }
        if (handlerBarrier() != BarrierAck.Acknowledged) missing += "handler"
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
                participants[entry.participantId] = Participant(entry, { BarrierAck.Missing })
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
        Files.writeString(censusPath, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        FileChannel.open(censusPath, StandardOpenOption.WRITE).use { it.force(true) }
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
) {
    init {
        require(maxCauses in 1..16)
        require(maxFramesPerCause in 1..128)
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
            } catch (_: RuntimeException) {
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
                JvmCrashFrame(it.className, it.methodName, it.lineNumber)
            }
            causes += JvmCrashCause(current.javaClass.name, if (policy.includeMessage) current.message else null, frames, cycle)
            if (cycle) break
            current = current.cause
        }
        return JvmCrashRecord(causes)
    }
}
