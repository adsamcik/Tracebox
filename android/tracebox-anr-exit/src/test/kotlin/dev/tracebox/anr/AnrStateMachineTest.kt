package dev.tracebox.anr

import dev.tracebox.core.PolicySnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnrStateMachineTest {
    @Test fun suppresses_debugger_and_suspend_gaps_without_candidate() {
        val machine = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) })
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 0)

        assertEquals(AnrTransition.Suppressed(AnrSuppression.DEBUGGER), machine.heartbeatDelayed(20_000, 6_000, 11, true, false))
        assertEquals(AnrTransition.Suppressed(AnrSuppression.SUSPEND_GAP), machine.heartbeatDelayed(20_000, 6_000, 11, false, true))
    }

    @Test fun candidate_is_never_confirmed_without_exit_reconciliation() {
        val machine = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) })
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 0)

        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(20_000, 6_000, 12, false, false))
        val captured = assertIs<AnrTransition.Captured>(machine.heartbeatDelayed(20_001, 6_100, 12, false, false))
        assertEquals(AnrEvidenceLevel.CANDIDATE, captured.evidence)
        assertEquals(AnrWatchState.CAPTURED_CANDIDATE, machine.state())
        assertEquals(AnrTransition.Recovered, machine.recovered())
    }

    @Test fun policy_denial_and_duplicate_signature_arebounded() {
        val denied = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 64) })
        denied.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 0)
        denied.heartbeatDelayed(20_000, 6_000, 22, false, false)
        assertEquals(AnrTransition.Suppressed(AnrSuppression.POLICY), denied.heartbeatDelayed(20_001, 6_001, 22, false, false))

        val machine = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) })
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 0)
        machine.heartbeatDelayed(20_000, 6_000, 22, false, false)
        machine.heartbeatDelayed(20_001, 6_001, 22, false, false)
        machine.recovered()
        machine.heartbeatDelayed(20_002, 12_000, 22, false, false)
        assertEquals(AnrTransition.Suppressed(AnrSuppression.DUPLICATE), machine.heartbeatDelayed(20_003, 12_001, 22, false, false))
    }

    @Test fun heartbeat_binding_requests_native_snapshot_only_after_real_second_delayed_heartbeat() {
        val requests = mutableListOf<Int>()
        val candidates = mutableListOf<AnrCandidate>()
        val binding = AnrHeartbeatBinding(
            AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) }, startupGraceMillis = 0),
            NonFatalRequester { timeout -> requests += timeout; true },
            { candidate -> candidates.add(candidate) },
        )
        binding.lifecycle(AnrOperatingMode.FOREGROUND_INTERACTIVE, 1)
        val frames = listOf(StackTraceElement("Main", "blocked", "Main.kt", 3))

        assertIs<AnrTransition.Suspected>(binding.delayed(20_000, 6_000, 11, frames, false, false))
        assertIs<AnrTransition.Captured>(binding.delayed(20_001, 6_100, 12, frames, false, false))

        assertEquals(listOf(2_000), requests)
        assertEquals(1, candidates.size)
        assertTrue(candidates.single().nonFatalRequested)
    }

    @Test fun recovered_is_serialized_after_an_inflight_candidate_transition() {
        val policyEntered = CountDownLatch(1)
        val allowCandidate = CountDownLatch(1)
        val recoveryStarted = CountDownLatch(1)
        val recoveryFinished = CountDownLatch(1)
        val machine = AnrStateMachine(
            AnrPolicy {
                policyEntered.countDown()
                assertTrue(allowCandidate.await(5, TimeUnit.SECONDS))
                PolicySnapshot(1, 0)
            },
            startupGraceMillis = 0,
        )
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 1)
        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(20_000, 6_000, 11, false, false))

        var candidate: AnrTransition? = null
        val candidateThread = Thread {
            candidate = machine.heartbeatDelayed(20_001, 6_001, 12, false, false)
        }
        val recoveryThread = Thread {
            recoveryStarted.countDown()
            machine.recovered()
            recoveryFinished.countDown()
        }
        candidateThread.start()
        assertTrue(policyEntered.await(5, TimeUnit.SECONDS))
        recoveryThread.start()
        assertTrue(recoveryStarted.await(5, TimeUnit.SECONDS))
        assertEquals(1, recoveryFinished.count)

        allowCandidate.countDown()
        candidateThread.join(5_000)
        recoveryThread.join(5_000)

        assertIs<AnrTransition.Captured>(candidate)
        assertEquals(0, recoveryFinished.count)
        assertEquals(AnrWatchState.HEALTHY, machine.state())
    }

    @Test fun startup_grace_and_rate_limit_use_elapsed_clock_not_stall_duration() {
        val machine = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) })
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 1)

        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(10_001, 6_000, 1, false, false))
        assertIs<AnrTransition.Captured>(machine.heartbeatDelayed(10_002, 6_001, 2, false, false))
        machine.recovered()

        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(10_003, 6_000, 3, false, false))
        assertEquals(
            AnrTransition.Suppressed(AnrSuppression.RATE_LIMIT),
            machine.heartbeatDelayed(10_004, 6_001, 4, false, false),
        )
        machine.recovered()

        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(610_002, 6_000, 5, false, false))
        assertIs<AnrTransition.Captured>(machine.heartbeatDelayed(610_003, 6_001, 6, false, false))
    }

    @Test fun policy_is_read_again_at_candidate_time_and_tightening_requires_fresh_samples() {
        var current = PolicySnapshot(1, 0)
        var reads = 0
        val machine = AnrStateMachine(
            AnrPolicy {
                reads++
                current
            },
            startupGraceMillis = 0,
        )
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 1)

        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(20_000, 6_000, 1, false, false))
        assertEquals(0, reads)
        current = PolicySnapshot(2, 64)
        assertEquals(
            AnrTransition.Suppressed(AnrSuppression.POLICY),
            machine.heartbeatDelayed(20_250, 6_250, 2, false, false),
        )
        assertEquals(1, reads)
        assertEquals(AnrWatchState.HEALTHY, machine.state())

        current = PolicySnapshot(3, 0)
        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(20_500, 6_500, 3, false, false))
        val captured = assertIs<AnrTransition.Captured>(
            machine.heartbeatDelayed(20_750, 6_750, 4, false, false),
        )
        assertEquals(AnrEvidenceLevel.CANDIDATE, captured.evidence)
        assertEquals(2, captured.sampleCount)
        assertEquals(2, reads)
    }

    @Test fun suspend_gap_is_detected_from_elapsed_vs_uptime_and_discards_prior_suspicion() {
        val detector = SuspendGapDetector(minimumSuspendGapMillis = 1_000)
        assertFalse(detector.detected(1_000, 1_000, 6_000, 6_000))
        assertTrue(detector.detected(1_000, 1_000, 8_000, 6_100))
        assertTrue(detector.detected(5_000, 5_000, 4_000, 4_000))

        val machine = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) }, startupGraceMillis = 0)
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 1)
        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(20_000, 6_000, 1, false, false))
        assertEquals(
            AnrTransition.Suppressed(AnrSuppression.SUSPEND_GAP),
            machine.heartbeatDelayed(20_250, 6_250, 2, false, true),
        )
        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(20_500, 6_500, 3, false, false))
        assertIs<AnrTransition.Captured>(machine.heartbeatDelayed(20_750, 6_750, 4, false, false))
    }

    @Test fun capture_controller_spaces_samples_bounds_frames_and_requests_only_for_a_candidate() {
        val requests = mutableListOf<Int>()
        val candidates = mutableListOf<AnrCandidate>()
        val controller = AnrCaptureController(
            AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) }, startupGraceMillis = 0),
            NonFatalRequester { timeout -> requests += timeout; true },
            candidates::add,
            sampleIntervalMillis = 333,
            maxFramesPerSample = 4,
        )
        controller.lifecycle(AnrOperatingMode.FOREGROUND_INTERACTIVE, 1)
        val firstFrames = List(20) { StackTraceElement("First$it", "blocked", "First.kt", it) }
        val secondFrames = List(20) { StackTraceElement("Second$it", "blocked", "Second.kt", it) }

        val suspected = controller.delayed(20_000, 6_000, 1, firstFrames, false, false)
        assertIs<AnrTransition.Suspected>(suspected.transition)
        assertEquals(333, suspected.retryDelayMillis)
        assertTrue(requests.isEmpty())
        assertTrue(candidates.isEmpty())

        val captured = controller.delayed(20_333, 6_333, 2, secondFrames, false, false)
        val transition = assertIs<AnrTransition.Captured>(captured.transition)
        assertEquals(AnrEvidenceLevel.CANDIDATE, transition.evidence)
        assertNull(captured.retryDelayMillis)
        assertEquals(listOf(2_000), requests)
        assertEquals(2, candidates.single().sampleCount)
        assertEquals(listOf(4, 4), candidates.single().sampleFrameCounts)
        assertEquals(8, candidates.single().mainFrames.size)
        assertTrue(candidates.single().mainFrames.none { it.className.startsWith("First4") || it.className.startsWith("Second4") })
    }

    @Test fun every_retryable_suppression_has_a_positive_delay_and_terminal_suppression_waits_for_progress() {
        var policy = PolicySnapshot(1, 0)
        val controller = AnrCaptureController(
            AnrStateMachine(AnrPolicy { policy }, startupGraceMillis = 0),
            NonFatalRequester { true },
            {},
            sampleIntervalMillis = 250,
        )
        controller.lifecycle(AnrOperatingMode.FOREGROUND_INTERACTIVE, 1)
        val frame = listOf(StackTraceElement("Main", "blocked", "Main.kt", 1))
        assertEquals(250, controller.delayed(20_000, 6_000, 1, frame, false, false).retryDelayMillis)
        policy = PolicySnapshot(2, 64)
        val denied = controller.delayed(20_250, 6_250, 2, frame, false, false)
        assertEquals(AnrTransition.Suppressed(AnrSuppression.POLICY), denied.transition)
        assertTrue(checkNotNull(denied.retryDelayMillis) > 0)

        val debugger = controller.delayed(20_500, 6_500, 3, frame, true, false)
        assertEquals(AnrTransition.Suppressed(AnrSuppression.DEBUGGER), debugger.transition)
        assertTrue(checkNotNull(debugger.retryDelayMillis) > 0)

        val suspended = controller.delayed(20_750, 6_750, 4, frame, false, true)
        assertEquals(AnrTransition.Suppressed(AnrSuppression.SUSPEND_GAP), suspended.transition)
        assertTrue(checkNotNull(suspended.retryDelayMillis) > 0)

        policy = PolicySnapshot(3, 0)
        controller.delayed(21_000, 7_000, 5, frame, false, false)
        controller.delayed(21_250, 7_250, 6, frame, false, false)
        controller.recovered()
        controller.delayed(21_500, 7_500, 7, frame, false, false)
        val duplicate = controller.delayed(21_750, 7_750, 6, frame, false, false)
        assertEquals(AnrTransition.Suppressed(AnrSuppression.DUPLICATE), duplicate.transition)
        assertNull(duplicate.retryDelayMillis)
    }

    @Test fun unavailable_policy_fails_closed_without_terminating_the_watchdog_state_machine() {
        val machine = AnrStateMachine(
            AnrPolicy { throw IllegalStateException("control page unavailable") },
            startupGraceMillis = 0,
        )
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 1)
        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(20_000, 6_000, 1, false, false))
        assertEquals(
            AnrTransition.Suppressed(AnrSuppression.POLICY),
            machine.heartbeatDelayed(20_250, 6_250, 2, false, false),
        )
        assertEquals(AnrWatchState.HEALTHY, machine.state())
    }
}
