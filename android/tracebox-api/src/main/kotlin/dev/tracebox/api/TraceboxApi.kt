package dev.tracebox.api

import android.content.Context
import android.content.Intent
import android.net.Uri
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

/** The runtime's observable capability state, separate from the broad readiness level. */
enum class TraceboxHealth {
    DISABLED,
    INITIALIZING,
    READY,
    DEGRADED,
    DELETING,
    CLOSED,
}

/** Exact facts decoded from finalized bytes before the user approves export. */
data class PackageDisclosure(
    val includedValueCount: Int,
    val includedBytes: Long,
    val privacyClasses: Set<PackagePrivacyClass>,
    val transformations: Set<PackageTransformation>,
    val omissionReasons: Set<PackageOmissionReason>,
    val sourceTimeRangeMillis: LongRange?,
    val sourceProcessCount: Int,
    val plaintextDigestSha256: ByteArray,
    val rawArtifactCount: Int,
    val warnings: Set<PackageWarning>,
) {
    init {
        require(includedValueCount >= 0)
        require(includedBytes >= 0)
        require(sourceProcessCount >= 0)
        require(rawArtifactCount >= 0)
        require(plaintextDigestSha256.size == 32)
    }
}

/** Privacy classes reported by a finalized Standard package. */
enum class PackagePrivacyClass { C0, C1, C2 }

/** Generated transformations; arbitrary transformations are not represented by this API. */
enum class PackageTransformation { NONE }

/** Bounded omission categories reported by a finalized package. */
enum class PackageOmissionReason { CORRUPT_ORDINARY_RECORD }

/** Exact export warnings. Standard packages always report that raw artifacts were excluded. */
enum class PackageWarning {
    RAW_CRASH_ARTIFACTS_EXCLUDED,
    OS_EXIT_HISTORY_REMAINS_ANDROID_OWNED,
    SHARE_OR_SAF_RECIPIENT_MAY_RETAIN_BYTES,
    DELIVERY_CANNOT_BE_PROVEN,
}

/** A finalized-byte disclosure preview. Its digest is copied defensively. */
class PackagePreview(
    disclosure: PackageDisclosure,
) {
    val disclosure = disclosure.copy(
        plaintextDigestSha256 = disclosure.plaintextDigestSha256.copyOf(),
    )
}

/** A bounded result from materializing a Standard package for review. */
sealed interface PackagePreparationResult {
    data class Ready(val preview: PackagePreview) : PackagePreparationResult
    data object NotReady : PackagePreparationResult
    data object Rejected : PackagePreparationResult
}

/**
 * Opaque proof returned only from a Tracebox-owned approval activity.
 *
 * The constructor is private. A forged activity result can create an opaque value but cannot
 * satisfy the runtime's one-time approval registry.
 */
class ApprovalToken private constructor(internal val opaque: ByteArray) {
    companion object {
        private const val RESULT_EXTRA = "dev.tracebox.api.approval"

        /** Converts a Tracebox-owned approval activity result into an opaque proof. */
        fun fromActivityResult(result: Intent?): ApprovalToken? {
            val opaque = result?.getByteArrayExtra(RESULT_EXTRA) ?: return null
            return opaque.takeIf { it.size == 32 }?.let { ApprovalToken(it.copyOf()) }
        }

        /** Used only by Tracebox's non-exported disclosure activity. */
        fun resultIntent(opaque: ByteArray): Intent {
            require(opaque.size == 32)
            return Intent().putExtra(RESULT_EXTRA, opaque.copyOf())
        }
    }
}

/** Package creation requires the opaque token issued by Tracebox's disclosure UI. */
interface DiagnosticPackages {
    /** Finalizes deterministic Standard bytes and returns facts decoded from those exact bytes. */
    fun prepare(request: PackageRequest): PackagePreparationResult

    /** Returns a non-exported Tracebox approval activity intent for this exact preview, if valid. */
    fun approvalIntent(context: Context, preview: PackagePreview): Intent?

    /** Creates a package only when [approval] matches the exact finalized preview bytes. */
    fun create(request: PackageRequest, approval: ApprovalToken): PackageResult
}

/** A local, user-initiated package result without any uploader or transport capability. */
sealed interface PackageResult {
    data class Created(val diagnosticPackage: DiagnosticPackage) : PackageResult
    data object Rejected : PackageResult
    data object NotReady : PackageResult
}

/** Accurate outcomes from a user-selected SAF save. */
sealed interface SavePackageResult {
    data class Complete(val bytesWritten: Long) : SavePackageResult
    data class PartialCopyWarning(val bytesWritten: Long, val cancelled: Boolean) : SavePackageResult
    data class Failed(val detail: SaveFailure) : SavePackageResult
}

/** Bounded save failure categories; provider exception messages are intentionally not surfaced. */
enum class SaveFailure { OUTPUT_UNAVAILABLE, WRITE_FAILED }

/** Accurate final handoff state; Android cannot prove recipient delivery. */
enum class SharePackageResult { NOT_STARTED, CHOOSER_OPENED, DELIVERY_UNKNOWN }

/** A package created from exact approved bytes. It has no network or automatic upload surface. */
interface DiagnosticPackage {
    val plaintextDigestSha256: ByteArray
    val sizeBytes: Long
    val receipt: StateFlow<SharePackageResult>

    /** Stages exact approved bytes and returns an Android Sharesheet intent. */
    fun shareIntent(context: Context): Intent?

    /** Creates an `ACTION_CREATE_DOCUMENT` intent for a local user-selected destination. */
    fun createSaveIntent(): Intent

    /** Copies exact approved bytes to a user-selected SAF destination. Call off the main thread. */
    fun save(context: Context, destination: Uri, isCancelled: () -> Boolean = { false }): SavePackageResult

    /** Deletes Tracebox-owned staging for this package; it cannot delete the OS-owned destination. */
    fun deleteStaging(): Boolean
}

/** The generated-only public Tracebox recording handle. */
interface TraceboxHandle : Closeable {
    val diagnostics: Diagnostics
    val readiness: StateFlow<Readiness>
    val health: StateFlow<TraceboxHealth>
    val packages: DiagnosticPackages

    fun updateProfile(profile: DiagnosticsProfile): PolicyUpdateResult
    fun delete(request: DeleteRequest): DeleteReport
}
