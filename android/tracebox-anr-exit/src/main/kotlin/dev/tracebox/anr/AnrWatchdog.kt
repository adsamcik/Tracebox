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
    private val lock = ReentrantLock()
    private val eligibilityChanged = lock.newCondition()
    private var worker: Thread? = null
    private var lastRequestMillis = Long.MIN_VALUE

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
        eligible.set(value)
        lock.withLock { eligibilityChanged.signalAll() }
    }

    override fun close() {
        running.set(false)
        lock.withLock { eligibilityChanged.signalAll() }
        worker?.interrupt()
        worker = null
    }

    private fun runLoop() {
        while (running.get()) {
            awaitEligibility()
            if (!running.get()) {
                return
            }

            val generation = postedGeneration.incrementAndGet()
            val postedAt = clockMillis()
            mainHandler.post {
                acknowledgedGeneration.accumulateAndGet(generation, ::maxOf)
            }
            Thread.sleep(3_000)
            if (!eligible.get() || acknowledgedGeneration.get() >= generation) {
                continue
            }

            val delayed = clockMillis() - postedAt
            if (delayed < 5_000) {
                Thread.sleep(5_000 - delayed)
            }
            if (!eligible.get() || acknowledgedGeneration.get() >= generation) {
                continue
            }
            captureCandidate(clockMillis() - postedAt)
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
}
