package dev.tracebox

import dev.tracebox.api.CaptureKind
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.LogLevel
import dev.tracebox.api.TraceboxPolicy
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimePolicyTest {
    @Test
    fun policySnapshotPreservesRuntimeSwitchesNeededByOtherProcesses() {
        val policy = TraceboxPolicy(
            minimumLogLevel = LogLevel.DEBUG,
            mirrorToLogcat = true,
            performanceLoggingEnabled = true,
            captures = setOf(CaptureKind.JVM_CRASH, CaptureKind.HANDLED_EXCEPTION, CaptureKind.ANR),
        )

        val restored = runtimePolicyForSnapshot(runtimePolicySnapshot(policy, 9L))

        assertEquals(policy.copy(minimumPerformanceDurationNanos = 0L), restored)
        assertEquals(DiagnosticsProfile.ENHANCED_DIAGNOSTIC_SESSION, profileFor(restored))
    }

    @Test
    fun fullPolicyStoreRoundTripsThresholdAndCaptureKinds() {
        val root = createTempDirectory("tracebox-policy")
        val store = RuntimePolicyStore(root.resolve("requested-policy-v2"))
        val policy = TraceboxPolicy(
            minimumLogLevel = LogLevel.WARN,
            minimumPerformanceDurationNanos = 5_000_000L,
            captures = setOf(CaptureKind.JVM_CRASH, CaptureKind.OS_EXIT),
        )

        store.write(policy)

        assertEquals(policy, store.read())
    }

    @Test
    fun corruptPolicyStoreFailsClosedToNoPersistedChoice() {
        val root = createTempDirectory("tracebox-policy-corrupt")
        val path = root.resolve("requested-policy-v2")
        RuntimePolicyStore(path).write(TraceboxPolicy.debug())
        val bytes = Files.readAllBytes(path)
        bytes[12] = 99
        Files.write(path, bytes)

        assertNull(RuntimePolicyStore(path).read())
    }

    @Test
    fun managedIdentityAllocationIsDurableAndUnique() {
        val root = createTempDirectory("tracebox-identities")
        val path = root.resolve("identity-lifecycle-managed-v1")
        val store = ManagedIdentityStore(path)

        val first = store.allocate(1)
        val second = store.allocate(2)

        assertEquals(32, first.size)
        assertEquals(32, second.size)
        assertNotEquals(first.toList(), second.toList())
        assertTrue(Files.size(path) > 0L)
        assertContentEquals(first, first.copyOf())
    }
}
