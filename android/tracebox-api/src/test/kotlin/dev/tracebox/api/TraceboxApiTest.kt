package dev.tracebox.api

import dev.tracebox.api.generated.GeneratedDiagnostics
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceboxApiTest {
    @Test
    fun breadcrumb_does_not_construct_a_value_when_disabled() {
        val diagnostics = FakeDiagnostics(false)
        GeneratedDiagnostics.breadcrumb(diagnostics, 1u, 1uL)
        assertEquals(0, diagnostics.recordCount)
    }

    @Test
    fun generated_values_record_only_after_enablement() {
        val diagnostics = FakeDiagnostics(true)
        GeneratedDiagnostics.handledError(diagnostics, 1u, 2u)
        assertEquals(1, diagnostics.recordCount)
    }

    @Test
    fun policy_update_result_preserves_the_published_v1_ordinals() {
        assertEquals(0, PolicyUpdateResult.SUCCESS.ordinal)
        assertEquals(1, PolicyUpdateResult.LOCAL_ONLY_RESTRICTED.ordinal)
        assertEquals(2, PolicyUpdateResult.PARTIAL.ordinal)
        assertEquals(3, PolicyUpdateResult.FAILED.ordinal)
    }

    private class FakeDiagnostics(private val enabled: Boolean) : Diagnostics {
        var recordCount = 0
        override fun eventEnabled(eventId: GeneratedEventId) = enabled
        override fun record(value: GeneratedRecord, context: DiagnosticContext?) {
            recordCount += 1
        }
    }
}
