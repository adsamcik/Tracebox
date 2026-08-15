package dev.tracebox

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import dev.tracebox.anr.AnrCandidate
import dev.tracebox.anr.AnrPolicy
import dev.tracebox.anr.AnrWatchdog
import dev.tracebox.anr.ApplicationExitInfoAdapter
import dev.tracebox.anr.ExitArtifactKind
import dev.tracebox.anr.ExitImportEntry
import dev.tracebox.anr.ExitImportJournal
import dev.tracebox.anr.ExitImportResult
import dev.tracebox.anr.ExitImportStage
import dev.tracebox.anr.ExitImportTerminalization
import dev.tracebox.anr.ExitImportTerminalizer
import dev.tracebox.anr.ExitLinkConfidence
import dev.tracebox.anr.ExitPolicyToken
import dev.tracebox.anr.ExitRawArtifactProvenance
import dev.tracebox.anr.ExitRawReadState
import dev.tracebox.anr.ExitSourceKey
import dev.tracebox.anr.ExitTombstoneLedger
import dev.tracebox.api.ApprovalToken
import dev.tracebox.api.DeleteReport
import dev.tracebox.api.DeleteRequest
import dev.tracebox.api.DiagnosticContext
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.DiagnosticPackages
import dev.tracebox.api.Diagnostics
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.PackageDisclosure
import dev.tracebox.api.PackageOmissionReason
import dev.tracebox.api.PackagePreparationResult
import dev.tracebox.api.PackagePreview
import dev.tracebox.api.PackagePrivacyClass
import dev.tracebox.api.PackageRequest
import dev.tracebox.api.PackageResult
import dev.tracebox.api.PackageTransformation
import dev.tracebox.api.PackageWarning
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.Readiness
import dev.tracebox.api.SaveFailure
import dev.tracebox.api.SavePackageResult
import dev.tracebox.api.SharePackageResult
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.TraceboxHealth
import dev.tracebox.api.CaptureKind
import dev.tracebox.api.CrashReporter
import dev.tracebox.api.DiagnosticSummary
import dev.tracebox.api.LogLevel
import dev.tracebox.api.LogArgument
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.PrivacyConfiguration
import dev.tracebox.api.PerformanceMeasurement
import dev.tracebox.api.TraceboxLogger
import dev.tracebox.api.TraceboxPolicy
import dev.tracebox.api.generated.GeneratedDiagnostics
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedExceptionRecord
import dev.tracebox.api.generated.GeneratedAnrTrace
import dev.tracebox.api.generated.GeneratedManagedCrash
import dev.tracebox.api.generated.GeneratedOsExit
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.api.generated.GeneratedRustPanic
import dev.tracebox.api.generated.GeneratedSchemaFingerprint
import dev.tracebox.core.ControlPage
import dev.tracebox.core.GateResult
import dev.tracebox.core.PolicyPageException
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.PolicyTransitionException
import dev.tracebox.core.PolicyTransitionJournal
import dev.tracebox.core.PolicyTransitionLoad
import dev.tracebox.core.PolicyTransitionPhase
import dev.tracebox.core.RecordPriority
import dev.tracebox.core.WriterPolicyGate
import dev.tracebox.directboot.DenyMirror
import dev.tracebox.directboot.DenyState
import dev.tracebox.directboot.DirectBootDisableResult
import dev.tracebox.directboot.DirectBootLayout
import dev.tracebox.directboot.DirectBootManager
import dev.tracebox.directboot.DirectBootMutation
import dev.tracebox.directboot.DirectBootSetupResult
import dev.tracebox.directboot.DirectBootStorageMutationGuard
import dev.tracebox.directboot.DirectBootStorageMutationRequest
import dev.tracebox.directboot.GeneratedDirectBootSchemaFingerprint
import dev.tracebox.export.PackagePipelineResult
import dev.tracebox.export.RecoveredSnapshotRequestAdapter
import dev.tracebox.export.SnapshotPreparer
import dev.tracebox.export.StandardPackagePipeline
import dev.tracebox.export.ui.DisclosureDecodeResult
import dev.tracebox.export.ui.DisclosureRenderer
import dev.tracebox.export.ui.TraceboxFileProvider
import dev.tracebox.nativecapture.HandlerStartPermit
import dev.tracebox.nativecapture.NativeRuntime
import dev.tracebox.nativecapture.TraceboxHandlerService
import dev.tracebox.storage.GeneratedRecordSegmentAdapter
import dev.tracebox.storage.GeneratedRecordAppendResult
import dev.tracebox.storage.CrashpadClientLifecycleReconciler
import dev.tracebox.storage.CrashpadHandoffIngestor
import dev.tracebox.storage.CrashpadHandoffOutcome
import dev.tracebox.storage.CrashpadMinidumpSummarizer
import dev.tracebox.storage.CrashpadPendingHandoffRecoverer
import dev.tracebox.storage.CrashpadPendingRecoveryResult
import dev.tracebox.storage.DurableStructuralSummaryAppender
import dev.tracebox.storage.DurableSummaryAppendResult
import dev.tracebox.storage.EmergencyStartupIngestor
import dev.tracebox.storage.ExternalOwnedStorageMutationFailureReason
import dev.tracebox.storage.ExternalOwnedStorageMutationResult
import dev.tracebox.storage.JournaledStorageTreeDeletion
import dev.tracebox.storage.OwnedStorageDomain
import dev.tracebox.storage.OwnedStoragePath
import dev.tracebox.storage.OwnedStorageRoot
import dev.tracebox.storage.PersistedSegmentIdentity
import dev.tracebox.storage.RawArtifactKind
import dev.tracebox.storage.RawArtifactStore
import dev.tracebox.storage.RoleQuotaLedger
import dev.tracebox.storage.RoleQuotaPolicy
import dev.tracebox.storage.RustPanicStartupIngestor
import dev.tracebox.storage.SegmentHeader
import dev.tracebox.storage.SegmentWriter
import dev.tracebox.storage.StorageDeletionDenyCommit
import dev.tracebox.storage.StorageDeletionDenyVerification
import dev.tracebox.storage.StorageMutationBarrierResult
import dev.tracebox.storage.StorageMutationEligibility
import dev.tracebox.storage.StorageOwnershipReport
import dev.tracebox.storage.StorageQuiesceParticipant
import dev.tracebox.storage.StorageRootReactivationResult
import dev.tracebox.storage.StorageTreeDeletionFailureReason
import dev.tracebox.storage.StorageTreeDeletionReport
import dev.tracebox.storage.StorageTreeDeletionState
import dev.tracebox.storage.SummaryIdentityDeriver
import dev.tracebox.storage.TraceboxOwnedStorageRoot
import dev.tracebox.storage.UidAccounting
import dev.tracebox.storage.UidBucket
import dev.tracebox.storage.UidQuota
import dev.tracebox.storage.UidWideQuotaCoordinator
import dev.tracebox.storage.UidWideStorageReconciler
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable installation settings. Diagnostics start disabled unless the host explicitly chooses
 * another profile and explicitly permits persistence of that user choice.
 */
class TraceboxConfiguration private constructor(
    /**
     * Stable app-process identifier used by native emergency slots and persisted records.
     * Every process in one app UID must configure a distinct value; role 2 is handler-reserved.
     */
    val processRole: Int,
    val initialProfile: DiagnosticsProfile,
    val initialPolicy: TraceboxPolicy,
    val privacyConfiguration: PrivacyConfiguration,
    val nativeCaptureEnabled: Boolean,
    val persistRequestedProfile: Boolean,
    /**
     * Enables the explicit C0-only device-protected emergency store.
     *
     * This is false by default. The primary process creates its durable activation only during an
     * unlocked installation transaction; locked-boot callers cannot enable it themselves.
     */
    val directBootC0Enabled: Boolean,
    generatedSchemaFingerprint: ByteArray,
) {
    private val generatedSchemaFingerprintBytes = generatedSchemaFingerprint.copyOf()

    /**
     * Fingerprint for the generated record schema. Every access returns an independent copy so an
     * integrating app cannot mutate installation identity after validation.
     */
    val generatedSchemaFingerprint: ByteArray
        get() = generatedSchemaFingerprintBytes.copyOf()

    init {
        require(processRole > 0 && processRole != HANDLER_PROCESS_ROLE) {
            "processRole must be positive and must not use the reserved handler role"
        }
        require(generatedSchemaFingerprintBytes.size == 32)
    }

    class Builder {
        private var processRole = DEFAULT_PROCESS_ROLE
        private var initialProfile = DiagnosticsProfile.DISABLED
        private var initialPolicy = TraceboxPolicy.disabled()
        private var privacyConfiguration = PrivacyConfiguration.defaults()
        private var nativeCaptureEnabled = false
        private var persistRequestedProfile = false
        private var directBootC0Enabled = false
        private var generatedSchemaFingerprint = schemaFingerprint()

        /** Sets this app process's stable, UID-unique role. Role 2 is reserved by Tracebox. */
        fun setProcessRole(value: Int) = apply {
            require(value > 0 && value != HANDLER_PROCESS_ROLE)
            processRole = value
        }

        fun setInitialProfile(value: DiagnosticsProfile) = apply {
            initialProfile = value
            initialPolicy = legacyPolicy(value)
        }

        fun setInitialPolicy(value: TraceboxPolicy) = apply {
            initialPolicy = value
            initialProfile = profileFor(value)
        }

        fun privacy(configure: PrivacyConfiguration.Builder.() -> Unit) = apply {
            privacyConfiguration = PrivacyConfiguration.Builder().apply(configure).build()
        }

        /** Reuses an immutable privacy configuration, including for idempotent process install. */
        fun setPrivacyConfiguration(value: PrivacyConfiguration) = apply {
            privacyConfiguration = value
        }

        /** Enables capture supplied by the separately declared tracebox-native artifact. */
        fun setNativeCaptureEnabled(value: Boolean) = apply {
            nativeCaptureEnabled = value
        }

        /**
         * Enables persistence only for a profile selected by the host's explicit user-control UI.
         * It is false by default so installation alone never persists enablement.
         */
        fun setPersistRequestedProfile(value: Boolean) = apply {
            persistRequestedProfile = value
        }

        /** Explicitly opts this installation into bounded C0-only Direct Boot capture. */
        fun setDirectBootC0Enabled(value: Boolean) = apply {
            directBootC0Enabled = value
        }

        fun build(): TraceboxConfiguration = TraceboxConfiguration(
            processRole,
            initialProfile,
            initialPolicy,
            privacyConfiguration,
            nativeCaptureEnabled,
            persistRequestedProfile,
            directBootC0Enabled,
            generatedSchemaFingerprint.copyOf(),
        )
    }

    internal fun equivalentTo(other: TraceboxConfiguration): Boolean =
        processRole == other.processRole &&
            initialProfile == other.initialProfile &&
            initialPolicy == other.initialPolicy &&
            privacyConfiguration.isEquivalentForInstallation(other.privacyConfiguration) &&
            nativeCaptureEnabled == other.nativeCaptureEnabled &&
            persistRequestedProfile == other.persistRequestedProfile &&
            directBootC0Enabled == other.directBootC0Enabled &&
            generatedSchemaFingerprintBytes.contentEquals(other.generatedSchemaFingerprintBytes)

    companion object {
        const val DEFAULT_PROCESS_ROLE = 1
        private const val HANDLER_PROCESS_ROLE = 2

        private fun schemaFingerprint(): ByteArray = GeneratedSchemaFingerprint.bytes()
    }
}

/** Public, generated-only Tracebox entry point. It has no uploader, transport, or network surface. */
object Tracebox {
    private val installLock = Any()
    private var installed: DefaultTraceboxHandle? = null

    /** Returns the process installation for optional Tracebox-owned UI integrations. */
    fun current(): TraceboxHandle? = synchronized(installLock) { installed }

    /** Process logger; calls are safe no-ops until installation completes in this process. */
    val log: TraceboxLogger
        get() = current()?.log ?: UninstalledTraceboxLogger

    /** Process crash reporter; calls are safe no-ops until installation completes. */
    val crashes: CrashReporter
        get() = current()?.crashes ?: UninstalledCrashReporter

    fun install(
        context: Context,
        configuration: TraceboxConfiguration = TraceboxConfiguration.Builder().build(),
    ): TraceboxHandle = synchronized(installLock) {
        val existing = installed
        if (existing != null) {
            require(existing.configuration.equivalentTo(configuration)) {
                "Tracebox is already installed with a different immutable configuration"
            }
            return@synchronized existing
        }
        // A completed close already clears this registry under the package fence. Clearing again
        // at the installation boundary makes stale in-memory approval state unconsumable even if
        // construction previously failed before a handle became globally visible.
        RuntimePackageRegistry.clear()
        // During Application.attachBaseContext(), Android has attached the Application's base
        // context but LoadedApk may not expose getApplicationContext() until attach returns.
        // Explicit attachment-time installation is the primary integration point, so retain the
        // supplied context when that transient lookup is null.
        val stableContext = context.applicationContext ?: context
        DefaultTraceboxHandle(stableContext, configuration).also { installed = it }
    }

    internal fun onClosed(handle: DefaultTraceboxHandle) = synchronized(installLock) {
        if (installed === handle) installed = null
    }
}

private object UninstalledTraceboxLogger : TraceboxLogger {
    override fun isEnabled(level: LogLevel, category: dev.tracebox.api.LogCategory): Boolean = false
    override fun log(level: LogLevel, template: LogTemplate, vararg arguments: LogArgument) = Unit
    override fun error(throwable: Throwable, template: LogTemplate, vararg arguments: LogArgument) = Unit
    override fun performanceStart(template: LogTemplate, vararg arguments: LogArgument): PerformanceMeasurement =
        UninstalledPerformanceMeasurement
}

private object UninstalledPerformanceMeasurement : PerformanceMeasurement {
    override fun success() = Unit
    override fun failure() = Unit
    override fun cancelled() = Unit
}

private object UninstalledCrashReporter : CrashReporter {
    override fun record(throwable: Throwable) = Unit
    override fun record(throwable: Throwable, template: LogTemplate, vararg arguments: LogArgument) = Unit
}

private data class NativeHandlerServiceBinding(
    val intent: Intent,
    val connection: ServiceConnection,
)

/**
 * Keeps activity hand-offs and asynchronous watchdog creation on one ordered eligibility boundary.
 * A watchdog attached after the first Activity start immediately receives the current state.
 */
internal class ActivityVisibilityTracker {
    private val lock = Any()
    private var visibleActivities = 0
    private var eligibilitySink: ((Boolean) -> Unit)? = null

    fun activityStarted() {
        synchronized(lock) {
            visibleActivities += 1
            eligibilitySink?.invoke(true)
        }
    }

    fun activityStopped() {
        synchronized(lock) {
            visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
            eligibilitySink?.invoke(visibleActivities > 0)
        }
    }

    fun attach(sink: (Boolean) -> Unit) {
        synchronized(lock) {
            eligibilitySink = sink
            sink(visibleActivities > 0)
        }
    }

    fun detach() {
        synchronized(lock) {
            eligibilitySink = null
        }
    }
}

/**
 * Restores public readiness only when a previously healthy primary runtime recovers its native
 * participant. Initial startup and unrelated degraded states remain owned by their original path.
 */
internal class PrimaryNativeReadinessRecovery {
    private var restoreReady = false

    fun begin(readiness: Readiness, health: TraceboxHealth) {
        restoreReady = restoreReady ||
            (readiness == Readiness.DURABLE && health == TraceboxHealth.READY)
    }

    fun complete(recovered: Boolean): Boolean {
        if (!recovered || !restoreReady) return false
        restoreReady = false
        return true
    }

    fun clear() {
        restoreReady = false
    }
}

internal class DefaultTraceboxHandle(
    private val applicationContext: Context,
    val configuration: TraceboxConfiguration,
) : TraceboxHandle {
    private val root = applicationContext.noBackupFilesDir.toPath().resolve(ROOT_DIRECTORY)
    private val directBootRoot = directBootStorageRoot(applicationContext)
    private val coordinatesGlobalStorage = isPrimaryApplicationProcess(applicationContext)
    private val profileStore = ProfileStore(root.resolve("requested-profile-v1"))
    private val runtimePolicyStore = RuntimePolicyStore(root.resolve("requested-policy-v2"))
    private val policyTransitionJournal =
        PolicyTransitionJournal(root.resolve(POLICY_TRANSITION_FILE))
    private var coordinatorLease: PrimaryCoordinatorLease? = null
    private var secondaryPolicyObserver: ScheduledExecutorService? = null
    private var primaryNativeObserver: ScheduledExecutorService? = null
    @Volatile
    private var executorThread: Thread? = null
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WORK_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "tracebox-writer").apply {
                isDaemon = true
                executorThread = this
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val mutableReadiness = MutableStateFlow(Readiness.VOLATILE_CAPTURE)
    private val mutableHealth = MutableStateFlow(TraceboxHealth.INITIALIZING)
    private val mutablePolicy = MutableStateFlow(TraceboxPolicy.disabled())
    private val mutableSummary = MutableStateFlow(DiagnosticSummary())
    private val closed = AtomicBoolean(false)
    private val profileLock = Any()
    private val packageCapabilityFence = RuntimePackageCapabilityFence()
    private val handlerServiceLifetime = SingleHeldLifetime<NativeHandlerServiceBinding>(
        start = { applicationContext.startService(it.intent) != null },
        bind = {
            applicationContext.bindService(
                it.intent,
                it.connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        },
        stop = { applicationContext.stopService(it.intent) },
        unbind = { applicationContext.unbindService(it.connection) },
    )
    private val volatileManagedCrashes =
        BoundedManagedCrashBuffer<GeneratedManagedCrash>(VOLATILE_CRASH_CAPACITY)
    private val volatileExceptionCrashes =
        BoundedManagedCrashBuffer<GeneratedExceptionRecord>(VOLATILE_CRASH_CAPACITY)
    private val previousJvmHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val installedJvmHandler = dev.tracebox.core.TraceboxUncaughtExceptionHandler(
        previousJvmHandler,
        dev.tracebox.core.JvmCapturePolicy(),
        ::captureManagedCrash,
    )
    private val crashSurface: RuntimeCrashReporter by lazy {
        RuntimeCrashReporter(
            policy = mutablePolicy,
            record = ::recordGeneratedException,
            recordContext = { template, arguments -> loggerSurface.recordContext(template, arguments) },
        )
    }
    private val loggerSurface: RuntimeTraceboxLogger by lazy {
        RuntimeTraceboxLogger(
            policy = mutablePolicy,
            privacy = configuration.privacyConfiguration,
            record = ::recordGenerated,
            reportCrash = crashSurface::record,
        )
    }

    @Volatile
    private var activeProfile = DiagnosticsProfile.DISABLED

    @Volatile
    private var activeRuntimePolicy = TraceboxPolicy.disabled()

    @Volatile
    private var controlPage: ControlPage? = null

    @Volatile
    private var policyGate: WriterPolicyGate? = null

    @Volatile
    private var writer: SegmentWriter? = null

    @Volatile
    private var generatedAdapter: GeneratedRecordSegmentAdapter? = null

    @Volatile
    private var uidQuota: UidWideQuotaCoordinator? = null

    @Volatile
    private var rawArtifactStore: RawArtifactStore? = null

    @Volatile
    private var storageOwnership: UidWideStorageReconciler? = null

    @Volatile
    private var storageDeletion: JournaledStorageTreeDeletion? = null

    @Volatile
    private var directBootManager: DirectBootManager? = null

    @Volatile
    private var currentProcessIdentity: ByteArray? = null

    @Volatile
    private var handlerProcessIdentity: ByteArray? = null

    @Volatile
    private var armedRawArtifactIdentity: ByteArray? = null

    private var exitTombstones: ExitTombstoneLedger? = null
    private var exitImports: ExitImportJournal? = null

    @Volatile
    private var nativeReady = false

    @Volatile
    private var nativeClientMode = NativeRuntime.CLIENT_MODE_REJECTED

    @Volatile
    private var crashpadRecoveryReady = true

    @Volatile
    private var directBootRecoveryReady = true

    @Volatile
    private var secondaryPolicySnapshot: PolicySnapshot? = null

    @Volatile
    private var secondaryInitialized = false

    @Volatile
    private var authorizedRepairEnableEpoch: Long? = null

    private val activityVisibility = ActivityVisibilityTracker()
    private val primaryNativeReadinessRecovery = PrimaryNativeReadinessRecovery()
    private var visibilityCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var watchdog: AnrWatchdog? = null
    private val packageSurface = RuntimePackages(this)

    override val readiness: StateFlow<Readiness> = mutableReadiness.asStateFlow()
    override val health: StateFlow<TraceboxHealth> = mutableHealth.asStateFlow()
    override val policy: StateFlow<TraceboxPolicy> = mutablePolicy.asStateFlow()
    override val summary: StateFlow<DiagnosticSummary> = mutableSummary.asStateFlow()
    override val log: TraceboxLogger get() = loggerSurface
    override val crashes: CrashReporter get() = crashSurface

    override val diagnostics: Diagnostics = object : Diagnostics {
        override fun eventEnabled(eventId: GeneratedEventId): Boolean = accepts(eventId)

        override fun record(value: GeneratedRecord, context: DiagnosticContext?) {
            recordGenerated(value, context)
        }
    }

    override val packages: DiagnosticPackages = packageSurface

    init {
        installVisibilityCallbacks()
        Thread.setDefaultUncaughtExceptionHandler(installedJvmHandler)
        enqueue(::initialize)
    }

    override fun updateProfile(profile: DiagnosticsProfile): PolicyUpdateResult =
        updatePolicy(legacyPolicy(profile))

    override fun updatePolicy(policy: TraceboxPolicy): PolicyUpdateResult {
        invalidateRuntimePackageCapabilities()
        if (!coordinatesGlobalStorage || isMainThread() || closed.get()) {
            return PolicyUpdateResult.FAILED
        }
        return call {
            synchronized(profileLock) {
                val profile = profileFor(policy)
                val result = applyProfile(profile, runtimePolicy = policy)
                if (shouldClearPolicyRepairMarker(profile, result) &&
                    !clearPolicyRepairRequired()
                ) {
                    failClosedAfterPolicyApplication(
                        runCatching { controlPage?.committed()?.epoch }.getOrNull() ?: 0L,
                    )
                    PolicyUpdateResult.PARTIAL
                } else {
                    result
                }
            }
        } ?: PolicyUpdateResult.FAILED
    }

    override fun delete(request: DeleteRequest): DeleteReport {
        invalidateRuntimePackageCapabilities()
        if (!coordinatesGlobalStorage || isMainThread() || closed.get()) {
            return DeleteReport.REJECTED
        }
        return call {
            synchronized(profileLock) {
                when (request) {
                    DeleteRequest.EXPIRED_SNAPSHOTS -> {
                        packageSurface.deleteExpiredStaging()
                        DeleteReport.COMPLETE
                    }

                    DeleteRequest.ALL_TRACEBOX_DATA -> deleteAllTraceboxData()
                }
            }
        } ?: DeleteReport.PENDING_FAILURE
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidateRuntimePackageCapabilities()
        secondaryPolicyObserver?.shutdownNow()
        secondaryPolicyObserver = null
        stopPrimaryNativeObserver()
        val terminalBarrier = Runnable {
            synchronized(profileLock) {
                try {
                    runCatching { packageSurface.retireActivePackage() }
                    runCatching { quiesceManagedWriters() }
                    runCatching { quiesceNativeClient() }
                    if (coordinatesGlobalStorage) runCatching { stopNativeHandler() }
                } finally {
                    mutableHealth.value = TraceboxHealth.CLOSED
                    mutableReadiness.value = Readiness.CLOSED
                }
            }
        }
        if (Thread.currentThread() === executorThread) {
            terminalBarrier.run()
        } else {
            var future: java.util.concurrent.Future<*>? = null
            while (future == null) {
                try {
                    future = executor.submit(terminalBarrier)
                } catch (_: RejectedExecutionException) {
                    // close() owns the only shutdown transition. A full queue drains quickly
                    // because every not-yet-started action observes closed=true and skips.
                    Thread.yield()
                }
            }
            val terminalFuture = checkNotNull(future)
            var interrupted = false
            while (true) {
                try {
                    terminalFuture.get()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (_: ExecutionException) {
                    break
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        executor.shutdown()
        if (Thread.getDefaultUncaughtExceptionHandler() === installedJvmHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousJvmHandler)
        }
        releaseCoordinatorLease()
        uninstallVisibilityCallbacks()
        Tracebox.onClosed(this)
    }

    private fun initialize() {
        try {
            Files.createDirectories(root)
            if (coordinatesGlobalStorage && !acquireCoordinatorLease()) {
                markDegraded()
                return
            }
            if (coordinatesGlobalStorage) {
                TraceboxOwnedStorageRoot.claim(root)
                TraceboxOwnedStorageRoot.claim(directBootRoot)
            } else if (!TraceboxOwnedStorageRoot.isClaimed(root)) {
                markDegraded()
                startSecondaryPolicyObserver()
                return
            }
            val quota = UidQuota(
                mapOf(
                    UidBucket.ROLE_SEGMENTS to ROLE_SEGMENT_LIMIT,
                    UidBucket.RAW_ARTIFACTS to RAW_ARTIFACT_LIMIT,
                    UidBucket.SUMMARY_SPOOL to SUMMARY_SPOOL_LIMIT,
                    UidBucket.SUMMARY_STAGING to SUMMARY_STAGING_LIMIT,
                    UidBucket.SNAPSHOTS to SNAPSHOT_LIMIT,
                    UidBucket.COMPACTION to COMPACTION_WORKSPACE_LIMIT,
                    UidBucket.EMERGENCY to EMERGENCY_RESERVE_LIMIT,
                    UidBucket.METADATA to METADATA_LIMIT,
                ),
            )
            // CE accounting remains in the credential-protected root, while every process and
            // locked-boot writer joins one device-protected mutation lock. Policy commits,
            // deletion, handler startup, quota mutations, and Direct Boot appends therefore
            // cannot resurrect or mutate storage across one another.
            val coordinator = UidWideQuotaCoordinator(
                root,
                quota,
                MAX_FILES,
                mutationBarrierRoot = directBootRoot,
            )
            uidQuota = coordinator
            val ownership = if (coordinatesGlobalStorage) {
                productionStorageOwnership(coordinator).also {
                    storageOwnership = it
                }
            } else {
                null
            }

            if (configuration.nativeCaptureEnabled && ownership != null &&
                !coordinator.withStorageMutation { stopNativeHandler() }
            ) {
                markDegraded()
                return
            }
            if (ownership != null &&
                ownership.reconcile() is StorageOwnershipReport.Partial
            ) {
                markDegraded()
                return
            }
            if (coordinatesGlobalStorage) {
                // Reconciliation must adopt/release stale UID ledger entries before any startup
                // reservation. Otherwise a full stale ledger can prevent its own repair.
                ensureMetadataReservation(root.resolve(COORDINATOR_LOCK_FILE), 0L)
            }
            val policyPath = root.resolve(POLICY_CONTROL_FILE)
            ensureMetadataReservation(policyPath, POLICY_CONTROL_BYTES)
            ensurePolicyTransitionReservations()
            val page = ControlPage(policyPath)
            var requireExplicitEnableAfterPolicyRepair = false
            val policyFileWasPresent = Files.exists(policyPath, LinkOption.NOFOLLOW_LINKS)
            if (policyFileWasPresent &&
                !Files.isRegularFile(policyPath, LinkOption.NOFOLLOW_LINKS)
            ) {
                if (!coordinatesGlobalStorage) {
                    markDegraded()
                    startSecondaryPolicyObserver()
                    return
                }
                markPolicyRepairRequired()
                writeFailClosedDirectBootRepair()
                markDegraded()
                return
            }
            var current = try {
                page.committed()
            } catch (_: PolicyPageException) {
                if (!coordinatesGlobalStorage) {
                    markDegraded()
                    startSecondaryPolicyObserver()
                    return
                }
                val journalState = policyTransitionJournal.load()
                if (journalState is PolicyTransitionLoad.Active) {
                    if (journalState.transition.phase.ordinal <
                        PolicyTransitionPhase.LOCAL_DURABLE.ordinal
                    ) {
                        journalState.transition.previous
                    } else {
                        journalState.transition.target
                    }
                } else {
                    val completed = policyTransitionJournal.lastCompletedTarget()
                    val directBoot = directBootMirror().effective()
                    val priorEvidence = policyFileWasPresent ||
                        policyTransitionJournal.slotPaths.any {
                            Files.exists(it, LinkOption.NOFOLLOW_LINKS)
                        } ||
                        completed != null ||
                        directBoot != null ||
                        Files.exists(root.resolve(REQUESTED_PROFILE_FILE), LinkOption.NOFOLLOW_LINKS) ||
                        Files.exists(root.resolve(IDENTITY_JOURNAL_FILE), LinkOption.NOFOLLOW_LINKS) ||
                        Files.exists(root.resolve("instances"), LinkOption.NOFOLLOW_LINKS)
                    val previousEpoch = maxOf(
                        completed?.epoch ?: 0L,
                        directBoot?.epoch ?: 0L,
                        policyTransitionJournal.highWaterEpoch() ?: 0L,
                    )
                    val repairEpoch = if (priorEvidence) {
                        Math.addExact(previousEpoch, 1L)
                    } else {
                        1L
                    }
                    requireExplicitEnableAfterPolicyRepair = priorEvidence
                    if (priorEvidence) {
                        // The explicit-enable latch must reach durable storage before either CE
                        // or Direct Boot is repaired. A crash can then never silently re-enable.
                        markPolicyRepairRequired()
                    }
                    disabledPolicy(repairEpoch).also { repaired ->
                        commitLocalPolicy(page, repaired)
                        if (journalState is PolicyTransitionLoad.Corrupt) {
                            val recoveryPrevious = when {
                                completed != null && completed.epoch < repaired.epoch -> completed
                                directBoot != null && directBoot.epoch < repaired.epoch ->
                                    PolicySnapshot(
                                        directBoot.epoch,
                                        directBoot.c0DenyMask,
                                        directBoot.disabled,
                                    )

                                else -> disabledPolicy(repaired.epoch - 1L)
                            }
                            policyTransitionJournal.reinitializeCompleted(
                                recoveryPrevious,
                                repaired,
                            )
                        }
                    }
                }
            }
            if (coordinatesGlobalStorage &&
                policyTransitionJournal.load() is PolicyTransitionLoad.Corrupt
            ) {
                // A valid CE page is insufficient when the transaction boundary is corrupt.
                // Persist the explicit-enable latch, move to a fresh disabled epoch, and then
                // rebuild both journal generations around that durable repair.
                markPolicyRepairRequired()
                val directBoot = directBootMirror().effective()
                val repairEpoch = Math.addExact(
                    maxOf(
                        current.epoch,
                        directBoot?.epoch ?: 0L,
                        policyTransitionJournal.highWaterEpoch() ?: 0L,
                    ),
                    1L,
                )
                val repaired = disabledPolicy(repairEpoch)
                commitLocalPolicy(page, repaired)
                policyTransitionJournal.reinitializeCompleted(current, repaired)
                current = repaired
                requireExplicitEnableAfterPolicyRepair = true
            }
            controlPage = page
            policyGate = WriterPolicyGate(page)
            if (coordinatesGlobalStorage && policyRepairRequired()) {
                // The durable user-authorization latch dominates every interrupted transition.
                // In particular, an enabling LOCAL_DURABLE journal must never rearm capture on
                // restart before the explicit update has also cleared this marker.
                current = repairDisabledPolicyForPersistentLatch(page, current)
                requireExplicitEnableAfterPolicyRepair = true
            }
            if (ownership != null) {
                val deletion = productionStorageDeletion(ownership)
                storageDeletion = deletion
                val deletionState = deletion.currentState()
                val rootsEligible = ownedStorageRootsEligible()
                if (rootsEligible) {
                    initializePersistentStores()
                    if (!reconcileCrashpadClientLifecycles(handlerQuiesced = true)) {
                        markDegraded()
                        return
                    }
                }
                if (!recoverInterruptedPolicyTransition(page, storesAvailable = rootsEligible)) {
                    markDegraded()
                    return
                }
                current = page.committed()
                when {
                    deletionState == StorageTreeDeletionState.COMPLETE && !rootsEligible -> {
                        remainDisabledAfterDeletion()
                        return
                    }

                    (deletionState != null && deletionState != StorageTreeDeletionState.COMPLETE) ||
                        (deletionState == null && !rootsEligible) -> {
                        when (runStorageDeletionToBoundary(deletion)) {
                            is StorageTreeDeletionReport.Complete -> remainDisabledAfterDeletion()
                            is StorageTreeDeletionReport.Pending -> markDegraded()
                        }
                        return
                    }
                }
            } else if (!TraceboxOwnedStorageRoot.isEligible(root)) {
                remainDisabledAfterDeletion()
                startSecondaryPolicyObserver()
                return
            } else {
                initializePersistentStores()
                if (policyTransitionJournal.load() !is PolicyTransitionLoad.Empty) {
                    // Only the primary role may stop/restart the UID-wide native coordinator.
                    markDegraded()
                    startSecondaryPolicyObserver()
                    return
                }
            }

            initializePersistentStores()
            if (coordinatesGlobalStorage && !configureDirectBootStorage()) {
                markDegraded()
                return
            }
            val rawStore = checkNotNull(rawArtifactStore)
            if (coordinatesGlobalStorage) {
                rawStore.expire(System.currentTimeMillis(), RAW_ARTIFACT_TTL_MILLIS)
                rawStore.deleteUnverifiableOrphans()
            }
            val persistedPolicy = if (configuration.persistRequestedProfile) {
                runtimePolicyStore.read() ?: profileStore.read()?.let(::legacyPolicy)
            } else {
                null
            }
            val requestedPolicy = resolveRequestedPolicy(configuration, persistedPolicy)
            val requested = profileFor(requestedPolicy)
            if (!coordinatesGlobalStorage) {
                initializeSecondaryProcess()
                return
            }
            activeProfile = DiagnosticsProfile.DISABLED
            if (requireExplicitEnableAfterPolicyRepair) {
                markPolicyRepairRequired()
            }
            if (policyRepairRequired()) {
                check(policyGate?.reload() == GateResult.Reloaded)
                volatileManagedCrashes.resolve(enabled = false, sinkReady = false)
                volatileExceptionCrashes.resolve(enabled = false, sinkReady = false)
                mutableHealth.value = TraceboxHealth.DEGRADED
                mutableReadiness.value = Readiness.DURABLE
                return
            }
            applyProfile(requested, current.epoch, requestedPolicy)
        } catch (_: IOException) {
            markDegraded()
            if (!coordinatesGlobalStorage) startSecondaryPolicyObserver()
        } catch (_: PolicyPageException) {
            markDegraded()
            if (!coordinatesGlobalStorage) startSecondaryPolicyObserver()
        } catch (_: IllegalStateException) {
            markDegraded()
            if (!coordinatesGlobalStorage) startSecondaryPolicyObserver()
        } catch (_: RuntimeException) {
            markDegraded()
            if (!coordinatesGlobalStorage) startSecondaryPolicyObserver()
        }
    }

    private fun initializeSecondaryProcess() {
        secondaryInitialized = true
        startSecondaryPolicyObserver()
        val snapshot = stableSecondaryPolicySnapshot()
        if (snapshot == null) {
            enterSecondaryFailClosed()
            return
        }
        val profile = profileForPolicy(snapshot)
        if (profile == null || policyGate?.reload() != GateResult.Reloaded) {
            enterSecondaryFailClosed()
            return
        }
        secondaryPolicySnapshot = snapshot
        activeRuntimePolicy = runtimePolicyForSnapshot(snapshot)
        mutablePolicy.value = activeRuntimePolicy
        activeProfile = profile
        if (snapshot.disabled) {
            quiesceNativeClient()
            publishDisabledExitPolicy(snapshot.epoch)
            mutableHealth.value = TraceboxHealth.DISABLED
            mutableReadiness.value = Readiness.DURABLE
            return
        }
        if (!activateSecondaryCapture(snapshot)) {
            enterSecondaryFailClosed()
        }
    }

    private fun startSecondaryPolicyObserver() {
        if (coordinatesGlobalStorage || secondaryPolicyObserver != null) return
        secondaryPolicyObserver = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "tracebox-policy-observer").apply { isDaemon = true }
        }.also { observer ->
            observer.scheduleWithFixedDelay(
                {
                    enqueue {
                        synchronized(profileLock) {
                            if (secondaryInitialized) {
                                refreshSecondaryPolicy()
                            } else {
                                initialize()
                            }
                        }
                    }
                },
                SECONDARY_POLICY_POLL_MILLIS,
                SECONDARY_POLICY_POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun startPrimaryNativeObserver() {
        if (!coordinatesGlobalStorage || primaryNativeObserver != null) return
        primaryNativeObserver = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "tracebox-native-observer").apply { isDaemon = true }
        }.also { observer ->
            observer.scheduleWithFixedDelay(
                {
                    enqueue {
                        if (activeProfile != DiagnosticsProfile.DISABLED) {
                            refreshPrimaryNativeParticipant()
                        }
                    }
                },
                PRIMARY_NATIVE_POLL_MILLIS,
                PRIMARY_NATIVE_POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun stopPrimaryNativeObserver() {
        primaryNativeObserver?.shutdownNow()
        primaryNativeObserver = null
        primaryNativeReadinessRecovery.clear()
    }

    private fun refreshSecondaryPolicy() {
        if (closed.get() || coordinatesGlobalStorage) return
        val confirmed = stableSecondaryPolicySnapshot()
        if (confirmed == null) {
            enterSecondaryFailClosed()
            return
        }
        if (secondaryPolicySnapshot == confirmed) {
            if (confirmed.disabled) return
            if (
                drainRustPanicRingIfHealthy(
                    nativeClientMode == NativeRuntime.CLIENT_MODE_EMERGENCY_RUST &&
                        nativePolicyParticipantAlive() &&
                        writer != null,
                    ::drainRustPanicRing,
                )
            ) return
            if (!activateSecondaryCapture(confirmed)) {
                enterSecondaryFailClosed()
            }
            return
        }
        val profile = profileForPolicy(confirmed)
        if (profile == null || policyGate?.reload() != GateResult.Reloaded) {
            enterSecondaryFailClosed()
            return
        }
        if (stableSecondaryPolicySnapshot() != confirmed) {
            enterSecondaryFailClosed()
            return
        }
        activeProfile = profile
        activeRuntimePolicy = runtimePolicyForSnapshot(confirmed)
        mutablePolicy.value = activeRuntimePolicy
        secondaryPolicySnapshot = confirmed
        if (confirmed.disabled) {
            quiesceManagedWriters()
            quiesceNativeClient()
            publishDisabledExitPolicy(confirmed.epoch)
            mutableHealth.value = TraceboxHealth.DISABLED
            mutableReadiness.value = Readiness.DURABLE
            return
        }
        if (!activateSecondaryCapture(confirmed)) {
            enterSecondaryFailClosed()
        }
    }

    private fun activateSecondaryCapture(snapshot: PolicySnapshot): Boolean {
        // The stable, reloaded policy already permits managed crashes. Keep new hook records
        // memory-only until rotateWriter installs the corresponding gated sink.
        volatileManagedCrashes.resolve(enabled = true, sinkReady = false)
        volatileExceptionCrashes.resolve(enabled = true, sinkReady = false)
        // Preserve records captured before the secondary process learned its durable policy, and
        // records caught during this bounded writer rotation. The new gated writer resolves them.
        quiesceManagedWriters(preserveVolatileManagedCrashes = true)
        quiesceNativeClient()
        return try {
            // A previous process may have left a completed emergency/Rust slot. Opening native
            // capture resets those fixed slots, so create the gated writer and ingest them first.
            rotateWriter(snapshot)
            if (configuration.nativeCaptureEnabled) drainRustPanicRing()
            if (stableSecondaryPolicySnapshot() != snapshot ||
                (configuration.nativeCaptureEnabled &&
                    (!startSecondaryNativeParticipant(snapshot) ||
                        !nativePolicyParticipantAlive()))
            ) {
                quiesceManagedWriters()
                quiesceNativeClient()
                false
            } else {
                mutableHealth.value = TraceboxHealth.READY
                mutableReadiness.value = Readiness.DURABLE
                true
            }
        } catch (_: IOException) {
            quiesceManagedWriters()
            quiesceNativeClient()
            false
        } catch (_: RuntimeException) {
            quiesceManagedWriters()
            quiesceNativeClient()
            false
        } catch (_: LinkageError) {
            quiesceManagedWriters()
            quiesceNativeClient()
            false
        }
    }

    private fun stableSecondaryPolicySnapshot(): PolicySnapshot? {
        val page = controlPage ?: return null
        if (policyRepairRequired() ||
            !TraceboxOwnedStorageRoot.isEligible(root) ||
            !TraceboxOwnedStorageRoot.isEligible(directBootRoot)
        ) {
            return null
        }
        if (policyTransitionJournal.load() !is PolicyTransitionLoad.Empty) return null
        val candidate = convergedLocalPolicy(page) ?: return null
        if (policyTransitionJournal.load() !is PolicyTransitionLoad.Empty) return null
        val confirmed = convergedLocalPolicy(page) ?: return null
        if (candidate != confirmed ||
            policyTransitionJournal.load() !is PolicyTransitionLoad.Empty ||
            convergedLocalPolicy(page) != confirmed
        ) {
            return null
        }
        return confirmed
    }

    private fun enterSecondaryFailClosed() {
        val lastEpoch = secondaryPolicySnapshot?.epoch
            ?: runCatching { controlPage?.committed()?.epoch }.getOrNull()
            ?: 0L
        publishDisabledExitPolicy(lastEpoch)
        activeProfile = DiagnosticsProfile.DISABLED
        secondaryPolicySnapshot = null
        quiesceManagedWriters()
        quiesceNativeClient()
        markDegraded()
    }

    private fun publishDisabledExitPolicy(epoch: Long) {
        currentProcessIdentity?.let { processIdentity ->
            try {
                ApplicationExitInfoAdapter().publishPolicyToken(
                    applicationContext,
                    ExitPolicyToken(
                        epoch.coerceAtLeast(0L),
                        rawArtifactAllowed = false,
                        processIdentity,
                        configuration.processRole,
                    ),
                )
            } catch (_: RuntimeException) {
                // The durable CE/Direct-Boot deny remains authoritative.
            }
        }
    }

    private fun applyProfile(
        profile: DiagnosticsProfile,
        observedEpoch: Long? = null,
        runtimePolicy: TraceboxPolicy = legacyPolicy(profile),
    ): PolicyUpdateResult {
        invalidateRuntimePackageCapabilities()
        primaryNativeReadinessRecovery.clear()
        val page = controlPage ?: return PolicyUpdateResult.FAILED
        if (policyTransitionJournal.load() !is PolicyTransitionLoad.Empty &&
            (!coordinatesGlobalStorage ||
                !recoverInterruptedPolicyTransition(page, storesAvailable = rawArtifactStore != null))
        ) {
            markDegraded()
            return PolicyUpdateResult.FAILED
        }
        if (profile != DiagnosticsProfile.DISABLED && !prepareStorageForEnable()) {
            markDegraded()
            return PolicyUpdateResult.FAILED
        }
        val previous = try {
            page.committed()
        } catch (_: PolicyPageException) {
            markDegraded()
            return PolicyUpdateResult.FAILED
        }
        val requestedAtCurrentEpoch = policyFor(runtimePolicy, previous.epoch)
        val transitionHighWater = policyTransitionJournal.highWaterEpoch() ?: previous.epoch
        val canReuseCommittedPolicy =
            requestedAtCurrentEpoch == previous &&
                (observedEpoch ?: previous.epoch) <= previous.epoch &&
                transitionHighWater <= previous.epoch &&
                localPolicyMatches(page, previous) &&
                policyTransitionJournal.load() is PolicyTransitionLoad.Empty
        val next = if (canReuseCommittedPolicy) {
            previous
        } else {
            val nextEpoch = try {
                Math.addExact(
                    maxOf(
                        previous.epoch,
                        observedEpoch ?: 0L,
                        transitionHighWater,
                    ),
                    1L,
                )
            } catch (_: ArithmeticException) {
                markDegraded()
                return PolicyUpdateResult.FAILED
            }
            policyFor(runtimePolicy, nextEpoch)
        }

        val commitResult = if (canReuseCommittedPolicy) {
            PolicyUpdateResult.SUCCESS
        } else {
            try {
                commitPolicy(page, previous, next)
            } catch (_: IOException) {
                classifyPolicyCommitFailure(page, previous, next)
            } catch (_: PolicyPageException) {
                classifyPolicyCommitFailure(page, previous, next)
            } catch (_: PolicyTransitionException) {
                classifyPolicyCommitFailure(page, previous, next)
            } catch (_: IllegalStateException) {
                classifyPolicyCommitFailure(page, previous, next)
            }
        }

        if (policyGate?.reload() != GateResult.Reloaded) {
            val result = if (commitResult == PolicyUpdateResult.SUCCESS) {
                postDurabilityPolicyResult(previous, next)
            } else {
                commitResult
            }
            failClosedAfterPolicyApplication(next.epoch)
            return result
        }
        if (commitResult == PolicyUpdateResult.FAILED &&
            localPolicyMatches(page, previous) &&
            policyTransitionJournal.load() is PolicyTransitionLoad.Empty
        ) {
            activeRuntimePolicy = runtimePolicyForSnapshot(previous)
            mutablePolicy.value = activeRuntimePolicy
            activeProfile = profileFor(activeRuntimePolicy)
            if (activeProfile == DiagnosticsProfile.DISABLED) {
                volatileManagedCrashes.resolve(enabled = false, sinkReady = false)
                volatileExceptionCrashes.resolve(enabled = false, sinkReady = false)
            }
            markDegraded()
            return PolicyUpdateResult.FAILED
        }
        activeProfile = profile
        activeRuntimePolicy = runtimePolicy
        mutablePolicy.value = runtimePolicy
        if (commitResult != PolicyUpdateResult.SUCCESS) {
            failClosedAfterPolicyApplication(next.epoch)
            return commitResult
        }
        var auxiliaryPartial = false
        if (configuration.persistRequestedProfile) {
            try {
                writeRequestedPolicy(profile, runtimePolicy)
            } catch (_: IOException) {
                markDegraded()
                auxiliaryPartial = true
            } catch (_: RuntimeException) {
                markDegraded()
                auxiliaryPartial = true
            }
        }

        if (profile == DiagnosticsProfile.DISABLED) {
            stopPrimaryNativeObserver()
            publishDisabledExitPolicy(next.epoch)
            val managedStopped = quiesceManagedWriters()
            val nativeStopped = quiesceNativeClient()
            val handlerStopped = !coordinatesGlobalStorage || stopNativeHandler()
            mutableHealth.value =
                if (!auxiliaryPartial && managedStopped && nativeStopped && handlerStopped) {
                    TraceboxHealth.DISABLED
                } else {
                    TraceboxHealth.DEGRADED
                }
            mutableReadiness.value = Readiness.DURABLE
            return if (!auxiliaryPartial && managedStopped && nativeStopped && handlerStopped) {
                PolicyUpdateResult.SUCCESS
            } else {
                PolicyUpdateResult.PARTIAL
            }
        }

        // The enabled policy is durable and the gate has reloaded, but writer/native setup can
        // still take time. Preserve hook records in the bounded memory queue until rotateWriter
        // installs the sink, and discard them if the setup later fails closed.
        volatileManagedCrashes.resolve(enabled = true, sinkReady = false)
        volatileExceptionCrashes.resolve(enabled = true, sinkReady = false)
        authorizedRepairEnableEpoch =
            if (policyRepairRequired()) next.epoch else null
        return try {
            initializePersistentStores()
            rotateWriter(next)
            ensureNativeAndWatchdog()
            reconcileExitHistory()
            val nativeReadyForPolicy = !configuration.nativeCaptureEnabled ||
                (nativeReady && nativeClientMode == NativeRuntime.CLIENT_MODE_CRASHPAD)
            val ready = nativeReadyForPolicy &&
                crashpadRecoveryReady &&
                directBootRecoveryReady
            val fullyReady = ready && !auxiliaryPartial
            mutableReadiness.value = if (fullyReady) Readiness.DURABLE else Readiness.DEGRADED
            mutableHealth.value = if (fullyReady) TraceboxHealth.READY else TraceboxHealth.DEGRADED
            if (fullyReady) {
                PolicyUpdateResult.SUCCESS
            } else {
                failClosedAfterPolicyApplication(next.epoch)
                PolicyUpdateResult.PARTIAL
            }
        } catch (_: IOException) {
            failClosedAfterPolicyApplication(next.epoch)
            PolicyUpdateResult.PARTIAL
        } catch (_: RuntimeException) {
            failClosedAfterPolicyApplication(next.epoch)
            PolicyUpdateResult.PARTIAL
        } catch (_: LinkageError) {
            failClosedAfterPolicyApplication(next.epoch)
            PolicyUpdateResult.PARTIAL
        } finally {
            authorizedRepairEnableEpoch = null
        }
    }

    private fun classifyPolicyCommitFailure(
        page: ControlPage,
        previous: PolicySnapshot,
        next: PolicySnapshot,
    ): PolicyUpdateResult {
        markDegraded()
        return if (localPolicyMatches(page, next)) {
            stopAllNativeParticipants()
            postDurabilityPolicyResult(previous, next)
        } else {
            PolicyUpdateResult.FAILED
        }
    }

    private fun commitPolicy(
        page: ControlPage,
        previous: PolicySnapshot,
        next: PolicySnapshot,
    ): PolicyUpdateResult =
        checkNotNull(uidQuota).withStorageMutation {
            commitPolicyUnderMutationBarrier(page, previous, next)
        }

    private fun commitPolicyUnderMutationBarrier(
        page: ControlPage,
        previous: PolicySnapshot,
        next: PolicySnapshot,
    ): PolicyUpdateResult {
        ensurePolicyTransitionReservations()
        policyTransitionJournal.begin(previous, next)
        val coordinateNative = configuration.nativeCaptureEnabled &&
            !previous.disabled && activeProfile != DiagnosticsProfile.DISABLED

        try {
            if (coordinateNative) {
                if (!nativePolicyParticipantAlive()) {
                    // Handler starts acquire the same UID mutation barrier in their own process.
                    // Never launch one while this policy transaction holds that barrier; fail the
                    // pre-durable update and let the serialized liveness observer rearm afterward.
                    throw IOException("native policy participant is not alive")
                }
                val prepared = NativeRuntime.preparePolicy(
                    next.epoch,
                    next.disabled,
                    next.denyMask,
                    POLICY_COORDINATION_TIMEOUT_MILLIS,
                )
                if (prepared != NATIVE_POLICY_SUCCESS) {
                    throw IOException(
                        "native policy prepare was not globally acknowledged: $prepared",
                    )
                }
            }
            policyTransitionJournal.markPrepared(next.epoch)
            commitLocalPolicy(page, next)
            policyTransitionJournal.markLocalDurable(next.epoch)
        } catch (failure: Throwable) {
            val crossedDurabilityBoundary = when (val loaded = policyTransitionJournal.load()) {
                is PolicyTransitionLoad.Active ->
                    loaded.transition.phase.ordinal >=
                        PolicyTransitionPhase.LOCAL_DURABLE.ordinal

                PolicyTransitionLoad.Corrupt,
                PolicyTransitionLoad.Empty,
                -> {
                    when {
                        localPolicyMatches(page, next) -> true
                        localPolicyMatches(page, previous) -> false
                        else -> {
                            // The transaction metadata is unavailable and neither complete local
                            // tuple is authoritative. Persist an explicit-repair latch, fence every
                            // runtime writer, and report an honest uncertain partial outcome.
                            markPolicyRepairRequired()
                            stopAllNativeParticipants()
                            return PolicyUpdateResult.PARTIAL
                        }
                    }
                }
            }
            if (!crossedDurabilityBoundary) {
                if (!rollbackBeforeLocalDurability(
                        page,
                        previous,
                        next.epoch,
                        coordinateNative,
                    )
                ) {
                    stopAllNativeParticipants()
                }
                throwPolicyFailure(failure)
            }
            if (finishDurablePolicyCommit(next, coordinateNative)) {
                return PolicyUpdateResult.SUCCESS
            }
            stopAllNativeParticipants()
            return postDurabilityPolicyResult(previous, next)
        }

        if (!finishDurablePolicyCommit(next, coordinateNative)) {
            stopAllNativeParticipants()
            return postDurabilityPolicyResult(previous, next)
        }
        return PolicyUpdateResult.SUCCESS
    }

    private fun failClosedAfterPolicyApplication(epoch: Long) {
        invalidateRuntimePackageCapabilities()
        stopPrimaryNativeObserver()
        publishDisabledExitPolicy(epoch)
        activeProfile = DiagnosticsProfile.DISABLED
        activeRuntimePolicy = TraceboxPolicy.disabled()
        mutablePolicy.value = activeRuntimePolicy
        quiesceManagedWriters()
        stopAllNativeParticipants()
        markDegraded()
    }

    private fun commitLocalPolicy(page: ControlPage, snapshot: PolicySnapshot) {
        val quota = uidQuota
        if (quota == null) {
            commitLocalPolicyUnderMutationBarrier(page, snapshot)
        } else {
            quota.withStorageMutation {
                commitLocalPolicyUnderMutationBarrier(page, snapshot)
            }
        }
    }

    private fun commitLocalPolicyUnderMutationBarrier(
        page: ControlPage,
        snapshot: PolicySnapshot,
    ) {
        val mirror = directBootMirror()
        // Pending is conservative: DenyMirror combines it with active by OR until promotion.
        // This single ordering is therefore safe for both tightening and rollback/loosening.
        writePendingDeny(
            mirror,
            DenyState(snapshot.epoch, snapshot.disabled, snapshot.denyMask),
        )
        page.commit(snapshot)
        promotePendingDeny(mirror)
        check(localPolicyMatches(page, snapshot)) {
            "CE and Direct Boot policy did not converge at epoch ${snapshot.epoch}"
        }
    }

    private fun finishDurablePolicyCommit(
        target: PolicySnapshot,
        coordinateNative: Boolean,
    ): Boolean {
        return try {
            if (coordinateNative &&
                commitPreparedPolicyWithRetry(target.epoch) != NATIVE_POLICY_SUCCESS
            ) {
                return false
            }
            policyTransitionJournal.complete(target.epoch)
            true
        } catch (_: IOException) {
            false
        } catch (_: PolicyTransitionException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun rollbackBeforeLocalDurability(
        page: ControlPage,
        previous: PolicySnapshot,
        targetEpoch: Long,
        coordinateNative: Boolean,
    ): Boolean {
        val localRestored = runCatching {
            commitLocalPolicy(page, previous)
            true
        }.getOrDefault(false)
        val nativeRestored = if (coordinateNative) {
            abortPreparedPolicyWithRetry(targetEpoch)
            // ABORT intentionally fences the requester transport/raw lease. The liveness
            // observer rearms only after this transaction releases the UID mutation barrier.
            stopAllNativeParticipants()
        } else {
            true
        }
        if (!localRestored || !nativeRestored) return false
        return runCatching {
            policyTransitionJournal.resolveAfterRecovery(targetEpoch)
            true
        }.getOrDefault(false)
    }

    private fun repairDisabledPolicyForPersistentLatch(
        page: ControlPage,
        current: PolicySnapshot,
    ): PolicySnapshot {
        if (policyTransitionJournal.load() is PolicyTransitionLoad.Empty &&
            current.disabled &&
            localPolicyMatches(page, current)
        ) {
            return current
        }
        check(stopAllNativeParticipants()) {
            "native participants could not be stopped for policy-latch repair"
        }
        val directBoot = directBootMirror().effective()
        val repairEpoch = Math.addExact(
            maxOf(
                current.epoch,
                directBoot?.epoch ?: 0L,
                policyTransitionJournal.highWaterEpoch() ?: 0L,
            ),
            1L,
        )
        val repaired = disabledPolicy(repairEpoch)
        // The marker was already durable before this function was entered. Commit the
        // fail-closed local tuple first; superseding the old transition is safe only afterward.
        commitLocalPolicy(page, repaired)
        policyTransitionJournal.supersedeCompleted(current, repaired)
        check(localPolicyMatches(page, repaired))
        return repaired
    }

    private fun recoverInterruptedPolicyTransition(
        page: ControlPage,
        storesAvailable: Boolean,
    ): Boolean {
        return when (val loaded = policyTransitionJournal.load()) {
            PolicyTransitionLoad.Empty -> true
            PolicyTransitionLoad.Corrupt -> {
                stopAllNativeParticipants()
                false
            }

            is PolicyTransitionLoad.Active -> {
                if (!coordinatesGlobalStorage || !stopAllNativeParticipants()) {
                    return false
                }
                val transition = loaded.transition
                val selected =
                    if (transition.phase.ordinal < PolicyTransitionPhase.LOCAL_DURABLE.ordinal) {
                        transition.previous
                    } else {
                        transition.target
                    }
                try {
                    commitLocalPolicy(page, selected)
                    if (!selected.disabled) {
                        if (!storesAvailable) return false
                        activeProfile = profileForPolicy(selected) ?: return false
                        nativeReady = false
                        nativeClientMode = NativeRuntime.CLIENT_MODE_REJECTED
                    } else {
                        activeProfile = DiagnosticsProfile.DISABLED
                    }
                    policyTransitionJournal.resolveAfterRecovery(transition.target.epoch)
                    true
                } catch (_: IOException) {
                    stopAllNativeParticipants()
                    false
                } catch (_: PolicyPageException) {
                    stopAllNativeParticipants()
                    false
                } catch (_: PolicyTransitionException) {
                    stopAllNativeParticipants()
                    false
                } catch (_: IllegalStateException) {
                    stopAllNativeParticipants()
                    false
                } catch (_: LinkageError) {
                    stopAllNativeParticipants()
                    false
                }
            }
        }
    }

    private fun commitPreparedPolicyWithRetry(targetEpoch: Long): Int {
        var latest = NATIVE_POLICY_PROTOCOL
        repeat(NATIVE_POLICY_RETRY_ATTEMPTS) {
            latest = try {
                NativeRuntime.commitPreparedPolicy(
                    targetEpoch,
                    POLICY_COORDINATION_TIMEOUT_MILLIS,
                )
            } catch (_: LinkageError) {
                return NATIVE_POLICY_PROTOCOL
            }
            if (latest == NATIVE_POLICY_SUCCESS) return latest
        }
        return latest
    }

    private fun abortPreparedPolicyWithRetry(targetEpoch: Long): Int {
        var latest = NATIVE_POLICY_PROTOCOL
        repeat(NATIVE_POLICY_RETRY_ATTEMPTS) {
            latest = try {
                NativeRuntime.abortPreparedPolicy(
                    targetEpoch,
                    POLICY_COORDINATION_TIMEOUT_MILLIS,
                )
            } catch (_: LinkageError) {
                return NATIVE_POLICY_PROTOCOL
            }
            if (latest == NATIVE_POLICY_SUCCESS) return latest
        }
        return latest
    }

    private fun stopAllNativeParticipants(): Boolean {
        if (!configuration.nativeCaptureEnabled) return true
        val clientStopped = quiesceNativeClient()
        val handlerStopped = !coordinatesGlobalStorage || stopNativeHandler()
        return clientStopped && handlerStopped
    }

    private fun localPolicyMatches(page: ControlPage, expected: PolicySnapshot): Boolean {
        return convergedLocalPolicy(page) == expected
    }

    private fun convergedLocalPolicy(page: ControlPage): PolicySnapshot? {
        val credential = try {
            page.committed()
        } catch (_: PolicyPageException) {
            return null
        }
        return if (localPolicyTuplesConverge(credential, directBootMirror().effective())) {
            credential
        } else {
            null
        }
    }

    private fun throwPolicyFailure(failure: Throwable): Nothing = when (failure) {
        is IOException -> throw failure
        is PolicyPageException -> throw failure
        is PolicyTransitionException -> throw failure
        is LinkageError -> throw IOException("native policy coordination failed", failure)
        is RuntimeException -> throw failure
        else -> throw IOException("policy transaction failed", failure)
    }

    private fun directBootMirror(): DenyMirror {
        return DenyMirror(
            directBootRoot.resolve(ACTIVE_DENY_FILE),
            directBootRoot.resolve(PENDING_DENY_FILE),
        )
    }

    /**
     * Provisions or removes the fixed C0 store only from the unlocked primary process.
     *
     * The manager writes activation last. Its guard reserves every possible physical byte first
     * and joins the same DE mutation barrier used by policy, deletion, handler startup, and the
     * locked-boot writer.
     */
    private fun configureDirectBootStorage(): Boolean {
        if (!coordinatesGlobalStorage) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            directBootManager = null
            return true
        }
        val quota = uidQuota ?: return false
        val ownership = storageOwnership ?: return false
        val guard = DirectBootStorageMutationGuard { request, mutation ->
            runProductionDirectBootMutation(
                request,
                mutation,
                quota,
                ownership,
            )
        }
        val manager = DirectBootManager.fromDeviceProtectedContext(
            applicationContext,
            GeneratedDirectBootSchemaFingerprint.bytes(),
            guard,
        )
        val configured = try {
            if (configuration.directBootC0Enabled) {
                when (val initial = manager.setup()) {
                    DirectBootSetupResult.ACTIVATED,
                    DirectBootSetupResult.ALREADY_ACTIVE,
                    -> true

                    DirectBootSetupResult.STORAGE_INELIGIBLE,
                    -> false

                    DirectBootSetupResult.INVALID_STORAGE,
                    DirectBootSetupResult.SCHEMA_MISMATCH,
                    -> {
                        // A schema-projection change, corrupt activation, or crash after migration
                        // removed activation but before records is recovered unlocked. C0 is
                        // deliberately bounded and lossy: disable first (activation disappears
                        // before records), then provision the new canonical projection.
                        val disabled = manager.disable()
                        if (disabled != DirectBootDisableResult.DISABLED &&
                            disabled != DirectBootDisableResult.ALREADY_DISABLED
                        ) {
                            false
                        } else {
                            when (manager.setup()) {
                                DirectBootSetupResult.ACTIVATED,
                                DirectBootSetupResult.ALREADY_ACTIVE,
                                -> true

                                else -> false
                            }
                        }
                    }
                }
            } else {
                when (manager.disable()) {
                    DirectBootDisableResult.DISABLED,
                    DirectBootDisableResult.ALREADY_DISABLED,
                    -> true

                    DirectBootDisableResult.STORAGE_INELIGIBLE,
                    DirectBootDisableResult.INVALID_STORAGE,
                    -> false
                }
            }
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        directBootManager =
            if (configured && configuration.directBootC0Enabled) manager else null
        return configured
    }

    private fun runProductionDirectBootMutation(
        request: DirectBootStorageMutationRequest,
        mutation: () -> Unit,
        quota: UidWideQuotaCoordinator,
        ownership: UidWideStorageReconciler,
    ): Boolean {
        val expectedRecords = directBootRoot.resolve(DirectBootLayout.RECORDS_FILE_NAME)
            .toAbsolutePath().normalize()
        if (request.recordsPath.toAbsolutePath().normalize() != expectedRecords ||
            request.reservationBytes != DirectBootLayout.RECORDS_BYTES.toLong()
        ) {
            return false
        }

        var callbackEntered = false
        return try {
            quota.withStorageMutation {
                if (!TraceboxOwnedStorageRoot.isEligible(directBootRoot)) {
                    return@withStorageMutation false
                }
                if (request.operation == DirectBootMutation.SETUP &&
                    !prepareDirectBootExternalReservations(ownership)
                ) {
                    synchronizeDirectBootExternalReservations(ownership)
                    return@withStorageMutation false
                }
                callbackEntered = true
                var mutationFailure: Throwable? = null
                try {
                    mutation()
                } catch (failure: Throwable) {
                    mutationFailure = failure
                    throw failure
                } finally {
                    if (request.operation == DirectBootMutation.SETUP ||
                        request.operation == DirectBootMutation.DISABLE
                    ) {
                        try {
                            check(
                                synchronizeDirectBootExternalReservations(
                                    ownership,
                                    requireCanonicalSizes = mutationFailure == null,
                                ),
                            ) {
                                "Direct Boot quota ownership did not match durable files"
                            }
                        } catch (reconciliationFailure: Throwable) {
                            if (mutationFailure == null) {
                                throw reconciliationFailure
                            }
                            mutationFailure.addSuppressed(reconciliationFailure)
                        }
                    }
                }
                true
            }
        } catch (failure: IOException) {
            if (callbackEntered) throw failure
            false
        } catch (failure: RuntimeException) {
            if (callbackEntered) throw failure
            false
        }
    }

    private fun prepareDirectBootExternalReservations(
        ownership: UidWideStorageReconciler,
    ): Boolean = listOf(
        ensureDirectBootExternalCapacity(
            ownership,
            DirectBootLayout.RECORDS_FILE_NAME,
            UidBucket.EMERGENCY,
            DirectBootLayout.RECORDS_BYTES.toLong(),
        ),
            ensureDirectBootExternalCapacity(
                ownership,
                DirectBootLayout.ACTIVATION_FILE_NAME,
                UidBucket.METADATA,
                DirectBootLayout.ACTIVATION_BYTES.toLong(),
            ),
            ensureDirectBootExternalCapacity(
                ownership,
                DirectBootLayout.ACTIVATION_TEMP_FILE_NAME,
                UidBucket.METADATA,
                DirectBootLayout.ACTIVATION_BYTES.toLong(),
            ),
    ).all { it }

    private fun ensureDirectBootExternalCapacity(
        ownership: UidWideStorageReconciler,
        relativePath: String,
        bucket: UidBucket,
        requiredBytes: Long,
    ): Boolean {
        val physical = directBootRoot.resolve(relativePath)
        if (!Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
            return ownership.reserveExternal(
                DE_STORAGE_ROOT_ID,
                relativePath,
                bucket,
                requiredBytes,
            ) is ExternalOwnedStorageMutationResult.Applied
        }
        if (!Files.isRegularFile(physical, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(physical)
        ) {
            return false
        }
        val actualBytes = Files.size(physical)
        if (ownership.resizeExternal(
                DE_STORAGE_ROOT_ID,
                relativePath,
                bucket,
                actualBytes,
            ) !is ExternalOwnedStorageMutationResult.Applied
        ) {
            return false
        }
        return actualBytes >= requiredBytes ||
            ownership.growExternal(
                DE_STORAGE_ROOT_ID,
                relativePath,
                bucket,
                requiredBytes - actualBytes,
            ) is ExternalOwnedStorageMutationResult.Applied
    }

    private fun synchronizeDirectBootExternalReservations(
        ownership: UidWideStorageReconciler,
        requireCanonicalSizes: Boolean = true,
    ): Boolean = listOf(
        synchronizeDirectBootExternalReservation(
            ownership,
            DirectBootLayout.RECORDS_FILE_NAME,
            UidBucket.EMERGENCY,
            DirectBootLayout.RECORDS_BYTES.toLong(),
            requireCanonicalSizes,
        ),
            synchronizeDirectBootExternalReservation(
                ownership,
                DirectBootLayout.ACTIVATION_FILE_NAME,
            UidBucket.METADATA,
            DirectBootLayout.ACTIVATION_BYTES.toLong(),
            requireCanonicalSizes,
        ),
            synchronizeDirectBootExternalReservation(
                ownership,
                DirectBootLayout.ACTIVATION_TEMP_FILE_NAME,
            UidBucket.METADATA,
            DirectBootLayout.ACTIVATION_BYTES.toLong(),
            requireCanonicalSizes,
        ),
    ).all { it }

    private fun synchronizeDirectBootExternalReservation(
        ownership: UidWideStorageReconciler,
        relativePath: String,
        bucket: UidBucket,
        expectedBytes: Long,
        requireCanonicalSize: Boolean,
    ): Boolean {
        val physical = directBootRoot.resolve(relativePath)
        if (!Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
            return when (
                val released = ownership.releaseExternal(
                    DE_STORAGE_ROOT_ID,
                    relativePath,
                    bucket,
                )
            ) {
                ExternalOwnedStorageMutationResult.Applied -> true
                is ExternalOwnedStorageMutationResult.Rejected ->
                    released.reason ==
                        ExternalOwnedStorageMutationFailureReason.NOT_RESERVED
            }
        }
        if (!Files.isRegularFile(physical, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(physical)
        ) {
            return false
        }
        val actualBytes = Files.size(physical)
        if (requireCanonicalSize && actualBytes != expectedBytes) return false
        return ownership.resizeExternal(
            DE_STORAGE_ROOT_ID,
            relativePath,
            bucket,
            actualBytes,
        ) is ExternalOwnedStorageMutationResult.Applied
    }

    private fun writeFailClosedDirectBootRepair() {
        val mirror = directBootMirror()
        val completedEpoch = maxOf(
            policyTransitionJournal.lastCompletedTarget()?.epoch ?: 0L,
            policyTransitionJournal.highWaterEpoch() ?: 0L,
        )
        val directBootEpoch = maxOf(
            mirror.active()?.epoch ?: 0L,
            mirror.pending()?.epoch ?: 0L,
        )
        val epoch = Math.addExact(maxOf(completedEpoch, directBootEpoch), 1L)
        writePendingDeny(mirror, DenyState(epoch, disabled = true, Long.MAX_VALUE))
        promotePendingDeny(mirror)
    }

    private fun productionStorageOwnership(
        coordinator: UidWideQuotaCoordinator,
    ): UidWideStorageReconciler {
        val credentialProtected = OwnedStorageRoot(
            id = CE_STORAGE_ROOT_ID,
            path = root,
            maxFiles = CE_STORAGE_MAX_FILES,
            maxDepth = STORAGE_MAX_DEPTH,
            maxFileBytes = CE_STORAGE_MAX_FILE_BYTES,
            preservedRelativePaths = setOf(
                POLICY_CONTROL_FILE,
                COORDINATOR_LOCK_FILE,
                POLICY_REPAIR_MARKER_FILE,
                REQUESTED_PROFILE_FILE,
                "$POLICY_TRANSITION_FILE-a",
                "$POLICY_TRANSITION_FILE-b",
            ),
            reservationSizer = ::credentialProtectedReservationBytes,
            classifier = ::classifyCredentialProtectedStorage,
        )
        val deviceProtected = OwnedStorageRoot(
            id = DE_STORAGE_ROOT_ID,
            path = directBootRoot,
            maxFiles = DE_STORAGE_MAX_FILES,
            maxDepth = STORAGE_MAX_DEPTH,
            maxFileBytes = DE_STORAGE_MAX_FILE_BYTES,
            preservedRelativePaths = setOf(
                ACTIVE_DENY_FILE,
                PENDING_DENY_FILE,
                "$ACTIVE_DENY_FILE.new",
                "$PENDING_DENY_FILE.new",
            ),
            domain = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                OwnedStorageDomain.DEVICE_PROTECTED
            } else {
                OwnedStorageDomain.CREDENTIAL_PROTECTED
            },
            classifier = ::classifyDeviceProtectedStorage,
        )
        return UidWideStorageReconciler(
            accountingRoot = root,
            quota = coordinator,
            roots = listOf(credentialProtected, deviceProtected),
            maxCatalogEntries = STORAGE_CATALOG_MAX_ENTRIES,
        )
    }

    private fun productionStorageDeletion(
        ownership: UidWideStorageReconciler,
    ): JournaledStorageTreeDeletion = JournaledStorageTreeDeletion(
        ownership = ownership,
        transactionId = STORAGE_DELETE_TRANSACTION,
        denyCommit = StorageDeletionDenyCommit(::commitDurableDeletionDeny),
        denyVerification = StorageDeletionDenyVerification(::durableDenyIsCommitted),
        quiesceParticipants = listOf(
            StorageQuiesceParticipant("managed-writers", ::quiesceManagedWriters),
            StorageQuiesceParticipant("native-client", ::quiesceNativeClient),
            StorageQuiesceParticipant("native-handler", ::stopNativeHandler),
            StorageQuiesceParticipant("package-state") {
                invalidateRuntimePackageCapabilities()
                true
            },
            StorageQuiesceParticipant("storage-state") {
                rawArtifactStore = null
                exitTombstones = null
                exitImports = null
                currentProcessIdentity = null
                handlerProcessIdentity = null
                armedRawArtifactIdentity = null
                true
            },
        ),
        maxDeletesPerRun = STORAGE_DELETE_BATCH_SIZE,
    )

    private fun runStorageDeletionToBoundary(
        deletion: JournaledStorageTreeDeletion,
    ): StorageTreeDeletionReport {
        var latest: StorageTreeDeletionReport = deletion.deleteAll()
        repeat(STORAGE_DELETE_MAX_PASSES - 1) {
            val pending = latest as? StorageTreeDeletionReport.Pending ?: return latest
            if (pending.failures.isEmpty() ||
                pending.failures.any { it.reason != StorageTreeDeletionFailureReason.BATCH_LIMIT }
            ) {
                return pending
            }
            latest = deletion.deleteAll()
        }
        return latest
    }

    private fun commitDurableDeletionDeny(): Boolean {
        if (applyProfile(DiagnosticsProfile.DISABLED) != PolicyUpdateResult.SUCCESS) return false
        return durableDenyIsCommitted()
    }

    private fun durableDenyIsCommitted(): Boolean {
        val credentialState = try {
            controlPage?.committed()
        } catch (_: PolicyPageException) {
            null
        } ?: return false
        val directBootState = directBootMirror().effective() ?: return false
        return credentialState.disabled &&
            directBootState.disabled &&
            directBootState.epoch >= credentialState.epoch
    }

    private fun ownedStorageRootsEligible(): Boolean =
        TraceboxOwnedStorageRoot.isEligible(root) &&
            TraceboxOwnedStorageRoot.isEligible(directBootRoot)

    private fun captureStorageEligibility(expectedEpoch: Long? = null) =
        StorageMutationEligibility {
            val committed = try {
                controlPage?.committed()
            } catch (_: PolicyPageException) {
                null
            }
            committed != null &&
                !committed.disabled &&
                (expectedEpoch == null || committed.epoch == expectedEpoch) &&
                localPolicyMatches(checkNotNull(controlPage), committed) &&
                policyTransitionJournal.load() is PolicyTransitionLoad.Empty &&
                repairMarkerAllowsPrimaryEnable(committed.epoch) &&
                ownedStorageRootsEligible()
        }

    private fun repairMarkerAllowsPrimaryEnable(epoch: Long): Boolean =
        repairMarkerAllowsEnable(
            policyRepairRequired(),
            coordinatesGlobalStorage,
            authorizedRepairEnableEpoch,
            epoch,
        )

    private fun prepareStorageForEnable(): Boolean {
        if (!TraceboxOwnedStorageRoot.isClaimed(root)) return false
        if (!coordinatesGlobalStorage) return TraceboxOwnedStorageRoot.isEligible(root)
        val ownership = storageOwnership ?: return false
        var reactivated = false
        if (!TraceboxOwnedStorageRoot.isEligible(directBootRoot)) {
            if (ownership.reactivateRoot(DE_STORAGE_ROOT_ID) != StorageRootReactivationResult.Reactivated) {
                return false
            }
            reactivated = true
        }
        if (!TraceboxOwnedStorageRoot.isEligible(root)) {
            if (ownership.reactivateRoot(CE_STORAGE_ROOT_ID) != StorageRootReactivationResult.Reactivated) {
                return false
            }
            reactivated = true
        }
        if (!ownedStorageRootsEligible() ||
            reactivated && ownership.reconcile() is StorageOwnershipReport.Partial
        ) {
            return false
        }
        initializePersistentStores()
        return configureDirectBootStorage()
    }

    private fun initializePersistentStores() {
        val quota = checkNotNull(uidQuota)
        if (rawArtifactStore == null) {
            rawArtifactStore = RawArtifactStore(
                root.resolve(RAW_ARTIFACT_DIRECTORY),
                RAW_ARTIFACT_LIMIT,
                quota,
                captureStorageEligibility(),
            )
        }
        if (exitTombstones == null) {
            exitTombstones = ExitTombstoneLedger(
                root.resolve(EXIT_TOMBSTONE_FILE),
                EXIT_TOMBSTONE_LIMIT,
                EXIT_TOMBSTONE_BYTES,
            )
        }
        if (exitImports == null) {
            exitImports = ExitImportJournal(
                root.resolve(EXIT_IMPORT_DIRECTORY),
                EXIT_IMPORT_JOURNAL_LIMIT,
                EXIT_IMPORT_JOURNAL_BYTES,
            )
        }
    }

    private fun remainDisabledAfterDeletion() {
        stopPrimaryNativeObserver()
        activeProfile = DiagnosticsProfile.DISABLED
        publishDisabledExitPolicy(
            runCatching { controlPage?.committed()?.epoch }.getOrNull() ?: 0L,
        )
        quiesceManagedWriters()
        quiesceNativeClient()
        if (coordinatesGlobalStorage) stopNativeHandler()
        if (durableDenyIsCommitted()) {
            mutableHealth.value = TraceboxHealth.DISABLED
            mutableReadiness.value = Readiness.DURABLE
        } else {
            markDegraded()
        }
    }

    private fun quiesceManagedWriters(
        preserveVolatileManagedCrashes: Boolean = false,
    ): Boolean {
        activityVisibility.detach()
        watchdog?.close()
        watchdog = null
        val currentWriter = writer
        writer = null
        generatedAdapter = null
        if (!preserveVolatileManagedCrashes) {
            volatileManagedCrashes.resolve(enabled = false, sinkReady = false)
            volatileExceptionCrashes.resolve(enabled = false, sinkReady = false)
        }
        return if (currentWriter == null) {
            true
        } else {
            try {
                currentWriter.seal()
                true
            } catch (_: IllegalStateException) {
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun quiesceNativeClient(): Boolean {
        if (!configuration.nativeCaptureEnabled) {
            nativeReady = false
            nativeClientMode = NativeRuntime.CLIENT_MODE_REJECTED
            armedRawArtifactIdentity = null
            return true
        }
        val captureWasActive = nativeReady ||
            nativeClientMode != NativeRuntime.CLIENT_MODE_REJECTED ||
            armedRawArtifactIdentity != null
        val stopped = try {
            NativeRuntime.shutdownCapture()
            true
        } catch (_: LinkageError) {
            !captureWasActive
        } catch (_: RuntimeException) {
            false
        }
        if (stopped) armedRawArtifactIdentity = null
        nativeReady = false
        nativeClientMode = NativeRuntime.CLIENT_MODE_REJECTED
        return stopped
    }

    private fun nativePolicyParticipantAlive(): Boolean =
        configuration.nativeCaptureEnabled && nativeReady && try {
            NativeRuntime.isPolicyParticipantAlive()
        } catch (_: LinkageError) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun stopNativeHandler(): Boolean {
        if (!configuration.nativeCaptureEnabled) return true
        if (!coordinatesGlobalStorage) return true
        val quota = uidQuota
        return try {
            if (quota == null) {
                stopNativeHandlerUnderMutationBarrier()
            } else {
                quota.withStorageMutation(::stopNativeHandlerUnderMutationBarrier)
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun stopNativeHandlerUnderMutationBarrier(): Boolean {
        val nativeDirectory = nativeHandlerDirectory()
        val socket = nativeDirectory.resolve(TraceboxHandlerService.SOCKET_NAME)
        // Fence a start request before releasing the lifetime binding and asking Android to stop
        // the started-service half. A queued invocation can no longer consume an old
        // authorization after this method returns.
        val permitInvalidated = HandlerStartPermit.invalidate(nativeDirectory)
        if (permitInvalidated) releaseMissingHandlerStartPermitReservations()
        val bindingReleased = releaseNativeHandlerBinding()
        try {
            applicationContext.stopService(Intent(applicationContext, TraceboxHandlerService::class.java))
        } catch (_: SecurityException) {
            return false
        } catch (_: RuntimeException) {
            return false
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDLER_STOP_TIMEOUT_MILLIS)
        while (Files.exists(socket, LinkOption.NOFOLLOW_LINKS) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(HANDLER_STOP_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        // Normal socket disappearance is the native handler's completion signal. If Android
        // killed the handler without running its shutdown path, use the native conclusive probe:
        // it unlinks only a canonical, lock-confirmed stale AF_UNIX socket and fails closed for a
        // live listener, an in-flight lifecycle drain, or any ambiguous filesystem state.
        val stopped = recoverStoppedHandlerSocket(
            socket,
            NativeRuntime::cleanupStaleHandlerSocket,
        )
        return permitInvalidated &&
            bindingReleased &&
            stopped &&
            reconcileCrashpadClientLifecycles(handlerQuiesced = true)
    }

    private fun startAndHoldNativeHandler(intent: Intent): Boolean {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit

            override fun onServiceDisconnected(name: ComponentName) {
                // Android retains the binding and may reconnect it. Keep the connection token so
                // the serialized stop/restart path can unbind it exactly once, but withdraw
                // readiness immediately because the native transport process disappeared.
                nativeReady = false
            }
        }
        return handlerServiceLifetime.startAndHold(
            NativeHandlerServiceBinding(intent, connection),
        )
    }

    private fun releaseNativeHandlerBinding(): Boolean = handlerServiceLifetime.release()

    private fun prepareHandlerStartPermit(
        snapshot: PolicySnapshot,
        handlerIdentity: ByteArray,
        rawArtifactId: ByteArray,
    ): ByteArray? {
        val quota = uidQuota ?: return null
        val nativeDirectory = nativeHandlerDirectory()
        val permitPath = HandlerStartPermit.path(nativeDirectory)
        val temporaryPath = HandlerStartPermit.temporaryPath(nativeDirectory)
        val lifecyclePath = nativeClientLifecyclePath(
            nativeDirectory.resolve(CLIENT_LIFECYCLE_DIRECTORY),
            configuration.processRole,
            rawArtifactId,
        )
        val token = HandlerStartPermit.newToken()
        val prepared = try {
            when (
                val guarded = quota.mutateStorageIfEligible(
                    captureStorageEligibility(snapshot.epoch),
                ) {
                    reserveNativeSlots(TraceboxHandlerService.PROCESS_ROLE_HANDLER)
                    ensureMetadataReservation(permitPath, HandlerStartPermit.FILE_BYTES)
                    ensureMetadataReservation(temporaryPath, HandlerStartPermit.FILE_BYTES)
                    ensureMetadataReservation(
                        lifecyclePath,
                        CLIENT_LIFECYCLE_JOURNAL_BYTES,
                    )
                    if (stablePrimaryCaptureSnapshot() != snapshot ||
                        !HandlerStartPermit.invalidate(nativeDirectory) ||
                        !HandlerStartPermit.write(
                            nativeDirectory,
                            handlerIdentity,
                            snapshot.epoch,
                            token,
                        ) ||
                        stablePrimaryCaptureSnapshot() != snapshot
                    ) {
                        null
                    } else {
                        token.copyOf()
                    }
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value
                StorageMutationBarrierResult.Rejected -> null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        if (prepared == null) {
            runCatching {
                quota.withStorageMutation {
                    if (HandlerStartPermit.invalidate(nativeDirectory)) {
                        releaseMissingHandlerStartPermitReservations()
                        releaseMissingQuotaReservation(lifecyclePath)
                    }
                }
            }
        }
        return prepared
    }

    private fun releaseMissingHandlerStartPermitReservations() {
        val nativeDirectory = nativeHandlerDirectory()
        releaseMissingQuotaReservation(HandlerStartPermit.path(nativeDirectory))
        releaseMissingQuotaReservation(HandlerStartPermit.temporaryPath(nativeDirectory))
    }

    private fun writeRequestedPolicy(profile: DiagnosticsProfile, policy: TraceboxPolicy) {
        val policyTarget = root.resolve(REQUESTED_POLICY_FILE)
        val policyTemporary = policyTarget.resolveSibling("${policyTarget.fileName}.new")
        ensureMetadataReservation(policyTarget, REQUESTED_POLICY_BYTES)
        ensureMetadataReservation(policyTemporary, REQUESTED_POLICY_BYTES)
        try {
            runtimePolicyStore.write(policy)
        } finally {
            if (!Files.exists(policyTemporary, LinkOption.NOFOLLOW_LINKS)) {
                uidQuota?.release(policyTemporary)
            }
        }
        val target = root.resolve(REQUESTED_PROFILE_FILE)
        val temporary = target.resolveSibling("${target.fileName}.new")
        ensureMetadataReservation(target, REQUESTED_PROFILE_BYTES)
        ensureMetadataReservation(temporary, REQUESTED_PROFILE_BYTES)
        try {
            profileStore.write(profile)
        } finally {
            if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                uidQuota?.release(temporary)
            }
        }
    }

    private fun writePendingDeny(mirror: DenyMirror, state: DenyState) {
        prepareDirectBootControlWrite(PENDING_DENY_FILE)
        try {
            mirror.writePending(state)
        } finally {
            releaseMissingDirectBootControl("$PENDING_DENY_FILE.new")
            releaseMissingDirectBootControl(PENDING_DENY_FILE)
        }
    }

    private fun promotePendingDeny(mirror: DenyMirror) {
        prepareDirectBootControlWrite(ACTIVE_DENY_FILE)
        try {
            mirror.promotePending()
        } finally {
            releaseMissingDirectBootControl("$ACTIVE_DENY_FILE.new")
            releaseMissingDirectBootControl(ACTIVE_DENY_FILE)
            releaseMissingDirectBootControl(PENDING_DENY_FILE)
        }
    }

    private fun prepareDirectBootControlWrite(relativePath: String) {
        if (!coordinatesGlobalStorage) return
        ensureDirectBootMetadataReservation(relativePath, DIRECT_BOOT_DENY_BYTES)
        ensureDirectBootMetadataReservation("$relativePath.new", DIRECT_BOOT_DENY_BYTES)
    }

    private fun ensureDirectBootMetadataReservation(relativePath: String, bytes: Long) {
        val ownership = checkNotNull(storageOwnership)
        val physical = directBootRoot.resolve(relativePath)
        val result = if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
            ownership.resizeExternal(DE_STORAGE_ROOT_ID, relativePath, UidBucket.METADATA, bytes)
        } else {
            ownership.releaseExternal(DE_STORAGE_ROOT_ID, relativePath, UidBucket.METADATA)
            ownership.reserveExternal(DE_STORAGE_ROOT_ID, relativePath, UidBucket.METADATA, bytes)
        }
        val legacyExistingControl =
            Files.isRegularFile(physical, LinkOption.NOFOLLOW_LINKS) && Files.size(physical) == bytes
        check(result is ExternalOwnedStorageMutationResult.Applied || legacyExistingControl) {
            "Direct Boot metadata quota reservation failed for $relativePath: $result"
        }
    }

    private fun releaseMissingDirectBootControl(relativePath: String) {
        if (!coordinatesGlobalStorage) return
        if (Files.exists(directBootRoot.resolve(relativePath), LinkOption.NOFOLLOW_LINKS)) return
        storageOwnership?.releaseExternal(
            DE_STORAGE_ROOT_ID,
            relativePath,
            UidBucket.METADATA,
        )
    }

    private fun ensureMetadataReservation(path: Path, bytes: Long) {
        check(ensureQuotaReservation(path, UidBucket.METADATA, bytes)) {
            "Tracebox metadata quota exhausted for ${path.fileName}"
        }
    }

    private fun acquireCoordinatorLease(): Boolean {
        val acquired = PrimaryCoordinatorLease.tryAcquire(root.resolve(COORDINATOR_LOCK_FILE))
            ?: return false
        coordinatorLease = acquired
        return true
    }

    private fun releaseCoordinatorLease() {
        coordinatorLease?.close()
        coordinatorLease = null
    }

    private fun ensurePolicyTransitionReservations() {
        policyTransitionJournal.slotPaths.forEach { slot ->
            ensureMetadataReservation(
                slot,
                PolicyTransitionJournal.SLOT_BYTES.toLong(),
            )
        }
    }

    private fun markPolicyRepairRequired() {
        ensureMetadataFile(
            root.resolve(POLICY_REPAIR_MARKER_FILE),
            POLICY_REPAIR_MARKER_BYTES,
        )
    }

    private fun policyRepairRequired(): Boolean {
        val path = root.resolve(POLICY_REPAIR_MARKER_FILE)
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }

    private fun clearPolicyRepairRequired(): Boolean {
        val quota = uidQuota
        return try {
            if (quota == null) {
                clearPolicyRepairRequiredUnderMutationBarrier()
            } else {
                quota.withStorageMutation(::clearPolicyRepairRequiredUnderMutationBarrier)
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun clearPolicyRepairRequiredUnderMutationBarrier(): Boolean {
        val path = root.resolve(POLICY_REPAIR_MARKER_FILE)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        if (readExactMetadata(path, POLICY_REPAIR_MARKER_BYTES.size)
                ?.contentEquals(POLICY_REPAIR_MARKER_BYTES) != true
        ) {
            return false
        }
        return try {
            Files.delete(path)
            forceDirectoryBestEffort(path.parent)
            uidQuota?.release(path)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun readExactMetadata(path: Path, expectedBytes: Int): ByteArray? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() != expectedBytes.toLong()) return null
                val bytes = ByteArray(expectedBytes)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return null
                }
                bytes
            }
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private fun reserveNativeSlots(processRole: Int) {
        val nativeDirectory = nativeHandlerDirectory()
        check(
            ensureQuotaReservation(
                nativeDirectory.resolve("tracebox-emergency-$processRole.bin"),
                UidBucket.EMERGENCY,
                NATIVE_EMERGENCY_SLOT_BYTES,
            ),
        ) {
            "Tracebox emergency slot quota exhausted"
        }
        check(
            ensureQuotaReservation(
                nativeDirectory.resolve("tracebox-rust-panic-$processRole.bin"),
                UidBucket.EMERGENCY,
                RUST_PANIC_SLOT_BYTES,
            ),
        ) {
            "Tracebox Rust panic slot quota exhausted"
        }
    }

    private fun ensureQuotaReservation(path: Path, bucket: UidBucket, bytes: Long): Boolean {
        val quota = checkNotNull(uidQuota)
        return quota.owns(path, bucket, bytes) ||
            quota.resize(path, bucket, bytes) ||
            quota.reserve(path, bucket, bytes)
    }

    private fun markDegraded() {
        mutableHealth.value = TraceboxHealth.DEGRADED
        mutableReadiness.value = Readiness.DEGRADED
    }

    private fun rotateWriter(snapshot: PolicySnapshot) {
        val quota = checkNotNull(uidQuota)
        val eligibility = captureStorageEligibility(snapshot.epoch)
        when (
            val guarded = quota.mutateStorageIfEligible(eligibility) {
                rotateWriterUnderMutationBarrier(snapshot, quota, eligibility)
            }
        ) {
            is StorageMutationBarrierResult.Applied -> Unit
            StorageMutationBarrierResult.Rejected ->
                throw IllegalStateException("storage is not eligible at policy epoch ${snapshot.epoch}")
        }
    }

    private fun rotateWriterUnderMutationBarrier(
        snapshot: PolicySnapshot,
        quota: UidWideQuotaCoordinator,
        eligibility: StorageMutationEligibility,
    ) {
        writer?.let {
            try {
                it.seal()
            } catch (_: IllegalStateException) {
                // The prior segment remains immutable and is excluded only when its epoch differs.
            }
        }
        val instances = root.resolve("instances")
        Files.createDirectories(instances)
        val processIdentity = currentProcessIdentity?.copyOf()
            ?: allocateJournaledIdentity(PROCESS_INSTANCE_IDENTITY_KIND).also {
                currentProcessIdentity = it.copyOf()
            }
        val instance = instances.resolve(encode(processIdentity))
        Files.createDirectories(instance)
        val identityPath = instance.resolve("process-instance-id")
        ensureMetadataFile(identityPath, processIdentity)
        val segments = instance.resolve("segments")
        Files.createDirectories(segments)
        ensureMetadataReservation(segments.resolve(ROLE_QUOTA_LOCK_FILE), 0L)
        val segmentIdentity = allocateJournaledIdentity(ORDINARY_SEGMENT_IDENTITY_KIND)
        val segmentPath = segments.resolve("${encode(segmentIdentity)}.tbseg")
        val roleLedger = RoleQuotaLedger(RoleQuotaPolicy(mapOf(configuration.processRole to ROLE_SEGMENT_LIMIT)), segments)
        val created = SegmentWriter.create(
            segmentPath,
            SegmentHeader(
                PersistedSegmentIdentity(segmentIdentity, processIdentity),
                configuration.generatedSchemaFingerprint,
                snapshot.epoch,
                0,
                configuration.processRole,
            ),
            checkNotNull(policyGate),
            roleLedger,
            quota,
            eligibility,
        )
        writer = created
        generatedAdapter = GeneratedRecordSegmentAdapter(created, checkNotNull(policyGate))
        currentProcessIdentity = processIdentity.copyOf()
        if (configuration.nativeCaptureEnabled) {
            ingestEmergencySlot()
            ingestRustPanicSlot()
        }
        directBootRecoveryReady = ingestDirectBootStartup()
        drainVolatileManagedCrashes()
        crashpadRecoveryReady = !configuration.nativeCaptureEnabled ||
            !coordinatesGlobalStorage || ingestCrashpadStartup(handlerQuiesced = false)
        ApplicationExitInfoAdapter().publishPolicyToken(
            applicationContext,
            ExitPolicyToken(
                snapshot.epoch,
                rawArtifactAllowed = activeProfile == DiagnosticsProfile.STANDARD_DIAGNOSTICS ||
                    activeProfile == DiagnosticsProfile.ENHANCED_DIAGNOSTIC_SESSION,
                processIdentity,
                configuration.processRole,
            ),
        )
    }

    private fun ingestEmergencySlot(role: Int = configuration.processRole) {
        val adapter = generatedAdapter ?: return
        val slot = nativeSlotPath(nativeHandlerDirectory(), NativeSlotKind.EMERGENCY, role)
        EmergencyStartupIngestor(slot, adapter).ingest()
    }

    private fun ingestRustPanicSlot(role: Int = configuration.processRole) {
        val adapter = generatedAdapter ?: return
        val slot = nativeSlotPath(nativeHandlerDirectory(), NativeSlotKind.RUST_PANIC, role)
        RustPanicStartupIngestor(slot, adapter).ingest()
    }

    /**
     * Imports C0 records oldest-first, then retires only a durably acknowledged tail.
     *
     * `recordWithInternalIdentity` prefixes the schema body with the Direct Boot source ID. The
     * emergency event uses the critical append path, which forces the frame before returning
     * [GeneratedRecordAppendResult.Appended]. A crash at any later boundary leaves the DE frame
     * for an idempotent retry.
     */
    private fun ingestDirectBootStartup(): Boolean {
        val manager = directBootManager ?: return true
        val records = generatedAdapter ?: return false
        val expectedSchema = GeneratedDirectBootSchemaFingerprint.bytes()
        return DirectBootStartupImporter(expectedSchema).import(
            source = ManagerDirectBootImportSource(manager),
            sink = DirectBootCeImportSink { candidate ->
                val sourceId = candidate.sourceId
                if (containsImportedRecord(GeneratedEventId.EMERGENCYRECORD, sourceId)) {
                    DirectBootCeDurability.ALREADY_DURABLE
                } else {
                    when (records.recordWithInternalIdentity(candidate.record, sourceId)) {
                        is GeneratedRecordAppendResult.Appended ->
                            DirectBootCeDurability.APPENDED_DURABLE

                        is GeneratedRecordAppendResult.Dropped,
                        is GeneratedRecordAppendResult.DroppedQuota,
                        GeneratedRecordAppendResult.Ignored,
                        -> DirectBootCeDurability.RETRY_REQUIRED
                    }
                }
            },
        ).complete
    }

    private fun ingestCrashpadStartup(handlerQuiesced: Boolean): Boolean {
        val rawStore = rawArtifactStore ?: return false
        val records = generatedAdapter ?: return false
        val quota = uidQuota ?: return false
        val identityJournal = root.resolve(IDENTITY_JOURNAL_FILE)
        val handoffDirectory = nativeHandlerDirectory().resolve(HANDOFF_DIRECTORY)
        val lifecycleDirectory = nativeHandlerDirectory().resolve(CLIENT_LIFECYCLE_DIRECTORY)
        if (handlerQuiesced &&
            !recoverPendingCrashpadHandoff(
                rawStore,
                lifecycleDirectory,
                handoffDirectory,
                quota,
            )
        ) {
            return false
        }
        val ingestor = CrashpadHandoffIngestor(
            handoffDirectory = handoffDirectory,
            rawStore = rawStore,
            summarySpoolDirectory = root.resolve(SUMMARY_SPOOL_DIRECTORY),
            schemaFingerprint = configuration.generatedSchemaFingerprint,
            identityDeriver = SummaryIdentityDeriver {
                    rawArtifactId,
                    extractorVersion,
                    schemaFingerprint,
                    canonicalContentSha256,
                ->
                try {
                    checkNotNull(
                        NativeRuntime.deriveSummaryId(
                            identityJournal.toString(),
                            rawArtifactId,
                            extractorVersion,
                            schemaFingerprint,
                            canonicalContentSha256,
                        ),
                    ) {
                        "Rust summary identity derivation failed"
                    }
                } catch (error: LinkageError) {
                    throw IllegalStateException("Rust summary identity derivation is unavailable", error)
                }
            },
            summarizer = CrashpadMinidumpSummarizer { committedRawPath, maximumBytes ->
                NativeRuntime.summarizeMinidump(committedRawPath.toString(), maximumBytes)
            },
            appender = DurableStructuralSummaryAppender { internalSummaryId, summary ->
                if (containsSummaryRecord(internalSummaryId)) {
                    DurableSummaryAppendResult.DURABLE
                } else {
                    when (records.recordWithInternalIdentity(summary, internalSummaryId)) {
                        is GeneratedRecordAppendResult.Appended -> DurableSummaryAppendResult.DURABLE
                        else -> DurableSummaryAppendResult.RETRY
                    }
                }
            },
            uidQuota = quota,
            storageEligibility = captureStorageEligibility(),
        )

        var handoffComplete = true
        var handoffTruncated: Boolean
        var handoffPasses = 0
        do {
            val batch = ingestor.ingest(CRASHPAD_RECOVERY_BATCH_FILES)
            if (batch.outcomes.any { it is CrashpadHandoffOutcome.Retained }) {
                handoffComplete = false
            }
            handoffTruncated = batch.truncated
            handoffPasses++
        } while (handoffTruncated && handoffPasses < CRASHPAD_RECOVERY_MAX_PASSES)

        val lifecycleComplete = reconcileCrashpadClientLifecycles(
            rawStore,
            lifecycleDirectory,
            handoffDirectory,
            handlerQuiesced,
        )
        return handoffComplete && !handoffTruncated && lifecycleComplete
    }

    private fun recoverPendingCrashpadHandoff(
        rawStore: RawArtifactStore,
        lifecycleDirectory: Path,
        handoffDirectory: Path,
        quota: UidWideQuotaCoordinator,
    ): Boolean =
        when (
            CrashpadPendingHandoffRecoverer(
                pendingDirectory = nativeHandlerDirectory()
                    .resolve(CRASHPAD_DATABASE_DIRECTORY)
                    .resolve(CRASHPAD_PENDING_DIRECTORY),
                lifecycleDirectory = lifecycleDirectory,
                handoffDirectory = handoffDirectory,
                rawStore = rawStore,
                uidQuota = quota,
            ).recover(handlerQuiesced = true)
        ) {
            CrashpadPendingRecoveryResult.NONE,
            CrashpadPendingRecoveryResult.RECOVERED,
            -> true

            CrashpadPendingRecoveryResult.AMBIGUOUS,
            CrashpadPendingRecoveryResult.FAILED,
            -> false
        }

    private fun reconcileCrashpadClientLifecycles(
        rawStore: RawArtifactStore,
        lifecycleDirectory: Path,
        handoffDirectory: Path,
        handlerQuiesced: Boolean,
    ): Boolean {
        val quota = uidQuota ?: return false
        if (handlerQuiesced &&
            !recoverPendingCrashpadHandoff(rawStore, lifecycleDirectory, handoffDirectory, quota)
        ) {
            return false
        }
        val reconciler = CrashpadClientLifecycleReconciler(
            lifecycleDirectory = lifecycleDirectory,
            handoffDirectory = handoffDirectory,
            rawStore = rawStore,
            uidQuota = uidQuota,
        )
        var truncated: Boolean
        var passes = 0
        do {
            val batch = reconciler.reconcile(
                handlerQuiesced = handlerQuiesced,
                maxFiles = CRASHPAD_RECOVERY_BATCH_FILES,
            )
            truncated = batch.truncated
            passes++
        } while (truncated && passes < CRASHPAD_RECOVERY_MAX_PASSES)
        return !truncated
    }

    private fun reconcileCrashpadClientLifecycles(handlerQuiesced: Boolean): Boolean {
        val rawStore = rawArtifactStore ?: return true
        val nativeDirectory = nativeHandlerDirectory()
        return reconcileCrashpadClientLifecycles(
            rawStore,
            nativeDirectory.resolve(CLIENT_LIFECYCLE_DIRECTORY),
            nativeDirectory.resolve(HANDOFF_DIRECTORY),
            handlerQuiesced,
        )
    }

    private fun ensureNativeAndWatchdog() {
        if (configuration.nativeCaptureEnabled) {
            startPrimaryNativeObserver()
            refreshPrimaryNativeParticipant()
        }
        if (CaptureKind.ANR in activeRuntimePolicy.captures && watchdog == null) {
            watchdog = AnrWatchdog(
                requester = ::requestPrimaryNonFatal,
                onCandidate = ::recordAnrCandidate,
                policy = AnrPolicy {
                    try {
                        controlPage?.committed() ?: disabledPolicy(0)
                    } catch (_: PolicyPageException) {
                        disabledPolicy(0)
                    }
                },
            ).also {
                it.start()
                activityVisibility.attach(it::setEligible)
            }
        }
    }

    private fun stablePrimaryCaptureSnapshot(): PolicySnapshot? {
        if (!coordinatesGlobalStorage ||
            activeProfile == DiagnosticsProfile.DISABLED ||
            !ownedStorageRootsEligible() ||
            policyTransitionJournal.load() !is PolicyTransitionLoad.Empty
        ) {
            return null
        }
        val page = controlPage ?: return null
        val candidate = convergedLocalPolicy(page) ?: return null
        if (candidate.disabled ||
            profileForPolicy(candidate) != activeProfile ||
            !repairMarkerAllowsPrimaryEnable(candidate.epoch)
        ) {
            return null
        }
        if (policyTransitionJournal.load() !is PolicyTransitionLoad.Empty) return null
        val confirmed = convergedLocalPolicy(page) ?: return null
        return if (candidate == confirmed &&
            policyTransitionJournal.load() is PolicyTransitionLoad.Empty &&
            convergedLocalPolicy(page) == confirmed
        ) {
            confirmed
        } else {
            null
        }
    }

    private fun primaryNativeCaptureAlive(): Boolean =
        configuration.nativeCaptureEnabled &&
            nativeClientMode == NativeRuntime.CLIENT_MODE_CRASHPAD &&
            nativePolicyParticipantAlive() &&
            try {
                NativeRuntime.isHandlerAlive()
            } catch (_: LinkageError) {
                false
            } catch (_: RuntimeException) {
                false
            }

    private fun refreshPrimaryNativeParticipant() {
        if (!configuration.nativeCaptureEnabled) return
        val snapshot = stablePrimaryCaptureSnapshot()
        if (snapshot == null) {
            if (activeProfile != DiagnosticsProfile.DISABLED) {
                failClosedAfterPolicyApplication(
                    runCatching { controlPage?.committed()?.epoch }.getOrNull() ?: 0L,
                )
            }
            return
        }
        if (
            drainRustPanicRingIfHealthy(
                primaryNativeCaptureAlive(),
                ::drainRustPanicRing,
            )
        ) return

        primaryNativeReadinessRecovery.begin(mutableReadiness.value, mutableHealth.value)
        nativeReady = false
        val quota = uidQuota ?: run {
            primaryNativeReadinessRecovery.clear()
            markDegraded()
            return
        }
        val restartPrepared = try {
            quota.withStorageMutation {
                if (stablePrimaryCaptureSnapshot() != snapshot ||
                    !quiesceNativeClient() ||
                    !stopNativeHandler()
                ) {
                    false
                } else {
                    // The handler owns a separate reserved role and has no managed segment writer.
                    // Drain its slots only after positive service quiescence, before a replacement
                    // process can initialize and reset them.
                    ingestEmergencySlot(TraceboxHandlerService.PROCESS_ROLE_HANDLER)
                    ingestRustPanicSlot(TraceboxHandlerService.PROCESS_ROLE_HANDLER)
                    crashpadRecoveryReady = ingestCrashpadStartup(handlerQuiesced = true)
                    crashpadRecoveryReady && stablePrimaryCaptureSnapshot() == snapshot
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!restartPrepared) {
            markDegraded()
            return
        }

        // The handler service acquires the cross-process mutation barrier through socket
        // readiness. Launch only after releasing our copy of that barrier.
        nativeReady = startNativeHandler(snapshot)
        val recovered = nativeReady &&
            primaryNativeCaptureAlive() &&
            stablePrimaryCaptureSnapshot() == snapshot &&
            crashpadRecoveryReady &&
            directBootRecoveryReady
        if (!recovered) {
            quiesceNativeClient()
            markDegraded()
        } else if (primaryNativeReadinessRecovery.complete(recovered = true)) {
            mutableHealth.value = TraceboxHealth.READY
            mutableReadiness.value = Readiness.DURABLE
        }
    }

    private fun startSecondaryNativeParticipant(snapshot: PolicySnapshot): Boolean {
        if (coordinatesGlobalStorage || snapshot.disabled ||
            stableSecondaryPolicySnapshot() != snapshot
        ) {
            return false
        }
        val quota = uidQuota ?: return false
        return try {
            when (
                val guarded = quota.mutateStorageIfEligible(
                    captureStorageEligibility(snapshot.epoch),
                ) {
                    startSecondaryNativeParticipantUnderMutationBarrier(snapshot)
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value
                StorageMutationBarrierResult.Rejected -> false
            }
        } catch (_: RuntimeException) {
            rejectSecondaryNativeParticipant()
        }
    }

    private fun startSecondaryNativeParticipantUnderMutationBarrier(
        snapshot: PolicySnapshot,
    ): Boolean {
        return try {
            val processIdentity = currentProcessIdentity?.copyOf()
                ?: allocateJournaledIdentity(PROCESS_INSTANCE_IDENTITY_KIND).also {
                    currentProcessIdentity = it.copyOf()
                }
            val nativeDirectory = nativeHandlerDirectory()
            Files.createDirectories(nativeDirectory)
            reserveNativeSlots(configuration.processRole)
            if (!NativeRuntime.initializeEmergency(
                    nativeDirectory.toString(),
                    configuration.processRole,
                    processIdentity,
                    snapshot.epoch,
                ) ||
                !NativeRuntime.installRustPanicHook()
            ) {
                return rejectSecondaryNativeParticipant()
            }
            val mode = NativeRuntime.connectClientMode(
                nativeDirectory.resolve(TraceboxHandlerService.SOCKET_NAME).toString(),
                configuration.processRole,
                processIdentity,
                null,
                snapshot.epoch,
                NativeRuntime.CLIENT_REQUEST_EMERGENCY_RUST_ONLY,
            )
            if (mode != NativeRuntime.CLIENT_MODE_EMERGENCY_RUST ||
                !NativeRuntime.isPolicyParticipantAlive() ||
                stableSecondaryPolicySnapshot() != snapshot
            ) {
                return rejectSecondaryNativeParticipant()
            }
            // Registration happens while the local client remains fail-closed. Once it is in the
            // handler census, install the permissive tuple and prove the credential stayed stable
            // on both sides of the handoff.
            if (!NativeRuntime.updatePolicy(snapshot.epoch, snapshot.disabled, snapshot.denyMask) ||
                !NativeRuntime.isPolicyParticipantAlive() ||
                stableSecondaryPolicySnapshot() != snapshot
            ) {
                return rejectSecondaryNativeParticipant()
            }
            nativeClientMode = mode
            nativeReady = true
            true
        } catch (_: IOException) {
            rejectSecondaryNativeParticipant()
        } catch (_: SecurityException) {
            rejectSecondaryNativeParticipant()
        } catch (_: RuntimeException) {
            rejectSecondaryNativeParticipant()
        } catch (_: LinkageError) {
            rejectSecondaryNativeParticipant()
        }
    }

    private fun rejectSecondaryNativeParticipant(): Boolean {
        try {
            NativeRuntime.shutdownCapture()
        } catch (_: LinkageError) {
            // The caller still fails closed below.
        } catch (_: RuntimeException) {
            // The caller still fails closed below.
        }
        nativeReady = false
        nativeClientMode = NativeRuntime.CLIENT_MODE_REJECTED
        return false
    }

    private fun startNativeHandler(snapshot: PolicySnapshot): Boolean {
        if (snapshot.disabled || !localPolicyMatches(controlPage ?: return false, snapshot)) {
            return false
        }
        val processIdentity = currentProcessIdentity?.copyOf() ?: return false
        val rawArtifactId = try {
            allocateJournaledIdentity(RAW_ARTIFACT_IDENTITY_KIND)
        } catch (_: IllegalStateException) {
            return false
        }
        val rawStore = rawArtifactStore ?: return false
        val handoffReservation = nativeHandlerDirectory()
            .resolve(HANDOFF_DIRECTORY)
            .resolve("${encodeHex(rawArtifactId)}.dmp")
        val lifecycleReservation = nativeClientLifecyclePath(
            nativeHandlerDirectory().resolve(CLIENT_LIFECYCLE_DIRECTORY),
            configuration.processRole,
            rawArtifactId,
        )
        if (!ensureQuotaReservation(
                handoffReservation,
                UidBucket.RAW_ARTIFACTS,
                RAW_ARTIFACT_LIMIT,
            )
        ) {
            return false
        }
        if (!rawStore.preCapture(
                rawArtifactId,
                processIdentity,
                configuration.processRole,
                snapshot.epoch,
            )
        ) {
            uidQuota?.release(handoffReservation)
            return false
        }
        var nativeTouched = false
        var handlerMayOwnLease = false
        val connected = try {
            val nativeDirectory = nativeHandlerDirectory()
            Files.createDirectories(nativeDirectory)
            reserveNativeSlots(configuration.processRole)
            nativeTouched = true
            if (!NativeRuntime.initializeEmergency(
                    nativeDirectory.toString(),
                    configuration.processRole,
                    processIdentity,
                    snapshot.epoch,
                ) ||
                !NativeRuntime.installRustPanicHook()
            ) {
                false
            } else {
                drainRustPanicRing()
                val handlerIdentity = handlerProcessIdentity?.copyOf()
                    ?: allocateJournaledIdentity(PROCESS_INSTANCE_IDENTITY_KIND).also {
                        handlerProcessIdentity = it.copyOf()
                    }
                val startToken = prepareHandlerStartPermit(
                    snapshot,
                    handlerIdentity,
                    rawArtifactId,
                )
                if (startToken == null) {
                    false
                } else {
                    val handlerIntent = TraceboxHandlerService.startIntent(
                        applicationContext,
                        handlerIdentity,
                        snapshot.epoch,
                        snapshot.disabled,
                        snapshot.denyMask,
                        startToken,
                    )
                    try {
                        if (!startAndHoldNativeHandler(handlerIntent)) {
                            false
                        } else {
                            handlerMayOwnLease = true
                            val mode = NativeRuntime.connectClientMode(
                                nativeDirectory.resolve(TraceboxHandlerService.SOCKET_NAME).toString(),
                                configuration.processRole,
                                processIdentity,
                                rawArtifactId,
                                snapshot.epoch,
                                NativeRuntime.CLIENT_REQUEST_CRASHPAD_REQUIRED,
                            )
                            // Keep the local client fail-closed until registration is visible in
                            // the policy census. Then install the permitted tuple and revalidate
                            // both durable policy and transport liveness before exposing readiness.
                            mode == NativeRuntime.CLIENT_MODE_CRASHPAD &&
                                NativeRuntime.isPolicyParticipantAlive() &&
                                localPolicyMatches(checkNotNull(controlPage), snapshot) &&
                                NativeRuntime.updatePolicy(
                                    snapshot.epoch,
                                    snapshot.disabled,
                                    snapshot.denyMask,
                                ) &&
                                NativeRuntime.isPolicyParticipantAlive() &&
                                NativeRuntime.isHandlerAlive() &&
                                localPolicyMatches(checkNotNull(controlPage), snapshot)
                        }
                    } finally {
                        releaseMissingHandlerStartPermitReservations()
                        releaseMissingQuotaReservation(lifecycleReservation)
                    }
                }
            }
        } catch (_: LinkageError) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (connected) {
            nativeClientMode = NativeRuntime.CLIENT_MODE_CRASHPAD
            nativeReady = true
            armedRawArtifactIdentity = rawArtifactId.copyOf()
            return true
        }
        releaseMissingQuotaReservation(lifecycleReservation)
        return cleanupFailedNativeStart(
            rawStore,
            rawArtifactId,
            handoffReservation,
            nativeTouched,
            handlerMayOwnLease,
        )
    }

    private fun cleanupFailedNativeStart(
        rawStore: RawArtifactStore,
        rawArtifactId: ByteArray,
        handoffReservation: Path,
        nativeTouched: Boolean,
        handlerMayOwnLease: Boolean,
    ): Boolean {
        val fenced = if (!nativeTouched) {
            true
        } else {
            try {
                NativeRuntime.shutdownCapture()
                true
            } catch (_: LinkageError) {
                false
            } catch (_: RuntimeException) {
                false
            }
        }
        nativeReady = false
        nativeClientMode = NativeRuntime.CLIENT_MODE_REJECTED
        val handlerFenced = if (handlerMayOwnLease || coordinatesGlobalStorage) {
            stopNativeHandler()
        } else {
            true
        }
        if (!fenced || !handlerFenced) {
            // Once a start intent or registration may have reached the handler, retain durable
            // ownership. The serialized handler drain/reconciler will decide whether a handoff
            // exists; eagerly deleting here could orphan a late completed dump.
            armedRawArtifactIdentity = rawArtifactId.copyOf()
            return false
        }
        armedRawArtifactIdentity = null
        rawStore.deleteOwned(rawArtifactId)
        uidQuota?.release(handoffReservation)
        return false
    }

    private fun nativeHandlerDirectory(): Path = root.resolve(NATIVE_HANDLER_DIRECTORY)

    private fun requestPrimaryNonFatal(timeoutMillis: Int): Boolean {
        if (!configuration.nativeCaptureEnabled) return false
        val quota = uidQuota ?: return false
        val requested = try {
            quota.withStorageMutation {
                val snapshot = stablePrimaryCaptureSnapshot()
                if (snapshot == null || !primaryNativeCaptureAlive()) {
                    false
                } else {
                    NativeRuntime.requestNonFatal(ANR_NON_FATAL_REASON, timeoutMillis)
                }
            }
        } catch (_: LinkageError) {
            false
        } catch (_: RuntimeException) {
            false
        }
        // A successful nonfatal consumes the single Crashpad raw lease; failures can also leave
        // transport liveness uncertain. Reconcile/import and rearm on the serialized writer.
        enqueue {
            drainRustPanicRing()
            refreshPrimaryNativeParticipant()
        }
        return requested
    }

    private fun drainRustPanicRing() {
        if (!configuration.nativeCaptureEnabled) return
        val adapter = generatedAdapter ?: return
        repeat(RUST_PANIC_RING_CAPACITY) {
            val value = NativeRuntime.drainRustPanic() ?: return
            if (value.size != 4) return
            val payloadClass = value[0].coerceIn(0, 2)
            val hasLocation = value[1] == 1
            val line = value[2].coerceAtLeast(0)
            val column = value[3].coerceAtLeast(0)
            val locationCode = if (hasLocation) {
                line.rotateLeft(13) xor column.rotateLeft(27)
            } else {
                0
            }
            adapter.record(
                GeneratedRustPanic(
                    payloadClass.toUInt(),
                    configuration.processRole.toUInt(),
                    locationCode.toUInt(),
                    if (hasLocation) 1u else 0u,
                ),
                null,
            )
        }
    }

    private fun recordGeneratedException(value: GeneratedExceptionRecord) {
        val capture = if (value.kind == 1u) CaptureKind.JVM_CRASH else CaptureKind.HANDLED_EXCEPTION
        if (capture !in activeRuntimePolicy.captures) return
        recordGenerated(value)
    }

    private fun recordGenerated(value: GeneratedRecord, context: DiagnosticContext? = null) {
        if (!accepts(value.eventId)) return
        enqueue {
            if (!accepts(value.eventId)) return@enqueue
            val adapter = generatedAdapter ?: return@enqueue
            adapter.record(value, context)
            if (adapter.latestResult() is GeneratedRecordAppendResult.Appended) {
                mutableSummary.value = DiagnosticSummary(
                    recordedValueCount = mutableSummary.value.recordedValueCount + 1L,
                    lastRecordedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun recordAnrCandidate(candidate: AnrCandidate) {
        GeneratedDiagnostics.anrCandidate(
            diagnostics,
            elapsed_millis = candidate.delayedMillis.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt(),
            sample_count = candidate.sampleCount.coerceAtMost(UShort.MAX_VALUE.toInt()).toUShort(),
            frame_count = candidate.mainFrames.size.coerceAtMost(UShort.MAX_VALUE.toInt()).toUShort(),
            nonfatal_result = if (candidate.nonFatalRequested) 1u else 0u,
            flags = if (candidate.debuggerAffected) 1u else 0u,
        )
        if (CaptureKind.ANR in activeRuntimePolicy.captures) {
            val stack = truncateUtf8(
                candidate.mainFrames.joinToString("\n") { frame ->
                    "${frame.className}.${frame.methodName}:${frame.lineNumber}"
                },
                2_048,
            )
            recordGenerated(
                GeneratedAnrTrace(
                    elapsed_millis = candidate.delayedMillis
                        .coerceIn(0, UInt.MAX_VALUE.toLong())
                        .toUInt(),
                    frame_count = candidate.mainFrames.size
                        .coerceAtMost(UShort.MAX_VALUE.toInt())
                        .toUShort(),
                    stack_fingerprint = fingerprint64(stack).toULong(),
                    stack_trace = stack,
                ),
            )
        }
    }

    private fun captureManagedCrash(captured: dev.tracebox.core.JvmCrashRecord) {
        val first = captured.causes.firstOrNull()
        val record = GeneratedManagedCrash(
            primary_exception_code = (first?.type?.hashCode() ?: 0).toUInt(),
            cause_count = captured.causes.size.coerceAtMost(UShort.MAX_VALUE.toInt()).toUShort(),
            frame_count = captured.causes.sumOf { it.frames.size }
                .coerceAtMost(UShort.MAX_VALUE.toInt())
                .toUShort(),
            flags = if (captured.causes.any { it.cycle }) 1u else 0u,
        )
        val adapter = generatedAdapter
        when (volatileManagedCrashes.offer(record, sinkReady = adapter != null)) {
            BoundedManagedCrashOffer.DELIVER -> checkNotNull(adapter).record(record, null)
            BoundedManagedCrashOffer.QUEUED,
            BoundedManagedCrashOffer.DROPPED,
            -> Unit
        }
        val firstCause = captured.causes.firstOrNull()
        val stack = truncateUtf8(
            firstCause?.frames.orEmpty().joinToString("\n") { frame ->
                "${frame.declaringClass}.${frame.method}:${frame.line}"
            },
            2_048,
        )
        val exceptionRecord = GeneratedExceptionRecord(
            kind = 1u,
            exception_type = truncateUtf8(firstCause?.type ?: "java.lang.Throwable", 256),
            frame_count = firstCause?.frames.orEmpty().size
                .coerceAtMost(UShort.MAX_VALUE.toInt())
                .toUShort(),
            stack_fingerprint = fingerprint64("${firstCause?.type}\n$stack").toULong(),
            stack_trace = stack,
            monotonic_time_ns = android.os.SystemClock.elapsedRealtimeNanos().toULong(),
        )
        when (volatileExceptionCrashes.offer(exceptionRecord, sinkReady = adapter != null)) {
            BoundedManagedCrashOffer.DELIVER -> checkNotNull(adapter).record(exceptionRecord, null)
            BoundedManagedCrashOffer.QUEUED,
            BoundedManagedCrashOffer.DROPPED,
            -> Unit
        }
    }

    private fun drainVolatileManagedCrashes() {
        val adapter = generatedAdapter
        val enabled = adapter != null && accepts(GeneratedEventId.MANAGEDCRASH)
        val pending = volatileManagedCrashes.resolve(enabled, sinkReady = adapter != null)
        val detailedEnabled = adapter != null && accepts(GeneratedEventId.EXCEPTIONRECORD)
        val detailed = volatileExceptionCrashes.resolve(
            detailedEnabled,
            sinkReady = adapter != null,
        )
        if (!enabled) return
        pending.forEach { record -> checkNotNull(adapter).record(record, null) }
        if (detailedEnabled) {
            detailed.forEach { record -> checkNotNull(adapter).record(record, null) }
        }
    }

    private fun installVisibilityCallbacks() {
        val application = applicationContext as? Application ?: return
        if (visibilityCallbacks != null) return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = activityVisibility.activityStarted()

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = activityVisibility.activityStopped()

            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        visibilityCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    private fun uninstallVisibilityCallbacks() {
        activityVisibility.detach()
        val application = applicationContext as? Application ?: return
        val callbacks = visibilityCallbacks ?: return
        visibilityCallbacks = null
        application.unregisterActivityLifecycleCallbacks(callbacks)
    }

    private fun reconcileExitHistory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val records = generatedAdapter ?: return
        val ledger = exitTombstones ?: return
        val journal = exitImports ?: return
        val rawStore = rawArtifactStore ?: return
        val policy = try {
            controlPage?.committed() ?: return
        } catch (_: PolicyPageException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            return
        }
        if (!localPolicyMatches(controlPage ?: return, policy) ||
            policyTransitionJournal.load() !is PolicyTransitionLoad.Empty ||
            policyRepairRequired()
        ) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            return
        }

        val adapter = ApplicationExitInfoAdapter()
        val history = adapter.anrHistory(applicationContext, EXIT_HISTORY_LIMIT)
            .associateBy { ExitSourceKey.derive(it) }

        journal.pending().forEach { pending ->
            val recovered = reconcileExitRawArtifact(
                adapter,
                rawStore,
                journal,
                policy,
                pending,
                history[pending.sourceKey],
            ) ?: return@forEach
            terminalizeExitImport(records, rawStore, journal, ledger, recovered)
        }

        history.forEach { (key, exit) ->
            if (ledger.imported(key) || journal.read(key) != null) return@forEach
            val token = ExitPolicyToken.decode(exit.processStateSummary)
            val provenance = if (token != null &&
                rawExitTokenAuthorizes(token, policy, OS_EXIT_CATEGORY)
            ) {
                try {
                    ExitRawArtifactProvenance(
                        exit.artifactKind,
                        allocateJournaledIdentity(RAW_ARTIFACT_IDENTITY_KIND),
                        token.epoch,
                        token.processInstanceId,
                        checkNotNull(token.processRole),
                    )
                } catch (_: IllegalArgumentException) {
                    mutableHealth.value = TraceboxHealth.DEGRADED
                    null
                } catch (_: IllegalStateException) {
                    mutableHealth.value = TraceboxHealth.DEGRADED
                    null
                }
            } else {
                null
            }
            val entry = ExitImportEntry(
                key,
                ExitImportStage.PREPARED,
                exit.reason,
                exit.status,
                exit.importance,
                if (token == null) ExitLinkConfidence.UNMATCHED else ExitLinkConfidence.EXACT,
                ExitRawReadState.NONE,
                provenance,
            )
            if (!prepareExitImport(journal, entry)) {
                mutableHealth.value = TraceboxHealth.DEGRADED
                return@forEach
            }
            val acquired = reconcileExitRawArtifact(
                adapter,
                rawStore,
                journal,
                policy,
                entry,
                exit,
            ) ?: return@forEach
            terminalizeExitImport(records, rawStore, journal, ledger, acquired)
        }
    }

    private fun reconcileExitRawArtifact(
        adapter: ApplicationExitInfoAdapter,
        rawStore: RawArtifactStore,
        journal: ExitImportJournal,
        policy: PolicySnapshot,
        entry: ExitImportEntry,
        source: dev.tracebox.anr.SyntheticApplicationExitInfo?,
    ): ExitImportEntry? {
        val provenance = entry.rawArtifact ?: return entry
        if (entry.stage == ExitImportStage.APPENDED) return entry
        val rawId = provenance.rawArtifactId
        val rawKind = rawArtifactKind(provenance.artifactKind)
        val policyStillAuthorizes =
            provenance.acquisitionEpoch == policy.epoch &&
                !policy.disabled &&
                policy.permits(OS_EXIT_CATEGORY)

        val state = when {
            !policyStillAuthorizes -> {
                rawStore.deleteOwned(rawId, rawKind)
                ExitRawReadState.NONE
            }

            rawStore.containsRaw(rawId, rawKind) -> ExitRawReadState.AVAILABLE

            else -> {
                val token = ExitPolicyToken.decode(source?.processStateSummary)
                val sourceStillMatches =
                    source != null &&
                        source.artifactKind == provenance.artifactKind &&
                        token != null &&
                        rawExitTokenAuthorizes(token, policy, OS_EXIT_CATEGORY) &&
                        token.epoch == provenance.acquisitionEpoch &&
                        token.processRole == provenance.originRole &&
                        token.processInstanceId.contentEquals(provenance.originProcessInstanceId)
                if (!sourceStillMatches ||
                    !rawStore.preCapture(
                        rawId,
                        provenance.originProcessInstanceId,
                        provenance.originRole,
                        provenance.acquisitionEpoch,
                        rawKind,
                    )
                ) {
                    rawStore.deleteOwned(rawId, rawKind)
                    if (source == null) ExitRawReadState.READ_FAILED else ExitRawReadState.NONE
                } else {
                    val artifact = adapter.readAnrArtifact(
                        applicationContext,
                        entry.sourceKey,
                        EXIT_HISTORY_LIMIT,
                        EXIT_RAW_IMPORT_LIMIT,
                    )
                    val committed = artifact?.rawBytes?.let { rawStore.commitRaw(rawId, it) } == true
                    if (committed) {
                        ExitRawReadState.AVAILABLE
                    } else {
                        rawStore.deleteOwned(rawId, rawKind)
                        when {
                            artifact == null -> ExitRawReadState.READ_FAILED
                            artifact.rawReadState == ExitRawReadState.AVAILABLE ->
                                ExitRawReadState.READ_FAILED
                            else -> artifact.rawReadState
                        }
                    }
                }
            }
        }
        if (state == entry.artifactState) return entry
        if (!updateExitImportArtifactState(journal, entry.sourceKey, state)) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            return null
        }
        return entry.copy(artifactState = state)
    }

    private fun terminalizeExitImport(
        records: GeneratedRecordSegmentAdapter,
        rawStore: RawArtifactStore,
        journal: ExitImportJournal,
        ledger: ExitTombstoneLedger,
        entry: ExitImportEntry,
    ) {
        val terminalized = ExitImportTerminalizer.terminalize(
            entry = entry,
            recordTombstone = { recordExitTombstone(ledger, it) },
            containsRecord = ::containsExitRecord,
            appendRecord = { appendExitRecord(records, it) },
            markAppended = { markExitImportAppended(journal, it) },
            complete = { completeExitImport(journal, it) },
            retireRaw = { provenance ->
                val rawId = provenance.rawArtifactId
                val kind = rawArtifactKind(provenance.artifactKind)
                rawStore.deleteOwned(rawId, kind) ||
                    rawStore.journal(rawId) == null && !rawStore.containsRaw(rawId, kind)
            },
        )
        if (terminalized == ExitImportTerminalization.RETRY_REQUIRED) {
            mutableHealth.value = TraceboxHealth.DEGRADED
        }
    }

    private fun appendExitRecord(
        records: GeneratedRecordSegmentAdapter,
        entry: ExitImportEntry,
    ): Boolean =
        records.recordWithInternalIdentity(
            GeneratedOsExit(
                entry.reason,
                entry.status,
                entry.importance,
                entry.linkConfidence.ordinal.toUInt(),
                entry.artifactState.ordinal.toUInt(),
            ),
            entry.sourceKey.bytes(),
        ) is GeneratedRecordAppendResult.Appended

    private fun rawArtifactKind(kind: ExitArtifactKind): RawArtifactKind =
        when (kind) {
            ExitArtifactKind.ANR_TRACE -> RawArtifactKind.OS_EXIT_ANR_TRACE
            ExitArtifactKind.NATIVE_TOMBSTONE -> RawArtifactKind.OS_EXIT_NATIVE_TOMBSTONE
        }

    private fun prepareExitImport(
        journal: ExitImportJournal,
        entry: ExitImportEntry,
    ): Boolean {
        val target = exitImportPath(entry.sourceKey)
        val temporary = target.resolveSibling("${target.fileName}.new")
        ensureMetadataReservation(target, EXIT_IMPORT_ENTRY_BYTES)
        ensureMetadataReservation(temporary, EXIT_IMPORT_ENTRY_BYTES)
        return try {
            journal.prepare(entry)
        } finally {
            releaseMissingQuotaReservation(temporary)
            releaseMissingQuotaReservation(target)
        }
    }

    private fun markExitImportAppended(
        journal: ExitImportJournal,
        sourceKey: ExitSourceKey,
    ): Boolean {
        val target = exitImportPath(sourceKey)
        val temporary = target.resolveSibling("${target.fileName}.new")
        ensureMetadataReservation(target, EXIT_IMPORT_ENTRY_BYTES)
        ensureMetadataReservation(temporary, EXIT_IMPORT_ENTRY_BYTES)
        return try {
            journal.markAppended(sourceKey)
        } finally {
            releaseMissingQuotaReservation(temporary)
            releaseMissingQuotaReservation(target)
        }
    }

    private fun updateExitImportArtifactState(
        journal: ExitImportJournal,
        sourceKey: ExitSourceKey,
        state: ExitRawReadState,
    ): Boolean {
        val target = exitImportPath(sourceKey)
        val temporary = target.resolveSibling("${target.fileName}.new")
        ensureMetadataReservation(target, EXIT_IMPORT_ENTRY_BYTES)
        ensureMetadataReservation(temporary, EXIT_IMPORT_ENTRY_BYTES)
        return try {
            journal.updateArtifactState(sourceKey, state)
        } finally {
            releaseMissingQuotaReservation(temporary)
            releaseMissingQuotaReservation(target)
        }
    }

    private fun completeExitImport(
        journal: ExitImportJournal,
        sourceKey: ExitSourceKey,
    ): Boolean {
        val target = exitImportPath(sourceKey)
        val completed = journal.complete(sourceKey)
        if (completed) {
            releaseMissingQuotaReservation(target)
            releaseMissingQuotaReservation(target.resolveSibling("${target.fileName}.new"))
        }
        return completed
    }

    private fun recordExitTombstone(
        ledger: ExitTombstoneLedger,
        sourceKey: ExitSourceKey,
    ): ExitImportResult {
        val target = root.resolve(EXIT_TOMBSTONE_FILE)
        val temporary = target.resolveSibling("${target.fileName}.new")
        ensureMetadataReservation(target, EXIT_TOMBSTONE_BYTES.toLong())
        ensureMetadataReservation(temporary, EXIT_TOMBSTONE_BYTES.toLong())
        return try {
            ledger.record(sourceKey)
        } finally {
            releaseMissingQuotaReservation(temporary)
            releaseMissingQuotaReservation(target)
        }
    }

    private fun exitImportPath(sourceKey: ExitSourceKey): Path =
        root.resolve(EXIT_IMPORT_DIRECTORY).resolve("${sourceKey.encoded}.tbexitjournal")

    private fun releaseMissingQuotaReservation(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) uidQuota?.release(path)
    }

    private fun containsExitRecord(sourceKey: ExitSourceKey): Boolean {
        return containsImportedRecord(GeneratedEventId.OSEXIT, sourceKey.bytes())
    }

    private fun containsSummaryRecord(summaryId: ByteArray): Boolean {
        return containsImportedRecord(GeneratedEventId.STRUCTURALSUMMARY, summaryId)
    }

    private fun containsImportedRecord(
        eventId: GeneratedEventId,
        sourceId: ByteArray,
    ): Boolean {
        if (sourceId.size != PersistedSegmentIdentity.ID_SIZE || !Files.isDirectory(root)) {
            return false
        }
        val expectedPayloadBytes = PersistedSegmentIdentity.ID_SIZE + when (eventId) {
            GeneratedEventId.STRUCTURALSUMMARY -> 18
            GeneratedEventId.EMERGENCYRECORD -> 40
            GeneratedEventId.OSEXIT -> 20
            else -> return false
        }
        return Files.walk(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tbseg") }
                .limit(MAX_SEGMENTS_TO_SCAN.toLong())
                .anyMatch { path ->
                    try {
                        SegmentWriter.recover(path, repair = false).frames.any { frame ->
                            frame.recordType == eventId.stableId &&
                                frame.payload.size == expectedPayloadBytes &&
                                frame.payload.copyOfRange(0, sourceId.size).contentEquals(sourceId)
                        }
                    } catch (_: IOException) {
                        false
                    } catch (_: IllegalStateException) {
                        false
                    }
                }
        }
    }

    private fun deleteAllTraceboxData(): DeleteReport {
        val deletion = storageDeletion ?: return DeleteReport.REJECTED
        mutableHealth.value = TraceboxHealth.DELETING
        return when (runStorageDeletionToBoundary(deletion)) {
            is StorageTreeDeletionReport.Complete -> {
                remainDisabledAfterDeletion()
                if (mutableHealth.value == TraceboxHealth.DISABLED) {
                    DeleteReport.COMPLETE
                } else {
                    DeleteReport.PENDING_FAILURE
                }
            }

            is StorageTreeDeletionReport.Pending -> {
                markDegraded()
                DeleteReport.PENDING_FAILURE
            }
        }
    }

    internal fun prepareStandardPackage(): PackagePreparationResult {
        if (!coordinatesGlobalStorage ||
            isMainThread() ||
            activeProfile == DiagnosticsProfile.DISABLED ||
            closed.get()
        ) {
            return PackagePreparationResult.NotReady
        }
        return call {
            val policy = controlPage?.committed() ?: return@call PackagePreparationResult.NotReady
            val quota = uidQuota ?: return@call PackagePreparationResult.NotReady
            val eligibility = captureStorageEligibility(policy.epoch)
            try {
                when (
                    val guarded = quota.mutateStorageIfEligible(
                        eligibility,
                    ) {
                        prepareStandardPackageUnderMutationBarrier(
                            policy,
                            quota,
                            eligibility,
                        )
                    }
                ) {
                    is StorageMutationBarrierResult.Applied -> guarded.value
                    StorageMutationBarrierResult.Rejected -> PackagePreparationResult.NotReady
                }
            } catch (_: IOException) {
                PackagePreparationResult.Rejected
            } catch (_: RuntimeException) {
                PackagePreparationResult.Rejected
            }
        } ?: PackagePreparationResult.Rejected
    }

    private fun prepareStandardPackageUnderMutationBarrier(
        policy: PolicySnapshot,
        quota: UidWideQuotaCoordinator,
        eligibility: StorageMutationEligibility,
    ): PackagePreparationResult {
        writer?.let {
            try {
                it.seal()
            } catch (_: IllegalStateException) {
                Unit
            }
        }
        writer = null
        generatedAdapter = null
        val segmentPaths = Files.walk(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tbseg") }
                .filter {
                    SegmentWriter.recover(it, repair = false).header.policyGeneration == policy.epoch
                }
                .toList()
        }
        val preparation = if (segmentPaths.isEmpty()) {
            PackagePreparationResult.NotReady
        } else {
            val stagingAccounting = UidAccounting(
                UidQuota(mapOf(UidBucket.SNAPSHOTS to SNAPSHOT_LIMIT)),
                mapOf(UidBucket.SNAPSHOTS to 1),
            )
            val pipeline = StandardPackagePipeline(
                SnapshotPreparer(stagingAccounting, root.resolve("snapshot-reservation")),
            )
            val request = RecoveredSnapshotRequestAdapter().build(
                policy.epoch,
                Long.MAX_VALUE,
                segmentPaths,
            )
            when (val result = pipeline.finalize(request)) {
                is PackagePipelineResult.Failed -> PackagePreparationResult.Rejected
                is PackagePipelineResult.Ready -> {
                    val disclosed = DisclosureRenderer.render(result.packageBytes)
                    if (disclosed !is DisclosureDecodeResult.Decoded) {
                        result.packageBytes.releaseStagingQuota()
                        PackagePreparationResult.Rejected
                    } else {
                        val preview = preview(disclosed)
                        result.packageBytes.releaseStagingQuota()
                        val published = publishPreparedPackage(
                            preview,
                            result.packageBytes.exactBytes(),
                        )
                        if (published) {
                            PackagePreparationResult.Ready(preview)
                        } else {
                            PackagePreparationResult.Rejected
                        }
                    }
                }
            }
        }
        try {
            rotateWriterUnderMutationBarrier(policy, quota, eligibility)
        } catch (_: IOException) {
            markDegraded()
        } catch (_: RuntimeException) {
            markDegraded()
        } catch (_: LinkageError) {
            markDegraded()
        }
        return preparation
    }

    private fun preview(decoded: DisclosureDecodeResult.Decoded): PackagePreview {
        val facts = decoded.facts
        val transformations = facts.transformations.mapNotNull {
            when (it) {
                "none" -> PackageTransformation.NONE
                "parameter_redaction" -> PackageTransformation.PARAMETER_REDACTION
                else -> null
            }
        }.toSet().ifEmpty { setOf(PackageTransformation.NONE) }
        val omissions = facts.omissions.mapNotNull {
            if (it == "corrupt_ordinary_record") PackageOmissionReason.CORRUPT_ORDINARY_RECORD else null
        }.toSet()
        val classes = facts.privacyClasses.map {
            when (it) {
                dev.tracebox.export.PackagePrivacyClass.C0 -> PackagePrivacyClass.C0
                dev.tracebox.export.PackagePrivacyClass.C1 -> PackagePrivacyClass.C1
                dev.tracebox.export.PackagePrivacyClass.C2 -> PackagePrivacyClass.C2
            }
        }.toSet()
        return PackagePreview(
            PackageDisclosure(
                facts.includedCount,
                facts.includedBytes,
                classes,
                transformations,
                omissions,
                facts.sourceRangeMillis,
                facts.entries.map { it.processLocalId }.toSet().size,
                facts.plaintextDigest.copyOf(),
                facts.rawC2Artifacts.size,
                setOf(
                    PackageWarning.RAW_CRASH_ARTIFACTS_EXCLUDED,
                    PackageWarning.OS_EXIT_HISTORY_REMAINS_ANDROID_OWNED,
                    PackageWarning.SHARE_OR_SAF_RECIPIENT_MAY_RETAIN_BYTES,
                    PackageWarning.DELIVERY_CANNOT_BE_PROVEN,
                ),
            ),
        )
    }

    internal fun approvalIntent(context: Context, preview: PackagePreview): Intent? =
        RuntimePackageRegistry.intent(context, preview)

    private fun publishPreparedPackage(
        preview: PackagePreview,
        bytes: ByteArray,
    ): Boolean = publishAndConsumePackageBytes(bytes) { transferredBytes ->
        packageCapabilityFence.publishIf(
            isAllowed = {
                coordinatesGlobalStorage &&
                    !closed.get() &&
                    activeProfile != DiagnosticsProfile.DISABLED
            },
            publish = { RuntimePackageRegistry.put(preview, transferredBytes) },
        )
    }

    internal fun consumeApproval(
        approval: ApprovalToken,
    ): RuntimePackageCapability<ApprovedPackageBytes>? {
        val nonce = approval.opaqueBytes()
        return try {
            packageCapabilityFence.bind(
                acquire = { RuntimePackageRegistry.take(nonce) },
                retire = ApprovedPackageBytes::close,
            )
        } finally {
            nonce.fill(0)
        }
    }

    internal fun stagePackage(
        bytes: ByteArray,
        capabilityGeneration: Long,
        capabilityToken: Long,
    ): Path? =
        withPackageCapability(capabilityGeneration, capabilityToken) {
            stagePackageWithCurrentCapability(bytes)
        }

    private fun stagePackageWithCurrentCapability(bytes: ByteArray): Path? {
        if (!canStagePackageWithCurrentCapability()) return null
        val quota = uidQuota ?: return null
        return try {
            when (
                val guarded = quota.mutateStorageIfEligible(captureStorageEligibility()) {
                    if (!canStagePackageWithCurrentCapability()) {
                        return@mutateStorageIfEligible null
                    }
                    val directory = stagingRoot()
                    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        return@mutateStorageIfEligible null
                    }
                    Files.createDirectories(directory)
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                        return@mutateStorageIfEligible null
                    }
                    val path = directory.resolve("tbdiag-${UUID.randomUUID()}.tbdiag")
                    if (!quota.reserve(path, UidBucket.SNAPSHOTS, bytes.size.toLong())) {
                        return@mutateStorageIfEligible null
                    }
                    try {
                        forceWrite(path, bytes)
                        val expiryDeadline =
                            packageStagingExpiryDeadlineMillis(System.currentTimeMillis())
                        if (!path.toFile().setLastModified(expiryDeadline)) {
                            throw IOException("could not persist package staging expiry")
                        }
                        path
                    } catch (failure: IOException) {
                        quota.release(path)
                        throw failure
                    }
                }
            ) {
                is StorageMutationBarrierResult.Applied -> guarded.value
                StorageMutationBarrierResult.Rejected -> null
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun canStagePackageWithCurrentCapability(): Boolean =
        coordinatesGlobalStorage &&
            !closed.get() &&
            activeProfile != DiagnosticsProfile.DISABLED &&
            TraceboxOwnedStorageRoot.isEligible(root)

    internal fun <T> withPackageCapability(
        capabilityGeneration: Long,
        capabilityToken: Long,
        action: () -> T,
    ): T? = packageCapabilityFence.use(capabilityGeneration, capabilityToken, action)

    internal fun retirePackageCapability(capabilityToken: Long) {
        packageCapabilityFence.retire(capabilityToken)
    }

    private fun invalidateRuntimePackageCapabilities() {
        packageCapabilityFence.invalidate(RuntimePackageRegistry::clear)
        if (Thread.currentThread() === executorThread) {
            packageSurface.retireActivePackage()
        } else if (!closed.get()) {
            enqueue(packageSurface::retireActivePackage)
        }
    }

    internal fun deleteStagingPath(path: Path): Boolean {
        val quota = uidQuota ?: return false
        return try {
            quota.withStorageMutation {
                val deleted = Files.deleteIfExists(path)
                if (deleted || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    quota.release(path)
                    true
                } else {
                    false
                }
            }
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    internal fun scheduleStagingDeletion(paths: List<Path>) {
        if (paths.isEmpty()) return
        val cleanup = Runnable { paths.forEach(::deleteStagingPath) }
        if (Thread.currentThread() === executorThread) {
            cleanup.run()
            return
        }
        try {
            // Package retirement must drain even after close() marks the runtime closed. The
            // terminal barrier is submitted after this task and therefore preserves ordering.
            executor.execute(cleanup)
        } catch (_: RejectedExecutionException) {
            // Runtime close drains the active package before shutting down this executor. A
            // rejected task can only be a redundant retirement after that terminal boundary.
        }
    }

    internal fun stagingRoot(): Path = root.resolve("export-staging")

    private fun accepts(eventId: GeneratedEventId): Boolean {
        val policy = activeRuntimePolicy
        if (!policy.enabled) return false
        return when (eventId) {
            GeneratedEventId.STRUCTURALSUMMARY,
            GeneratedEventId.EMERGENCYRECORD,
            -> CaptureKind.NATIVE_CRASH in policy.captures

            GeneratedEventId.BREADCRUMB -> policy.minimumLogLevel != LogLevel.OFF
            GeneratedEventId.HANDLEDERROR -> CaptureKind.HANDLED_EXCEPTION in policy.captures
            GeneratedEventId.MANAGEDCRASH -> CaptureKind.JVM_CRASH in policy.captures
            GeneratedEventId.RUSTPANIC -> CaptureKind.RUST_PANIC in policy.captures
            GeneratedEventId.ANRCANDIDATE,
            GeneratedEventId.ANRTRACE,
            -> CaptureKind.ANR in policy.captures

            GeneratedEventId.OSEXIT -> CaptureKind.OS_EXIT in policy.captures
            GeneratedEventId.LOGRECORD -> policy.minimumLogLevel != LogLevel.OFF
            GeneratedEventId.EXCEPTIONRECORD ->
                CaptureKind.JVM_CRASH in policy.captures ||
                    CaptureKind.HANDLED_EXCEPTION in policy.captures
        }
    }

    private fun policyFor(policy: TraceboxPolicy, epoch: Long): PolicySnapshot =
        runtimePolicySnapshot(policy, epoch)

    private fun profileForPolicy(snapshot: PolicySnapshot): DiagnosticsProfile? =
        profileFor(runtimePolicyForSnapshot(snapshot))

    private fun disabledPolicy(epoch: Long): PolicySnapshot = PolicySnapshot(epoch, Long.MAX_VALUE, disabled = true)

    private fun ensureMetadataFile(path: Path, value: ByteArray) {
        val quota = checkNotNull(uidQuota)
        val owned = quota.owns(path, UidBucket.METADATA, value.size.toLong())
        check(owned || quota.reserve(path, UidBucket.METADATA, value.size.toLong())) {
            "Tracebox metadata quota exhausted"
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            check(
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    readExactMetadata(path, value.size)?.contentEquals(value) == true,
            ) {
                "persisted Tracebox metadata does not match its expected value"
            }
        } else {
            try {
                forceWrite(path, value)
            } catch (error: IOException) {
                quota.release(path)
                throw error
            }
        }
    }

    private fun allocateJournaledIdentity(kind: Int): ByteArray {
        val quota = checkNotNull(uidQuota)
        val journal = root.resolve(
            if (configuration.nativeCaptureEnabled) {
                IDENTITY_JOURNAL_FILE
            } else {
                MANAGED_IDENTITY_JOURNAL_FILE
            },
        )
        val alreadyOwned = quota.owns(journal, UidBucket.METADATA, IDENTITY_JOURNAL_MAX_BYTES)
        check(alreadyOwned || quota.reserve(journal, UidBucket.METADATA, IDENTITY_JOURNAL_MAX_BYTES)) {
            "Tracebox identity journal quota exhausted"
        }
        return try {
            if (configuration.nativeCaptureEnabled) {
                checkNotNull(NativeRuntime.allocateIdentity(journal.toString(), kind)) {
                    "Rust identity allocation or durable journaling failed"
                }
            } else {
                ManagedIdentityStore(journal).allocate(kind)
            }
        } catch (error: LinkageError) {
            if (!alreadyOwned && !Files.exists(journal)) quota.release(journal)
            throw IllegalStateException("Rust identity allocator is unavailable", error)
        } catch (error: RuntimeException) {
            if (!alreadyOwned && !Files.exists(journal)) quota.release(journal)
            throw error
        }
    }

    private fun enqueue(action: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute {
                if (!closed.get()) action()
            }
        } catch (_: RejectedExecutionException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
        }
    }

    private fun <T> call(action: () -> T): T? {
        if (Thread.currentThread() === executorThread) {
            return if (closed.get()) null else action()
        }
        val future = try {
            executor.submit<T> {
                check(!closed.get()) { "Tracebox is closed" }
                action()
            }
        } catch (_: RejectedExecutionException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            return null
        }
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } catch (_: ExecutionException) {
            if (!closed.get()) mutableHealth.value = TraceboxHealth.DEGRADED
            return null
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun forceWrite(path: Path, value: ByteArray) {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(value)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        forceDirectoryBestEffort(path.parent)
    }

    private fun forceDirectoryBestEffort(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // The file itself was forced; Android/filesystem providers may reject directory FDs.
        } catch (_: UnsupportedOperationException) {
            // Directory channels are unavailable on some host test providers.
        } catch (_: SecurityException) {
            // Retain the forced file and fail closed through the persisted marker on next read.
        }
    }

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun encodeHex(value: ByteArray): String =
        value.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        internal const val ROOT_DIRECTORY = "tracebox"
        const val DIRECT_BOOT_DIRECTORY = "tracebox-directboot"
        const val CE_STORAGE_ROOT_ID = "ce"
        const val DE_STORAGE_ROOT_ID = "de"
        const val POLICY_CONTROL_FILE = "policy-control-v1"
        const val POLICY_CONTROL_BYTES = 32L
        const val POLICY_TRANSITION_FILE = "policy-native-transition-v1"
        const val COORDINATOR_LOCK_FILE = ".tracebox-primary.lock"
        const val POLICY_REPAIR_MARKER_FILE = "policy-repair-required-v1"
        val POLICY_REPAIR_MARKER_BYTES = byteArrayOf(0x54, 0x42, 0x50, 0x52)
        const val REQUESTED_PROFILE_FILE = "requested-profile-v1"
        const val REQUESTED_PROFILE_BYTES = 2L
        const val REQUESTED_POLICY_FILE = "requested-policy-v2"
        const val REQUESTED_POLICY_BYTES = 36L
        const val ACTIVE_DENY_FILE = "active-deny-v1"
        const val PENDING_DENY_FILE = "pending-deny-v1"
        const val DIRECT_BOOT_DENY_BYTES = 32L
        const val RAW_ARTIFACT_DIRECTORY = "raw-artifacts"
        const val EXIT_TOMBSTONE_FILE = "exit-tombstones-v1"
        const val EXIT_IMPORT_DIRECTORY = "exit-import-journal"
        const val WORK_QUEUE_CAPACITY = 64
        const val VOLATILE_CRASH_CAPACITY = 8
        const val BREADCRUMB_MASK = 4L
        const val HANDLED_ERROR_MASK = 8L
        const val MANAGED_CRASH_MASK = 16L
        const val RUST_PANIC_MASK = 32L
        const val ANR_MASK = 64L
        const val ANR_NON_FATAL_REASON = 0x54424152
        const val OS_EXIT_CATEGORY = 128L
        const val LOG_RECORD_MASK = 256L
        const val EXCEPTION_RECORD_MASK = 512L
        const val ANR_TRACE_MASK = 1_024L
        const val EXIT_HISTORY_LIMIT = 32
        const val EXIT_RAW_IMPORT_LIMIT = 2 * 1024 * 1024
        const val EXIT_TOMBSTONE_LIMIT = 1024
        const val EXIT_TOMBSTONE_BYTES = 64 * 1024
        const val EXIT_IMPORT_JOURNAL_LIMIT = 64
        const val EXIT_IMPORT_JOURNAL_BYTES =
            ExitImportJournal.ENTRY_BYTES * EXIT_IMPORT_JOURNAL_LIMIT
        const val EXIT_IMPORT_ENTRY_BYTES = ExitImportJournal.ENTRY_BYTES * 1L
        const val MAX_SEGMENTS_TO_SCAN = 64
        const val NATIVE_POLICY_SUCCESS = 0
        const val NATIVE_POLICY_PROTOCOL = 2
        const val NATIVE_POLICY_RETRY_ATTEMPTS = 3
        const val POLICY_COORDINATION_TIMEOUT_MILLIS = 2_000
        const val IDENTITY_JOURNAL_FILE = "identity-lifecycle-v1"
        const val MANAGED_IDENTITY_JOURNAL_FILE = "identity-lifecycle-managed-v1"
        const val NATIVE_HANDLER_DIRECTORY = "native-handler"
        const val HANDOFF_DIRECTORY = "tracebox-handler-handoff"
        const val CRASHPAD_DATABASE_DIRECTORY = "crashpad-db"
        const val CRASHPAD_PENDING_DIRECTORY = "pending"
        const val CLIENT_LIFECYCLE_DIRECTORY = "tracebox-handler-clients"
        const val CLIENT_LIFECYCLE_JOURNAL_BYTES = 384L
        const val SUMMARY_SPOOL_DIRECTORY = "summary-spool"
        const val ROLE_QUOTA_LOCK_FILE = ".tracebox-role-quota.lock"
        const val IDENTITY_JOURNAL_MAX_BYTES = 64L * 1024
        const val NATIVE_EMERGENCY_SLOT_BYTES = 256L
        const val RUST_PANIC_SLOT_BYTES = 64L
        const val PROCESS_INSTANCE_IDENTITY_KIND = 1
        const val ORDINARY_SEGMENT_IDENTITY_KIND = 2
        const val RAW_ARTIFACT_IDENTITY_KIND = 3
        const val RUST_PANIC_RING_CAPACITY = 64
        const val CRASHPAD_RECOVERY_BATCH_FILES = 16
        const val CRASHPAD_RECOVERY_MAX_PASSES = 16
        const val HANDLER_STOP_TIMEOUT_MILLIS = 5_000L
        const val HANDLER_STOP_POLL_MILLIS = 10L
        const val SECONDARY_POLICY_POLL_MILLIS = 1_000L
        const val PRIMARY_NATIVE_POLL_MILLIS = 1_000L
        const val STORAGE_DELETE_TRANSACTION = "all-data"
        const val STORAGE_DELETE_BATCH_SIZE = 256
        const val STORAGE_DELETE_MAX_PASSES = 4
        const val CE_STORAGE_MAX_FILES = 512
        const val DE_STORAGE_MAX_FILES = 32
        const val STORAGE_MAX_DEPTH = 16
        const val STORAGE_CATALOG_MAX_ENTRIES = 1_024
        const val CE_STORAGE_MAX_FILE_BYTES = 128L * 1024 * 1024
        const val DE_STORAGE_MAX_FILE_BYTES = 4L * 1024 * 1024
        val RAW_ARTIFACT_TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(24)
        const val ROLE_SEGMENT_LIMIT = 8L * 1024 * 1024
        const val RAW_ARTIFACT_LIMIT = 16L * 1024 * 1024
        const val SUMMARY_SPOOL_LIMIT = 8L * 1024 * 1024
        const val SUMMARY_STAGING_LIMIT = 2L * 1024 * 1024
        const val SNAPSHOT_LIMIT = 64L * 1024 * 1024
        const val COMPACTION_WORKSPACE_LIMIT = 1L * 1024 * 1024
        const val EMERGENCY_RESERVE_LIMIT = 4L * 1024
        const val METADATA_LIMIT = 1L * 1024 * 1024
        val MAX_FILES = UidBucket.entries.associateWith { bucket ->
            when (bucket) {
                UidBucket.ROLE_SEGMENTS -> 32
                UidBucket.RAW_ARTIFACTS -> 16
                UidBucket.SUMMARY_SPOOL -> 32
                UidBucket.SUMMARY_STAGING -> 8
                UidBucket.SNAPSHOTS -> 8
                UidBucket.COMPACTION -> 2
                UidBucket.EMERGENCY -> 8
                UidBucket.METADATA -> 256
            }
        }
    }
}

internal fun postDurabilityPolicyResult(
    previous: PolicySnapshot,
    target: PolicySnapshot,
): PolicyUpdateResult {
    val isTightening = target.disabled ||
        (!previous.disabled && (target.denyMask or previous.denyMask) == target.denyMask)
    return if (isTightening) {
        PolicyUpdateResult.LOCAL_ONLY_RESTRICTED
    } else {
        PolicyUpdateResult.PARTIAL
    }
}

internal fun localPolicyTuplesConverge(
    credential: PolicySnapshot,
    directBoot: DenyState?,
): Boolean =
    directBoot != null &&
        directBoot.epoch == credential.epoch &&
        directBoot.disabled == credential.disabled &&
        directBoot.c0DenyMask == credential.denyMask

internal fun rawExitTokenAuthorizes(
    token: ExitPolicyToken,
    currentPolicy: PolicySnapshot,
    categoryMask: Long,
): Boolean =
    token.rawArtifactAllowed &&
        token.processRole != null &&
        token.epoch == currentPolicy.epoch &&
        !currentPolicy.disabled &&
        currentPolicy.permits(categoryMask)

internal enum class NativeSlotKind { EMERGENCY, RUST_PANIC }

internal fun nativeSlotPath(directory: Path, kind: NativeSlotKind, role: Int): Path {
    require(role >= 0)
    val prefix = when (kind) {
        NativeSlotKind.EMERGENCY -> "tracebox-emergency"
        NativeSlotKind.RUST_PANIC -> "tracebox-rust-panic"
    }
    return directory.resolve("$prefix-$role.bin")
}

internal fun nativeClientLifecyclePath(
    directory: Path,
    processRole: Int,
    rawArtifactId: ByteArray,
): Path {
    require(processRole > 0)
    require(rawArtifactId.size == 32)
    require(rawArtifactId.any { it != 0.toByte() })
    val rawHex = rawArtifactId.joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
    return directory.resolve("client-r$processRole-$rawHex.tbclient")
}

internal fun shouldClearPolicyRepairMarker(
    requestedProfile: DiagnosticsProfile,
    result: PolicyUpdateResult,
): Boolean =
    requestedProfile != DiagnosticsProfile.DISABLED &&
        result == PolicyUpdateResult.SUCCESS

internal fun repairMarkerAllowsEnable(
    markerPresent: Boolean,
    coordinatesGlobalStorage: Boolean,
    authorizedEpoch: Long?,
    policyEpoch: Long,
): Boolean =
    !markerPresent ||
        (coordinatesGlobalStorage && authorizedEpoch == policyEpoch)

internal fun resolveRequestedProfile(
    configuration: TraceboxConfiguration,
    persistedProfile: DiagnosticsProfile?,
): DiagnosticsProfile =
    if (configuration.persistRequestedProfile) {
        persistedProfile ?: configuration.initialProfile
    } else {
        configuration.initialProfile
    }

internal fun resolveRequestedPolicy(
    configuration: TraceboxConfiguration,
    persistedPolicy: TraceboxPolicy?,
): TraceboxPolicy =
    if (configuration.persistRequestedProfile) {
        persistedPolicy ?: configuration.initialPolicy
    } else {
        configuration.initialPolicy
    }

internal fun legacyPolicy(profile: DiagnosticsProfile): TraceboxPolicy = when (profile) {
    DiagnosticsProfile.DISABLED -> TraceboxPolicy.disabled()
    DiagnosticsProfile.MINIMAL_CRASH -> TraceboxPolicy(
        minimumLogLevel = LogLevel.OFF,
        captures = CaptureKind.entries.toSet() - CaptureKind.HANDLED_EXCEPTION,
    )
    DiagnosticsProfile.STANDARD_DIAGNOSTICS -> TraceboxPolicy.standard()
    DiagnosticsProfile.ENHANCED_DIAGNOSTIC_SESSION -> TraceboxPolicy.debug()
}

internal fun profileFor(policy: TraceboxPolicy): DiagnosticsProfile = when {
    !policy.enabled -> DiagnosticsProfile.DISABLED
    policy.minimumLogLevel == LogLevel.OFF &&
        CaptureKind.HANDLED_EXCEPTION !in policy.captures &&
        !policy.performanceLoggingEnabled -> DiagnosticsProfile.MINIMAL_CRASH
    policy.minimumLogLevel.ordinal <= LogLevel.DEBUG.ordinal ||
        policy.mirrorToLogcat || policy.performanceLoggingEnabled ->
        DiagnosticsProfile.ENHANCED_DIAGNOSTIC_SESSION
    else -> DiagnosticsProfile.STANDARD_DIAGNOSTICS
}

internal fun runtimePolicySnapshot(policy: TraceboxPolicy, epoch: Long): PolicySnapshot {
    if (!policy.enabled) return PolicySnapshot(epoch, Long.MAX_VALUE, disabled = true)
    var denyMask = 0L
    if (CaptureKind.NATIVE_CRASH !in policy.captures) denyMask = denyMask or 1L or 2L
    if (policy.minimumLogLevel == LogLevel.OFF) {
        denyMask = denyMask or 4L or 256L
    }
    if (CaptureKind.HANDLED_EXCEPTION !in policy.captures) denyMask = denyMask or 8L
    if (CaptureKind.JVM_CRASH !in policy.captures) denyMask = denyMask or 16L
    if (CaptureKind.RUST_PANIC !in policy.captures) denyMask = denyMask or 32L
    if (CaptureKind.ANR !in policy.captures) denyMask = denyMask or 64L or 1_024L
    if (CaptureKind.OS_EXIT !in policy.captures) denyMask = denyMask or 128L
    if (CaptureKind.JVM_CRASH !in policy.captures &&
        CaptureKind.HANDLED_EXCEPTION !in policy.captures
    ) {
        denyMask = denyMask or 512L
    }
    denyMask = denyMask or POLICY_METADATA_PRESENT
    denyMask = denyMask or (policy.minimumLogLevel.ordinal.toLong() shl POLICY_LOG_LEVEL_SHIFT)
    if (policy.performanceLoggingEnabled) denyMask = denyMask or POLICY_PERFORMANCE_ENABLED
    if (policy.mirrorToLogcat) denyMask = denyMask or POLICY_LOGCAT_ENABLED
    return PolicySnapshot(epoch, denyMask)
}

internal fun runtimePolicyForSnapshot(snapshot: PolicySnapshot): TraceboxPolicy {
    if (snapshot.disabled) return TraceboxPolicy.disabled()
    if (snapshot.denyMask and POLICY_METADATA_PRESENT == 0L) {
        return if (snapshot.denyMask == 12L) {
            legacyPolicy(DiagnosticsProfile.MINIMAL_CRASH)
        } else {
            legacyPolicy(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
        }
    }
    val levelOrdinal = ((snapshot.denyMask ushr POLICY_LOG_LEVEL_SHIFT) and 0x7L).toInt()
    val captures = buildSet {
        if (snapshot.denyMask and 3L == 0L) add(CaptureKind.NATIVE_CRASH)
        if (snapshot.denyMask and 8L == 0L) add(CaptureKind.HANDLED_EXCEPTION)
        if (snapshot.denyMask and 16L == 0L) add(CaptureKind.JVM_CRASH)
        if (snapshot.denyMask and 32L == 0L) add(CaptureKind.RUST_PANIC)
        if (snapshot.denyMask and (64L or 1_024L) == 0L) add(CaptureKind.ANR)
        if (snapshot.denyMask and 128L == 0L) add(CaptureKind.OS_EXIT)
    }
    return TraceboxPolicy(
        minimumLogLevel = LogLevel.entries.getOrElse(levelOrdinal) { LogLevel.OFF },
        mirrorToLogcat = snapshot.denyMask and POLICY_LOGCAT_ENABLED != 0L,
        performanceLoggingEnabled = snapshot.denyMask and POLICY_PERFORMANCE_ENABLED != 0L,
        captures = captures,
    )
}

private const val POLICY_LOG_LEVEL_SHIFT = 48
private const val POLICY_PERFORMANCE_ENABLED = 1L shl 51
private const val POLICY_LOGCAT_ENABLED = 1L shl 52
private const val POLICY_METADATA_PRESENT = 1L shl 53

internal fun classifyCredentialProtectedStorage(path: OwnedStoragePath): UidBucket? {
    if (path.rootId != "ce" || !isSafeOwnedRelative(path.relativePath)) return null
    val relative = path.relativePath
    return when {
        relative == "policy-control-v1" ||
            relative == ".tracebox-primary.lock" ||
            relative == "policy-repair-required-v1" ||
            relative == "policy-native-transition-v1-a" ||
            relative == "policy-native-transition-v1-b" ||
            relative == "requested-profile-v1" ||
            relative == "requested-profile-v1.new" ||
            relative == "requested-policy-v2" ||
            relative == "requested-policy-v2.new" ||
            relative == "identity-lifecycle-v1" ||
            relative == "identity-lifecycle-managed-v1" ||
            relative == "exit-tombstones-v1" ||
            relative == "exit-tombstones-v1.new" ->
            UidBucket.METADATA

        EXIT_IMPORT_PATH.matches(relative) -> UidBucket.METADATA
        PROCESS_IDENTITY_PATH.matches(relative) -> UidBucket.METADATA
        ROLE_QUOTA_CONTROL_PATH.matches(relative) -> UidBucket.METADATA
        SEGMENT_PATH.matches(relative) -> UidBucket.ROLE_SEGMENTS
        RAW_ARTIFACT_PATH.matches(relative) -> UidBucket.RAW_ARTIFACTS
        RAW_ARTIFACT_JOURNAL_PATH.matches(relative) -> UidBucket.METADATA
        SUMMARY_SPOOL_PATH.matches(relative) -> UidBucket.SUMMARY_SPOOL
        SUMMARY_IMPORT_ACK_PATH.matches(relative) -> UidBucket.METADATA
        SUMMARY_STAGING_PATH.matches(relative) -> UidBucket.SUMMARY_STAGING
        COMPACTION_PATH.matches(relative) -> UidBucket.COMPACTION
        EXPORT_STAGING_PATH.matches(relative) -> UidBucket.SNAPSHOTS
        NATIVE_EMERGENCY_PATH.matches(relative) -> UidBucket.EMERGENCY
        NATIVE_CLIENT_JOURNAL_PATH.matches(relative) -> UidBucket.METADATA
        NATIVE_HANDLER_START_PERMIT_PATH.matches(relative) -> UidBucket.METADATA
        NATIVE_HANDOFF_PATH.matches(relative) -> UidBucket.RAW_ARTIFACTS
        isBoundedCrashpadDatabaseFile(relative) -> UidBucket.RAW_ARTIFACTS
        else -> null
    }
}

internal fun credentialProtectedReservationBytes(
    path: OwnedStoragePath,
    physicalBytes: Long,
): Long = when {
    path.relativePath == "identity-lifecycle-v1" -> 64L * 1024
    path.relativePath == "exit-tombstones-v1" ||
        path.relativePath == "exit-tombstones-v1.new" -> 64L * 1024
    EXIT_IMPORT_PATH.matches(path.relativePath) -> ExitImportJournal.ENTRY_BYTES.toLong()
    else -> physicalBytes
}

internal fun classifyDeviceProtectedStorage(path: OwnedStoragePath): UidBucket? {
    if (path.rootId != "de" || !isSafeOwnedRelative(path.relativePath)) return null
    return when (path.relativePath) {
        "active-deny-v1",
        "active-deny-v1.new",
        "pending-deny-v1",
        "pending-deny-v1.new",
        DirectBootLayout.ACTIVATION_FILE_NAME,
        DirectBootLayout.ACTIVATION_TEMP_FILE_NAME,
        -> UidBucket.METADATA

        "c0.records",
        DirectBootLayout.RECORDS_FILE_NAME,
        -> UidBucket.EMERGENCY

        else -> null
    }
}

private fun directBootStorageRoot(context: Context): Path {
    val storageContext =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
    return storageContext.noBackupFilesDir.toPath().resolve("tracebox-directboot")
}

private fun isPrimaryApplicationProcess(context: Context): Boolean {
    val declared = context.applicationInfo.processName ?: context.packageName
    val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses
                ?.firstOrNull { process -> process.pid == android.os.Process.myPid() }
                ?.processName
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
    return isPrimaryProcessName(current, declared)
}

internal fun isPrimaryProcessName(current: String?, declared: String?): Boolean =
    !current.isNullOrBlank() && !declared.isNullOrBlank() && current == declared

internal fun recoverStoppedHandlerSocket(
    socket: Path,
    cleanupStaleSocket: (String) -> Boolean,
): Boolean {
    if (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) return true
    val cleaned = try {
        cleanupStaleSocket(socket.toString())
    } catch (_: LinkageError) {
        false
    } catch (_: RuntimeException) {
        false
    }
    return cleaned && !Files.exists(socket, LinkOption.NOFOLLOW_LINKS)
}

internal fun drainRustPanicRingIfHealthy(
    healthy: Boolean,
    drain: () -> Unit,
): Boolean {
    if (!healthy) return false
    drain()
    return true
}

internal enum class BoundedManagedCrashOffer {
    DELIVER,
    QUEUED,
    DROPPED,
}

internal data class RuntimePackageCapability<T>(
    val value: T,
    val generation: Long,
    val token: Long,
)

/**
 * Serializes capability acquisition/use with policy invalidation.
 *
 * A transition is ordered either before an operation, which rejects the stale generation, or
 * after the operation completes. Thus an already-created package cannot begin another share,
 * stage, or save once policy authority advances.
 */
internal class RuntimePackageCapabilityFence {
    private var generation = 0L
    private var nextToken = 1L
    private val retirements = linkedMapOf<Long, () -> Unit>()

    @Synchronized
    fun invalidate(clear: () -> Unit) {
        generation = if (generation == Long.MAX_VALUE) Long.MIN_VALUE else generation + 1L
        val callbacks = retirements.values.toList()
        retirements.clear()
        callbacks.forEach { callback -> runCatching(callback) }
        invalidatePackageCapabilitiesForPolicyChange(clear)
    }

    @Synchronized
    fun <T> bind(
        acquire: () -> T?,
        retire: (T) -> Unit = {},
    ): RuntimePackageCapability<T>? {
        val value = acquire() ?: return null
        val replaced = retirements.values.toList()
        retirements.clear()
        replaced.forEach { callback -> runCatching(callback) }
        val token = nextToken
        nextToken = if (nextToken == Long.MAX_VALUE) 1L else nextToken + 1L
        check(token !in retirements) { "package capability token space exhausted" }
        retirements[token] = { retire(value) }
        return RuntimePackageCapability(value, generation, token)
    }

    /**
     * Publishes a newly prepared capability in the same critical section as invalidation.
     *
     * Shutdown marks the handle closed before calling [invalidate]. Therefore publication either
     * completes before invalidation (and is then cleared), or observes the closed handle and is
     * rejected. It cannot repopulate the process-wide approval registry after shutdown.
     */
    @Synchronized
    fun publishIf(
        isAllowed: () -> Boolean,
        publish: () -> Unit,
    ): Boolean {
        if (!isAllowed()) return false
        publish()
        return true
    }

    @Synchronized
    fun <T> use(expectedGeneration: Long, token: Long, action: () -> T): T? {
        if (expectedGeneration != generation || token !in retirements) return null
        return action()
    }

    @Synchronized
    fun retire(token: Long) {
        retirements.remove(token)?.let { callback -> runCatching(callback) }
    }

    @Synchronized
    internal fun activeCapabilityCount(): Int = retirements.size
}

/**
 * Keeps crash-hook work bounded while policy initialization and writer rotation are in flight.
 *
 * Values remain memory-only until an enabled durable policy and its gated sink are both ready.
 * Resolving disabled atomically discards the queue and makes later offers fail closed.
 */
internal class BoundedManagedCrashBuffer<T>(
    private val capacity: Int,
) {
    private enum class PolicyState {
        UNRESOLVED,
        ENABLED,
        DISABLED,
    }

    private val pending = ArrayDeque<T>(capacity)
    private var state = PolicyState.UNRESOLVED

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun offer(value: T, sinkReady: Boolean): BoundedManagedCrashOffer {
        if (state == PolicyState.DISABLED) return BoundedManagedCrashOffer.DROPPED
        if (state == PolicyState.ENABLED && sinkReady) {
            return BoundedManagedCrashOffer.DELIVER
        }
        if (pending.size >= capacity) return BoundedManagedCrashOffer.DROPPED
        pending.addLast(value)
        return BoundedManagedCrashOffer.QUEUED
    }

    @Synchronized
    fun resolve(enabled: Boolean, sinkReady: Boolean): List<T> {
        state = if (enabled) PolicyState.ENABLED else PolicyState.DISABLED
        if (!enabled) {
            pending.clear()
            return emptyList()
        }
        if (!sinkReady || pending.isEmpty()) return emptyList()
        val drained = ArrayList<T>(pending.size)
        while (pending.isNotEmpty()) drained += pending.removeFirst()
        return drained
    }

    @Synchronized
    internal fun pendingCount(): Int = pending.size
}

internal class PrimaryCoordinatorLease private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun tryAcquire(path: Path): PrimaryCoordinatorLease? {
            val normalized = path.toAbsolutePath().normalize()
            val parent = normalized.parent ?: return null
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(normalized)
            ) {
                return null
            }
            return try {
                val channel = FileChannel.open(
                    normalized,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    channel.close()
                    null
                } else {
                    PrimaryCoordinatorLease(channel, lock)
                }
            } catch (_: IOException) {
                null
            } catch (_: UnsupportedOperationException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }
}

private fun isSafeOwnedRelative(relative: String): Boolean =
    relative.isNotBlank() &&
        !relative.startsWith("/") &&
        !relative.endsWith("/") &&
        '\\' !in relative &&
        relative.split('/').all { it.isNotBlank() && it != "." && it != ".." }

private fun isBoundedCrashpadDatabaseFile(relative: String): Boolean {
    val prefix = "native-handler/crashpad-db/"
    if (!relative.startsWith(prefix)) return false
    val components = relative.removePrefix(prefix).split('/')
    return components.size in 1..4 &&
        components.all { CRASHPAD_COMPONENT.matches(it) }
}

private const val CANONICAL_ID_PATTERN = "[A-Za-z0-9_-]{43}"
private val EXIT_IMPORT_PATH =
    Regex("exit-import-journal/$CANONICAL_ID_PATTERN\\.tbexitjournal(?:\\.new)?")
private val PROCESS_IDENTITY_PATH =
    Regex("instances/$CANONICAL_ID_PATTERN/process-instance-id")
private val ROLE_QUOTA_CONTROL_PATH =
    Regex("instances/$CANONICAL_ID_PATTERN/segments/\\.(?:tracebox-role-quota\\.lock|tracebox-ineligible)")
private val SEGMENT_PATH =
    Regex("instances/$CANONICAL_ID_PATTERN/segments/$CANONICAL_ID_PATTERN\\.tbseg")
private val RAW_ARTIFACT_PATH =
    Regex("raw-artifacts/$CANONICAL_ID_PATTERN\\.tbraw")
private val RAW_ARTIFACT_JOURNAL_PATH =
    Regex("raw-artifacts/$CANONICAL_ID_PATTERN\\.tbrawjournal")
private val SUMMARY_SPOOL_PATH =
    Regex("summary-spool/$CANONICAL_ID_PATTERN\\.tbsummary")
private val SUMMARY_IMPORT_ACK_PATH =
    Regex("summary-import-acks/$CANONICAL_ID_PATTERN\\.tbimportack")
private val SUMMARY_STAGING_PATH =
    Regex("summary-staging/$CANONICAL_ID_PATTERN\\.tbstaging")
private val COMPACTION_PATH =
    Regex("compaction/$CANONICAL_ID_PATTERN\\.tbcompact")
private val EXPORT_STAGING_PATH =
    Regex("export-staging/tbdiag-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tbdiag")
private val NATIVE_EMERGENCY_PATH =
    Regex("native-handler/tracebox-(?:emergency|rust-panic)-[0-9]{1,10}\\.bin")
private val NATIVE_CLIENT_JOURNAL_PATH =
    Regex(
        "native-handler/tracebox-handler-clients/" +
            "client-r[1-9][0-9]{0,9}-[0-9a-f]{64}\\.tbclient",
    )
private val NATIVE_HANDLER_START_PERMIT_PATH =
    Regex("native-handler/tracebox-handler-start-permit-v1(?:\\.new)?")
private val NATIVE_HANDOFF_PATH =
    Regex("native-handler/tracebox-handler-handoff/[0-9a-f]{64}\\.dmp")
private val CRASHPAD_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private class RuntimePackages(
    private val runtime: DefaultTraceboxHandle,
) : DiagnosticPackages {
    private val packageLock = Any()
    private var activePackage: RuntimeDiagnosticPackage? = null

    override fun prepare(request: PackageRequest): PackagePreparationResult =
        when (request) {
            PackageRequest.STANDARD -> runtime.prepareStandardPackage()
        }

    override fun approvalIntent(context: Context, preview: PackagePreview): Intent? =
        runtime.approvalIntent(context, preview)

    override fun create(request: PackageRequest, approval: ApprovalToken): PackageResult {
        if (request != PackageRequest.STANDARD) return PackageResult.Rejected
        val capability = runtime.consumeApproval(approval) ?: return PackageResult.Rejected
        val replacement = try {
            RuntimeDiagnosticPackage(runtime, capability, ::onPackageClosed)
        } catch (_: RuntimeException) {
            runtime.retirePackageCapability(capability.token)
            return PackageResult.Rejected
        }
        val previous = synchronized(packageLock) {
            activePackage.also { activePackage = replacement }
        }
        previous?.close()
        return PackageResult.Created(replacement)
    }

    fun deleteExpiredStaging() {
        RuntimeDiagnosticPackage.cleanupExpired(runtime)
    }

    fun deleteAllStaging() {
        RuntimeDiagnosticPackage.deleteAll(runtime)
    }

    fun retireActivePackage() {
        val retired = synchronized(packageLock) {
            activePackage.also { activePackage = null }
        }
        retired?.close()
    }

    private fun onPackageClosed(closedPackage: RuntimeDiagnosticPackage) {
        synchronized(packageLock) {
            if (activePackage === closedPackage) activePackage = null
        }
    }
}

internal inline fun publishAndConsumePackageBytes(
    bytes: ByteArray,
    publish: (ByteArray) -> Boolean,
): Boolean = try {
    publish(bytes)
} finally {
    bytes.fill(0)
}

private class RuntimeDiagnosticPackage(
    private val runtime: DefaultTraceboxHandle,
    capability: RuntimePackageCapability<ApprovedPackageBytes>,
    private val onClosed: (RuntimeDiagnosticPackage) -> Unit,
) : DiagnosticPackage {
    private val approvedBytes = capability.value
    private val capabilityGeneration = capability.generation
    private val capabilityToken = capability.token
    private val digest = checkNotNull(
        approvedBytes.use { MessageDigest.getInstance("SHA-256").digest(it) },
    )
    private val packageSizeBytes = approvedBytes.sizeBytes
    private val mutableReceipt = MutableStateFlow(SharePackageResult.NOT_STARTED)
    private val staged = linkedSetOf<Path>()
    private val closed = AtomicBoolean(false)

    override val plaintextDigestSha256: ByteArray
        get() = digest.copyOf()
    override val sizeBytes: Long
        get() = packageSizeBytes
    override val receipt: StateFlow<SharePackageResult> = mutableReceipt.asStateFlow()

    override fun shareIntent(context: Context): Intent? =
        runtime.withPackageCapability(capabilityGeneration, capabilityToken) {
            approvedBytes.use { bytes ->
                val path = synchronized(staged) { stage(bytes) }
                    ?: return@use null
                val uri = TraceboxFileProvider.uriForFile(context, path)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = android.content.ClipData.newRawUri(
                    context.getString(R.string.tracebox_package_clip_label),
                    uri,
                )
                mutableReceipt.value = SharePackageResult.CHOOSER_OPENED
                Intent.createChooser(send, context.getString(R.string.tracebox_package_share_title))
            }
        }

    override fun createSaveIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_TITLE, "tracebox.tbdiag")

    override fun save(context: Context, destination: Uri, isCancelled: () -> Boolean): SavePackageResult {
        return runtime.withPackageCapability(capabilityGeneration, capabilityToken) {
            approvedBytes.use { bytes ->
                savePackageBytes(
                    bytes = bytes,
                    openOutput = { context.contentResolver.openOutputStream(destination) },
                    isCancelled = isCancelled,
                )
            }
        } ?: SavePackageResult.Failed(SaveFailure.OUTPUT_UNAVAILABLE)
    }

    override fun <T> useInputStream(block: (java.io.InputStream) -> T): T? =
        runtime.withPackageCapability(capabilityGeneration, capabilityToken) {
            approvedBytes.use { bytes -> bytes.inputStream().use(block) }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runtime.retirePackageCapability(capabilityToken)
        digest.fill(0)
        val ownedStaging = synchronized(staged) {
            staged.toList().also { staged.clear() }
        }
        try {
            runtime.scheduleStagingDeletion(ownedStaging)
        } finally {
            onClosed(this)
        }
    }

    override fun deleteStaging(): Boolean = synchronized(staged) {
        val paths = staged.toList()
        var complete = true
        paths.forEach { path ->
            if (runtime.deleteStagingPath(path)) {
                staged.remove(path)
            } else {
                complete = false
            }
        }
        complete
    }

    private fun stage(bytes: ByteArray): Path? {
        val path = runtime.stagePackage(bytes, capabilityGeneration, capabilityToken) ?: return null
        return try {
            staged.add(path)
            path
        } catch (_: RuntimeException) {
            runtime.deleteStagingPath(path)
            null
        }
    }

    companion object {
        fun cleanupExpired(runtime: DefaultTraceboxHandle) {
            val directory = runtime.stagingRoot()
            if (!Files.isDirectory(directory)) return
            val now = System.currentTimeMillis()
            Files.list(directory).use { files ->
                files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .forEach { path ->
                        val expiryDeadline = try {
                            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                        } catch (_: IOException) {
                            return@forEach
                        } catch (_: SecurityException) {
                            return@forEach
                        }
                        if (packageStagingExpired(expiryDeadline, now)) {
                            runtime.deleteStagingPath(path)
                        }
                    }
            }
        }

        fun deleteAll(runtime: DefaultTraceboxHandle) {
            val directory = runtime.stagingRoot()
            if (!Files.isDirectory(directory)) return
            Files.list(directory).use { files ->
                files.forEach {
                    runtime.deleteStagingPath(it)
                }
            }
        }
    }
}

internal fun invalidatePackageCapabilitiesForPolicyChange(clear: () -> Unit) {
    clear()
}

private const val DIAGNOSTIC_SAVE_CHUNK_BYTES = 8 * 1024
private const val PACKAGE_STAGING_TTL_MILLIS = 10L * 60L * 1_000L

internal fun packageStagingExpiryDeadlineMillis(nowMillis: Long): Long {
    require(nowMillis >= 0L)
    return if (nowMillis > Long.MAX_VALUE - PACKAGE_STAGING_TTL_MILLIS) {
        Long.MAX_VALUE
    } else {
        nowMillis + PACKAGE_STAGING_TTL_MILLIS
    }
}

internal fun packageStagingExpired(expiryDeadlineMillis: Long, nowMillis: Long): Boolean =
    expiryDeadlineMillis <= nowMillis

internal fun savePackageBytes(
    bytes: ByteArray,
    openOutput: () -> java.io.OutputStream?,
    isCancelled: () -> Boolean,
): SavePackageResult {
    var written = 0L
    var cancelled = false
    val output = try {
        openOutput()
    } catch (_: IOException) {
        return SavePackageResult.Failed(SaveFailure.OUTPUT_UNAVAILABLE)
    } catch (_: RuntimeException) {
        return SavePackageResult.Failed(SaveFailure.OUTPUT_UNAVAILABLE)
    } ?: return SavePackageResult.Failed(SaveFailure.OUTPUT_UNAVAILABLE)

    return try {
        output.use { stream ->
            var offset = 0
            while (offset < bytes.size) {
                if (isCancelled()) {
                    cancelled = true
                    return SavePackageResult.PartialCopyWarning(written, cancelled = true)
                }
                val count = minOf(DIAGNOSTIC_SAVE_CHUNK_BYTES, bytes.size - offset)
                stream.write(bytes, offset, count)
                offset += count
                written += count
            }
            stream.flush()
        }
        SavePackageResult.Complete(written)
    } catch (_: IOException) {
        SavePackageResult.PartialCopyWarning(written, cancelled)
    } catch (_: RuntimeException) {
        SavePackageResult.PartialCopyWarning(written, cancelled)
    }
}

/** Tracebox-owned, non-exported exact-disclosure activity. */
class TraceboxPackageDisclosureActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val digest = intent.getByteArrayExtra(EXTRA_DIGEST)
        val preview = digest?.let(RuntimePackageRegistry::preview)
        if (preview == null) {
            finish()
            return
        }
        val disclosure = preview.disclosure
        val technicalDetails = buildString {
            append(getString(R.string.tracebox_disclosure_included_values, disclosure.includedValueCount)).append('\n')
            append(getString(R.string.tracebox_disclosure_included_bytes, disclosure.includedBytes)).append('\n')
            append(
                getString(
                    R.string.tracebox_disclosure_privacy_classes,
                    disclosure.privacyClasses.joinLocalized(::privacyClassLabel),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.tracebox_disclosure_transformations,
                    disclosure.transformations.joinLocalized(::transformationLabel),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.tracebox_disclosure_omissions,
                    disclosure.omissionReasons.joinLocalized(::omissionLabel),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.tracebox_disclosure_source_range,
                    sourceRangeLabel(disclosure.sourceTimeRangeMillis),
                ),
            ).append('\n')
            append(getString(R.string.tracebox_disclosure_source_processes, disclosure.sourceProcessCount)).append('\n')
            append(getString(R.string.tracebox_disclosure_raw_artifacts, disclosure.rawArtifactCount)).append('\n')
            append(
                getString(
                    R.string.tracebox_disclosure_warnings,
                    disclosure.warnings.joinLocalized(::warningLabel),
                ),
            ).append('\n')
            append(
                getString(
                    R.string.tracebox_disclosure_digest,
                    disclosure.plaintextDigestSha256.joinToString("") { "%02x".format(it) },
                ),
            )
        }
        val spacing = (16 * resources.displayMetrics.density).toInt()
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            addView(android.widget.TextView(this@TraceboxPackageDisclosureActivity).apply {
                text = getString(R.string.tracebox_disclosure_title)
                textSize = 24f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(android.widget.TextView(this@TraceboxPackageDisclosureActivity).apply {
                text = getString(R.string.tracebox_disclosure_privacy_notice)
                textSize = 16f
                setPadding(0, spacing / 2, 0, spacing)
            })
            addView(android.widget.TextView(this@TraceboxPackageDisclosureActivity).apply {
                text = buildString {
                    append(
                        resources.getQuantityString(
                            R.plurals.tracebox_disclosure_item_count,
                            disclosure.includedValueCount,
                            disclosure.includedValueCount,
                        ),
                    ).append('\n')
                    append(
                        getString(
                            R.string.tracebox_disclosure_total_size,
                            formatPackageSizeForDisplay(disclosure.includedBytes),
                        ),
                    ).append("\n\n")
                    append(getString(R.string.tracebox_disclosure_summary))
                }
                textSize = 18f
                setPadding(spacing, spacing, spacing, spacing)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = spacing.toFloat()
                    setColor(resolveDisclosureSurfaceColor())
                }
            })
            val detailsView = android.widget.TextView(this@TraceboxPackageDisclosureActivity).apply {
                text = technicalDetails
                textSize = 13f
                setTextIsSelectable(true)
                visibility = android.view.View.GONE
                setPadding(0, spacing / 2, 0, spacing / 2)
            }
            addView(android.widget.Button(this@TraceboxPackageDisclosureActivity).apply {
                text = getString(R.string.tracebox_disclosure_show_details)
                setOnClickListener {
                    val showing = detailsView.visibility == android.view.View.VISIBLE
                    detailsView.visibility = if (showing) android.view.View.GONE else android.view.View.VISIBLE
                    text = getString(
                        if (showing) {
                            R.string.tracebox_disclosure_show_details
                        } else {
                            R.string.tracebox_disclosure_hide_details
                        },
                    )
                }
            })
            addView(detailsView)
            addView(android.widget.Button(this@TraceboxPackageDisclosureActivity).apply {
                text = getString(R.string.tracebox_disclosure_approve)
                setOnClickListener {
                    val nonce = RuntimePackageRegistry.approve(checkNotNull(digest)) ?: return@setOnClickListener
                    setResult(RESULT_OK, ApprovalToken.resultIntent(nonce))
                    finish()
                }
            })
            addView(android.widget.Button(this@TraceboxPackageDisclosureActivity).apply {
                text = getString(R.string.tracebox_disclosure_cancel)
                setOnClickListener {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            })
        }
        setContentView(android.widget.ScrollView(this).apply { addView(content) })
    }

    private fun resolveDisclosureSurfaceColor(): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(android.R.attr.colorBackgroundFloating, value, true)) {
            value.data
        } else {
            0xffeeeeee.toInt()
        }
    }

    private fun formatPackageSizeForDisplay(bytes: Long): String = when {
        bytes < 1_024L -> getString(R.string.tracebox_package_size_bytes, bytes)
        bytes < 1_048_576L ->
            getString(R.string.tracebox_package_size_kilobytes, (bytes + 512L) / 1_024L)
        else ->
            getString(R.string.tracebox_package_size_megabytes, (bytes + 524_288L) / 1_048_576L)
    }

    private fun <T> Set<T>.joinLocalized(label: (T) -> String): String = if (isEmpty()) {
        getString(R.string.tracebox_disclosure_none)
    } else {
        sortedBy { it.toString() }
            .joinToString(getString(R.string.tracebox_disclosure_list_separator), transform = label)
    }

    private fun privacyClassLabel(value: PackagePrivacyClass): String = getString(
        when (value) {
            PackagePrivacyClass.C0 -> R.string.tracebox_disclosure_privacy_c0
            PackagePrivacyClass.C1 -> R.string.tracebox_disclosure_privacy_c1
            PackagePrivacyClass.C2 -> R.string.tracebox_disclosure_privacy_c2
        },
    )

    private fun transformationLabel(value: PackageTransformation): String = getString(
        when (value) {
            PackageTransformation.NONE -> R.string.tracebox_disclosure_transformation_none
            PackageTransformation.PARAMETER_REDACTION ->
                R.string.tracebox_disclosure_transformation_parameter_redaction
        },
    )

    private fun omissionLabel(value: PackageOmissionReason): String = getString(
        when (value) {
            PackageOmissionReason.CORRUPT_ORDINARY_RECORD ->
                R.string.tracebox_disclosure_omission_corrupt_record
        },
    )

    private fun warningLabel(value: PackageWarning): String = getString(
        when (value) {
            PackageWarning.RAW_CRASH_ARTIFACTS_EXCLUDED ->
                R.string.tracebox_disclosure_warning_raw_excluded
            PackageWarning.OS_EXIT_HISTORY_REMAINS_ANDROID_OWNED ->
                R.string.tracebox_disclosure_warning_os_exit_owned
            PackageWarning.SHARE_OR_SAF_RECIPIENT_MAY_RETAIN_BYTES ->
                R.string.tracebox_disclosure_warning_recipient_retention
            PackageWarning.DELIVERY_CANNOT_BE_PROVEN ->
                R.string.tracebox_disclosure_warning_delivery_unknown
        },
    )

    private fun sourceRangeLabel(range: LongRange?): String = if (range == null) {
        getString(R.string.tracebox_disclosure_source_range_unavailable)
    } else {
        getString(R.string.tracebox_disclosure_source_range_millis, range.first, range.last)
    }

    companion object {
        const val EXTRA_DIGEST = "dev.tracebox.preview.digest"
    }
}

internal fun formatPackageSize(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes bytes"
    bytes < 1_048_576L -> "${(bytes + 512L) / 1_024L} KB"
    else -> "${(bytes + 524_288L) / 1_048_576L} MB"
}

internal class ManagedIdentityStore(
    private val path: Path,
) {
    fun allocate(kind: Int): ByteArray {
        require(kind in 1..6)
        Files.createDirectories(path.parent)
        val created = !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        val allocated = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            channel.lock().use {
                val completeBytes = channel.size() - (channel.size() % RECORD_BYTES)
                if (completeBytes != channel.size()) channel.truncate(completeBytes)
                require(completeBytes + RECORD_BYTES <= MAXIMUM_BYTES) {
                    "managed identity journal is full"
                }
                val existing = hashSetOf<String>()
                var sequence = 1
                var offset = 0L
                while (offset < completeBytes) {
                    val encoded = ByteArray(RECORD_BYTES)
                    val target = ByteBuffer.wrap(encoded)
                    channel.position(offset)
                    while (target.hasRemaining()) {
                        if (channel.read(target) < 0) throw IOException("truncated identity journal")
                    }
                    val expected = ByteBuffer.wrap(encoded, PAYLOAD_BYTES, Int.SIZE_BYTES).int
                    val actual = CRC32().apply { update(encoded, 0, PAYLOAD_BYTES) }.value.toInt()
                    val source = ByteBuffer.wrap(encoded)
                    if (source.int != MAGIC || source.short.toInt() != VERSION ||
                        source.short.toInt() !in 1..6 || source.int != sequence ||
                        expected != actual
                    ) {
                        throw IOException("invalid managed identity journal")
                    }
                    val identity = ByteArray(IDENTITY_BYTES)
                    source.get(identity)
                    existing += Base64.getEncoder().encodeToString(identity)
                    sequence += 1
                    offset += RECORD_BYTES
                }
                var identity: ByteArray
                do {
                    identity = ByteArray(IDENTITY_BYTES).also(SecureRandom()::nextBytes)
                } while (identity.all { it == 0.toByte() } ||
                    Base64.getEncoder().encodeToString(identity) in existing
                )
                val encoded = ByteArray(RECORD_BYTES)
                ByteBuffer.wrap(encoded).apply {
                    putInt(MAGIC)
                    putShort(VERSION.toShort())
                    putShort(kind.toShort())
                    putInt(sequence)
                    put(identity)
                    val crc = CRC32().apply { update(encoded, 0, PAYLOAD_BYTES) }.value.toInt()
                    putInt(crc)
                }
                channel.position(completeBytes)
                val source = ByteBuffer.wrap(encoded)
                while (source.hasRemaining()) channel.write(source)
                channel.force(true)
                identity
            }
        }
        if (created) {
            try {
                FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
            } catch (_: IOException) {
                // The newly created journal itself is already forced.
            } catch (_: UnsupportedOperationException) {
                // Some host providers cannot open directory channels.
            }
        }
        return allocated
    }

    private companion object {
        const val MAGIC = 0x54424d49
        const val VERSION = 1
        const val IDENTITY_BYTES = 32
        const val PAYLOAD_BYTES = 44
        const val RECORD_BYTES = 48
        const val MAXIMUM_BYTES = 64L * 1024L
    }
}

private class ProfileStore(
    private val path: Path,
) {
    fun read(): DiagnosticsProfile? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = try {
            FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                if (channel.size() != 2L) return null
                val value = ByteArray(2)
                val buffer = ByteBuffer.wrap(value)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return null
                }
                value
            }
        } catch (_: IOException) {
            return null
        }
        if (bytes.size != 2) return null
        val ordinal = bytes[0].toInt()
        val checksum = bytes[1].toInt() and 0xff
        if (checksum != ((ordinal xor 0x5a) and 0xff)) return null
        return DiagnosticsProfile.entries.getOrNull(ordinal)
    }

    fun write(profile: DiagnosticsProfile) {
        Files.createDirectories(path.parent)
        val encoded = byteArrayOf(profile.ordinal.toByte(), (profile.ordinal xor 0x5a).toByte())
        val temporary = path.resolveSibling("${path.fileName}.new")
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(encoded)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        try {
            Files.move(
                temporary,
                path,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        try {
            FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // The profile file itself is already forced.
        } catch (_: UnsupportedOperationException) {
            // Some host providers cannot open directory channels.
        } catch (_: SecurityException) {
            // A later exact read still fails closed.
        }
    }
}

internal class RuntimePolicyStore(
    private val path: Path,
) {
    fun read(): TraceboxPolicy? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val encoded = try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() != ENCODED_BYTES.toLong()) return null
                ByteArray(ENCODED_BYTES).also { bytes ->
                    val target = ByteBuffer.wrap(bytes)
                    while (target.hasRemaining()) {
                        if (channel.read(target) < 0) return null
                    }
                }
            }
        } catch (_: IOException) {
            return null
        }
        val expectedCrc = ByteBuffer.wrap(encoded, PAYLOAD_BYTES, Int.SIZE_BYTES).int
        val actualCrc = CRC32().apply { update(encoded, 0, PAYLOAD_BYTES) }.value.toInt()
        if (expectedCrc != actualCrc) return null
        val source = ByteBuffer.wrap(encoded)
        if (source.int != MAGIC || source.int != VERSION) return null
        val flags = source.int
        val level = LogLevel.entries.getOrNull(source.int) ?: return null
        val captureMask = source.long
        val durationNanos = source.long
        val knownCaptureMask = (1L shl CaptureKind.entries.size) - 1L
        if (captureMask and knownCaptureMask.inv() != 0L || durationNanos < 0L) return null
        val captures = CaptureKind.entries.filterTo(linkedSetOf()) { kind ->
            captureMask and (1L shl kind.ordinal) != 0L
        }
        return runCatching {
            TraceboxPolicy(
                enabled = flags and FLAG_ENABLED != 0,
                minimumLogLevel = level,
                mirrorToLogcat = flags and FLAG_LOGCAT != 0,
                performanceLoggingEnabled = flags and FLAG_PERFORMANCE != 0,
                minimumPerformanceDurationNanos = durationNanos,
                captures = captures,
            )
        }.getOrNull()
    }

    fun write(policy: TraceboxPolicy) {
        Files.createDirectories(path.parent)
        var flags = 0
        if (policy.enabled) flags = flags or FLAG_ENABLED
        if (policy.mirrorToLogcat) flags = flags or FLAG_LOGCAT
        if (policy.performanceLoggingEnabled) flags = flags or FLAG_PERFORMANCE
        val captureMask = policy.captures.fold(0L) { mask, kind ->
            mask or (1L shl kind.ordinal)
        }
        val encoded = ByteArray(ENCODED_BYTES)
        ByteBuffer.wrap(encoded).apply {
            putInt(MAGIC)
            putInt(VERSION)
            putInt(flags)
            putInt(policy.minimumLogLevel.ordinal)
            putLong(captureMask)
            putLong(policy.minimumPerformanceDurationNanos)
            val crc = CRC32().apply { update(encoded, 0, PAYLOAD_BYTES) }.value.toInt()
            putInt(crc)
        }
        val temporary = path.resolveSibling("${path.fileName}.new")
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val source = ByteBuffer.wrap(encoded)
            while (source.hasRemaining()) channel.write(source)
            channel.force(true)
        }
        try {
            Files.move(
                temporary,
                path,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        try {
            FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // The policy file itself is already forced.
        } catch (_: UnsupportedOperationException) {
            // Some host providers cannot open directory channels.
        } catch (_: SecurityException) {
            // A later exact read still fails closed.
        }
    }

    private companion object {
        const val MAGIC = 0x54425032
        const val VERSION = 1
        const val FLAG_ENABLED = 1
        const val FLAG_LOGCAT = 2
        const val FLAG_PERFORMANCE = 4
        const val PAYLOAD_BYTES = 32
        const val ENCODED_BYTES = 36
    }
}
