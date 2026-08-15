package dev.tracebox.core

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun stale_temporary_census_from_a_crash_never_replaces_the_last_durable_census() {
        val root = root()
        val page = ControlPage(root.resolve("control"))
        page.commit(PolicySnapshot(1, 0))
        val first = GlobalPolicyCoordinator(root, page, byteArrayOf(7))
        first.register("writer-a", 1, ByteArray(32) { 7 }) { BarrierAck.Acknowledged }
        Files.writeString(root.resolve("participant-census-v1.new"), "truncated-crash-state")

        val restarted = GlobalPolicyCoordinator(root, page, byteArrayOf(7))

        assertEquals(listOf("writer-a"), restarted.participants().map { it.participantId })
        assertEquals(ParticipantState.UNVERIFIED, restarted.participants().single().state)
        assertFalse(Files.exists(root.resolve("participant-census-v1.new")))
    }

    @Test fun target_aware_barrier_fences_racing_registration_until_the_target_is_committed() {
        val root = root()
        val page = ControlPage(root.resolve("control"))
        page.commit(PolicySnapshot(1, 0))
        val coordinator = GlobalPolicyCoordinator(root, page, byteArrayOf(8))
        val barrierEntered = CountDownLatch(1)
        val releaseBarrier = CountDownLatch(1)
        val registrationStarted = CountDownLatch(1)
        val registrationFinished = CountDownLatch(1)
        val observedTargets = mutableListOf<PolicySnapshot>()
        coordinator.registerTargetAware("writer-a", 1, ByteArray(32) { 8 }) { target ->
            observedTargets += target
            barrierEntered.countDown()
            assertTrue(releaseBarrier.await(5, TimeUnit.SECONDS))
            BarrierAck.Acknowledged
        }
        val target = PolicySnapshot(2, 3, disabled = true)
        var update: ProfileUpdateResult? = null
        val updateThread = Thread {
            update = coordinator.updateProfileTargetAware(target) { handlerTarget ->
                observedTargets += handlerTarget
                BarrierAck.Acknowledged
            }
        }
        updateThread.start()
        assertTrue(barrierEntered.await(5, TimeUnit.SECONDS))

        val registrationThread = Thread {
            registrationStarted.countDown()
            coordinator.register("writer-b", 2, ByteArray(32) { 9 }) { BarrierAck.Acknowledged }
            registrationFinished.countDown()
        }
        registrationThread.start()
        assertTrue(registrationStarted.await(5, TimeUnit.SECONDS))
        assertFalse(registrationFinished.await(100, TimeUnit.MILLISECONDS))

        releaseBarrier.countDown()
        updateThread.join(5_000)
        registrationThread.join(5_000)

        assertIs<ProfileUpdateResult.Success>(update)
        assertEquals(listOf(target, target), observedTargets)
        assertEquals(2, page.committed().epoch)
        assertTrue(registrationFinished.await(1, TimeUnit.SECONDS))
        assertEquals(setOf("writer-a", "writer-b"), coordinator.participants().map { it.participantId }.toSet())
        assertEquals(2, coordinator.participants().single { it.participantId == "writer-b" }.lastAcknowledgedEpoch)
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
