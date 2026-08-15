package dev.tracebox.api

import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/** Runtime log threshold ordered from most to least verbose. */
enum class LogLevel(val wireCode: UInt) {
    VERBOSE(0u),
    DEBUG(1u),
    INFO(2u),
    WARN(3u),
    ERROR(4u),
    OFF(5u),
}

/** A log category is independently controllable without inventing another logging subsystem. */
enum class LogCategory(val wireCode: UInt) {
    GENERAL(0u),
    PERFORMANCE(1u),
}

/** Privacy classification applied before a parameter can reach Logcat or durable storage. */
enum class Privacy {
    PUBLIC,
    SENSITIVE,
    PII,
    SECRET,
}

/** Explicit per-value privacy override for a parameterized log call. */
class LogArgument internal constructor(
    internal val value: Any?,
    internal val privacyOverride: Privacy?,
)

/**
 * Immutable developer-authored text for a parameterized log event.
 *
 * Create templates once in an `object` or `companion object` with [of]. The Tracebox lint rule
 * rejects runtime-computed values passed to [of], keeping user or tracked data out of the template
 * and therefore subject to [LogArgument] privacy handling.
 */
class LogTemplate private constructor(
    val value: String,
) {
    companion object {
        const val MAX_ARGUMENTS: Int = 16
        const val MAX_UTF8_BYTES: Int = 512

        @JvmStatic
        fun of(value: String): LogTemplate {
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_UTF8_BYTES) {
                "log template exceeds $MAX_UTF8_BYTES UTF-8 bytes"
            }
            require(value.windowed(size = 2, step = 1).count { it == "{}" } <= MAX_ARGUMENTS) {
                "log template exceeds $MAX_ARGUMENTS arguments"
            }
            require(value.none { it == '\r' || it == '\n' || (it.isISOControl() && it != '\t') }) {
                "log template contains a forbidden control character"
            }
            return LogTemplate(value)
        }
    }
}

fun public(value: Any?): LogArgument = LogArgument(value, Privacy.PUBLIC)
fun sensitive(value: Any?): LogArgument = LogArgument(value, Privacy.SENSITIVE)
fun pii(value: Any?): LogArgument = LogArgument(value, Privacy.PII)
fun secret(value: Any?): LogArgument = LogArgument(value, Privacy.SECRET)
/** Classifies through the installed adapter, falling back to Tracebox's fail-closed defaults. */
fun argument(value: Any?): LogArgument = LogArgument(value, null)

/** Converts one registered domain value to bounded log text after classification. */
fun interface PrivacyRenderer<T : Any> {
    fun render(value: T): String
}

/** Immutable application privacy rules captured at Tracebox installation. */
class PrivacyConfiguration private constructor(
    private val adapters: List<Adapter<*>>,
) {
    data class Rendered(
        val text: String,
        val privacy: Privacy,
        val transformed: Boolean,
    )

    fun render(argument: LogArgument): Rendered {
        val value = argument.value
        val adapter = value?.let { candidate ->
            adapters.firstOrNull { it.type.isAssignableFrom(candidate.javaClass) }
        }
        val classification = argument.privacyOverride
            ?: adapter?.privacy
            ?: defaultPrivacy(value)
        return when (classification) {
            Privacy.SENSITIVE -> Rendered(SENSITIVE, Privacy.SENSITIVE, transformed = true)
            Privacy.PII -> Rendered(REDACTED, Privacy.PII, transformed = true)
            Privacy.SECRET -> Rendered(SECRET, Privacy.SECRET, transformed = true)
            Privacy.PUBLIC -> Rendered(
                text = renderValue(value, adapter),
                privacy = classification,
                transformed = false,
            )
        }
    }

    /**
     * Returns whether two configurations are provably identical for process-install reuse.
     *
     * Adapter order is significant because rendering selects the first assignable adapter. Types
     * and privacy classes are compared structurally. Custom renderers can contain arbitrary
     * application state, so renderer-backed adapters are equivalent only when they reuse the exact
     * [PrivacyRenderer] instance.
     */
    fun isEquivalentForInstallation(other: PrivacyConfiguration): Boolean {
        if (this === other) return true
        return adapters.size == other.adapters.size &&
            adapters.indices.all { index -> adapters[index].isEquivalentTo(other.adapters[index]) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderValue(value: Any?, adapter: Adapter<*>?): String {
        val renderer = adapter?.renderer
        return when {
            value == null -> "null"
            renderer != null -> (renderer as PrivacyRenderer<Any>).render(value)
            else -> value.toString()
        }
    }

    class Builder {
        private val adapters = linkedMapOf<Class<*>, Adapter<*>>()

        fun <T : Any> register(type: Class<T>, privacy: Privacy) = apply {
            require(privacy != Privacy.PUBLIC) {
                "public adapters require an explicit bounded renderer"
            }
            require(type !in adapters) { "privacy adapter already registered for ${type.name}" }
            adapters[type] = Adapter<T>(type, privacy, null)
        }

        fun <T : Any> register(
            type: Class<T>,
            privacy: Privacy,
            renderer: PrivacyRenderer<T>,
        ) = apply {
            require(type !in adapters) { "privacy adapter already registered for ${type.name}" }
            adapters[type] = Adapter(type, privacy, renderer)
        }

        fun build(): PrivacyConfiguration = PrivacyConfiguration(adapters.values.toList())
    }

    private data class Adapter<T : Any>(
        val type: Class<T>,
        val privacy: Privacy,
        val renderer: PrivacyRenderer<T>?,
    ) {
        fun isEquivalentTo(other: Adapter<*>): Boolean =
            type == other.type && privacy == other.privacy && renderer === other.renderer
    }

    companion object {
        private const val REDACTED = "[redacted]"
        private const val SENSITIVE = "[sensitive]"
        private const val SECRET = "[secret]"

        fun defaults(): PrivacyConfiguration = Builder().build()

        private fun defaultPrivacy(value: Any?): Privacy = when (value) {
            null, is Number, is Boolean, is Char, is Enum<*> -> Privacy.PUBLIC
            else -> Privacy.PII
        }
    }
}

inline fun <reified T : Any> PrivacyConfiguration.Builder.register(
    privacy: Privacy,
    noinline renderer: (T) -> String,
): PrivacyConfiguration.Builder = register(T::class.java, privacy, PrivacyRenderer(renderer))

inline fun <reified T : Any> PrivacyConfiguration.Builder.register(
    privacy: Privacy,
): PrivacyConfiguration.Builder = register(T::class.java, privacy)

/** Independently switchable capture sources. Native entries are inert without tracebox-native. */
enum class CaptureKind {
    JVM_CRASH,
    HANDLED_EXCEPTION,
    ANR,
    OS_EXIT,
    NATIVE_CRASH,
    RUST_PANIC,
}

/** Complete persisted runtime policy shared by managed and installed native participants. */
data class TraceboxPolicy(
    val enabled: Boolean = true,
    val minimumLogLevel: LogLevel = LogLevel.INFO,
    val mirrorToLogcat: Boolean = false,
    val performanceLoggingEnabled: Boolean = false,
    val minimumPerformanceDurationNanos: Long = 0L,
    val captures: Set<CaptureKind> = CaptureKind.entries.toSet(),
) {
    init {
        require(minimumPerformanceDurationNanos >= 0L)
    }

    fun accepts(level: LogLevel, category: LogCategory = LogCategory.GENERAL): Boolean =
        enabled && when (category) {
            LogCategory.GENERAL ->
                level != LogLevel.OFF && level.ordinal >= minimumLogLevel.ordinal
            LogCategory.PERFORMANCE -> performanceLoggingEnabled
        }

    companion object {
        fun disabled(): TraceboxPolicy = TraceboxPolicy(enabled = false, minimumLogLevel = LogLevel.OFF)
        fun standard(): TraceboxPolicy = TraceboxPolicy()
        fun debug(): TraceboxPolicy = TraceboxPolicy(
            minimumLogLevel = LogLevel.DEBUG,
            mirrorToLogcat = true,
            performanceLoggingEnabled = true,
        )
    }
}

/** A manual monotonic performance measurement. Completing it more than once is harmless. */
interface PerformanceMeasurement : Closeable {
    fun success()
    fun failure()
    fun cancelled()
    override fun close() = success()
}

/**
 * Parameterized, privacy-aware logging surface.
 *
 * Templates are public static text; runtime values belong in [arguments] so privacy rules can be
 * applied before Logcat or durable storage.
 */
interface TraceboxLogger {
    fun isEnabled(level: LogLevel, category: LogCategory = LogCategory.GENERAL): Boolean

    fun log(level: LogLevel, template: LogTemplate, vararg arguments: LogArgument)

    fun verbose(template: LogTemplate, vararg arguments: LogArgument) =
        log(LogLevel.VERBOSE, template, *arguments)

    fun debug(template: LogTemplate, vararg arguments: LogArgument) =
        log(LogLevel.DEBUG, template, *arguments)

    fun info(template: LogTemplate, vararg arguments: LogArgument) =
        log(LogLevel.INFO, template, *arguments)

    fun warn(template: LogTemplate, vararg arguments: LogArgument) =
        log(LogLevel.WARN, template, *arguments)

    fun error(template: LogTemplate, vararg arguments: LogArgument) =
        log(LogLevel.ERROR, template, *arguments)

    fun error(throwable: Throwable, template: LogTemplate, vararg arguments: LogArgument)

    fun performanceStart(template: LogTemplate, vararg arguments: LogArgument): PerformanceMeasurement

    fun <T> performance(template: LogTemplate, vararg arguments: LogArgument, block: () -> T): T {
        val measurement = performanceStart(template, *arguments)
        return try {
            block().also { measurement.success() }
        } catch (cancelled: CancellationException) {
            measurement.cancelled()
            throw cancelled
        } catch (error: Throwable) {
            measurement.failure()
            throw error
        }
    }

    suspend fun <T> performanceSuspend(
        template: LogTemplate,
        vararg arguments: LogArgument,
        block: suspend () -> T,
    ): T {
        val measurement = performanceStart(template, *arguments)
        return try {
            block().also { measurement.success() }
        } catch (cancelled: CancellationException) {
            measurement.cancelled()
            throw cancelled
        } catch (error: Throwable) {
            measurement.failure()
            throw error
        }
    }
}

/** Automatic fatal capture plus an explicit one-call handled-exception surface. */
interface CrashReporter {
    fun record(throwable: Throwable)
    fun record(throwable: Throwable, template: LogTemplate, vararg arguments: LogArgument)

    fun coroutineExceptionHandler(): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _: CoroutineContext, throwable: Throwable -> record(throwable) }
}

/** Lightweight facts used by the reusable diagnostics UI. */
data class DiagnosticSummary(
    val recordedValueCount: Long = 0,
    val lastRecordedAtMillis: Long? = null,
)
