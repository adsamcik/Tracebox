// SPDX-License-Identifier: Apache-2.0

package io.github.tracebox

import android.content.Context
import io.github.tracebox.api.DeleteReport
import io.github.tracebox.api.DeleteRequest
import io.github.tracebox.api.DiagnosticContext
import io.github.tracebox.api.DiagnosticPackages
import io.github.tracebox.api.Diagnostics
import io.github.tracebox.api.DiagnosticsProfile
import io.github.tracebox.api.GeneratedBreadcrumb
import io.github.tracebox.api.GeneratedHandledError
import io.github.tracebox.api.PackageCapability
import io.github.tracebox.api.PackagePreparationResult
import io.github.tracebox.api.PackageRequest
import io.github.tracebox.api.PolicyRejectionReason
import io.github.tracebox.api.PolicyUpdateResult
import io.github.tracebox.api.Readiness
import io.github.tracebox.api.TraceboxHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Entry point for the Tracebox alpha runtime.
 *
 * This release intentionally provides bounded volatile structural capture only. It does not yet
 * persist data, capture crashes or ANRs, or create shareable diagnostic packages.
 */
public object Tracebox {
    /** Installs an independent bounded volatile recorder for the calling process. */
    @JvmStatic
    @JvmOverloads
    public fun install(
        context: Context,
        configuration: TraceboxConfiguration = TraceboxConfiguration.builder().build(),
    ): TraceboxHandle {
        checkNotNull(context.applicationContext) { "Tracebox requires an Android application context." }
        return VolatileTraceboxHandle(configuration)
    }
}

/** Configuration for the deliberately bounded alpha recorder. */
public class TraceboxConfiguration private constructor(
    /** Maximum number of structural records retained in volatile memory. */
    public val volatileRecordCapacity: Int,
    /** Initial local recording profile. */
    public val initialProfile: DiagnosticsProfile,
) {
    /** Builder for [TraceboxConfiguration]. */
    public class Builder {
        private var volatileRecordCapacity: Int = DEFAULT_VOLATILE_RECORD_CAPACITY
        private var initialProfile: DiagnosticsProfile = DiagnosticsProfile.MinimalCrash

        /** Sets the fixed in-memory structural-record bound. */
        public fun setVolatileRecordCapacity(capacity: Int): Builder = apply {
            require(capacity in MIN_VOLATILE_RECORD_CAPACITY..MAX_VOLATILE_RECORD_CAPACITY) {
                "Volatile record capacity must be between " +
                    "$MIN_VOLATILE_RECORD_CAPACITY and $MAX_VOLATILE_RECORD_CAPACITY."
            }
            volatileRecordCapacity = capacity
        }

        /**
         * Sets the initial local profile. The privacy-preserving default is
         * [DiagnosticsProfile.MinimalCrash];
         * generated application events require an explicit [DiagnosticsProfile.StandardDiagnostics]
         * selection in this alpha.
         */
        public fun setInitialProfile(profile: DiagnosticsProfile): Builder = apply {
            initialProfile = profile
        }

        /** Creates an immutable configuration. */
        public fun build(): TraceboxConfiguration =
            TraceboxConfiguration(volatileRecordCapacity, initialProfile)
    }

    public companion object {
        /** Default structural-record capacity. */
        public const val DEFAULT_VOLATILE_RECORD_CAPACITY: Int = 64

        /** Lowest supported structural-record capacity. */
        public const val MIN_VOLATILE_RECORD_CAPACITY: Int = 16

        /** Highest supported structural-record capacity. */
        public const val MAX_VOLATILE_RECORD_CAPACITY: Int = 1_024

        /** Starts a configuration builder. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

internal class VolatileTraceboxHandle(
    private val configuration: TraceboxConfiguration,
) : TraceboxHandle {
    private val lock = Any()
    private val records = ArrayDeque<VolatileRecord>(configuration.volatileRecordCapacity)
    private val mutableReadiness = MutableStateFlow(Readiness.VOLATILE_CAPTURE)
    private var profile: DiagnosticsProfile = configuration.initialProfile

    override val readiness: StateFlow<Readiness> = mutableReadiness.asStateFlow()

    override val diagnostics: Diagnostics = object : Diagnostics {
        override fun breadcrumb(value: GeneratedBreadcrumb, context: DiagnosticContext?) {
            append(
                kind = VolatileRecordKind.BREADCRUMB,
                code = value.code.value,
                operation = context?.operation?.value,
            )
        }

        override fun handled(value: GeneratedHandledError, throwable: Throwable?) {
            append(
                kind = VolatileRecordKind.HANDLED_ERROR,
                code = value.code.value,
                operation = null,
            )
        }
    }

    override val packages: DiagnosticPackages = object : DiagnosticPackages {
        override fun prepare(request: PackageRequest): PackagePreparationResult =
            PackagePreparationResult.Unavailable(PackageCapability.DETERMINISTIC_TBDIAG)
    }

    override fun updateProfile(profile: DiagnosticsProfile): PolicyUpdateResult = synchronized(lock) {
        if (mutableReadiness.value == Readiness.CLOSED) {
            return PolicyUpdateResult.Rejected(PolicyRejectionReason.HANDLE_CLOSED)
        }
        if (profile is DiagnosticsProfile.EnhancedDiagnosticSession) {
            return PolicyUpdateResult.Rejected(PolicyRejectionReason.UNSUPPORTED_BY_THIS_ALPHA)
        }
        this.profile = profile
        if (profile !is DiagnosticsProfile.StandardDiagnostics) {
            records.clear()
        }
        PolicyUpdateResult.Applied(profile, mutableReadiness.value)
    }

    override fun delete(request: DeleteRequest): DeleteReport = synchronized(lock) {
        val removed = records.size
        records.clear()
        DeleteReport(completed = true, recordsRemoved = removed)
    }

    override fun close() {
        synchronized(lock) {
            records.clear()
            mutableReadiness.value = Readiness.CLOSED
        }
    }

    private fun append(kind: VolatileRecordKind, code: Int, operation: Int?) {
        synchronized(lock) {
            if (
                mutableReadiness.value == Readiness.CLOSED ||
                profile !is DiagnosticsProfile.StandardDiagnostics
            ) {
                return
            }
            if (records.size == configuration.volatileRecordCapacity) {
                records.removeFirst()
            }
            records.addLast(VolatileRecord(kind, code, operation))
        }
    }
}

private enum class VolatileRecordKind {
    BREADCRUMB,
    HANDLED_ERROR,
}

/** No generated free-form text or throwable content is retained by the alpha implementation. */
private data class VolatileRecord(
    val kind: VolatileRecordKind,
    val code: Int,
    val operation: Int?,
)
