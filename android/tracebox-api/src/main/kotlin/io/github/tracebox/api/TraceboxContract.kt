// SPDX-License-Identifier: Apache-2.0

package io.github.tracebox.api

import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow

/** The availability of the Tracebox recorder in the current process. */
public enum class Readiness {
    /** Records are accepted only into the bounded volatile alpha buffer. */
    VOLATILE_CAPTURE,

    /** Reserved for the durable storage implementation. */
    DURABLE,

    /** Reserved for a recorder whose required capture path is unavailable. */
    DEGRADED,

    /** The handle has been closed and accepts no more data. */
    CLOSED,
}

/**
 * A bounded diagnostic code. Stable generated schemas will own the assigned values in a later
 * alpha; this type prevents event names and arbitrary labels from entering the public surface.
 */
@JvmInline
public value class DiagnosticCode private constructor(private val encoded: Int) {
    /** The schema-owned numeric value. */
    public val value: Int
        get() = encoded

    public companion object {
        /** Creates a valid code in the alpha's reserved generated-code range. */
        @JvmStatic
        public fun of(value: Int): DiagnosticCode {
            require(value in 1..999_999) {
                "Diagnostic codes must be between 1 and 999999."
            }
            return DiagnosticCode(value)
        }
    }
}

/**
 * A bounded text value for a generated diagnostic field. Its content is deliberately not exposed
 * through [toString] so ordinary logging cannot accidentally disclose it.
 */
public class DiagnosticText private constructor(private val encoded: String) {
    /** Number of UTF-16 code units in the value. */
    public val length: Int
        get() = encoded.length

    override fun toString(): String = "DiagnosticText(length=$length)"

    public companion object {
        /** Maximum encoded UTF-16 code units permitted by this alpha contract. */
        public const val MAX_CODE_UNITS: Int = 128

        /**
         * Validates a bounded generated text field. Callers must not use this as a generic log
         * message API; the generated event type determines whether the field is permitted.
         */
        @JvmStatic
        public fun from(value: String): DiagnosticText {
            require(value.length in 1..MAX_CODE_UNITS) {
                "Diagnostic text must contain 1 to $MAX_CODE_UNITS UTF-16 code units."
            }
            require(value.none(Char::isISOControl)) {
                "Diagnostic text must not contain control characters."
            }
            return DiagnosticText(value)
        }
    }
}

/** A bounded, generated context reference that carries no arbitrary map or label. */
public class DiagnosticContext private constructor(
    /** The generated operation code, when the schema permits one. */
    public val operation: DiagnosticCode?,
) {
    public companion object {
        /** Context-free event recording. */
        @JvmStatic
        public fun none(): DiagnosticContext = DiagnosticContext(operation = null)

        /** Context linked to one generated operation code. */
        @JvmStatic
        public fun forOperation(operation: DiagnosticCode): DiagnosticContext =
            DiagnosticContext(operation = operation)
    }
}

/** A generated breadcrumb event in the initial alpha schema. */
public data class GeneratedBreadcrumb(
    /** Schema-owned event code. */
    public val code: DiagnosticCode,
    /** Optional bounded detail only when the generated schema allows it. */
    public val detail: DiagnosticText? = null,
)

/** Severity used by a generated handled-error event. */
public enum class HandledErrorSeverity {
    INFO,
    WARNING,
    ERROR,
}

/** A generated handled-error event in the initial alpha schema. */
public data class GeneratedHandledError(
    /** Schema-owned error code. */
    public val code: DiagnosticCode,
    /** Schema-owned severity. */
    public val severity: HandledErrorSeverity,
)

/** Recording operations intentionally restricted to generated value types. */
public interface Diagnostics {
    /** Records a generated breadcrumb without accepting arbitrary event names or field maps. */
    public fun breadcrumb(value: GeneratedBreadcrumb, context: DiagnosticContext? = null)

    /**
     * Records generated handled-error metadata. The alpha does not inspect throwable messages or
     * stack traces; those capabilities require later privacy and persistence gates.
     */
    public fun handled(value: GeneratedHandledError, throwable: Throwable? = null)
}

/** A privacy profile for recording. */
public sealed interface DiagnosticsProfile {
    /** Stops new collection and requests deletion of Tracebox-owned state. */
    public data object Disabled : DiagnosticsProfile

    /** Enables only the minimum C0/C1 structural diagnostic intent. */
    public data object MinimalCrash : DiagnosticsProfile

    /** Enables generated operational events subject to the active schema. */
    public data object StandardDiagnostics : DiagnosticsProfile

    /**
     * Time-bounded enhanced capture intent. Raw artifact behavior is not implemented in this
     * alpha and this value does not enable it.
     */
    public class EnhancedDiagnosticSession private constructor(
        /** Upper bound for the requested session. */
        public val durationSeconds: Int,
    ) : DiagnosticsProfile {
        public companion object {
            /** Creates a session request of at most one day. */
            @JvmStatic
            public fun forSeconds(durationSeconds: Int): EnhancedDiagnosticSession {
                require(durationSeconds in 1..86_400) {
                    "Enhanced diagnostic sessions must last from 1 to 86400 seconds."
                }
                return EnhancedDiagnosticSession(durationSeconds)
            }
        }
    }
}

/** Result of a profile update. */
public sealed interface PolicyUpdateResult {
    /** The profile is active in this handle. */
    public data class Applied(
        public val profile: DiagnosticsProfile,
        public val readiness: Readiness,
    ) : PolicyUpdateResult

    /** The handle cannot apply the requested policy. */
    public data class Rejected(public val reason: PolicyRejectionReason) : PolicyUpdateResult
}

/** Reasons a profile transition may be rejected. */
public enum class PolicyRejectionReason {
    HANDLE_CLOSED,
    UNSUPPORTED_BY_THIS_ALPHA,
}

/** A deletion request. Selective deletion arrives with the generated storage schema. */
public sealed interface DeleteRequest {
    /** Deletes every Tracebox-owned record reachable by this alpha handle. */
    public data object All : DeleteRequest
}

/** Result of a deletion request. */
public data class DeleteReport(
    /** Whether the requested locally owned records were removed. */
    public val completed: Boolean,
    /** Number of volatile structural records removed by this handle. */
    public val recordsRemoved: Int,
)

/** The only package request supported by the eventual standard export workflow. */
public sealed interface PackageRequest {
    /** Standard package selection. The alpha does not materialize a package. */
    public data object Standard : PackageRequest
}

/** Package capabilities intentionally unavailable until their correctness gates pass. */
public enum class PackageCapability {
    DETERMINISTIC_TBDIAG,
    DISCLOSURE_AND_APPROVAL,
    LOCAL_SAVE_AND_SHARE,
}

/** Outcome of package preparation. */
public sealed interface PackagePreparationResult {
    /** The requested workflow is not implemented in this alpha. */
    public data class Unavailable(public val capability: PackageCapability) : PackagePreparationResult
}

/** User-controlled local package operations. */
public interface DiagnosticPackages {
    /**
     * Starts package preparation when implemented. This alpha always returns an explicit
     * [PackagePreparationResult.Unavailable] rather than producing an incomplete export.
     */
    public fun prepare(request: PackageRequest = PackageRequest.Standard): PackagePreparationResult
}

/** The public per-installation Tracebox handle. */
public interface TraceboxHandle : Closeable {
    /** Generated recording surface. */
    public val diagnostics: Diagnostics

    /** Recorder state for this process. */
    public val readiness: StateFlow<Readiness>

    /** Local package workflow surface. */
    public val packages: DiagnosticPackages

    /** Changes the local policy or returns a typed rejection. */
    public fun updateProfile(profile: DiagnosticsProfile): PolicyUpdateResult

    /** Deletes locally owned alpha records. */
    public fun delete(request: DeleteRequest): DeleteReport
}

