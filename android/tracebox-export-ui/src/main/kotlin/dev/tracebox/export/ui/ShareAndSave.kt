package dev.tracebox.export.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.UUID

fun interface ExportClock { fun nowMillis(): Long }

class StagingLease internal constructor(
    val path: Path,
    val expiresAtMillis: Long,
    private val releaseReservation: () -> Unit,
) {
    internal fun release() = releaseReservation()
}

class StagingLeaseManager(private val directory: Path, private val clock: ExportClock) {
    private val reservationReleases = mutableMapOf<Path, () -> Unit>()
    init { require(directory.fileName.toString() == STAGING_DIRECTORY) }

    fun stage(approved: TraceboxDisclosureActivity.ApprovedPackage, ttlMillis: Long): StagingLease {
        return stageReservedForHostTest(
            approved.exactBytes(),
            approved::transferQuotaReservation,
            { approved.releaseQuotaReservation() },
            ttlMillis,
        )
    }

    /** Shared staging write path; host tests inject a rejected reservation without minting approval. */
    internal fun stageReservedForHostTest(
        exactBytes: ByteArray,
        transferReservation: (Path) -> Boolean,
        releaseReservation: () -> Unit,
        ttlMillis: Long,
    ): StagingLease {
        require(ttlMillis > 0)
        Files.createDirectories(directory)
        val output = directory.resolve("tbdiag-${UUID.randomUUID()}.tbdiag")
        if (!transferReservation(output)) {
            throw IllegalStateException("finalized package has no available staging quota reservation")
        }
        try { Files.write(output, exactBytes) } catch (failure: Throwable) {
            releaseReservation()
            throw failure
        }
        val expiry = clock.nowMillis() + ttlMillis
        Files.setLastModifiedTime(output, FileTime.fromMillis(expiry))
        reservationReleases[output] = releaseReservation
        return StagingLease(output, expiry, releaseReservation)
    }

    fun cleanupExpired(): List<Path> {
        if (!Files.exists(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && Files.getLastModifiedTime(it).toMillis() <= clock.nowMillis() }
                .toList().also { expired ->
                    expired.forEach { path ->
                        Files.deleteIfExists(path)
                        reservationReleases.remove(path)?.invoke()
                    }
                }
        }
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
    ): CompletableFuture<SaveResult> = CompletableFuture.supplyAsync(
        { copyFinalized(approved, destination, isCancelled, onProgress) },
        executor,
    )

    companion object { private const val CHUNK_SIZE = 8 * 1024 }
}
