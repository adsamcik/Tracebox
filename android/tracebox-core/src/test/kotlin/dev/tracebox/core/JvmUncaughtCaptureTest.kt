package dev.tracebox.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmUncaughtCaptureTest {
    @Test fun excludes_messages_bounds_frames_and_chains_prior_handler_exactly_once() {
        val previousCalls = AtomicInteger()
        val records = mutableListOf<JvmCrashRecord>()
        val previous = Thread.UncaughtExceptionHandler { _, _ -> previousCalls.incrementAndGet() }
        val handler = TraceboxUncaughtExceptionHandler(previous, JvmCapturePolicy(maxCauses = 2, maxFramesPerCause = 3)) {
            records += it
        }
        val root = IllegalStateException("secret-message", RuntimeException("nested"))

        handler.uncaughtException(Thread.currentThread(), root)

        assertEquals(1, previousCalls.get())
        assertEquals(1, records.size)
        assertFalse(records.single().toString().contains("secret-message"))
        assertFalse(records.single().toString().contains("nested"))
        assertTrue(records.single().causes.all { it.frames.size <= 3 })
    }

    @Test fun detects_cycles_and_never_recurses_when_recorder_fails() {
        val previousCalls = AtomicInteger()
        val handler = TraceboxUncaughtExceptionHandler(
            Thread.UncaughtExceptionHandler { _, _ -> previousCalls.incrementAndGet() },
            JvmCapturePolicy(),
        ) { throw IllegalStateException("recording failure") }
        val cyclic = Throwable("a")
        val nested = Throwable("b")
        cyclic.initCause(nested)
        nested.initCause(cyclic)

        handler.uncaughtException(Thread.currentThread(), cyclic)

        assertEquals(1, previousCalls.get())
    }

    @Test fun prior_handler_is_still_invoked_exactly_once_when_capture_sink_throws_an_error() {
        val previousCalls = AtomicInteger()
        val handler = TraceboxUncaughtExceptionHandler(
            Thread.UncaughtExceptionHandler { _, _ -> previousCalls.incrementAndGet() },
            JvmCapturePolicy(),
        ) { throw AssertionError("simulated fatal capture failure") }

        handler.uncaughtException(Thread.currentThread(), OutOfMemoryError("application failure"))

        assertEquals(1, previousCalls.get())
    }

    @Test fun class_and_method_are_truncated_at_deterministic_utf8_boundaries() {
        val records = mutableListOf<JvmCrashRecord>()
        val throwable = IllegalStateException("ééé")
        throwable.stackTrace = arrayOf(
            StackTraceElement("ééé.ClassName", "method-ééé", "Source.kt", 7),
        )
        val handler = TraceboxUncaughtExceptionHandler(
            null,
            JvmCapturePolicy(
                maxClassNameUtf8Bytes = 5,
                maxMethodNameUtf8Bytes = 9,
            ),
            records::add,
        )

        handler.uncaughtException(Thread.currentThread(), throwable)

        val cause = records.single().causes.single()
        val frame = cause.frames.single()
        assertTrue(cause.type.toByteArray(Charsets.UTF_8).size <= 5)
        assertEquals("éé", frame.declaringClass)
        assertEquals("method-é", frame.method)
        assertEquals(9, frame.method.toByteArray(Charsets.UTF_8).size)
        assertFalse(cause.type.contains('\uFFFD'))
        assertFalse(frame.declaringClass.contains('\uFFFD'))
        assertFalse(frame.method.contains('\uFFFD'))
        assertFalse(records.single().toString().contains("ééé"))
    }
}
