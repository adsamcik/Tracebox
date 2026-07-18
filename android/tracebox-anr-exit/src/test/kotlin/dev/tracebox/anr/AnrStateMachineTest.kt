package dev.tracebox.anr

import dev.tracebox.core.PolicySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AnrStateMachineTest {
    @Test fun suppresses_debugger_and_suspend_gaps_without_candidate() {
        val machine = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) })
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 0)

        assertEquals(AnrTransition.Suppressed(AnrSuppression.DEBUGGER), machine.heartbeatDelayed(6_000, 11, true, false))
        assertEquals(AnrTransition.Suppressed(AnrSuppression.SUSPEND_GAP), machine.heartbeatDelayed(6_000, 11, false, true))
    }

    @Test fun candidate_is_never_confirmed_without_exit_reconciliation() {
        val machine = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) })
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 0)

        assertIs<AnrTransition.Suspected>(machine.heartbeatDelayed(6_000, 12, false, false))
        val captured = assertIs<AnrTransition.Captured>(machine.heartbeatDelayed(6_100, 12, false, false))
        assertEquals(AnrEvidenceLevel.CANDIDATE, captured.evidence)
        assertEquals(AnrWatchState.CAPTURED_CANDIDATE, machine.state())
        assertEquals(AnrTransition.Recovered, machine.recovered())
    }

    @Test fun policy_denial_and_duplicate_signature_arebounded() {
        val denied = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 1) })
        denied.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 0)
        denied.heartbeatDelayed(6_000, 22, false, false)
        assertEquals(AnrTransition.Suppressed(AnrSuppression.POLICY), denied.heartbeatDelayed(6_001, 22, false, false))

        val machine = AnrStateMachine(AnrPolicy { PolicySnapshot(1, 0) })
        machine.mode(AnrOperatingMode.FOREGROUND_INTERACTIVE, 0)
        machine.heartbeatDelayed(6_000, 22, false, false)
        machine.heartbeatDelayed(6_001, 22, false, false)
        machine.recovered()
        machine.heartbeatDelayed(12_000, 22, false, false)
        assertEquals(AnrTransition.Suppressed(AnrSuppression.DUPLICATE), machine.heartbeatDelayed(12_001, 22, false, false))
    }
}
