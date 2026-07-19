package dev.tracebox.anr

import dev.tracebox.core.PolicySnapshot

/** Observable application states only; this deliberately does not claim cached-process fidelity. */
enum class AnrOperatingMode {
    FOREGROUND_INTERACTIVE,
    FOREGROUND_NON_INTERACTIVE,
    ACTIVE_SERVICE_OR_RECEIVER,
    SUSPENDED,
    DEBUGGER_SUPPRESSED,
}

enum class AnrWatchState { HEALTHY, SUSPECTED_STALL, CREDIBLE_STALL, CAPTURED_CANDIDATE }
enum class AnrEvidenceLevel { STALL_OBSERVATION, CANDIDATE, HIGH_CONFIDENCE_CANDIDATE, CONFIRMED_EXIT, CLASSIFIED_CONFIRMED_EXIT }
enum class AnrSuppression { DEBUGGER, SUSPEND_GAP, STARTUP_GRACE, INELIGIBLE, POLICY, RATE_LIMIT, DUPLICATE }

sealed interface AnrTransition {
    data class Suspected(val delayedMillis: Long) : AnrTransition
    data class Captured(val evidence: AnrEvidenceLevel, val snapshotAllowed: Boolean) : AnrTransition
    data class Suppressed(val reason: AnrSuppression) : AnrTransition
    data object Recovered : AnrTransition
}

/** Policy is read at candidate time so an ANR observation cannot bypass a later tightening. */
fun interface AnrPolicy {
    fun current(): PolicySnapshot
}

/**
 * Allocation-free-after-warm-up decision core for the production watchdog. The Android heartbeat
 * supplies the delay and bounded stack signature; this class makes no periodic cross-process call.
 */
class AnrStateMachine(
    private val policy: AnrPolicy,
    private val startupGraceMillis: Long = 10_000,
    private val thresholdMillis: Long = 5_000,
    private val rateWindowMillis: Long = 600_000,
    private val maxCapturesPerWindow: Int = 1,
) {
    private var currentMode = AnrOperatingMode.SUSPENDED
    private var startedAt = 0L
    private var watchState = AnrWatchState.HEALTHY
    private var captureWindowStart = Long.MIN_VALUE
    private var capturesInWindow = 0
    private var lastSignature = Long.MIN_VALUE

    init {
        require(startupGraceMillis >= 0)
        require(thresholdMillis > 0)
        require(rateWindowMillis > 0)
        require(maxCapturesPerWindow > 0)
    }

    @Synchronized
    fun mode(value: AnrOperatingMode, nowMillis: Long) {
        currentMode = value
        if (startedAt == 0L) startedAt = nowMillis
        if (value == AnrOperatingMode.SUSPENDED) watchState = AnrWatchState.HEALTHY
    }

    @Synchronized
    fun state(): AnrWatchState = watchState

    /**
     * A second delayed heartbeat makes the stall credible. It remains a candidate unless a later
     * `ApplicationExitInfo.REASON_ANR` reconciliation independently confirms it.
     */
    @Synchronized
    fun heartbeatDelayed(
        nowMillis: Long,
        delayedMillis: Long,
        stackSignature: Long,
        debuggerAttached: Boolean,
        suspendGap: Boolean,
    ): AnrTransition {
        if (currentMode == AnrOperatingMode.SUSPENDED) return AnrTransition.Suppressed(AnrSuppression.INELIGIBLE)
        if (currentMode == AnrOperatingMode.DEBUGGER_SUPPRESSED || debuggerAttached) return AnrTransition.Suppressed(AnrSuppression.DEBUGGER)
        if (suspendGap) return AnrTransition.Suppressed(AnrSuppression.SUSPEND_GAP)
        if (delayedMillis < thresholdMillis) {
            watchState = AnrWatchState.HEALTHY
            return AnrTransition.Recovered
        }
        if (startedAt != 0L && nowMillis - startedAt < startupGraceMillis && watchState == AnrWatchState.HEALTHY) {
            return AnrTransition.Suppressed(AnrSuppression.STARTUP_GRACE)
        }
        if (watchState == AnrWatchState.HEALTHY) {
            watchState = AnrWatchState.SUSPECTED_STALL
            return AnrTransition.Suspected(delayedMillis)
        }
        if (!policy.current().permits(ANR_CATEGORY)) return AnrTransition.Suppressed(AnrSuppression.POLICY)
        if (stackSignature == lastSignature) return AnrTransition.Suppressed(AnrSuppression.DUPLICATE)
        if (!takeToken(nowMillis)) return AnrTransition.Suppressed(AnrSuppression.RATE_LIMIT)
        watchState = AnrWatchState.CREDIBLE_STALL
        lastSignature = stackSignature
        watchState = AnrWatchState.CAPTURED_CANDIDATE
        return AnrTransition.Captured(AnrEvidenceLevel.CANDIDATE, snapshotAllowed = true)
    }

    @Synchronized
    fun recovered(): AnrTransition {
        watchState = AnrWatchState.HEALTHY
        return AnrTransition.Recovered
    }

    private fun takeToken(nowMillis: Long): Boolean {
        if (captureWindowStart == Long.MIN_VALUE || nowMillis - captureWindowStart >= rateWindowMillis) {
            captureWindowStart = nowMillis
            capturesInWindow = 0
        }
        if (capturesInWindow >= maxCapturesPerWindow) return false
        capturesInWindow++
        return true
    }

    private companion object {
        const val ANR_CATEGORY = 1L
    }
}
