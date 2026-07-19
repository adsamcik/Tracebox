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

data class ExportReceipt(
    val approvedPlaintextDigest: ByteArray?,
    val outputDigest: ByteArray?,
    val outputSize: Long?,
    val protectionMode: ProtectionMode?,
    val recipients: RecipientSet?,
    val generation: GenerationResult,
    val save: SaveResult,
    val handoff: ShareHandoffState,
    val cancellationObserved: Boolean,
    val stagingExpiryMillis: Long?,
)

object ReceiptFactory {
    fun fromPipeline(result: PackagePipelineResult): ExportReceipt = when (result) {
        is PackagePipelineResult.Failed -> ExportReceipt(
            null, null, null, null, null, GenerationResult.Failed(result.failure),
            SaveResult.NotRequested, ShareHandoffState.NOT_STARTED, false, null,
        )
        is PackagePipelineResult.Ready -> ExportReceipt(
            null, result.packageBytes.plaintextSha256(), result.packageBytes.exactBytes().size.toLong(),
            ProtectionMode.LOCAL_ONLY, RecipientSet.LocalOnly,
            GenerationResult.Finalized(result.packageBytes.plaintextSha256(), result.packageBytes.exactBytes().size.toLong()),
            SaveResult.NotRequested, ShareHandoffState.NOT_STARTED, false, null,
        )
    }

    internal fun approved(approved: ApprovedPackage, expiryMillis: Long? = null): ExportReceipt {
        val bytes = approved.exactBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        check(approved.token.matches(digest))
        return ExportReceipt(
            approved.approvedPlaintextDigest(), digest, bytes.size.toLong(), approved.token.protectionMode, approved.token.recipients,
            GenerationResult.Finalized(digest, bytes.size.toLong()), SaveResult.NotRequested,
            ShareHandoffState.NOT_STARTED, false, expiryMillis,
        )
    }

    internal fun regenerate(approved: ApprovedPackage, candidateBytes: ByteArray): GenerationResult {
        val digest = MessageDigest.getInstance("SHA-256").digest(candidateBytes)
        return if (approved.token.matches(digest) && approved.exactBytes().contentEquals(candidateBytes)) {
            GenerationResult.Finalized(digest, candidateBytes.size.toLong())
        } else {
            GenerationResult.ApprovalMismatch
        }
    }
}
