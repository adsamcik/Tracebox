package dev.tracebox.api

import kotlin.test.Test
import kotlin.test.assertEquals

class TraceboxApiTest {
    @Test
    fun breadcrumb_does_not_construct_a_value_when_disabled() {
        val diagnostics = FakeDiagnostics(false, false)
        GeneratedDiagnostics.breadcrumb(diagnostics, BreadcrumbCode.NAVIGATION, 1)
        assertEquals(0, diagnostics.breadcrumbCount)
    }

    @Test
    fun handled_constructs_only_after_enablement() {
        val diagnostics = FakeDiagnostics(false, true)
        GeneratedDiagnostics.handled(diagnostics, HandledErrorKind.EXCEPTION, 2u)
        assertEquals(1, diagnostics.handledCount)
    }

    private class FakeDiagnostics(
        private val breadcrumbs: Boolean,
        private val handled: Boolean,
    ) : Diagnostics {
        var breadcrumbCount = 0
        var handledCount = 0
        override fun breadcrumbEnabled() = breadcrumbs
        override fun handledEnabled() = handled
        override fun breadcrumb(value: GeneratedBreadcrumb, context: DiagnosticContext?) {
            breadcrumbCount += 1
        }
        override fun handled(value: GeneratedHandledError, throwable: Throwable?) {
            handledCount += 1
        }
    }
}
