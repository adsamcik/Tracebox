package dev.tracebox.anr

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.tracebox.core.PolicySnapshot
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class AnrCandidate(
    val delayedMillis: Long,
    val mainFrames: List<StackTraceElement>,
    val nonFatalRequested: Boolean,
    val debuggerAffected: Boolean,
)

data class AnrWatchdogStats(
    val postedGeneration: Long,
    val acknowledgedGeneration: Long,
    val eligible: Boolean,
    val heartbeatP99Nanos: Long,
)

fun interface NonFatalRequester {
    fun request(timeoutMillis: Int): Boolean
}

class AnrWatchdog(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val requester: NonFatalRequester,
    private val onCandidate: (AnrCandidate) -> Unit,
    private val stateMachine: AnrStateMachine = AnrStateMachine(AnrPolicy { PolicySnapshot(0, 0) }),
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val eligible = AtomicBoolean(false)
    private val postedGeneration = AtomicLong()
    private val acknowledgedGeneration = AtomicLong()
    private val lastAcknowledgedMillis = AtomicLong(clockMillis())
    private val heartbeatSamples = LongArray(2_048)
    private var heartbeatSampleCount = 0
    private val lock = ReentrantLock()
    private val eligibilityChanged = lock.newCondition()
    private var worker: Thread? = null
    private var lastRequestMillis = Long.MIN_VALUE
    private val startedAtMillis = clockMillis()
    private val heartbeat =
        object : Runnable {
            override fun run() {
                val startedNanos = SystemClock.elapsedRealtimeNanos()
                if (!running.get() || !eligible.get()) {
                    return
                }
                acknowledgedGeneration.set(postedGeneration.get())
                lastAcknowledgedMillis.set(clockMillis())
                stateMachine.recovered()
                lock.withLock { eligibilityChanged.signalAll() }
                scheduleHeartbeat(2_000)
                val duration = SystemClock.elapsedRealtimeNanos() - startedNanos
                if (heartbeatSampleCount < heartbeatSamples.size) {
                    heartbeatSamples[heartbeatSampleCount++] = duration
                }
            }
        }

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        worker = Thread(::runLoop, "tracebox-anr-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun setEligible(value: Boolean) {
        val changed = eligible.getAndSet(value) != value
        if (!changed) {
            return
        }

        mainHandler.removeCallbacks(heartbeat)
        stateMachine.mode(
            if (value) AnrOperatingMode.FOREGROUND_INTERACTIVE else AnrOperatingMode.SUSPENDED,
            clockMillis(),
        )
        if (value && running.get()) {
            lastAcknowledgedMillis.set(clockMillis())
            scheduleHeartbeat(0)
        }
        lock.withLock { eligibilityChanged.signalAll() }
    }

    fun stats(): AnrWatchdogStats =
        AnrWatchdogStats(
            postedGeneration = postedGeneration.get(),
            acknowledgedGeneration = acknowledgedGeneration.get(),
            eligible = eligible.get(),
            heartbeatP99Nanos = heartbeatP99Nanos(),
        )

    fun resetMeasurementStats() {
        heartbeatSampleCount = 0
    }

    override fun close() {
        running.set(false)
        mainHandler.removeCallbacks(heartbeat)
        lock.withLock { eligibilityChanged.signalAll() }
        worker = null
    }

    private fun runLoop() {
        var capturedGeneration = Long.MIN_VALUE
        while (running.get()) {
            awaitEligibility()
            if (!running.get()) {
                return
            }
            val observedGeneration = acknowledgedGeneration.get()
            val acknowledgedAt = lastAcknowledgedMillis.get()
            val remaining = acknowledgedAt + 5_000 - clockMillis()
            if (remaining > 0) {
                lock.withLock {
                    if (running.get() && eligible.get() &&
                        acknowledgedGeneration.get() == observedGeneration
                    ) {
                        eligibilityChanged.awaitNanos(remaining * 1_000_000)
                    }
                }
                continue
            }
            val startupRemaining = startedAtMillis + 10_000 - clockMillis()
            if (startupRemaining > 0) {
                lock.withLock {
                    eligibilityChanged.awaitNanos(startupRemaining * 1_000_000)
                }
                continue
            }
            if (capturedGeneration == observedGeneration && stateMachine.state() == AnrWatchState.CAPTURED_CANDIDATE) {
                lock.withLock { eligibilityChanged.await() }
                continue
            }
            capturedGeneration = observedGeneration
            captureCandidate(clockMillis() - acknowledgedAt)
        }
    }

    private fun awaitEligibility() {
        lock.withLock {
            while (running.get() && !eligible.get()) {
                eligibilityChanged.await()
            }
        }
    }

    private fun captureCandidate(delayedMillis: Long) {
        val debuggerAffected = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val frames = Looper.getMainLooper().thread.stackTrace.take(64)
        val signature = frames.fold(1L) { value, frame -> 31 * value + frame.hashCode() }
        val transition = stateMachine.heartbeatDelayed(clockMillis(), delayedMillis, signature, debuggerAffected, suspendGap = false)
        if (transition !is AnrTransition.Captured) return
        val now = clockMillis()
        val requested = if (lastRequestMillis == Long.MIN_VALUE || now - lastRequestMillis >= 600_000) {
            lastRequestMillis = now
            requester.request(2_000)
        } else {
            false
        }

        onCandidate(AnrCandidate(delayedMillis, frames, requested, debuggerAffected = false))
    }

    private fun scheduleHeartbeat(delayMillis: Long) {
        postedGeneration.incrementAndGet()
        mainHandler.postDelayed(heartbeat, delayMillis)
    }

    private fun heartbeatP99Nanos(): Long {
        if (heartbeatSampleCount == 0) {
            return 0
        }
        val samples = heartbeatSamples.copyOf(heartbeatSampleCount)
        samples.sort()
        return samples[kotlin.math.ceil(samples.size * 0.99).toInt() - 1]
    }
}

/** Host-testable bridge used by the Android watchdog to turn real heartbeat timing into capture work. */
class AnrHeartbeatBinding(
    private val stateMachine: AnrStateMachine,
    private val requester: NonFatalRequester,
    private val onCandidate: (AnrCandidate) -> Unit,
) {
    fun lifecycle(mode: AnrOperatingMode, nowMillis: Long) = stateMachine.mode(mode, nowMillis)

    fun delayed(
        nowMillis: Long,
        delayedMillis: Long,
        stackSignature: Long,
        frames: List<StackTraceElement>,
        debuggerAttached: Boolean,
        suspendGap: Boolean,
    ): AnrTransition {
        val transition = stateMachine.heartbeatDelayed(nowMillis, delayedMillis, stackSignature, debuggerAttached, suspendGap)
        if (transition is AnrTransition.Captured) {
            onCandidate(AnrCandidate(delayedMillis, frames.take(64), requester.request(2_000), debuggerAffected = false))
        }
        return transition
    }
}
