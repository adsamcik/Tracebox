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
    data class Captured(
        val evidence: AnrEvidenceLevel,
        val snapshotAllowed: Boolean,
        val sampleCount: Int,
    ) : AnrTransition {
        init {
            require(evidence == AnrEvidenceLevel.CANDIDATE || evidence == AnrEvidenceLevel.HIGH_CONFIDENCE_CANDIDATE)
            require(sampleCount >= 2)
        }
    }
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
    private var startedAt = Long.MIN_VALUE
    private var watchState = AnrWatchState.HEALTHY
    private var delayedSampleCount = 0
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
        if (startedAt == Long.MIN_VALUE) startedAt = nowMillis
        if (value == AnrOperatingMode.SUSPENDED || value == AnrOperatingMode.DEBUGGER_SUPPRESSED) {
            resetObservation()
        }
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
        if (currentMode == AnrOperatingMode.SUSPENDED) return suppress(AnrSuppression.INELIGIBLE)
        if (currentMode == AnrOperatingMode.DEBUGGER_SUPPRESSED || debuggerAttached) {
            return suppress(AnrSuppression.DEBUGGER)
        }
        if (suspendGap) return suppress(AnrSuppression.SUSPEND_GAP)
        if (delayedMillis < thresholdMillis) {
            resetObservation()
            return AnrTransition.Recovered
        }
        if (startedAt != Long.MIN_VALUE &&
            nowMillis - startedAt < startupGraceMillis &&
            watchState == AnrWatchState.HEALTHY
        ) {
            return suppress(AnrSuppression.STARTUP_GRACE)
        }
        if (watchState == AnrWatchState.HEALTHY) {
            watchState = AnrWatchState.SUSPECTED_STALL
            delayedSampleCount = 1
            return AnrTransition.Suspected(delayedMillis)
        }
        val currentPolicy = try {
            policy.current()
        } catch (_: RuntimeException) {
            return suppress(AnrSuppression.POLICY)
        }
        if (!currentPolicy.permits(ANR_CATEGORY)) return suppress(AnrSuppression.POLICY)
        if (stackSignature == lastSignature) return suppress(AnrSuppression.DUPLICATE)
        if (!takeToken(nowMillis)) return suppress(AnrSuppression.RATE_LIMIT)
        delayedSampleCount++
        watchState = AnrWatchState.CREDIBLE_STALL
        lastSignature = stackSignature
        watchState = AnrWatchState.CAPTURED_CANDIDATE
        return AnrTransition.Captured(
            AnrEvidenceLevel.CANDIDATE,
            snapshotAllowed = true,
            sampleCount = delayedSampleCount,
        )
    }

    @Synchronized
    fun recovered(): AnrTransition {
        resetObservation()
        return AnrTransition.Recovered
    }

    private fun suppress(reason: AnrSuppression): AnrTransition.Suppressed {
        resetObservation()
        return AnrTransition.Suppressed(reason)
    }

    private fun resetObservation() {
        watchState = AnrWatchState.HEALTHY
        delayedSampleCount = 0
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
        const val ANR_CATEGORY = 64L
    }
}
