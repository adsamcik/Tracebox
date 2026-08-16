package dev.tracebox.anr

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import java.io.InputStream

enum class ExitRawReadState { NONE, AVAILABLE, OVERSIZED, READ_FAILED }

data class AndroidExitArtifact(
    val exit: SyntheticApplicationExitInfo,
    val rawBytes: ByteArray?,
    val rawReadState: ExitRawReadState,
)

object BoundedExitStreamReader {
    fun read(stream: InputStream?, maximumBytes: Int): Pair<ByteArray?, ExitRawReadState> {
        require(maximumBytes in 1..MAX_RAW_BYTES)
        if (stream == null) return null to ExitRawReadState.NONE
        return try {
            stream.use { input ->
                val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (total + count > maximumBytes) return null to ExitRawReadState.OVERSIZED
                    output.write(buffer, 0, count)
                    total += count
                }
                output.toByteArray() to ExitRawReadState.AVAILABLE
            }
        } catch (_: java.io.IOException) {
            null to ExitRawReadState.READ_FAILED
        } catch (_: SecurityException) {
            null to ExitRawReadState.READ_FAILED
        }
    }

    const val MAX_RAW_BYTES = 2 * 1024 * 1024
}

/** Documented `ApplicationExitInfo` fields copied before source-key derivation and journaling. */
data class AndroidExitInfoFields(
    val processName: String?,
    val packageUid: Int,
    val timestampMillis: Long,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val pid: Int,
    val processStateSummary: ByteArray?,
    val artifactKind: ExitArtifactKind,
)

/** Converts documented Android values to the stable source-key input without assigning PID identity. */
object ApplicationExitInfoMapper {
    fun map(packageName: String, fields: AndroidExitInfoFields): SyntheticApplicationExitInfo? {
        val processName = fields.processName?.takeIf(String::isNotBlank) ?: return null
        return SyntheticApplicationExitInfo(
            packageName = packageName,
            processName = processName,
            definingUid = fields.packageUid,
            timestampMillis = fields.timestampMillis,
            reason = fields.reason,
            status = fields.status,
            importance = fields.importance,
            pid = fields.pid,
            processStateSummary = fields.processStateSummary?.copyOf(),
            artifactKind = fields.artifactKind,
        )
    }
}

/** Capability-gated Android adapter. API 23-29 return no OS-managed exit records. */
class ApplicationExitInfoAdapter {
    /**
     * Returns bounded structural metadata for every documented UID process exit. Only ANRs are
     * marked as having a raw artifact; all other reasons are metadata-only.
     */
    fun exitHistory(context: Context, maxEntries: Int): List<SyntheticApplicationExitInfo> {
        require(maxEntries in 1..128)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return Api30.exitHistory(context, maxEntries)
    }

    fun anrHistory(context: Context, maxEntries: Int): List<SyntheticApplicationExitInfo> {
        require(maxEntries in 1..128)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return Api30.anrHistory(context, maxEntries)
    }

    fun anrArtifacts(
        context: Context,
        maxEntries: Int,
        maxRawBytes: Int = BoundedExitStreamReader.MAX_RAW_BYTES,
        rawEligible: (SyntheticApplicationExitInfo) -> Boolean = { false },
    ): List<AndroidExitArtifact> {
        require(maxEntries in 1..128)
        require(maxRawBytes in 1..BoundedExitStreamReader.MAX_RAW_BYTES)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return Api30.anrArtifacts(context, maxEntries, maxRawBytes, rawEligible)
    }

    /**
     * Reopens only the exact source whose durable import journal already exists. This avoids
     * materializing raw streams while merely censusing bounded OS metadata.
     */
    fun readAnrArtifact(
        context: Context,
        sourceKey: ExitSourceKey,
        maxEntries: Int,
        maxRawBytes: Int = BoundedExitStreamReader.MAX_RAW_BYTES,
    ): AndroidExitArtifact? {
        require(maxEntries in 1..128)
        require(maxRawBytes in 1..BoundedExitStreamReader.MAX_RAW_BYTES)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Api30.readAnrArtifact(context, sourceKey, maxEntries, maxRawBytes)
    }

    /**
     * Publishes the bounded capture-time token used by a later OS-exit import. Failure is explicit:
     * an exit without this token can still contribute C0 metadata, but its raw stream is never read.
     */
    fun publishPolicyToken(context: Context, token: ExitPolicyToken): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return Api30.publishPolicyToken(context, token.encode())
    }

    @TargetApi(Build.VERSION_CODES.R)
    private object Api30 {
        fun exitHistory(context: Context, maxEntries: Int): List<SyntheticApplicationExitInfo> {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            return activityManager.getHistoricalProcessExitReasons(null, 0, maxEntries)
                .mapNotNull { mapExit(context, it) }
        }

        fun anrHistory(context: Context, maxEntries: Int): List<SyntheticApplicationExitInfo> {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            return activityManager.getHistoricalProcessExitReasons(null, 0, maxEntries)
                .asSequence()
                .filter { it.reason == ApplicationExitInfo.REASON_ANR }
                .mapNotNull { mapExit(context, it) }
                .toList()
        }

        fun anrArtifacts(
            context: Context,
            maxEntries: Int,
            maxRawBytes: Int,
            rawEligible: (SyntheticApplicationExitInfo) -> Boolean,
        ): List<AndroidExitArtifact> {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            return activityManager.getHistoricalProcessExitReasons(null, 0, maxEntries)
                .asSequence()
                .filter { it.reason == ApplicationExitInfo.REASON_ANR }
                .mapNotNull { exit ->
                    val mapped = mapExit(context, exit) ?: return@mapNotNull null
                    val (raw, state) = if (rawEligible(mapped)) {
                        readTrace(exit, maxRawBytes)
                    } else {
                        null to ExitRawReadState.NONE
                    }
                    AndroidExitArtifact(mapped, raw, state)
                }
                .toList()
        }

        fun readAnrArtifact(
            context: Context,
            sourceKey: ExitSourceKey,
            maxEntries: Int,
            maxRawBytes: Int,
        ): AndroidExitArtifact? {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
            return activityManager.getHistoricalProcessExitReasons(null, 0, maxEntries)
                .asSequence()
                .filter { it.reason == ApplicationExitInfo.REASON_ANR }
                .mapNotNull { exit ->
                    val mapped = mapExit(context, exit) ?: return@mapNotNull null
                    if (ExitSourceKey.derive(mapped) != sourceKey) return@mapNotNull null
                    val (raw, state) = readTrace(exit, maxRawBytes)
                    AndroidExitArtifact(mapped, raw, state)
                }
                .firstOrNull()
        }

        private fun mapExit(
            context: Context,
            exit: ApplicationExitInfo,
        ): SyntheticApplicationExitInfo? =
            ApplicationExitInfoMapper.map(
                context.packageName,
                AndroidExitInfoFields(
                    processName = exit.processName,
                    packageUid = exit.packageUid,
                    timestampMillis = exit.timestamp,
                    reason = exit.reason,
                    status = exit.status,
                    importance = exit.importance,
                    pid = exit.pid,
                    processStateSummary = exit.processStateSummary,
                    artifactKind = if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                        ExitArtifactKind.ANR_TRACE
                    } else {
                        ExitArtifactKind.NONE
                    },
                ),
            )

        private fun readTrace(
            exit: ApplicationExitInfo,
            maxRawBytes: Int,
        ): Pair<ByteArray?, ExitRawReadState> =
            try {
                BoundedExitStreamReader.read(exit.traceInputStream, maxRawBytes)
            } catch (_: java.io.IOException) {
                null to ExitRawReadState.READ_FAILED
            } catch (_: SecurityException) {
                null to ExitRawReadState.READ_FAILED
            }

        fun publishPolicyToken(context: Context, encoded: ByteArray): Boolean {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
            return try {
                activityManager.setProcessStateSummary(encoded)
                true
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }
    }
}
