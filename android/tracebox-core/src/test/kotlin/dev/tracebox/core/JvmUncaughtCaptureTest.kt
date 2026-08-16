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

    @Test fun repeated_out_of_memory_failures_use_the_prebuilt_reduced_record_and_chain_every_time() {
        val previousCalls = AtomicInteger()
        val records = mutableListOf<JvmCrashRecord>()
        val handler = TraceboxUncaughtExceptionHandler(
            Thread.UncaughtExceptionHandler { _, _ -> previousCalls.incrementAndGet() },
            JvmCapturePolicy(),
            records::add,
        )

        repeat(32) {
            handler.uncaughtException(Thread.currentThread(), OutOfMemoryError("must not persist"))
        }

        assertEquals(32, previousCalls.get())
        assertEquals(32, records.size)
        assertTrue(records.all { it === records.first() })
        assertEquals(JvmFatalKind.OUT_OF_MEMORY, records.first().fatalKind)
        assertTrue(records.first().reduced)
        assertTrue(records.first().causes.single().frames.isEmpty())
        assertFalse(records.first().toString().contains("must not persist"))
    }

    @Test fun stack_overflow_capture_is_bounded_and_classified() {
        val records = mutableListOf<JvmCrashRecord>()
        val overflow = StackOverflowError("must not persist").apply {
            stackTrace = Array(512) { index ->
                StackTraceElement("example.Type$index", "call", "Source.kt", index)
            }
        }
        val handler = TraceboxUncaughtExceptionHandler(
            null,
            JvmCapturePolicy(maxFramesPerCause = 7),
            records::add,
        )

        handler.uncaughtException(Thread.currentThread(), overflow)

        val record = records.single()
        assertEquals(JvmFatalKind.STACK_OVERFLOW, record.fatalKind)
        assertFalse(record.reduced)
        assertEquals(7, record.causes.single().frames.size)
        assertFalse(record.toString().contains("must not persist"))
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
