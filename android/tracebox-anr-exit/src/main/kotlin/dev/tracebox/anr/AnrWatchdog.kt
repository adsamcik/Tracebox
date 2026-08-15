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
    val sampleCount: Int = 1,
    val sampleFrameCounts: List<Int> = listOf(mainFrames.size),
) {
    init {
        require(delayedMillis >= 0)
        require(sampleCount in 1..MAX_SAMPLES)
        require(sampleFrameCounts.size == sampleCount)
        require(sampleFrameCounts.all { it in 0..MAX_FRAMES_PER_SAMPLE })
        require(sampleFrameCounts.sum() == mainFrames.size)
        require(mainFrames.size <= MAX_TOTAL_FRAMES)
    }

    companion object {
        const val MAX_SAMPLES = 3
        const val MAX_FRAMES_PER_SAMPLE = 64
        const val MAX_TOTAL_FRAMES = MAX_SAMPLES * MAX_FRAMES_PER_SAMPLE
    }
}

data class AnrWatchdogStats(
    val postedGeneration: Long,
    val acknowledgedGeneration: Long,
    val eligible: Boolean,
    val heartbeatP99Nanos: Long,
)

fun interface NonFatalRequester {
    fun request(timeoutMillis: Int): Boolean
}

/** Detects process suspension by comparing elapsed time (includes sleep) with uptime. */
class SuspendGapDetector(
    private val minimumSuspendGapMillis: Long = 1_000,
) {
    init {
        require(minimumSuspendGapMillis > 0)
    }

    fun detected(
        acknowledgedElapsedMillis: Long,
        acknowledgedUptimeMillis: Long,
        nowElapsedMillis: Long,
        nowUptimeMillis: Long,
    ): Boolean {
        val elapsedDelta = nowElapsedMillis - acknowledgedElapsedMillis
        val uptimeDelta = nowUptimeMillis - acknowledgedUptimeMillis
        if (elapsedDelta < 0 || uptimeDelta < 0) return true
        return elapsedDelta > uptimeDelta && elapsedDelta - uptimeDelta >= minimumSuspendGapMillis
    }
}

data class AnrCaptureStep(
    val transition: AnrTransition,
    /** Null means wait for heartbeat/lifecycle progress instead of sampling this generation again. */
    val retryDelayMillis: Long?,
)

/**
 * Host-testable multi-sample capture controller. Every nonterminal retry has a positive delay, so
 * a denied, debugger-affected, duplicate, or suspended observation cannot become a busy loop.
 */
class AnrCaptureController(
    private val stateMachine: AnrStateMachine,
    private val requester: NonFatalRequester,
    private val onCandidate: (AnrCandidate) -> Unit,
    private val sampleIntervalMillis: Long = 250,
    private val maxFramesPerSample: Int = AnrCandidate.MAX_FRAMES_PER_SAMPLE,
    private val nonFatalTimeoutMillis: Int = 2_000,
) {
    private val samples = ArrayDeque<List<StackTraceElement>>(AnrCandidate.MAX_SAMPLES)

    init {
        require(sampleIntervalMillis > 0)
        require(maxFramesPerSample in 1..AnrCandidate.MAX_FRAMES_PER_SAMPLE)
        require(nonFatalTimeoutMillis > 0)
    }

    @Synchronized
    fun lifecycle(mode: AnrOperatingMode, nowMillis: Long) {
        samples.clear()
        stateMachine.mode(mode, nowMillis)
    }

    @Synchronized
    fun recovered(): AnrTransition {
        samples.clear()
        return stateMachine.recovered()
    }

    @Synchronized
    fun delayed(
        nowMillis: Long,
        delayedMillis: Long,
        stackSignature: Long,
        frames: List<StackTraceElement>,
        debuggerAttached: Boolean,
        suspendGap: Boolean,
    ): AnrCaptureStep {
        val boundedFrames = frames.take(maxFramesPerSample).toList()
        if (samples.size == AnrCandidate.MAX_SAMPLES) samples.removeFirst()
        samples.addLast(boundedFrames)
        val transition = stateMachine.heartbeatDelayed(
            nowMillis,
            delayedMillis,
            stackSignature,
            debuggerAttached,
            suspendGap,
        )
        return when (transition) {
            is AnrTransition.Captured -> {
                val selected = samples.takeLast(transition.sampleCount)
                val combined = selected.flatten()
                val requested = transition.snapshotAllowed && try {
                    requester.request(nonFatalTimeoutMillis)
                } catch (_: Throwable) {
                    false
                }
                try {
                    onCandidate(
                        AnrCandidate(
                            delayedMillis = delayedMillis,
                            mainFrames = combined,
                            nonFatalRequested = requested,
                            debuggerAffected = false,
                            sampleCount = selected.size,
                            sampleFrameCounts = selected.map { it.size },
                        ),
                    )
                } catch (_: Throwable) {
                    // Candidate persistence failure must not terminate the bounded watchdog.
                }
                samples.clear()
                AnrCaptureStep(transition, null)
            }

            is AnrTransition.Suspected -> AnrCaptureStep(transition, sampleIntervalMillis)
            is AnrTransition.Suppressed -> {
                samples.clear()
                AnrCaptureStep(transition, suppressionRetryMillis(transition.reason))
            }

            AnrTransition.Recovered -> {
                samples.clear()
                AnrCaptureStep(transition, null)
            }
        }
    }

    private fun suppressionRetryMillis(reason: AnrSuppression): Long? = when (reason) {
        AnrSuppression.INELIGIBLE,
        AnrSuppression.DUPLICATE,
        AnrSuppression.RATE_LIMIT,
        -> NO_RETRY_WITHOUT_PROGRESS

        AnrSuppression.SUSPEND_GAP -> SUSPEND_RETRY_MILLIS
        AnrSuppression.DEBUGGER,
        AnrSuppression.STARTUP_GRACE,
        AnrSuppression.POLICY,
        -> SUPPRESSION_RETRY_MILLIS
    }

    private companion object {
        const val SUPPRESSION_RETRY_MILLIS = 1_000L
        const val SUSPEND_RETRY_MILLIS = 2_000L
        val NO_RETRY_WITHOUT_PROGRESS: Long? = null
    }
}

class AnrWatchdog(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val requester: NonFatalRequester,
    private val onCandidate: (AnrCandidate) -> Unit,
    policy: AnrPolicy = FAIL_CLOSED_POLICY,
    private val stateMachine: AnrStateMachine = AnrStateMachine(policy),
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
    private val suspendGapDetector: SuspendGapDetector = SuspendGapDetector(),
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val eligible = AtomicBoolean(false)
    private val postedGeneration = AtomicLong()
    private val acknowledgedGeneration = AtomicLong()
    private val lastAcknowledgedMillis = AtomicLong(clockMillis())
    private val lastAcknowledgedUptimeMillis = AtomicLong(uptimeMillis())
    private val heartbeatSamples = LongArray(2_048)
    private var heartbeatSampleCount = 0
    private val lock = ReentrantLock()
    private val eligibilityChanged = lock.newCondition()
    private var worker: Thread? = null
    private val captureController = AnrCaptureController(stateMachine, requester, onCandidate)
    private val heartbeat =
        object : Runnable {
            override fun run() {
                val startedNanos = SystemClock.elapsedRealtimeNanos()
                if (!running.get() || !eligible.get()) {
                    return
                }
                acknowledgedGeneration.set(postedGeneration.get())
                lastAcknowledgedMillis.set(clockMillis())
                lastAcknowledgedUptimeMillis.set(uptimeMillis())
                captureController.recovered()
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
        captureController.lifecycle(
            if (value) AnrOperatingMode.FOREGROUND_INTERACTIVE else AnrOperatingMode.SUSPENDED,
            clockMillis(),
        )
        if (value && running.get()) {
            lastAcknowledgedMillis.set(clockMillis())
            lastAcknowledgedUptimeMillis.set(uptimeMillis())
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
            val step = captureCandidate(clockMillis() - acknowledgedAt)
            awaitProgress(observedGeneration, step.retryDelayMillis)
        }
    }

    private fun awaitEligibility() {
        lock.withLock {
            while (running.get() && !eligible.get()) {
                eligibilityChanged.await()
            }
        }
    }

    private fun awaitProgress(observedGeneration: Long, retryDelayMillis: Long?) {
        lock.withLock {
            if (!running.get() || !eligible.get() ||
                acknowledgedGeneration.get() != observedGeneration
            ) {
                return
            }
            if (retryDelayMillis == null) {
                eligibilityChanged.await()
            } else {
                eligibilityChanged.awaitNanos(retryDelayMillis * 1_000_000)
            }
        }
    }

    private fun captureCandidate(delayedMillis: Long): AnrCaptureStep {
        val nowElapsed = clockMillis()
        val nowUptime = uptimeMillis()
        val debuggerAffected = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val frames = Looper.getMainLooper().thread.stackTrace.take(AnrCandidate.MAX_FRAMES_PER_SAMPLE)
        val signature = frames.fold(1L) { value, frame -> 31 * value + frame.hashCode() }
        val suspendGap = suspendGapDetector.detected(
            lastAcknowledgedMillis.get(),
            lastAcknowledgedUptimeMillis.get(),
            nowElapsed,
            nowUptime,
        )
        return captureController.delayed(
            nowElapsed,
            delayedMillis,
            signature,
            frames,
            debuggerAffected,
            suspendGap,
        )
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

    private companion object {
        val FAIL_CLOSED_POLICY = AnrPolicy { PolicySnapshot(0, Long.MAX_VALUE, disabled = true) }
    }
}

/** Host-testable bridge used by the Android watchdog to turn real heartbeat timing into capture work. */
class AnrHeartbeatBinding(
    private val stateMachine: AnrStateMachine,
    private val requester: NonFatalRequester,
    private val onCandidate: (AnrCandidate) -> Unit,
    sampleIntervalMillis: Long = 250,
    maxFramesPerSample: Int = AnrCandidate.MAX_FRAMES_PER_SAMPLE,
) {
    private val controller = AnrCaptureController(
        stateMachine,
        requester,
        onCandidate,
        sampleIntervalMillis,
        maxFramesPerSample,
    )

    fun lifecycle(mode: AnrOperatingMode, nowMillis: Long) = controller.lifecycle(mode, nowMillis)

    fun delayed(
        nowMillis: Long,
        delayedMillis: Long,
        stackSignature: Long,
        frames: List<StackTraceElement>,
        debuggerAttached: Boolean,
        suspendGap: Boolean,
    ): AnrTransition {
        return controller.delayed(
            nowMillis,
            delayedMillis,
            stackSignature,
            frames,
            debuggerAttached,
            suspendGap,
        ).transition
    }
}
