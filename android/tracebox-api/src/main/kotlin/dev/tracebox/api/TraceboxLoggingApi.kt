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

fun public(value: Any?): LogArgument = LogArgument(value, Privacy.PUBLIC)
fun sensitive(value: Any?): LogArgument = LogArgument(value, Privacy.SENSITIVE)
fun pii(value: Any?): LogArgument = LogArgument(value, Privacy.PII)
fun secret(value: Any?): LogArgument = LogArgument(value, Privacy.SECRET)
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

    fun render(argument: Any?): Rendered {
        val explicit = argument as? LogArgument
        val value = explicit?.value ?: argument
        val adapter = value?.let { candidate ->
            adapters.firstOrNull { it.type.isAssignableFrom(candidate.javaClass) }
        }
        val classification = explicit?.privacyOverride
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
     * Empty configurations are structurally equivalent. Custom renderers can contain arbitrary
     * application state, so Tracebox fails closed and accepts them only when the exact immutable
     * [PrivacyConfiguration] instance is reused.
     */
    fun isEquivalentForInstallation(other: PrivacyConfiguration): Boolean =
        this === other || (adapters.isEmpty() && other.adapters.isEmpty())

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
    )

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

    fun log(level: LogLevel, template: String, vararg arguments: Any?)

    fun verbose(template: String, vararg arguments: Any?) =
        log(LogLevel.VERBOSE, template, *arguments)

    fun debug(template: String, vararg arguments: Any?) =
        log(LogLevel.DEBUG, template, *arguments)

    fun info(template: String, vararg arguments: Any?) =
        log(LogLevel.INFO, template, *arguments)

    fun warn(template: String, vararg arguments: Any?) =
        log(LogLevel.WARN, template, *arguments)

    fun error(template: String, vararg arguments: Any?) =
        log(LogLevel.ERROR, template, *arguments)

    fun error(throwable: Throwable, template: String, vararg arguments: Any?)

    fun performanceStart(template: String, vararg arguments: Any?): PerformanceMeasurement

    fun <T> performance(template: String, vararg arguments: Any?, block: () -> T): T {
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
        template: String,
        vararg arguments: Any?,
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
    fun record(throwable: Throwable, template: String, vararg arguments: Any?)

    fun coroutineExceptionHandler(): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _: CoroutineContext, throwable: Throwable -> record(throwable) }
}

/** Lightweight facts used by the reusable diagnostics UI. */
data class DiagnosticSummary(
    val recordedValueCount: Long = 0,
    val lastRecordedAtMillis: Long? = null,
)
