package dev.tracebox

import dev.tracebox.api.LogCategory
import dev.tracebox.api.LogLevel
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.Privacy
import dev.tracebox.api.PrivacyConfiguration
import dev.tracebox.api.TraceboxPolicy
import dev.tracebox.api.argument
import dev.tracebox.api.generated.GeneratedLogRecord
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.api.public
import dev.tracebox.api.register
import dev.tracebox.api.secret
import dev.tracebox.api.sensitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class RuntimeLoggingTest {
    @Test
    fun parameterFormattingIsBoundedAndPrivacyFirst() {
        val records = mutableListOf<GeneratedRecord>()
        val mirrored = mutableListOf<String>()
        val policy = MutableStateFlow(TraceboxPolicy(minimumLogLevel = LogLevel.VERBOSE, mirrorToLogcat = true))
        val logger = RuntimeTraceboxLogger(
            policy = policy,
            privacy = PrivacyConfiguration.defaults(),
            record = records::add,
            reportCrash = {},
            monotonicNanos = { 42L },
            mirrorSink = { _, message -> mirrored += message },
        )

        logger.info(
            IMPORTED_POINTS,
            public(7),
            argument("private-source"),
            secret("token"),
            sensitive("account"),
        )

        val record = records.single() as GeneratedLogRecord
        assertEquals(
            "Imported 7 points from [redacted] using [secret] for [sensitive]",
            record.rendered_message,
        )
        assertEquals(record.rendered_message, mirrored.single())
        assertFalse(record.rendered_message.contains("private-source"))
        assertFalse(record.rendered_message.contains("token"))
        assertFalse(record.rendered_message.contains("account"))
        assertEquals(42uL, record.monotonic_time_ns)
    }

    @Test
    fun disabledLevelDoesNotRenderArguments() {
        var renderCount = 0
        val privacy = PrivacyConfiguration.Builder()
            .register(Boundary::class.java, Privacy.PUBLIC) {
                renderCount += 1
                it.value
            }
            .build()
        val records = mutableListOf<GeneratedRecord>()
        val logger = RuntimeTraceboxLogger(
            policy = MutableStateFlow(TraceboxPolicy(minimumLogLevel = LogLevel.INFO)),
            privacy = privacy,
            record = records::add,
            reportCrash = {},
            monotonicNanos = { 0L },
            mirrorSink = { _, _ -> },
        )

        logger.debug(BOUNDARY, argument(Boundary("not rendered")))

        assertEquals(0, renderCount)
        assertTrue(records.isEmpty())
    }

    @Test
    fun registeredPrivateTypeNeedsNoRendererAndCannotLeak() {
        val records = mutableListOf<GeneratedRecord>()
        val logger = RuntimeTraceboxLogger(
            policy = MutableStateFlow(TraceboxPolicy()),
            privacy = PrivacyConfiguration.Builder()
                .register<Boundary>(Privacy.PII)
                .build(),
            record = records::add,
            reportCrash = {},
            monotonicNanos = { 0L },
            mirrorSink = { _, _ -> },
        )

        logger.info(BOUNDARY, argument(Boundary("must not leak")))

        assertEquals("Boundary [redacted]", (records.single() as GeneratedLogRecord).rendered_message)
    }

    @Test
    fun performanceRecordsOnlyCompletedMeasurementsAboveThreshold() {
        var now = 1_000L
        val records = mutableListOf<GeneratedRecord>()
        val logger = RuntimeTraceboxLogger(
            policy = MutableStateFlow(
                TraceboxPolicy(
                    minimumLogLevel = LogLevel.OFF,
                    performanceLoggingEnabled = true,
                    minimumPerformanceDurationNanos = 100L,
                ),
            ),
            privacy = PrivacyConfiguration.defaults(),
            record = records::add,
            reportCrash = {},
            monotonicNanos = { now },
            mirrorSink = { _, _ -> },
        )

        logger.performanceStart(FAST, public("path")).also {
            now += 99L
            it.success()
        }
        logger.performanceStart(SLOW, public("path")).also {
            now += 100L
            it.failure()
            it.success()
        }

        val record = records.single() as GeneratedLogRecord
        assertEquals(100uL, record.duration_ns)
        assertEquals(2u, record.outcome)
    }

    @Test
    fun performanceEventIsIndependentlyGatedAndNotDurationFiltered() {
        var renderCount = 0
        val privacy = PrivacyConfiguration.Builder()
            .register(Boundary::class.java, Privacy.PUBLIC) {
                renderCount += 1
                it.value
            }
            .build()
        val policy = MutableStateFlow(
            TraceboxPolicy(
                minimumLogLevel = LogLevel.OFF,
                performanceLoggingEnabled = false,
                minimumPerformanceDurationNanos = Long.MAX_VALUE,
            ),
        )
        val records = mutableListOf<GeneratedRecord>()
        val logger = RuntimeTraceboxLogger(
            policy = policy,
            privacy = privacy,
            record = records::add,
            reportCrash = {},
            monotonicNanos = { 42L },
            mirrorSink = { _, _ -> },
        )

        logger.performanceEvent(OBSERVATION, argument(Boundary("disabled")))
        assertEquals(0, renderCount)
        assertTrue(records.isEmpty())

        policy.value = policy.value.copy(performanceLoggingEnabled = true)
        logger.performanceEvent(OBSERVATION, argument(Boundary("sample")))

        val record = records.single() as GeneratedLogRecord
        assertEquals("Observation sample", record.rendered_message)
        assertEquals(LogCategory.PERFORMANCE.wireCode, record.category)
        assertEquals(0uL, record.duration_ns)
        assertEquals(0u, record.outcome)
        assertEquals(1, renderCount)
    }

    @Test
    fun throwableErrorRecordsContextAndDelegatesCrashOnce() {
        val failure = IllegalStateException("must never be formatted")
        val records = mutableListOf<GeneratedRecord>()
        var reported: Throwable? = null
        val logger = RuntimeTraceboxLogger(
            policy = MutableStateFlow(TraceboxPolicy()),
            privacy = PrivacyConfiguration.defaults(),
            record = records::add,
            reportCrash = { reported = it },
            monotonicNanos = { 1L },
            mirrorSink = { _, _ -> },
        )

        logger.error(failure, OPERATION_FAILED, public("sync"))

        assertSame(failure, reported)
        assertEquals("Operation sync failed", (records.single() as GeneratedLogRecord).rendered_message)
    }

    private data class Boundary(val value: String)

    private companion object {
        val IMPORTED_POINTS = LogTemplate.of("Imported {} points from {} using {} for {}")
        val BOUNDARY = LogTemplate.of("Boundary {}")
        val FAST = LogTemplate.of("Fast {}")
        val SLOW = LogTemplate.of("Slow {}")
        val OBSERVATION = LogTemplate.of("Observation {}")
        val OPERATION_FAILED = LogTemplate.of("Operation {} failed")
    }
}
