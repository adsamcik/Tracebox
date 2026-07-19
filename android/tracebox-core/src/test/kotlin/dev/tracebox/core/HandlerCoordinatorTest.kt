package dev.tracebox.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HandlerCoordinatorTest {
    private fun root() = Files.createTempDirectory("tracebox-handler")

    @Test fun handler_death_immediately_degrades_and_reconnects_only_on_explicit_trigger() {
        val runtime = TraceboxRuntime(TraceboxConfiguration(ByteArray(32), 1))
        val connection = HandlerConnection(runtime) { HandlerConnectionResult.Connected(PolicySnapshot(1, 0)) }

        assertIs<HandlerConnectionResult.Connected>(connection.connect(HandlerConnectTrigger.INSTALL))
        connection.onHandlerDeath()
        assertEquals(dev.tracebox.api.Readiness.DEGRADED, runtime.readiness.value)
        assertEquals(HandlerConnectionResult.NotTriggered, connection.connect(HandlerConnectTrigger.NONE))
        assertIs<HandlerConnectionResult.Connected>(connection.connect(HandlerConnectTrigger.CAPTURE))
    }

    @Test fun update_reports_partial_when_live_unverified_or_missing_participant_does_not_acknowledge() {
        val root = root()
        val page = ControlPage(root.resolve("control"))
        page.commit(PolicySnapshot(1, 0))
        val coordinator = GlobalPolicyCoordinator(root, page, byteArrayOf(1))
        coordinator.register("writer-a", 1, ByteArray(32) { 1 }, barrier = { BarrierAck.Acknowledged })
        coordinator.register("writer-b", 1, ByteArray(32) { 2 }, barrier = { BarrierAck.Missing })

        val result = coordinator.updateProfile(PolicySnapshot(2, 1), handlerBarrier = { BarrierAck.Acknowledged })

        assertIs<ProfileUpdateResult.Partial>(result)
        assertEquals(1, page.committed().epoch)
        assertTrue(result.unacknowledged.contains("writer-b"))
    }

    @Test fun restart_marks_census_unverified_until_lease_proves_death_then_tombstones_it() {
        val root = root()
        val page = ControlPage(root.resolve("control"))
        page.commit(PolicySnapshot(1, 0))
        val first = GlobalPolicyCoordinator(root, page, byteArrayOf(3))
        first.register("writer-a", 1, ByteArray(32) { 3 }) { BarrierAck.Acknowledged }

        val restarted = GlobalPolicyCoordinator(root, page, byteArrayOf(3))
        assertEquals(ParticipantState.UNVERIFIED, restarted.participants().single().state)
        assertEquals(LeaseProbeResult.StillLive, restarted.probeUnverified("writer-a"))
    }

    @Test fun reboot_replaces_boot_session_and_releases_prior_census() {
        val root = root()
        val page = ControlPage(root.resolve("control"))
        page.commit(PolicySnapshot(1, 0))
        val first = GlobalPolicyCoordinator(root, page, byteArrayOf(4))
        first.register("writer-a", 1, ByteArray(32) { 4 }) { BarrierAck.Acknowledged }.close()

        val rebooted = GlobalPolicyCoordinator(root, page, byteArrayOf(5))

        assertTrue(rebooted.participants().isEmpty())
    }

    @Test fun coexistence_policy_selects_exactly_one_capture_outcome() {
        assertEquals(
            CrashDispatchResult.PrimaryCrashpad,
            CrashDispatchStateMachine(CrashCoexistencePolicy.EXCLUSIVE).dispatch(priorHandlerDetected = false),
        )
        assertEquals(
            CrashDispatchResult.PrimaryCrashpadThenPrior,
            CrashDispatchStateMachine(CrashCoexistencePolicy.BEST_EFFORT_CHAIN).dispatch(priorHandlerDetected = true),
        )
        assertEquals(
            CrashDispatchResult.DegradedNativeDisabled,
            CrashDispatchStateMachine(CrashCoexistencePolicy.DISABLE_ON_CONFLICT).dispatch(priorHandlerDetected = true),
        )
    }

    @Test fun best_effort_plan_invokes_prior_once_then_forces_default_termination_if_it_returns() {
        val plan = CrashDispatchStateMachine(CrashCoexistencePolicy.BEST_EFFORT_CHAIN).nativePlan(priorHandlerDetected = true)

        assertEquals(CrashDispatchResult.PrimaryCrashpadThenPrior, plan.result)
        assertTrue(plan.preservePreviousAction)
        assertTrue(plan.invokePreviousHandlerExactlyOnce)
        assertTrue(plan.forceDefaultTerminationIfPreviousReturns)
    }
}
