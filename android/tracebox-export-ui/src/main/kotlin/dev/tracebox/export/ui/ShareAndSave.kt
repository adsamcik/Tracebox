package dev.tracebox.export.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.UUID

fun interface ExportClock { fun nowMillis(): Long }

private class StagingLeaseManagerHostTestHooks(
    val afterLeaseFileWritten: () -> Unit,
    val deletePartialFile: (Path) -> Unit = { Files.deleteIfExists(it) },
)

class StagingLease internal constructor(
    val path: Path,
    val expiresAtMillis: Long,
    private val releaseReservation: () -> Unit,
) {
    internal fun release() = releaseReservation()
}

class StagingLeaseManager private constructor(
    private val directory: Path,
    private val clock: ExportClock,
    private val hostTestHooks: StagingLeaseManagerHostTestHooks?,
) {
    constructor(directory: Path, clock: ExportClock) : this(directory, clock, null)
    internal constructor(
        directory: Path,
        clock: ExportClock,
        afterLeaseFileWrittenForHostTest: () -> Unit,
    ) : this(directory, clock, StagingLeaseManagerHostTestHooks(afterLeaseFileWrittenForHostTest))
    internal constructor(
        directory: Path,
        clock: ExportClock,
        afterLeaseFileWrittenForHostTest: () -> Unit,
        deletePartialFileForHostTest: (Path) -> Unit,
    ) : this(
        directory,
        clock,
        StagingLeaseManagerHostTestHooks(afterLeaseFileWrittenForHostTest, deletePartialFileForHostTest),
    )

    private val leaseLock = Any()
    private val reservationReleases = mutableMapOf<Path, () -> Unit>()
    init { require(directory.fileName.toString() == STAGING_DIRECTORY) }

    fun stage(approved: TraceboxDisclosureActivity.ApprovedPackage, ttlMillis: Long): StagingLease {
        return stageReservedForHostTest(
            approved.exactBytes(),
            approved::reserveStagingQuota,
            ttlMillis,
        )
    }

    /** Shared staging write path; host tests inject a rejected reservation without minting approval. */
    internal fun stageReservedForHostTest(
        exactBytes: ByteArray,
        reserveLease: (Path) -> (() -> Unit)?,
        ttlMillis: Long,
    ): StagingLease = synchronized(leaseLock) {
        require(ttlMillis > 0)
        Files.createDirectories(directory)
        val output = directory.resolve("tbdiag-${UUID.randomUUID()}.tbdiag")
        val releaseReservation = reserveLease(output) ?: run {
            throw IllegalStateException("finalized package has no available staging quota reservation")
        }
        try {
            Files.write(output, exactBytes)
            val expiry = clock.nowMillis() + ttlMillis
            Files.setLastModifiedTime(output, FileTime.fromMillis(expiry))
            hostTestHooks?.afterLeaseFileWritten?.invoke()
            reservationReleases[output] = releaseReservation
            return@synchronized StagingLease(output, expiry) { release(output) }
        } catch (failure: Throwable) {
            try {
                hostTestHooks?.deletePartialFile?.invoke(output) ?: Files.deleteIfExists(output)
            } finally {
                releaseReservation()
            }
            throw failure
        }
    }

    fun cleanupExpired(): List<Path> {
        return synchronized(leaseLock) {
            if (!Files.exists(directory)) return@synchronized emptyList()
            Files.list(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) && Files.getLastModifiedTime(it).toMillis() <= clock.nowMillis() }
                    .toList().also { expired ->
                        expired.forEach { path ->
                            Files.deleteIfExists(path)
                            reservationReleases.remove(path)?.invoke()
                        }
                    }
            }
        }
    }

    private fun release(path: Path) = synchronized(leaseLock) {
        reservationReleases.remove(path)?.invoke()
    }

    companion object {
        const val STAGING_DIRECTORY = "tracebox-export-staging"
    }
}

object Sharesheet {
    const val REQUEST_CODE = 4904

    fun chooser(context: Context, lease: StagingLease): Intent {
        val uri = TraceboxFileProvider.uriForFile(context, lease.path)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri("Tracebox package", uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share Tracebox package")
    }

    /** Android result data is optional; no result is honestly a delivery-unknown outcome. */
    fun observedResult(data: Intent?): ShareHandoffState =
        if (data?.data != null) ShareHandoffState.TARGET_SELECTED else ShareHandoffState.DELIVERY_UNKNOWN
}

class SafPackageSaver {
    fun createDocumentIntent(displayName: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_TITLE, displayName)

    fun copyFinalized(
        approved: TraceboxDisclosureActivity.ApprovedPackage,
        destination: () -> OutputStream,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): SaveResult {
        val bytes = approved.exactBytes()
        var written = 0L
        try {
            destination().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    if (isCancelled()) return SaveResult.PartialCopyWarning(written, cancelled = true)
                    val count = minOf(CHUNK_SIZE, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                    written += count
                    onProgress(written)
                }
                output.flush()
            }
        } catch (failure: java.io.IOException) {
            return SaveResult.Failed(failure.message ?: "SAF copy failed")
        }
        return SaveResult.Complete(written)
    }

    fun copyFinalizedInBackground(
        executor: Executor,
        approved: TraceboxDisclosureActivity.ApprovedPackage,
        destination: () -> OutputStream,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): Future<SaveResult> = executeFuture(executor) {
        copyFinalized(approved, destination, isCancelled, onProgress)
    }

    companion object { private const val CHUNK_SIZE = 8 * 1024 }
}

/**
 * Future and FutureTask are available throughout Tracebox's API 23 range. FutureTask preserves the
 * executor scheduling, cancellation, result, and exceptional-completion behavior without exposing
 * an API 24 CompletableFuture in the library API.
 */
internal fun <T> executeFuture(executor: Executor, operation: () -> T): Future<T> {
    val task = FutureTask(Callable { operation() })
    executor.execute(task)
    return task
}
