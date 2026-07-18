package dev.tracebox.anr

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Minimal documented-API-shaped input; the Android adapter maps ApplicationExitInfo into this value. */
data class SyntheticApplicationExitInfo(
    val processName: String,
    val timestampMillis: Long,
    val reason: Int,
    val pid: Int,
    val processStateSummary: ByteArray?,
) {
    init {
        require(processName.isNotBlank() && processName.toByteArray(StandardCharsets.UTF_8).size <= 256)
        require(pid >= 0)
        require(processStateSummary == null || processStateSummary.size <= 128)
    }
}

/** Installation-lifetime exact OS source key; no bounded-history record is imported twice. */
@JvmInline
value class ExitSourceKey(val encoded: String) {
    companion object {
        fun derive(exit: SyntheticApplicationExitInfo): ExitSourceKey {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("tracebox-exit-source-v1".toByteArray(StandardCharsets.UTF_8))
            digest.update(exit.processName.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(ByteBuffer.allocate(8).putLong(exit.timestampMillis).array())
            digest.update(ByteBuffer.allocate(4).putInt(exit.reason).array())
            digest.update(ByteBuffer.allocate(4).putInt(exit.pid).array())
            digest.update(exit.processStateSummary ?: byteArrayOf())
            return ExitSourceKey(Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest()))
        }
    }
}

enum class ExitImportResult { IMPORTED, ALREADY_IMPORTED, DISABLED_EXHAUSTED }

/**
 * Bounded installation-lifetime exact source tombstones. Exhaustion deliberately disables new
 * imports rather than evicting an entry and breaking idempotency.
 */
class ExitTombstoneLedger(private val maxEntries: Int, private val maxBytes: Int) {
    private val keys = linkedSetOf<ExitSourceKey>()
    private var bytes = 0

    init {
        require(maxEntries > 0 && maxBytes > 0)
    }

    fun record(key: ExitSourceKey): ExitImportResult {
        if (key in keys) return ExitImportResult.ALREADY_IMPORTED
        val nextBytes = bytes + key.encoded.toByteArray(StandardCharsets.US_ASCII).size
        if (keys.size >= maxEntries || nextBytes > maxBytes) return ExitImportResult.DISABLED_EXHAUSTED
        keys += key
        bytes = nextBytes
        return ExitImportResult.IMPORTED
    }

    fun imported(key: ExitSourceKey): Boolean = key in keys
    fun entryCount(): Int = keys.size
    fun usedBytes(): Int = bytes
}

enum class ExitLinkConfidence { UNMATCHED, POSSIBLE, PROBABLE, EXACT }

data class LocalExitEvidence(
    val processName: String,
    val timestampMillis: Long,
    val reason: Int,
    val pid: Int,
    val processInstanceToken: ByteArray?,
)

object ExitLinker {
    fun link(exit: SyntheticApplicationExitInfo, local: LocalExitEvidence?): ExitLinkConfidence {
        if (local == null) return ExitLinkConfidence.UNMATCHED
        if (exit.processStateSummary != null && local.processInstanceToken != null &&
            exit.processStateSummary.contentEquals(local.processInstanceToken)
        ) return ExitLinkConfidence.EXACT
        val sameName = exit.processName == local.processName
        val closeTime = kotlin.math.abs(exit.timestampMillis - local.timestampMillis) <= 300_000
        if (sameName && closeTime && exit.reason == local.reason) return ExitLinkConfidence.PROBABLE
        if (sameName && exit.pid == local.pid) return ExitLinkConfidence.POSSIBLE
        return ExitLinkConfidence.UNMATCHED
    }
}
