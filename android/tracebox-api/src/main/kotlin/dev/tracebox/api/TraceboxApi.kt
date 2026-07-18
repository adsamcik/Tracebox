package dev.tracebox.api

import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedRecord

/** The recorder's observable capability state. */
enum class Readiness { VOLATILE_CAPTURE, DURABLE, DEGRADED, CLOSED }

/** A bounded process context supplied by generated callers. */
data class DiagnosticContext(val processRole: Int, val policyEpoch: Long)

/** Accepts only schema-generated values; it deliberately has no map or string recording surface. */
interface Diagnostics {
    /** Returns false when the generated value must not be constructed or recorded. */
    fun eventEnabled(eventId: GeneratedEventId): Boolean

    /** Records a schema-generated bounded value. */
    fun record(value: GeneratedRecord, context: DiagnosticContext? = null)
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
    val readiness: StateFlow<Readiness>
    val packages: DiagnosticPackages

    fun updateProfile(profile: DiagnosticsProfile): PolicyUpdateResult
    fun delete(request: DeleteRequest): DeleteReport
}
