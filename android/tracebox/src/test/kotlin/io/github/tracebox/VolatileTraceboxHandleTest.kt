// SPDX-License-Identifier: Apache-2.0

package io.github.tracebox

import io.github.tracebox.api.DeleteRequest
import io.github.tracebox.api.DiagnosticCode
import io.github.tracebox.api.DiagnosticsProfile
import io.github.tracebox.api.GeneratedBreadcrumb
import io.github.tracebox.api.GeneratedHandledError
import io.github.tracebox.api.HandledErrorSeverity
import io.github.tracebox.api.PackagePreparationResult
import io.github.tracebox.api.PolicyRejectionReason
import io.github.tracebox.api.PolicyUpdateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolatileTraceboxHandleTest {
    @Test
    fun disabledProfileClearsAndBlocksVolatileRecords() {
        val handle = newStandardHandle()
        handle.diagnostics.breadcrumb(GeneratedBreadcrumb(DiagnosticCode.of(7)))

        assertEquals(1, handle.delete(DeleteRequest.All).recordsRemoved)

        handle.diagnostics.breadcrumb(GeneratedBreadcrumb(DiagnosticCode.of(8)))
        handle.updateProfile(DiagnosticsProfile.Disabled)
        handle.diagnostics.breadcrumb(GeneratedBreadcrumb(DiagnosticCode.of(9)))

        assertEquals(0, handle.delete(DeleteRequest.All).recordsRemoved)
    }

    @Test
    fun packageCreationFailsExplicitlyUntilItsCorrectnessGatesExist() {
        val handle = newStandardHandle()

        assertTrue(handle.packages.prepare() is PackagePreparationResult.Unavailable)
    }

    @Test
    fun enhancedCaptureIsRejectedInsteadOfImplyingRawArtifactSupport() {
        val handle = newStandardHandle()

        val result = handle.updateProfile(DiagnosticsProfile.EnhancedDiagnosticSession.forSeconds(60))

        assertEquals(
            PolicyUpdateResult.Rejected(PolicyRejectionReason.UNSUPPORTED_BY_THIS_ALPHA),
            result,
        )
    }

    @Test
    fun generatedEventsRequireTheExplicitStandardProfile() {
        val handle = VolatileTraceboxHandle(TraceboxConfiguration.builder().build())

        handle.diagnostics.breadcrumb(GeneratedBreadcrumb(DiagnosticCode.of(10)))
        assertEquals(0, handle.delete(DeleteRequest.All).recordsRemoved)

        handle.updateProfile(DiagnosticsProfile.StandardDiagnostics)
        handle.diagnostics.breadcrumb(GeneratedBreadcrumb(DiagnosticCode.of(11)))
        handle.diagnostics.handled(
            GeneratedHandledError(DiagnosticCode.of(12), HandledErrorSeverity.ERROR),
        )
        assertEquals(2, handle.delete(DeleteRequest.All).recordsRemoved)

        handle.diagnostics.breadcrumb(GeneratedBreadcrumb(DiagnosticCode.of(13)))
        handle.updateProfile(DiagnosticsProfile.MinimalCrash)
        assertEquals(0, handle.delete(DeleteRequest.All).recordsRemoved)
    }

    @Test
    fun bufferIsBoundedAndCloseStopsFutureWrites() {
        val handle = VolatileTraceboxHandle(
            TraceboxConfiguration.builder()
                .setInitialProfile(DiagnosticsProfile.StandardDiagnostics)
                .setVolatileRecordCapacity(TraceboxConfiguration.MIN_VOLATILE_RECORD_CAPACITY)
                .build(),
        )

        repeat(TraceboxConfiguration.MIN_VOLATILE_RECORD_CAPACITY + 1) { index ->
            handle.diagnostics.breadcrumb(GeneratedBreadcrumb(DiagnosticCode.of(index + 1)))
        }
        assertEquals(
            TraceboxConfiguration.MIN_VOLATILE_RECORD_CAPACITY,
            handle.delete(DeleteRequest.All).recordsRemoved,
        )

        handle.diagnostics.breadcrumb(GeneratedBreadcrumb(DiagnosticCode.of(99)))
        handle.close()
        handle.diagnostics.breadcrumb(GeneratedBreadcrumb(DiagnosticCode.of(100)))

        assertEquals(0, handle.delete(DeleteRequest.All).recordsRemoved)
        assertEquals(
            PolicyUpdateResult.Rejected(PolicyRejectionReason.HANDLE_CLOSED),
            handle.updateProfile(DiagnosticsProfile.StandardDiagnostics),
        )
    }

    private fun newStandardHandle(): VolatileTraceboxHandle =
        VolatileTraceboxHandle(
            TraceboxConfiguration.builder()
                .setInitialProfile(DiagnosticsProfile.StandardDiagnostics)
                .build(),
        )
}
