package dev.tracebox

import android.os.SystemClock
import android.util.Log
import dev.tracebox.api.CaptureKind
import dev.tracebox.api.CrashReporter
import dev.tracebox.api.LogCategory
import dev.tracebox.api.LogArgument
import dev.tracebox.api.LogLevel
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.PerformanceMeasurement
import dev.tracebox.api.Privacy
import dev.tracebox.api.PrivacyConfiguration
import dev.tracebox.api.TraceboxLogger
import dev.tracebox.api.TraceboxPolicy
import dev.tracebox.api.generated.GeneratedExceptionRecord
import dev.tracebox.api.generated.GeneratedLogRecord
import dev.tracebox.api.generated.GeneratedRecord
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow

internal class RuntimeTraceboxLogger(
    private val policy: StateFlow<TraceboxPolicy>,
    private val privacy: PrivacyConfiguration,
    private val record: (GeneratedRecord) -> Unit,
    private val reportCrash: (Throwable) -> Unit,
    private val monotonicNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val mirrorSink: (LogLevel, String) -> Unit = ::mirrorToAndroidLog,
) : TraceboxLogger {
    override fun isEnabled(level: LogLevel, category: LogCategory): Boolean =
        policy.value.accepts(level, category)

    override fun log(level: LogLevel, template: LogTemplate, vararg arguments: LogArgument) {
        recordLog(level, LogCategory.GENERAL, template, arguments, durationNanos = 0L, outcome = OUTCOME_NONE)
    }

    override fun error(throwable: Throwable, template: LogTemplate, vararg arguments: LogArgument) {
        recordLog(LogLevel.ERROR, LogCategory.GENERAL, template, arguments, 0L, OUTCOME_FAILURE)
        reportCrash(throwable)
    }

    override fun performanceStart(
        template: LogTemplate,
        vararg arguments: LogArgument,
    ): PerformanceMeasurement {
        val startingPolicy = policy.value
        if (!startingPolicy.accepts(LogLevel.DEBUG, LogCategory.PERFORMANCE)) {
            return NoOpPerformanceMeasurement
        }
        val prepared = prepare(template, arguments)
        val startedAt = monotonicNanos()
        return RuntimePerformanceMeasurement { outcome ->
            val duration = (monotonicNanos() - startedAt).coerceAtLeast(0L)
            val current = policy.value
            if (current.accepts(LogLevel.DEBUG, LogCategory.PERFORMANCE) &&
                duration >= current.minimumPerformanceDurationNanos
            ) {
                recordPrepared(
                    level = LogLevel.DEBUG,
                    category = LogCategory.PERFORMANCE,
                    prepared = prepared,
                    durationNanos = duration,
                    outcome = outcome,
                )
            }
        }
    }

    internal fun recordContext(
        template: LogTemplate,
        arguments: Array<out LogArgument>,
    ) {
        recordLog(LogLevel.ERROR, LogCategory.GENERAL, template, arguments, 0L, OUTCOME_FAILURE)
    }

    private fun recordLog(
        level: LogLevel,
        category: LogCategory,
        template: LogTemplate,
        arguments: Array<out LogArgument>,
        durationNanos: Long,
        outcome: UInt,
    ) {
        if (!isEnabled(level, category)) return
        recordPrepared(level, category, prepare(template, arguments), durationNanos, outcome)
    }

    private fun recordPrepared(
        level: LogLevel,
        category: LogCategory,
        prepared: PreparedLog,
        durationNanos: Long,
        outcome: UInt,
    ) {
        val current = policy.value
        if (!current.accepts(level, category)) return
        if (current.mirrorToLogcat) mirrorSink(level, prepared.rendered)
        record(
            GeneratedLogRecord(
                level = level.wireCode,
                category = category.wireCode,
                template_fingerprint = prepared.templateFingerprint.toULong(),
                rendered_message = prepared.rendered,
                privacy_flags = prepared.privacyFlags,
                monotonic_time_ns = monotonicNanos().toULong(),
                duration_ns = durationNanos.toULong(),
                outcome = outcome,
            ),
        )
    }

    private fun prepare(template: LogTemplate, arguments: Array<out LogArgument>): PreparedLog {
        val boundedTemplate = template.value
        val rendered = arguments.take(MAX_ARGUMENTS).map(privacy::render)
        var privacyFlags = 0u
        rendered.forEach {
            privacyFlags = privacyFlags or when (it.privacy) {
                Privacy.PUBLIC -> 0u
                Privacy.SENSITIVE -> FLAG_SENSITIVE
                Privacy.PII -> FLAG_PII_REDACTED
                Privacy.SECRET -> FLAG_SECRET_DROPPED
            }
        }
        val message = StringBuilder(boundedTemplate.length + rendered.sumOf { it.text.length })
        var cursor = 0
        var argumentIndex = 0
        while (cursor < boundedTemplate.length) {
            val placeholder = boundedTemplate.indexOf("{}", cursor)
            if (placeholder < 0) {
                message.append(boundedTemplate, cursor, boundedTemplate.length)
                break
            }
            message.append(boundedTemplate, cursor, placeholder)
            if (argumentIndex < rendered.size) {
                message.append(rendered[argumentIndex++].text)
            } else {
                message.append("{}")
            }
            cursor = placeholder + 2
        }
        while (argumentIndex < rendered.size) {
            message.append(" [arg").append(argumentIndex).append('=')
                .append(rendered[argumentIndex].text).append(']')
            argumentIndex += 1
        }
        if (arguments.size > MAX_ARGUMENTS) {
            message.append(" [additional arguments omitted]")
            privacyFlags = privacyFlags or FLAG_ARGUMENTS_OMITTED
        }
        return PreparedLog(
            rendered = truncateUtf8(message.toString(), MAX_MESSAGE_BYTES),
            templateFingerprint = fingerprint64(boundedTemplate),
            privacyFlags = privacyFlags,
        )
    }

    private data class PreparedLog(
        val rendered: String,
        val templateFingerprint: Long,
        val privacyFlags: UInt,
    )

    private companion object {
        const val MAX_ARGUMENTS = LogTemplate.MAX_ARGUMENTS
        const val MAX_MESSAGE_BYTES = 1_024
        const val OUTCOME_NONE = 0u
        const val OUTCOME_SUCCESS = 1u
        const val OUTCOME_FAILURE = 2u
        const val OUTCOME_CANCELLED = 3u
        const val FLAG_SENSITIVE = 1u
        const val FLAG_PII_REDACTED = 2u
        const val FLAG_SECRET_DROPPED = 4u
        const val FLAG_ARGUMENTS_OMITTED = 8u
    }

    private object NoOpPerformanceMeasurement : PerformanceMeasurement {
        override fun success() = Unit
        override fun failure() = Unit
        override fun cancelled() = Unit
    }

    private class RuntimePerformanceMeasurement(
        private val complete: (UInt) -> Unit,
    ) : PerformanceMeasurement {
        private val completed = AtomicBoolean(false)

        override fun success() = finish(OUTCOME_SUCCESS)
        override fun failure() = finish(OUTCOME_FAILURE)
        override fun cancelled() = finish(OUTCOME_CANCELLED)

        private fun finish(outcome: UInt) {
            if (completed.compareAndSet(false, true)) complete(outcome)
        }
    }
}

private fun mirrorToAndroidLog(level: LogLevel, message: String) {
    when (level) {
        LogLevel.VERBOSE -> Log.v("Tracebox", message)
        LogLevel.DEBUG -> Log.d("Tracebox", message)
        LogLevel.INFO -> Log.i("Tracebox", message)
        LogLevel.WARN -> Log.w("Tracebox", message)
        LogLevel.ERROR -> Log.e("Tracebox", message)
        LogLevel.OFF -> Unit
    }
}

internal class RuntimeCrashReporter(
    private val policy: StateFlow<TraceboxPolicy>,
    private val record: (GeneratedExceptionRecord) -> Unit,
    private val recordContext: (LogTemplate, Array<out LogArgument>) -> Unit,
) : CrashReporter {
    override fun record(throwable: Throwable) {
        if (!policy.value.enabled || CaptureKind.HANDLED_EXCEPTION !in policy.value.captures) return
        record(exceptionRecord(throwable, fatal = false))
    }

    override fun record(
        throwable: Throwable,
        template: LogTemplate,
        vararg arguments: LogArgument,
    ) {
        recordContext(template, arguments)
        record(throwable)
    }
}

internal fun exceptionRecord(throwable: Throwable, fatal: Boolean): GeneratedExceptionRecord {
    val frames = throwable.stackTrace.take(MAX_EXCEPTION_FRAMES)
    val stack = frames.joinToString("\n") { frame ->
        "${frame.className}.${frame.methodName}:${frame.lineNumber}"
    }
    val boundedStack = truncateUtf8(stack, MAX_STACK_BYTES)
    return GeneratedExceptionRecord(
        kind = if (fatal) 1u else 0u,
        exception_type = truncateUtf8(throwable.javaClass.name, MAX_EXCEPTION_TYPE_BYTES),
        frame_count = frames.size.toUShort(),
        stack_fingerprint = fingerprint64("${throwable.javaClass.name}\n$boundedStack").toULong(),
        stack_trace = boundedStack,
        monotonic_time_ns = SystemClock.elapsedRealtimeNanos().toULong(),
    )
}

internal fun truncateUtf8(value: String, maximumBytes: Int): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maximumBytes) return value
    var end = maximumBytes
    while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end -= 1
    return bytes.copyOf(end).toString(Charsets.UTF_8)
}

internal fun fingerprint64(value: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    var result = 0L
    repeat(Long.SIZE_BYTES) { index ->
        result = (result shl 8) or (digest[index].toLong() and 0xffL)
    }
    return result
}

private const val MAX_EXCEPTION_FRAMES = 64
private const val MAX_EXCEPTION_TYPE_BYTES = 256
private const val MAX_STACK_BYTES = 2_048
