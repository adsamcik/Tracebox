package dev.tracebox.anr

import dev.tracebox.core.PolicySnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        val denied = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 1) })
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
}
