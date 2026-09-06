package dev.tracebox

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExceptionStructureTest {
    @Test fun fatalCaptureRetainsCauseFramesAndCycleMarker() {
        val frame = dev.tracebox.core.JvmCrashFrame("Codec", "decode", 12)
        val structure = fatalExceptionStructure(listOf(
            dev.tracebox.core.JvmCrashCause("java.lang.IllegalStateException", List(100) { frame }, false),
            dev.tracebox.core.JvmCrashCause("java.lang.IllegalArgumentException", listOf(frame), true),
        ))
        assertTrue(structure.stack.contains("cause java.lang.IllegalArgumentException [cycle]"))
        assertTrue(structure.frameCount <= 64)
        assertTrue(structure.stack.toByteArray().size <= 2_048)
    }

    @Test fun retainsCausesAndSuppressedFramesWithoutMessagesOrPaths() {
        val cause = IllegalArgumentException("https://private.example/secret")
        cause.stackTrace = arrayOf(StackTraceElement("Codec", "decode", "/private/media", 12))
        val root = IllegalStateException("token=secret", cause)
        root.addSuppressed(UnsupportedOperationException("private suppressed message"))
        val structure = exceptionStructure(root)
        assertTrue(structure.stack.contains("cause java.lang.IllegalArgumentException"))
        assertTrue(structure.stack.contains("Codec.decode:12"))
        assertTrue(structure.stack.contains("suppressed java.lang.UnsupportedOperationException"))
        assertFalse(structure.stack.contains("secret"))
        assertFalse(structure.stack.contains("private"))
        assertTrue(structure.frameCount <= 64)
        assertTrue(structure.stack.toByteArray().size <= 2_048)
    }

    @Test fun cyclicCausesAndLargeSuppressedGraphsStayBounded() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        repeat(100) { first.addSuppressed(Exception("private")) }
        val structure = exceptionStructure(first)
        assertTrue(structure.frameCount <= 64)
        assertTrue(structure.stack.toByteArray().size <= 2_048)
    }
}
