package dev.tracebox.anr

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    val heartbeatMaxNanos: Long,
)

fun interface NonFatalRequester {
    fun request(timeoutMillis: Int): Boolean
}

class AnrWatchdog(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val requester: NonFatalRequester,
    private val onCandidate: (AnrCandidate) -> Unit,
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val eligible = AtomicBoolean(false)
    private val postedGeneration = AtomicLong()
    private val acknowledgedGeneration = AtomicLong()
    private val lastAcknowledgedMillis = AtomicLong(clockMillis())
    private val heartbeatMaxNanos = AtomicLong()
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
                lock.withLock { eligibilityChanged.signalAll() }
                scheduleHeartbeat(2_000)
                heartbeatMaxNanos.accumulateAndGet(
                    SystemClock.elapsedRealtimeNanos() - startedNanos,
                    ::maxOf,
                )
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
            heartbeatMaxNanos = heartbeatMaxNanos.get(),
        )

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
            if (capturedGeneration == observedGeneration) {
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
        if (debuggerAffected) {
            return
        }
        val frames = Looper.getMainLooper().thread.stackTrace.take(64)
        val now = clockMillis()
        val mayRequest = lastRequestMillis == Long.MIN_VALUE || now - lastRequestMillis >= 600_000
        val requested = if (mayRequest) {
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
}
