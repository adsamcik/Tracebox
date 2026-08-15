package dev.tracebox.storage

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Rechecked while the UID-wide storage mutation barrier is held.
 *
 * Production uses this to combine the durable capture policy with every owned root's eligibility
 * marker. Throwing is treated as a denial so an unavailable policy control fails closed.
 */
fun interface StorageMutationEligibility {
    fun allowsMutation(): Boolean

    companion object {
        val ALWAYS = StorageMutationEligibility { true }
    }
}

/** Result of entering the mutation barrier and rechecking [StorageMutationEligibility]. */
sealed interface StorageMutationBarrierResult<out T> {
    data class Applied<T>(val value: T) : StorageMutationBarrierResult<T>
    data object Rejected : StorageMutationBarrierResult<Nothing>
}

/** The cross-process mutation lock could not be obtained safely. */
class StorageMutationBarrierException internal constructor(cause: IOException) :
    IllegalStateException("UID-wide storage mutation barrier is unavailable", cause)

/**
 * A bounded lease on an already-created UID storage-mutation lock.
 *
 * This is intentionally narrower than [UidWideQuotaCoordinator.withStorageMutation]: it never
 * creates the root or lock file and performs no quota operation. It exists for a delayed Android
 * handler process which must join the same exclusion domain before its first filesystem mutation.
 * The lease must be closed by the thread which acquired it.
 */
class ExistingUidStorageMutationLease private constructor(
    private val processLock: ReentrantLock,
    private val channel: FileChannel,
    private val fileLock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            runCatching { fileLock.release() }
            runCatching { channel.close() }
        } finally {
            processLock.unlock()
        }
    }

    companion object {
        /**
         * Attempts to lock an existing safe root for at most [timeoutMillis].
         *
         * Missing roots and lock files are rejected without creating either. A caller in another
         * process which is started while the façade still holds this lock times out and must be
         * retried after the façade operation; starting it synchronously from inside that policy
         * barrier would otherwise form a cross-process re-entry deadlock.
         */
        fun tryAcquire(root: Path, timeoutMillis: Long): ExistingUidStorageMutationLease? {
            require(timeoutMillis in 1..MAX_EXISTING_LEASE_TIMEOUT_MILLIS)
            val normalized = runCatching { safeStorageRoot(root) }.getOrNull() ?: return null
            val lockPath = normalized.resolve(UidWideStorageMutationBarrier.LOCK_FILE_NAME)
            if (!existingLockIsSafe(normalized, lockPath)) return null

            val processLock = storageMutationProcessLock(lockPath)
            val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val started = System.nanoTime()
            val processAcquired = try {
                processLock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!processAcquired) return null

            var channel: FileChannel? = null
            var transferred = false
            try {
                // The internal barrier is reentrant within one process, but this public lease is
                // a cross-process handoff. Refuse same-thread nesting instead of self-deadlocking
                // on Java's non-reentrant FileLock.
                if (processLock.holdCount > 1 || !existingLockIsSafe(normalized, lockPath)) {
                    return null
                }
                val opened = try {
                    FileChannel.open(
                        lockPath,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: IOException) {
                    return null
                } catch (_: UnsupportedOperationException) {
                    return null
                } catch (_: SecurityException) {
                    return null
                }
                channel = opened

                while (true) {
                    val acquired = try {
                        opened.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    } catch (_: IOException) {
                        return null
                    }
                    if (acquired != null) {
                        if (!existingLockIsSafe(normalized, lockPath)) {
                            runCatching { acquired.release() }
                            return null
                        }
                        transferred = true
                        return ExistingUidStorageMutationLease(
                            processLock,
                            channel,
                            acquired,
                        )
                    }
                    val elapsed = System.nanoTime() - started
                    if (elapsed >= timeoutNanos) return null
                    val remaining = timeoutNanos - elapsed
                    try {
                        TimeUnit.NANOSECONDS.sleep(
                            minOf(remaining, EXISTING_LEASE_POLL_NANOS),
                        )
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            } finally {
                if (!transferred) {
                    runCatching { channel?.close() }
                    processLock.unlock()
                }
            }
        }

        private fun existingLockIsSafe(root: Path, lockPath: Path): Boolean =
            runCatching {
                !hasSymbolicLinkComponent(root) &&
                    Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(lockPath) &&
                    Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)
            }.getOrDefault(false)

        private const val MAX_EXISTING_LEASE_TIMEOUT_MILLIS = 60_000L
        private val EXISTING_LEASE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5)
    }
}

/**
 * UID-wide, cross-process exclusion for physical storage mutations.
 *
 * This deliberately uses a different file from the quota-ledger lock. Reconciliation and
 * deletion hold this barrier while performing nested quota transactions, which would otherwise
 * attempt to re-enter Java's non-reentrant file lock. A process-wide reentrant lock also prevents
 * `OverlappingFileLockException` when separate coordinator instances target the same root.
 */
internal class UidWideStorageMutationBarrier(root: Path) {
    private val root = safeStorageRoot(root)
    private val lockPath = this.root.resolve(LOCK_FILE_NAME)
    private val processLock = storageMutationProcessLock(lockPath)

    fun <T> withExclusiveMutation(block: () -> T): T {
        processLock.lock()
        try {
            if (processLock.holdCount > 1) return block()
            prepareRoot()
            try {
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    channel.lock().use {
                        return block()
                    }
                }
            } catch (failure: IOException) {
                throw StorageMutationBarrierException(failure)
            }
        } finally {
            processLock.unlock()
        }
    }

    fun <T> mutateIfEligible(
        eligibility: StorageMutationEligibility,
        block: () -> T,
    ): StorageMutationBarrierResult<T> = withExclusiveMutation {
        if (!runCatching { eligibility.allowsMutation() }.getOrDefault(false)) {
            StorageMutationBarrierResult.Rejected
        } else {
            StorageMutationBarrierResult.Applied(block())
        }
    }

    private fun prepareRoot() {
        try {
            if (hasSymbolicLinkComponent(root)) {
                throw IOException("symbolic-link mutation-barrier root is forbidden")
            }
            Files.createDirectories(root)
            if (hasSymbolicLinkComponent(root) ||
                !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(lockPath)
            ) {
                throw IOException("unsafe mutation-barrier root")
            }
        } catch (failure: IOException) {
            throw StorageMutationBarrierException(failure)
        }
    }

    companion object {
        const val LOCK_FILE_NAME = ".tracebox-storage-mutation.lock"
    }
}

private val STORAGE_MUTATION_PROCESS_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()

private fun storageMutationProcessLock(lockPath: Path): ReentrantLock =
    STORAGE_MUTATION_PROCESS_LOCKS.computeIfAbsent(
        lockPath.toAbsolutePath().normalize(),
    ) {
        ReentrantLock(true)
    }

internal fun <T> guardedStorageMutation(
    coordinator: UidWideQuotaCoordinator?,
    eligibility: StorageMutationEligibility,
    block: () -> T,
): StorageMutationBarrierResult<T> =
    coordinator?.mutateStorageIfEligible(eligibility, block)
        ?: if (runCatching { eligibility.allowsMutation() }.getOrDefault(false)) {
            StorageMutationBarrierResult.Applied(block())
        } else {
            StorageMutationBarrierResult.Rejected
        }
