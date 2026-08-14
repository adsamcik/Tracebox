package dev.tracebox.ui.compose

import dev.tracebox.api.CaptureKind
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.LogLevel
import dev.tracebox.api.TraceboxPolicy
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

/** Host-overridable copy used by both the casual and advanced layouts. */
data class TraceboxDiagnosticsUiStrings(
    val title: String = "Help improve this app",
    val description: String =
        "If something went wrong, you can send local diagnostics to the developer.",
    val privacyNotice: String =
        "You will review what is included before anything leaves this device.",
    val supportTitle: String = "Send diagnostics",
    val supportDescription: String =
        "Tracebox packages recent crashes, errors, and performance context for troubleshooting.",
    val reviewAndUpload: String = "Review and send to developer",
    val reviewAndShare: String = "Review and share diagnostics",
    val reviewOnly: String = "Review diagnostics",
    val packageReady: String = "Diagnostics are ready.",
    val uploadSuccess: String = "Diagnostics were sent to the developer.",
    val uploadRetryableFailure: String = "Could not send diagnostics. Check your connection and try again.",
    val uploadRejected: String = "The diagnostics service did not accept this package.",
    val uploadFailure: String = "Could not send diagnostics.",
    val operationFailure: String = "The operation failed. Diagnostic data remains protected.",
    val uploadUnavailable: String = "Direct sending is not configured for this app.",
    val sharePackage: String = "Share with another app",
    val savePackage: String = "Save a copy",
    val sendPackage: String = "Send to developer",
    val moreSharingOptions: String = "Other sharing options",
    val advancedOptions: String = "Advanced options",
    val hideAdvancedOptions: String = "Hide advanced options",
    val statusReady: String = "Diagnostics are ready",
    val statusUnavailable: String = "Diagnostics are not ready yet",
    val statusTitle: String = "Technical status",
    val runtimeTitle: String = "Logging and performance",
    val captureSourcesTitle: String = "Capture sources",
    val diagnosticsEnabled: String = "Diagnostics",
    val minimumLogLevel: String = "Minimum log level",
    val mirrorToLogcat: String = "Mirror redacted logs to Logcat",
    val performanceTimings: String = "Performance timings",
    val minimumPerformanceDuration: String = "Minimum performance duration",
    val restoreDefaults: String = "Restore app defaults",
    val deleteAllData: String = "Delete all diagnostic data",
    val deleteDialogTitle: String = "Delete diagnostics?",
    val deleteDialogBody: String =
        "This removes Tracebox records and staged packages from this app.",
    val deleteConfirm: String = "Delete",
    val cancel: String = "Cancel",
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
 * Implementations may use OkHttp, Ktor, a platform service, or any native stack already present in
 * the host. Tracebox deliberately supplies no network client, endpoint, authentication, or retry
 * worker. The request should be consumed during this call rather than retained.
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
