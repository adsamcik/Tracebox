package dev.tracebox.api

import java.io.Closeable

/** The recorder's observable capability state. */
enum class Readiness { VOLATILE_CAPTURE, DURABLE, DEGRADED, CLOSED }

/** A bounded, generated breadcrumb category. */
enum class BreadcrumbCode { NAVIGATION, LIFECYCLE, USER_ACTION, INTERNAL_DROP }

/** A bounded, generated handled-error category. */
enum class HandledErrorKind { EXCEPTION, RESULT_FAILURE, NATIVE_STATUS }

/** A bounded process context supplied by generated callers. */
data class DiagnosticContext(val processRole: Int, val policyEpoch: Long)

/** A generated breadcrumb value. Its constructor is not public API. */
class GeneratedBreadcrumb internal constructor(
    val code: BreadcrumbCode,
    val monotonicTimeNs: Long,
)

/** A generated handled-error value. Its constructor is not public API. */
class GeneratedHandledError internal constructor(
    val kind: HandledErrorKind,
    val frameCount: UShort,
)

/** Accepts only generated value types; it deliberately has no generic record method. */
interface Diagnostics {
    /** Returns false when the value must not be constructed or recorded. */
    fun breadcrumbEnabled(): Boolean

    /** Returns false when the value must not be constructed or recorded. */
    fun handledEnabled(): Boolean

    /** Records a generated bounded breadcrumb. */
    fun breadcrumb(value: GeneratedBreadcrumb, context: DiagnosticContext? = null)

    /** Records a generated bounded handled-error summary. Throwable text is not recorded. */
    fun handled(value: GeneratedHandledError, throwable: Throwable? = null)
}

/** Generated entry points guard enablement before allocating a recording value. */
object GeneratedDiagnostics {
    fun breadcrumb(
        diagnostics: Diagnostics,
        code: BreadcrumbCode,
        monotonicTimeNs: Long,
        context: DiagnosticContext? = null,
    ) {
        if (diagnostics.breadcrumbEnabled()) {
            diagnostics.breadcrumb(GeneratedBreadcrumb(code, monotonicTimeNs), context)
        }
    }

    fun handled(diagnostics: Diagnostics, kind: HandledErrorKind, frameCount: UShort) {
        if (diagnostics.handledEnabled()) {
            diagnostics.handled(GeneratedHandledError(kind, frameCount))
        }
    }
}

/** A closed set of profiles; Prohibited data can never be selected. */
enum class DiagnosticsProfile { DISABLED, MINIMAL_CRASH, STANDARD_DIAGNOSTICS, ENHANCED_DIAGNOSTIC_SESSION }

/** Result of a policy update. */
enum class PolicyUpdateResult { SUCCESS, LOCAL_ONLY_RESTRICTED, PARTIAL, FAILED }

/** A bounded deletion request. */
enum class DeleteRequest { ALL_TRACEBOX_DATA, EXPIRED_SNAPSHOTS }

/** Result of a deletion request. */
enum class DeleteReport { COMPLETE, PENDING_FAILURE, REJECTED }

/** Public package request types deliberately carry no arbitrary labels or values. */
enum class PackageRequest { STANDARD }

/** Opaque approval proof. Production code cannot construct one. */
class ApprovalToken internal constructor(internal val opaque: ByteArray)

/** Package creation requires the opaque token issued by a future disclosure UI. */
interface DiagnosticPackages {
    fun create(request: PackageRequest, approval: ApprovalToken): PackageResult
}

/** Package preparation result, without a transport or uploader surface. */
enum class PackageResult { CREATED, REJECTED, NOT_READY }

/** The generated-only public Tracebox recording handle. */
interface TraceboxHandle : Closeable {
    val diagnostics: Diagnostics
    val readiness: Readiness
    val packages: DiagnosticPackages

    fun updateProfile(profile: DiagnosticsProfile): PolicyUpdateResult
    fun delete(request: DeleteRequest): DeleteReport
}
