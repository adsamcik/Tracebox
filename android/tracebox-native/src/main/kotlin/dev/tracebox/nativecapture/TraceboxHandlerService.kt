package dev.tracebox.nativecapture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dev.tracebox.storage.ExistingUidStorageMutationLease
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The production capture-only handler process. It has no binder recording API, ordinary writer,
 * periodic work, or ANR watchdog; native bounded IPC is its sole client transport.
 */
class TraceboxHandlerService : Service() {
    private val handlerStarted = AtomicBoolean()
    private val lifetimeBinder = Binder()

    @Volatile
    private var handlerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val processIdentity = intent?.getByteArrayExtra(EXTRA_PROCESS_IDENTITY)
        val startToken = intent?.getByteArrayExtra(EXTRA_START_TOKEN)
        val policyEpoch = intent?.getLongExtra(EXTRA_POLICY_EPOCH, INVALID_EPOCH) ?: INVALID_EPOCH
        val disabled = intent?.getBooleanExtra(EXTRA_POLICY_DISABLED, true) ?: true
        val denyMask = intent?.getLongExtra(EXTRA_POLICY_DENY_MASK, Long.MAX_VALUE) ?: Long.MAX_VALUE
        if (processIdentity?.size != PROCESS_IDENTITY_BYTES ||
            processIdentity.all { it == 0.toByte() } ||
            startToken?.size != HandlerStartPermit.TOKEN_BYTES ||
            startToken.all { it == 0.toByte() } ||
            policyEpoch < 0
        ) {
            return rejectStart(startId)
        }

        val paths = try {
            val deviceProtectedNoBackup = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext().noBackupFilesDir
            } else {
                noBackupFilesDir
            }
            handlerPaths(noBackupFilesDir, deviceProtectedNoBackup)
        } catch (_: IOException) {
            return rejectStart(startId)
        } catch (_: SecurityException) {
            return rejectStart(startId)
        }
        val lease = ExistingUidStorageMutationLease.tryAcquire(
            paths.mutationBarrierRoot.toPath(),
            STARTUP_FENCE_TIMEOUT_MILLIS,
        ) ?: return rejectStart(startId)

        var releaseLease = true
        try {
            val expected = HandlerStartupExpectation(policyEpoch, disabled, denyMask)
            if (handlerStartupState(paths.storageRoot.toPath(), expected) !=
                HandlerStartupState.ELIGIBLE
            ) {
                return rejectStart(startId)
            }
            if (HandlerStartPermit.consume(
                    paths.handlerDirectory.toPath(),
                    processIdentity,
                    policyEpoch,
                    startToken,
                ) != HandlerStartPermitConsumeResult.CONSUMED
            ) {
                return rejectStart(startId)
            }
            if (!paths.handlerDirectory.isDirectory) {
                return rejectStart(startId)
            }

            val socket = paths.handlerDirectory.toPath().resolve(SOCKET_NAME)
            if (!handlerStarted.get()) {
                val staleRemoved = try {
                    !Files.exists(socket, LinkOption.NOFOLLOW_LINKS) ||
                        NativeRuntime.cleanupStaleHandlerSocket(socket.toString())
                } catch (_: LinkageError) {
                    false
                } catch (_: RuntimeException) {
                    false
                }
                if (!staleRemoved || Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
                    return rejectStart(startId)
                }
            }

            val initialized = try {
                NativeRuntime.initializeEmergency(
                    paths.handlerDirectory.absolutePath,
                    PROCESS_ROLE_HANDLER,
                    processIdentity,
                    policyEpoch,
                ) && NativeRuntime.updatePolicy(policyEpoch, disabled, denyMask)
            } catch (_: LinkageError) {
                false
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: RuntimeException) {
                false
            }
            if (!initialized) {
                return rejectStart(startId)
            }

            if (handlerStarted.compareAndSet(false, true)) {
                Thread(
                    {
                        try {
                            NativeRuntime.startHandler(socket.toString())
                        } finally {
                            try {
                                drainNativeHandlerCapture()
                            } finally {
                                handlerThread = null
                                handlerStarted.set(false)
                                stopSelf()
                            }
                        }
                    },
                    "tracebox-native-handler",
                ).apply {
                    isDaemon = true
                    handlerThread = this
                    start()
                }
            }

            if (!awaitHandlerReady(socket)) {
                drainNativeHandlerCapture()
                if (!awaitHandlerStopped(socket)) {
                    // Keep the OS file lock held until SIGKILL tears down this dedicated process.
                    // Releasing it while native startup is still live would re-open the
                    // post-deletion resurrection race this fence exists to close.
                    releaseLease = false
                    stopSelf(startId)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return START_NOT_STICKY
                }
                return rejectStart(startId)
            }
            return START_NOT_STICKY
        } catch (_: IOException) {
            drainNativeHandlerCapture()
            return rejectStart(startId)
        } catch (_: SecurityException) {
            drainNativeHandlerCapture()
            return rejectStart(startId)
        } catch (_: RuntimeException) {
            drainNativeHandlerCapture()
            return rejectStart(startId)
        } catch (_: LinkageError) {
            return rejectStart(startId)
        } finally {
            if (releaseLease) lease.close()
        }
    }

    private fun awaitHandlerReady(socket: Path): Boolean {
        val deadline = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(HANDLER_READY_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            val ready = try {
                handlerStarted.get() &&
                    handlerThread?.isAlive == true &&
                    NativeRuntime.isHandlerAlive() &&
                    Files.exists(socket, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(socket)
            } catch (_: LinkageError) {
                false
            } catch (_: RuntimeException) {
                false
            }
            if (ready) return true
            if (!handlerStarted.get()) return false
            if (!pauseStartupPoll()) return false
        }
        return false
    }

    private fun awaitHandlerStopped(socket: Path): Boolean {
        val deadline = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(HANDLER_STOP_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            if (!handlerStarted.get() &&
                handlerThread?.isAlive != true &&
                !Files.exists(socket, LinkOption.NOFOLLOW_LINKS)
            ) {
                return true
            }
            if (!pauseStartupPoll()) return false
        }
        return !handlerStarted.get() &&
            handlerThread?.isAlive != true &&
            !Files.exists(socket, LinkOption.NOFOLLOW_LINKS)
    }

    private fun pauseStartupPoll(): Boolean =
        try {
            Thread.sleep(STARTUP_POLL_MILLIS)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun rejectStart(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /**
     * This binder deliberately exposes no recording or control API. The primary process holds
     * the connection only to make this a bound service, which is exempt from Android's timed
     * background-started-service shutdown while its crashing client process remains alive.
     */
    override fun onBind(intent: Intent?): IBinder = lifetimeBinder

    override fun onDestroy() {
        try {
            drainNativeHandlerCapture()
        } finally {
            try {
                super.onDestroy()
            } finally {
                // Crashpad's HandlerMain is single-use within one process. This service owns a
                // dedicated process, so end that process with the drained service lifetime rather
                // than letting Android cache native singleton state for the next policy epoch.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun drainNativeHandlerCapture(): Boolean =
        drainNativeHandlerCaptureWith { timeoutMillis ->
            NativeRuntime.shutdownCaptureAndDrain(timeoutMillis)
        }

    companion object {
        const val PROCESS_ROLE_HANDLER = 2
        const val SOCKET_NAME = "tracebox-handler.sock"
        const val HANDLER_RELATIVE_DIRECTORY = "tracebox/native-handler"
        const val EXTRA_PROCESS_IDENTITY = "dev.tracebox.nativecapture.PROCESS_IDENTITY"
        const val EXTRA_START_TOKEN = "dev.tracebox.nativecapture.START_TOKEN"
        const val EXTRA_POLICY_EPOCH = "dev.tracebox.nativecapture.POLICY_EPOCH"
        const val EXTRA_POLICY_DISABLED = "dev.tracebox.nativecapture.POLICY_DISABLED"
        const val EXTRA_POLICY_DENY_MASK = "dev.tracebox.nativecapture.POLICY_DENY_MASK"

        fun startIntent(
            context: Context,
            processIdentity: ByteArray,
            policyEpoch: Long,
            disabled: Boolean,
            denyMask: Long,
            startToken: ByteArray,
        ): Intent {
            require(processIdentity.size == PROCESS_IDENTITY_BYTES) {
                "processIdentity must contain exactly $PROCESS_IDENTITY_BYTES bytes"
            }
            require(processIdentity.any { it != 0.toByte() }) {
                "processIdentity must not be all zero"
            }
            require(startToken.size == HandlerStartPermit.TOKEN_BYTES) {
                "startToken must contain exactly ${HandlerStartPermit.TOKEN_BYTES} bytes"
            }
            require(startToken.any { it != 0.toByte() }) {
                "startToken must not be all zero"
            }
            require(policyEpoch >= 0) { "policyEpoch must be non-negative" }
            return Intent(context, TraceboxHandlerService::class.java)
                .putExtra(EXTRA_PROCESS_IDENTITY, processIdentity.copyOf())
                .putExtra(EXTRA_START_TOKEN, startToken.copyOf())
                .putExtra(EXTRA_POLICY_EPOCH, policyEpoch)
                .putExtra(EXTRA_POLICY_DISABLED, disabled)
                .putExtra(EXTRA_POLICY_DENY_MASK, denyMask)
        }

        private const val PROCESS_IDENTITY_BYTES = 32
        private const val INVALID_EPOCH = -1L
        private const val STARTUP_FENCE_TIMEOUT_MILLIS = 2_000L
        private const val HANDLER_READY_TIMEOUT_MILLIS = 2_000L
        private const val HANDLER_STOP_TIMEOUT_MILLIS = 4_000L
        private const val STARTUP_POLL_MILLIS = 5L

        internal fun handlerPaths(
            credentialNoBackupDirectory: File,
            deviceProtectedNoBackupDirectory: File,
        ): HandlerPaths {
            val credentialNoBackupRoot = credentialNoBackupDirectory.canonicalFile
            val deviceProtectedNoBackupRoot = deviceProtectedNoBackupDirectory.canonicalFile
            val storageRoot =
                File(credentialNoBackupRoot, STORAGE_ROOT_DIRECTORY_NAME).canonicalFile
            val handlerDirectory = File(storageRoot, HANDLER_DIRECTORY_NAME).canonicalFile
            val mutationBarrierRoot =
                File(
                    deviceProtectedNoBackupRoot,
                    DIRECT_BOOT_ROOT_DIRECTORY_NAME,
                ).canonicalFile
            val beneathCredentialNoBackup =
                storageRoot.path.startsWith(
                    "${credentialNoBackupRoot.path}${File.separator}",
                ) &&
                    handlerDirectory.path.startsWith("${storageRoot.path}${File.separator}")
            val beneathDeviceProtectedNoBackup =
                mutationBarrierRoot.path.startsWith(
                    "${deviceProtectedNoBackupRoot.path}${File.separator}",
                )
            if (!beneathCredentialNoBackup ||
                !beneathDeviceProtectedNoBackup ||
                handlerDirectory.parentFile != storageRoot ||
                mutationBarrierRoot.parentFile != deviceProtectedNoBackupRoot
            ) {
                throw IOException("unsafe Tracebox handler path")
            }
            return HandlerPaths(storageRoot, handlerDirectory, mutationBarrierRoot)
        }

        internal data class HandlerPaths(
            val storageRoot: File,
            val handlerDirectory: File,
            val mutationBarrierRoot: File,
        )

        internal const val DIRECT_BOOT_ROOT_DIRECTORY_NAME = "tracebox-directboot"
    }
}

/**
 * Testable service boundary for the process-local native drain. Native `false`
 * is deliberately preserved: callers must keep the socket marker authoritative
 * and may retry or let process death make it eligible for stale cleanup.
 */
internal fun drainNativeHandlerCaptureWith(
    shutdown: (Int) -> Boolean,
): Boolean =
    try {
        shutdown(NativeRuntime.DEFAULT_HANDLER_DRAIN_TIMEOUT_MILLIS)
    } catch (_: LinkageError) {
        false
    } catch (_: RuntimeException) {
        false
    }
