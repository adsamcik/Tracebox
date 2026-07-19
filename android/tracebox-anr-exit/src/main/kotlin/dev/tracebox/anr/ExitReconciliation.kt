package dev.tracebox.anr

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

/** Minimal documented-API-shaped input; the Android adapter maps ApplicationExitInfo into this value. */
data class SyntheticApplicationExitInfo(
    val packageName: String,
    val processName: String,
    val definingUid: Int,
    val timestampMillis: Long,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val pid: Int,
    val processStateSummary: ByteArray?,
    val artifactKind: ExitArtifactKind,
) {
    init {
        require(packageName.isNotBlank() && packageName.toByteArray(StandardCharsets.UTF_8).size <= 256)
        require(processName.isNotBlank() && processName.toByteArray(StandardCharsets.UTF_8).size <= 256)
        require(definingUid >= 0)
        require(pid >= 0)
        require(processStateSummary == null || processStateSummary.size <= 128)
    }
}

/** The documented raw source associated with an OS exit record. */
enum class ExitArtifactKind { ANR_TRACE, NATIVE_TOMBSTONE }

/** Installation-lifetime exact OS source key; no bounded-history record is imported twice. */
@JvmInline
value class ExitSourceKey(val encoded: String) {
    companion object {
        fun derive(exit: SyntheticApplicationExitInfo): ExitSourceKey {
            val digest = MessageDigest.getInstance("SHA-256")
            updateBytes(digest, "tracebox-exit-source-v1".toByteArray(StandardCharsets.UTF_8))
            updateBytes(digest, exit.packageName.toByteArray(StandardCharsets.UTF_8))
            updateBytes(digest, exit.processName.toByteArray(StandardCharsets.UTF_8))
            updateInt(digest, exit.definingUid)
            updateLong(digest, exit.timestampMillis)
            updateInt(digest, exit.reason)
            updateInt(digest, exit.status)
            updateInt(digest, exit.importance)
            updateInt(digest, exit.pid)
            updateInt(digest, exit.artifactKind.ordinal)
            val summaryDigest = exit.processStateSummary?.let {
                MessageDigest.getInstance("SHA-256").digest(it)
            }
            updateInt(digest, if (summaryDigest == null) 0 else 1)
            if (summaryDigest != null) updateBytes(digest, summaryDigest)
            return ExitSourceKey(Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest()))
        }

        private fun updateInt(digest: MessageDigest, value: Int) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
        }

        private fun updateLong(digest: MessageDigest, value: Long) {
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
        }

        private fun updateBytes(digest: MessageDigest, value: ByteArray) {
            updateInt(digest, value.size)
            digest.update(value)
        }
    }
}

enum class ExitImportResult { IMPORTED, ALREADY_IMPORTED, DISABLED_EXHAUSTED }

/**
 * Bounded installation-lifetime exact source tombstones. Exhaustion deliberately disables new
 * imports rather than evicting an entry and breaking idempotency.
 */
class ExitTombstoneLedger(
    private val storagePath: Path,
    private val maxEntries: Int,
    private val maxBytes: Int,
) {
    private val keys = linkedSetOf<ExitSourceKey>()
    private var bytes = 0
    private var disabled = false

    init {
        require(maxEntries > 0 && maxBytes > 0)
        load()
    }

    @Synchronized
    fun record(key: ExitSourceKey): ExitImportResult {
        if (key in keys) return ExitImportResult.ALREADY_IMPORTED
        if (disabled) return ExitImportResult.DISABLED_EXHAUSTED
        val nextBytes = bytes + key.encoded.toByteArray(StandardCharsets.US_ASCII).size
        if (keys.size >= maxEntries || nextBytes > maxBytes) {
            disabled = true
            persist()
            return ExitImportResult.DISABLED_EXHAUSTED
        }
        keys += key
        bytes = nextBytes
        persist()
        return ExitImportResult.IMPORTED
    }

    @Synchronized
    fun imported(key: ExitSourceKey): Boolean = key in keys

    @Synchronized
    fun entryCount(): Int = keys.size

    @Synchronized
    fun usedBytes(): Int = bytes

    private fun load() {
        if (!Files.exists(storagePath)) return
        val lines = Files.readAllLines(storagePath, StandardCharsets.US_ASCII)
        if (lines.firstOrNull() !in setOf(LEDGER_ENABLED, LEDGER_DISABLED)) {
            throw IllegalStateException("invalid exit tombstone ledger")
        }
        disabled = lines.first() == LEDGER_DISABLED
        lines.drop(1).filter(String::isNotBlank).forEach { encoded ->
            val key = ExitSourceKey(encoded)
            if (keys.add(key)) bytes += encoded.toByteArray(StandardCharsets.US_ASCII).size
        }
        if (keys.size > maxEntries || bytes > maxBytes) disabled = true
    }

    private fun persist() {
        Files.createDirectories(storagePath.parent)
        val temporary = storagePath.resolveSibling("${storagePath.fileName}.new")
        val contents = buildString {
            append(if (disabled) LEDGER_DISABLED else LEDGER_ENABLED).append('\n')
            keys.forEach { append(it.encoded).append('\n') }
        }
        Files.writeString(
            temporary,
            contents,
            StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        try {
            Files.move(temporary, storagePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, storagePath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val LEDGER_ENABLED = "tracebox-exit-tombstones-v1|enabled"
        const val LEDGER_DISABLED = "tracebox-exit-tombstones-v1|disabled"
    }
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
