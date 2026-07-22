package dev.tracebox.anr

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.annotation.TargetApi
import android.content.Context
import android.os.Build

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

/**
 * Capability-gated Android adapter. API 23-29 return no OS records; only ANR exits are surfaced
 * here because they are the documented API-30 raw trace source handled by the import journal.
 */
class ApplicationExitInfoAdapter {
    fun anrHistory(context: Context, maxEntries: Int): List<SyntheticApplicationExitInfo> {
        require(maxEntries in 1..128)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return Api30.anrHistory(context, maxEntries)
    }

    @TargetApi(Build.VERSION_CODES.R)
    private object Api30 {
        fun anrHistory(context: Context, maxEntries: Int): List<SyntheticApplicationExitInfo> {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            return activityManager.getHistoricalProcessExitReasons(null, 0, maxEntries)
                .asSequence()
                .filter { it.reason == ApplicationExitInfo.REASON_ANR }
                .mapNotNull { exit ->
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
                            artifactKind = ExitArtifactKind.ANR_TRACE,
                        ),
                    )
                }
                .toList()
        }
    }
}
