// SPDX-License-Identifier: Apache-2.0

package io.github.tracebox.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TraceboxContractTest {
    @Test
    fun diagnosticCodeRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) { DiagnosticCode.of(0) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticCode.of(1_000_000) }
        assertEquals(42, DiagnosticCode.of(42).value)
    }

    @Test
    fun diagnosticTextIsBoundedAndRedactedFromToString() {
        val value = DiagnosticText.from("safe-detail")

        assertEquals(11, value.length)
        assertFalse(value.toString().contains("safe-detail"))
        assertThrows(IllegalArgumentException::class.java) { DiagnosticText.from("") }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticText.from("a".repeat(129)) }
    }
}

