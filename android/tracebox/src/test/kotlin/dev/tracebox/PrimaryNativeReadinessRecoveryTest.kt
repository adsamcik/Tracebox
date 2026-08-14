package dev.tracebox

import dev.tracebox.api.Readiness
import dev.tracebox.api.TraceboxHealth
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimaryNativeReadinessRecoveryTest {
    @Test
    fun previously_ready_runtime_is_restored_once_after_native_recovery() {
        val recovery = PrimaryNativeReadinessRecovery()

        recovery.begin(Readiness.DURABLE, TraceboxHealth.READY)

        assertFalse(recovery.complete(recovered = false))
        assertTrue(recovery.complete(recovered = true))
        assertFalse(recovery.complete(recovered = true))
    }

    @Test
    fun initial_or_unrelated_degraded_state_is_never_promoted() {
        val recovery = PrimaryNativeReadinessRecovery()

        recovery.begin(Readiness.VOLATILE_CAPTURE, TraceboxHealth.INITIALIZING)
        recovery.begin(Readiness.DEGRADED, TraceboxHealth.DEGRADED)

        assertFalse(recovery.complete(recovered = true))
    }

    @Test
    fun repeated_failed_native_attempts_preserve_the_original_ready_entitlement() {
        val recovery = PrimaryNativeReadinessRecovery()

        recovery.begin(Readiness.DURABLE, TraceboxHealth.READY)
        recovery.begin(Readiness.DEGRADED, TraceboxHealth.DEGRADED)

        assertFalse(recovery.complete(recovered = false))
        assertTrue(recovery.complete(recovered = true))
    }

    @Test
    fun policy_or_lifecycle_reset_prevents_later_promotion() {
        val recovery = PrimaryNativeReadinessRecovery()

        recovery.begin(Readiness.DURABLE, TraceboxHealth.READY)
        recovery.clear()

        assertFalse(recovery.complete(recovered = true))
    }
}
