package dev.tracebox.export.ui

import dev.tracebox.export.PackagePipelineFailure
import dev.tracebox.export.PackagePipelineResult
import java.security.MessageDigest

enum class ShareHandoffState { NOT_STARTED, CHOOSER_OPENED, TARGET_SELECTED, DELIVERY_UNKNOWN }

sealed interface GenerationResult {
    data class Finalized(val outputDigest: ByteArray, val outputSize: Long) : GenerationResult
    data class Failed(val cause: PackagePipelineFailure) : GenerationResult
    data object Cancelled : GenerationResult
    data object ApprovalMismatch : GenerationResult
}

sealed interface SaveResult {
    data object NotRequested : SaveResult
    data class Complete(val bytesWritten: Long) : SaveResult
    data class PartialCopyWarning(val bytesWritten: Long, val cancelled: Boolean) : SaveResult
    data class Failed(val detail: String) : SaveResult
}

/**
 * Failed and cancelled variants deliberately have no save or handoff fields, making a shareable
 * receipt unrepresentable until an Activity-issued ApprovedPackage exists.
 */
sealed interface ExportReceipt {
    data class GenerationFailed(val cause: PackagePipelineFailure) : ExportReceipt
    data object Cancelled : ExportReceipt
    data class PreviewGenerated(val outputDigest: ByteArray, val outputSize: Long) : ExportReceipt
    data class Approved(
        val approvedPlaintextDigest: ByteArray,
        val outputDigest: ByteArray,
        val outputSize: Long,
        val protectionMode: ProtectionMode,
        val recipients: RecipientSet,
        val save: SaveResult,
        val handoff: ShareHandoffState,
        val cancellationObserved: Boolean,
        val stagingExpiryMillis: Long?,
    ) : ExportReceipt
}

object ReceiptFactory {
    fun fromPipeline(result: PackagePipelineResult): ExportReceipt = when (result) {
        is PackagePipelineResult.Failed -> ExportReceipt.GenerationFailed(result.failure)
        is PackagePipelineResult.Ready -> ExportReceipt.PreviewGenerated(
            result.packageBytes.plaintextSha256(),
            result.packageBytes.exactBytes().size.toLong(),
        )
    }

    internal fun approved(
        approved: TraceboxDisclosureActivity.ApprovedPackage,
        expiryMillis: Long? = null,
    ): ExportReceipt.Approved {
        val bytes = approved.exactBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        check(approved.matches(digest))
        return ExportReceipt.Approved(
            approved.approvedPlaintextDigest(), digest, bytes.size.toLong(), approved.protectionMode(), approved.recipients(),
            SaveResult.NotRequested, ShareHandoffState.NOT_STARTED, false, expiryMillis,
        )
    }

    internal fun regenerate(approved: TraceboxDisclosureActivity.ApprovedPackage, candidateBytes: ByteArray): GenerationResult {
        val digest = MessageDigest.getInstance("SHA-256").digest(candidateBytes)
        return if (approved.matches(digest) && approved.exactBytes().contentEquals(candidateBytes)) {
            GenerationResult.Finalized(digest, candidateBytes.size.toLong())
        } else {
            GenerationResult.ApprovalMismatch
        }
    }
}
