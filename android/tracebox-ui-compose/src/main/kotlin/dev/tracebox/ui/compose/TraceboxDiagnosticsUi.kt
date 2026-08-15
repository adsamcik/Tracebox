package dev.tracebox.ui.compose

import dev.tracebox.api.CaptureKind
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.LogLevel
import dev.tracebox.api.TraceboxPolicy
import java.io.Closeable
import java.io.InputStream

/** The action performed after the user reviews and approves the exact diagnostic package. */
enum class TraceboxPrimaryAction {
    /** Upload when a host uploader exists, otherwise use Android sharing. */
    AUTOMATIC,

    /** Send through the host-supplied [TraceboxDiagnosticUploader]. */
    UPLOAD,

    /** Open Android's Sharesheet. */
    SHARE,

    /** Only create the approved package; the user then chooses a secondary action. */
    REVIEW_ONLY,
}

/** Package actions an application permits the reusable UI to expose. */
data class TraceboxPackageActions(
    val upload: Boolean = true,
    val share: Boolean = true,
    val save: Boolean = true,
    val deleteAllData: Boolean = true,
)

/** Fine-grained controls an application permits under the advanced disclosure. */
data class TraceboxAdvancedControls(
    val visible: Boolean = true,
    val initiallyExpanded: Boolean = false,
    val statusDetails: Boolean = true,
    val diagnosticsEnabled: Boolean = true,
    val logLevels: List<LogLevel> = LogLevel.entries,
    val logcatMirroring: Boolean = true,
    val performanceLogging: Boolean = true,
    val performanceThresholdsNanos: List<Long> = listOf(
        0L,
        1_000_000L,
        10_000_000L,
        100_000_000L,
    ),
    val captureKinds: Set<CaptureKind> = CaptureKind.entries.toSet(),
    val resetToDefaults: Boolean = true,
) {
    init {
        require(logLevels.distinct().size == logLevels.size) { "logLevels must be unique" }
        require(performanceThresholdsNanos.all { it >= 0L }) {
            "performance thresholds must be non-negative"
        }
        require(performanceThresholdsNanos.distinct().size == performanceThresholdsNanos.size) {
            "performance thresholds must be unique"
        }
    }
}

/**
 * Host-overridable Android string resources used by the complete diagnostics surface.
 *
 * Applications can pass their own localized resource IDs or override the library resources by
 * name. Keeping formatting in resources preserves locale-specific argument ordering.
 */
data class TraceboxDiagnosticsUiStrings(
    val title: Int = R.string.tracebox_ui_title,
    val description: Int = R.string.tracebox_ui_description,
    val privacyNotice: Int = R.string.tracebox_ui_privacy_notice,
    val supportTitle: Int = R.string.tracebox_ui_support_title,
    val supportDescription: Int = R.string.tracebox_ui_support_description,
    val reviewAndUpload: Int = R.string.tracebox_ui_review_and_upload,
    val reviewAndShare: Int = R.string.tracebox_ui_review_and_share,
    val reviewOnly: Int = R.string.tracebox_ui_review_only,
    val packageReady: Int = R.string.tracebox_ui_package_ready,
    val uploadSuccess: Int = R.string.tracebox_ui_upload_success,
    val uploadRetryableFailure: Int = R.string.tracebox_ui_upload_retryable_failure,
    val uploadRejected: Int = R.string.tracebox_ui_upload_rejected,
    val uploadFailure: Int = R.string.tracebox_ui_upload_failure,
    val operationFailure: Int = R.string.tracebox_ui_operation_failure,
    val uploadUnavailable: Int = R.string.tracebox_ui_upload_unavailable,
    val sharePackage: Int = R.string.tracebox_ui_share_package,
    val savePackage: Int = R.string.tracebox_ui_save_package,
    val sendPackage: Int = R.string.tracebox_ui_send_package,
    val moreSharingOptions: Int = R.string.tracebox_ui_more_sharing_options,
    val advancedOptions: Int = R.string.tracebox_ui_advanced_options,
    val hideAdvancedOptions: Int = R.string.tracebox_ui_hide_advanced_options,
    val statusReady: Int = R.string.tracebox_ui_status_ready,
    val statusUnavailable: Int = R.string.tracebox_ui_status_unavailable,
    val statusTitle: Int = R.string.tracebox_ui_status_title,
    val runtimeTitle: Int = R.string.tracebox_ui_runtime_title,
    val captureSourcesTitle: Int = R.string.tracebox_ui_capture_sources_title,
    val diagnosticsEnabled: Int = R.string.tracebox_ui_diagnostics_enabled,
    val minimumLogLevel: Int = R.string.tracebox_ui_minimum_log_level,
    val mirrorToLogcat: Int = R.string.tracebox_ui_mirror_to_logcat,
    val performanceTimings: Int = R.string.tracebox_ui_performance_timings,
    val minimumPerformanceDuration: Int = R.string.tracebox_ui_minimum_performance_duration,
    val restoreDefaults: Int = R.string.tracebox_ui_restore_defaults,
    val deleteAllData: Int = R.string.tracebox_ui_delete_all_data,
    val deleteDialogTitle: Int = R.string.tracebox_ui_delete_dialog_title,
    val deleteDialogBody: Int = R.string.tracebox_ui_delete_dialog_body,
    val deleteConfirm: Int = R.string.tracebox_ui_delete_confirm,
    val cancel: Int = R.string.tracebox_ui_cancel,
    val policyUpdated: Int = R.string.tracebox_ui_policy_updated,
    val policyRestrictedLocally: Int = R.string.tracebox_ui_policy_restricted_locally,
    val policyPartiallyApplied: Int = R.string.tracebox_ui_policy_partially_applied,
    val policyFailed: Int = R.string.tracebox_ui_policy_failed,
    val packageReviewCancelled: Int = R.string.tracebox_ui_package_review_cancelled,
    val diagnosticsNotReady: Int = R.string.tracebox_ui_diagnostics_not_ready,
    val packageApprovalMismatch: Int = R.string.tracebox_ui_package_approval_mismatch,
    val packageShareFailed: Int = R.string.tracebox_ui_package_share_failed,
    val reviewUnavailable: Int = R.string.tracebox_ui_review_unavailable,
    val packagePreparationRejected: Int = R.string.tracebox_ui_package_preparation_rejected,
    val saveCancelled: Int = R.string.tracebox_ui_save_cancelled,
    val reviewFirst: Int = R.string.tracebox_ui_review_first,
    val savedBytes: Int = R.plurals.tracebox_ui_saved_bytes,
    val partialCopyBytes: Int = R.plurals.tracebox_ui_partial_copy_bytes,
    val saveFailed: Int = R.string.tracebox_ui_save_failed,
    val deleteComplete: Int = R.string.tracebox_ui_delete_complete,
    val deletePending: Int = R.string.tracebox_ui_delete_pending,
    val deleteRejected: Int = R.string.tracebox_ui_delete_rejected,
    val readinessValue: Int = R.string.tracebox_ui_readiness_value,
    val healthValue: Int = R.string.tracebox_ui_health_value,
    val recordedCount: Int = R.string.tracebox_ui_recorded_count,
    val readinessVolatileCapture: Int = R.string.tracebox_ui_readiness_volatile_capture,
    val readinessDurable: Int = R.string.tracebox_ui_readiness_durable,
    val readinessDegraded: Int = R.string.tracebox_ui_readiness_degraded,
    val readinessClosed: Int = R.string.tracebox_ui_readiness_closed,
    val healthDisabled: Int = R.string.tracebox_ui_health_disabled,
    val healthInitializing: Int = R.string.tracebox_ui_health_initializing,
    val healthReady: Int = R.string.tracebox_ui_health_ready,
    val healthDegraded: Int = R.string.tracebox_ui_health_degraded,
    val healthDeleting: Int = R.string.tracebox_ui_health_deleting,
    val healthClosed: Int = R.string.tracebox_ui_health_closed,
    val logLevelVerbose: Int = R.string.tracebox_ui_log_level_verbose,
    val logLevelDebug: Int = R.string.tracebox_ui_log_level_debug,
    val logLevelInfo: Int = R.string.tracebox_ui_log_level_info,
    val logLevelWarn: Int = R.string.tracebox_ui_log_level_warn,
    val logLevelError: Int = R.string.tracebox_ui_log_level_error,
    val logLevelOff: Int = R.string.tracebox_ui_log_level_off,
    val captureJvmCrash: Int = R.string.tracebox_ui_capture_jvm_crash,
    val captureHandledException: Int = R.string.tracebox_ui_capture_handled_exception,
    val captureAnr: Int = R.string.tracebox_ui_capture_anr,
    val captureOsExit: Int = R.string.tracebox_ui_capture_os_exit,
    val captureNativeCrash: Int = R.string.tracebox_ui_capture_native_crash,
    val captureRustPanic: Int = R.string.tracebox_ui_capture_rust_panic,
    val durationAny: Int = R.string.tracebox_ui_duration_any,
    val durationNanos: Int = R.string.tracebox_ui_duration_nanos,
    val durationMillis: Int = R.string.tracebox_ui_duration_millis,
    val controlOn: Int = R.string.tracebox_ui_control_on,
    val controlOff: Int = R.string.tracebox_ui_control_off,
    val sectionExpanded: Int = R.string.tracebox_ui_section_expanded,
    val sectionCollapsed: Int = R.string.tracebox_ui_section_collapsed,
    val operationInProgress: Int = R.string.tracebox_ui_operation_in_progress,
)

/**
 * Complete reusable-UI policy supplied by an application.
 *
 * [defaultPolicy] is used only by the optional “restore defaults” control. Fresh-install runtime
 * defaults remain owned by `TraceboxConfiguration.Builder.setInitialPolicy`, so opening a screen
 * never silently overwrites a user's persisted policy.
 */
data class TraceboxDiagnosticsUiConfiguration(
    val strings: TraceboxDiagnosticsUiStrings = TraceboxDiagnosticsUiStrings(),
    val showHeading: Boolean = true,
    val showCasualStatus: Boolean = true,
    val primaryAction: TraceboxPrimaryAction = TraceboxPrimaryAction.AUTOMATIC,
    val packageActions: TraceboxPackageActions = TraceboxPackageActions(),
    val advancedControls: TraceboxAdvancedControls = TraceboxAdvancedControls(),
    val defaultPolicy: TraceboxPolicy = TraceboxPolicy.standard(),
)

/** A bounded, exact-byte request passed only after the user approves the package disclosure. */
class TraceboxUploadRequest internal constructor(
    private val diagnosticPackage: DiagnosticPackage,
) {
    val contentType: String = "application/zip"
    val suggestedFileName: String = "tracebox.tbdiag"
    val sizeBytes: Long = diagnosticPackage.sizeBytes
    val plaintextDigestSha256: ByteArray
        get() = diagnosticPackage.plaintextDigestSha256.copyOf()

    /** Reads the approved ZIP without exposing Tracebox storage paths or long-lived URIs. */
    fun <T> useInputStream(block: (InputStream) -> T): T? =
        diagnosticPackage.useInputStream(block)
}

/** Single-slot UI ownership that closes packages on replacement and screen disposal. */
internal class DiagnosticPackageOwner : Closeable {
    private var active: DiagnosticPackage? = null

    @Synchronized
    fun replace(replacement: DiagnosticPackage) {
        if (active === replacement) return
        val retired = active
        active = replacement
        retired?.close()
    }

    @Synchronized
    fun retire(expected: DiagnosticPackage? = null) {
        val retired = active ?: return
        if (expected != null && retired !== expected) return
        active = null
        retired.close()
    }

    @Synchronized
    internal fun current(): DiagnosticPackage? = active

    override fun close() = retire()
}

/** Backend outcome intentionally excludes raw server messages from the user-facing UI. */
sealed interface TraceboxUploadResult {
    data class Uploaded(val receipt: String? = null) : TraceboxUploadResult
    data object RetryableFailure : TraceboxUploadResult
    data object Rejected : TraceboxUploadResult
    data object Failed : TraceboxUploadResult
}

/**
 * Application-owned transport for approved diagnostic packages.
 *
 * Implementations use a transport already owned and governed by the host. Tracebox deliberately
 * supplies no network client, endpoint, authentication, or retry worker. The request should be
 * consumed during this call rather than retained.
 */
fun interface TraceboxDiagnosticUploader {
    suspend fun upload(request: TraceboxUploadRequest): TraceboxUploadResult
}

/** Process configuration used by [TraceboxDiagnosticsActivity]. Embedded screens can pass directly. */
object TraceboxDiagnosticsUi {
    @Volatile
    private var binding = TraceboxDiagnosticsUiBinding()

    @JvmStatic
    fun configure(
        configuration: TraceboxDiagnosticsUiConfiguration,
        uploader: TraceboxDiagnosticUploader? = null,
    ) {
        binding = TraceboxDiagnosticsUiBinding(configuration, uploader)
    }

    @JvmStatic
    fun reset() {
        binding = TraceboxDiagnosticsUiBinding()
    }

    internal fun currentBinding(): TraceboxDiagnosticsUiBinding = binding
}

internal data class TraceboxDiagnosticsUiBinding(
    val configuration: TraceboxDiagnosticsUiConfiguration = TraceboxDiagnosticsUiConfiguration(),
    val uploader: TraceboxDiagnosticUploader? = null,
)

internal enum class ResolvedPrimaryAction { UPLOAD, SHARE, REVIEW_ONLY }

internal fun resolvePrimaryAction(
    configured: TraceboxPrimaryAction,
    actions: TraceboxPackageActions,
    uploaderAvailable: Boolean,
): ResolvedPrimaryAction = when (configured) {
    TraceboxPrimaryAction.AUTOMATIC -> when {
        actions.upload && uploaderAvailable -> ResolvedPrimaryAction.UPLOAD
        actions.share -> ResolvedPrimaryAction.SHARE
        else -> ResolvedPrimaryAction.REVIEW_ONLY
    }
    TraceboxPrimaryAction.UPLOAD -> if (actions.upload && uploaderAvailable) {
        ResolvedPrimaryAction.UPLOAD
    } else {
        ResolvedPrimaryAction.REVIEW_ONLY
    }
    TraceboxPrimaryAction.SHARE -> if (actions.share) {
        ResolvedPrimaryAction.SHARE
    } else {
        ResolvedPrimaryAction.REVIEW_ONLY
    }
    TraceboxPrimaryAction.REVIEW_ONLY -> ResolvedPrimaryAction.REVIEW_ONLY
}
