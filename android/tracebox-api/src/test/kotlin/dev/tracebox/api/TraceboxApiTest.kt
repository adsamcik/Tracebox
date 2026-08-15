package dev.tracebox.api

import dev.tracebox.api.generated.GeneratedDiagnostics
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun log_templates_enforce_the_public_bounds_at_construction() {
        assertEquals("Static {}", LogTemplate.of("Static {}").value)

        assertFailsWith<IllegalArgumentException> {
            LogTemplate.of("x".repeat(LogTemplate.MAX_UTF8_BYTES + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            LogTemplate.of("{}".repeat(LogTemplate.MAX_ARGUMENTS + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            LogTemplate.of("line one\nline two")
        }
    }

    @Test
    fun privacy_configuration_equivalence_compares_adapter_behavior() {
        val piiString = PrivacyConfiguration.Builder()
            .register(String::class.java, Privacy.PII)
            .build()
        val samePiiString = PrivacyConfiguration.Builder()
            .register(String::class.java, Privacy.PII)
            .build()
        val secretString = PrivacyConfiguration.Builder()
            .register(String::class.java, Privacy.SECRET)
            .build()

        assertTrue(piiString.isEquivalentForInstallation(samePiiString))
        assertFalse(piiString.isEquivalentForInstallation(secretString))
        assertFalse(piiString.isEquivalentForInstallation(PrivacyConfiguration.defaults()))
    }

    @Test
    fun privacy_configuration_equivalence_requires_renderer_identity_and_adapter_order() {
        val sharedRenderer = PrivacyRenderer<Number> { it.toLong().toString() }
        val first = PrivacyConfiguration.Builder()
            .register(Number::class.java, Privacy.PUBLIC, sharedRenderer)
            .register(Int::class.java, Privacy.PII)
            .build()
        val same = PrivacyConfiguration.Builder()
            .register(Number::class.java, Privacy.PUBLIC, sharedRenderer)
            .register(Int::class.java, Privacy.PII)
            .build()
        val reordered = PrivacyConfiguration.Builder()
            .register(Int::class.java, Privacy.PII)
            .register(Number::class.java, Privacy.PUBLIC, sharedRenderer)
            .build()
        val differentRenderer = PrivacyConfiguration.Builder()
            .register(Number::class.java, Privacy.PUBLIC) { it.toLong().toString() }
            .register(Int::class.java, Privacy.PII)
            .build()

        assertTrue(first.isEquivalentForInstallation(same))
        assertFalse(first.isEquivalentForInstallation(reordered))
        assertFalse(first.isEquivalentForInstallation(differentRenderer))
    }

    private class FakeDiagnostics(private val enabled: Boolean) : Diagnostics {
        var recordCount = 0
        override fun eventEnabled(eventId: GeneratedEventId) = enabled
        override fun record(value: GeneratedRecord, context: DiagnosticContext?) {
            recordCount += 1
        }
    }
}
