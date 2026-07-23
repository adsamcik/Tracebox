package dev.tracebox

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import dev.tracebox.anr.AnrCandidate
import dev.tracebox.anr.AnrWatchdog
import dev.tracebox.anr.ApplicationExitInfoAdapter
import dev.tracebox.anr.ExitImportResult
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
import dev.tracebox.api.generated.GeneratedDiagnostics
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.api.generated.GeneratedStructuralSummary
import dev.tracebox.core.ControlPage
import dev.tracebox.core.GateResult
import dev.tracebox.core.PolicyPageException
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.RecordPriority
import dev.tracebox.core.WriterPolicyGate
import dev.tracebox.directboot.DenyMirror
import dev.tracebox.directboot.DenyState
import dev.tracebox.export.PackagePipelineResult
import dev.tracebox.export.RecoveredSnapshotRequestAdapter
import dev.tracebox.export.SnapshotPreparer
import dev.tracebox.export.StandardPackagePipeline
import dev.tracebox.export.ui.DisclosureDecodeResult
import dev.tracebox.export.ui.DisclosureRenderer
import dev.tracebox.export.ui.TraceboxFileProvider
import dev.tracebox.nativecapture.NativeRuntime
import dev.tracebox.nativecapture.TraceboxHandlerService
import dev.tracebox.storage.GeneratedRecordSegmentAdapter
import dev.tracebox.storage.PersistedSegmentIdentity
import dev.tracebox.storage.RoleQuotaLedger
import dev.tracebox.storage.RoleQuotaPolicy
import dev.tracebox.storage.SegmentHeader
import dev.tracebox.storage.SegmentWriter
import dev.tracebox.storage.UidAccounting
import dev.tracebox.storage.UidBucket
import dev.tracebox.storage.UidQuota
import dev.tracebox.storage.UidWideQuotaCoordinator
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable installation settings. Diagnostics start disabled unless the host explicitly chooses
 * another profile and explicitly permits persistence of that user choice.
 */
class TraceboxConfiguration private constructor(
    val processRole: Int,
    val initialProfile: DiagnosticsProfile,
    val persistRequestedProfile: Boolean,
    val generatedSchemaFingerprint: ByteArray,
) {
    init {
        require(processRole >= 0)
        require(generatedSchemaFingerprint.size == 32)
    }

    class Builder {
        private var processRole = DEFAULT_PROCESS_ROLE
        private var initialProfile = DiagnosticsProfile.DISABLED
        private var persistRequestedProfile = false
        private var generatedSchemaFingerprint = schemaFingerprint()

        fun setProcessRole(value: Int) = apply {
            require(value >= 0)
            processRole = value
        }

        fun setInitialProfile(value: DiagnosticsProfile) = apply {
            initialProfile = value
        }

        /**
         * Enables persistence only for a profile selected by the host's explicit user-control UI.
         * It is false by default so installation alone never persists enablement.
         */
        fun setPersistRequestedProfile(value: Boolean) = apply {
            persistRequestedProfile = value
        }

        fun setGeneratedSchemaFingerprint(value: ByteArray) = apply {
            require(value.size == 32)
            generatedSchemaFingerprint = value.copyOf()
        }

        fun build(): TraceboxConfiguration = TraceboxConfiguration(
            processRole,
            initialProfile,
            persistRequestedProfile,
            generatedSchemaFingerprint.copyOf(),
        )
    }

    internal fun equivalentTo(other: TraceboxConfiguration): Boolean =
        processRole == other.processRole &&
            initialProfile == other.initialProfile &&
            persistRequestedProfile == other.persistRequestedProfile &&
            generatedSchemaFingerprint.contentEquals(other.generatedSchemaFingerprint)

    companion object {
        const val DEFAULT_PROCESS_ROLE = 1

        private fun schemaFingerprint(): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("tracebox-schema-v1".toByteArray(Charsets.US_ASCII))
    }
}

/** Public, generated-only Tracebox entry point. It has no uploader, transport, or network surface. */
object Tracebox {
    private val installLock = Any()
    private var installed: DefaultTraceboxHandle? = null

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
        DefaultTraceboxHandle(context.applicationContext, configuration).also { installed = it }
    }
}

private class DefaultTraceboxHandle(
    private val applicationContext: Context,
    val configuration: TraceboxConfiguration,
) : TraceboxHandle {
    private val root = applicationContext.noBackupFilesDir.toPath().resolve(ROOT_DIRECTORY)
    private val profileStore = ProfileStore(root.resolve("requested-profile-v1"))
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WORK_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "tracebox-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val mutableReadiness = MutableStateFlow(Readiness.VOLATILE_CAPTURE)
    private val mutableHealth = MutableStateFlow(TraceboxHealth.INITIALIZING)
    private val closed = AtomicBoolean(false)
    private val profileLock = Any()

    @Volatile
    private var activeProfile = DiagnosticsProfile.DISABLED

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
    private var nativeReady = false

    private var watchdog: AnrWatchdog? = null
    private var visibleActivities = 0
    private val packageSurface = RuntimePackages(this)

    override val readiness: StateFlow<Readiness> = mutableReadiness.asStateFlow()
    override val health: StateFlow<TraceboxHealth> = mutableHealth.asStateFlow()

    override val diagnostics: Diagnostics = object : Diagnostics {
        override fun eventEnabled(eventId: GeneratedEventId): Boolean = accepts(eventId)

        override fun record(value: GeneratedRecord, context: DiagnosticContext?) {
            if (!accepts(value.eventId)) return
            enqueue {
                if (accepts(value.eventId)) {
                    generatedAdapter?.record(value, context)
                }
            }
        }
    }

    override val packages: DiagnosticPackages = packageSurface

    init {
        enqueue(::initialize)
    }

    override fun updateProfile(profile: DiagnosticsProfile): PolicyUpdateResult {
        if (isMainThread() || closed.get()) return PolicyUpdateResult.FAILED
        return call {
            synchronized(profileLock) {
                applyProfile(profile)
            }
        } ?: PolicyUpdateResult.FAILED
    }

    override fun delete(request: DeleteRequest): DeleteReport {
        if (isMainThread() || closed.get()) return DeleteReport.REJECTED
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
        mutableHealth.value = TraceboxHealth.CLOSED
        mutableReadiness.value = Readiness.CLOSED
        watchdog?.close()
        executor.shutdownNow()
    }

    private fun initialize() {
        try {
            Files.createDirectories(root)
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
            uidQuota = UidWideQuotaCoordinator(root, quota, MAX_FILES)
            val page = ControlPage(root.resolve("policy-control-v1"))
            val current = try {
                page.committed()
            } catch (_: PolicyPageException) {
                page.commit(disabledPolicy(1))
                disabledPolicy(1)
            }
            controlPage = page
            policyGate = WriterPolicyGate(page)
            installJvmWrapper()
            installVisibilityCallbacks()
            val requested = if (configuration.persistRequestedProfile) {
                profileStore.read() ?: DiagnosticsProfile.DISABLED
            } else {
                DiagnosticsProfile.DISABLED
            }
            activeProfile = DiagnosticsProfile.DISABLED
            applyProfile(requested, current.epoch)
            reconcileExitHistory()
        } catch (_: IOException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            mutableReadiness.value = Readiness.DEGRADED
        } catch (_: PolicyPageException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            mutableReadiness.value = Readiness.DEGRADED
        }
    }

    private fun applyProfile(profile: DiagnosticsProfile, observedEpoch: Long? = null): PolicyUpdateResult {
        val page = controlPage ?: return PolicyUpdateResult.FAILED
        val previous = try {
            page.committed()
        } catch (_: PolicyPageException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            mutableReadiness.value = Readiness.DEGRADED
            return PolicyUpdateResult.FAILED
        }
        val nextEpoch = maxOf(previous.epoch, observedEpoch ?: 0) + 1
        val next = policyFor(profile, nextEpoch)

        try {
            commitPolicy(page, previous, next)
            check(policyGate?.reload() == GateResult.Reloaded) { "policy gate did not load committed policy" }
        } catch (_: IOException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            mutableReadiness.value = Readiness.DEGRADED
            return PolicyUpdateResult.FAILED
        } catch (_: PolicyPageException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            mutableReadiness.value = Readiness.DEGRADED
            return PolicyUpdateResult.FAILED
        }

        activeProfile = profile
        if (configuration.persistRequestedProfile) {
            try {
                profileStore.write(profile)
            } catch (_: IOException) {
                mutableHealth.value = TraceboxHealth.DEGRADED
                mutableReadiness.value = Readiness.DEGRADED
                return PolicyUpdateResult.FAILED
            }
        }

        if (profile == DiagnosticsProfile.DISABLED) {
            writer?.let {
                try {
                    it.seal()
                } catch (_: IllegalStateException) {
                    // A sealed or quota-bound segment stays immutable; policy already blocks writes.
                }
            }
            writer = null
            generatedAdapter = null
            watchdog?.close()
            watchdog = null
            nativeReady = false
            mutableHealth.value = TraceboxHealth.DISABLED
            mutableReadiness.value = Readiness.DURABLE
            return PolicyUpdateResult.SUCCESS
        }

        return try {
            rotateWriter(next)
            ensureNativeAndWatchdog()
            mutableReadiness.value = if (nativeReady) Readiness.DURABLE else Readiness.DEGRADED
            mutableHealth.value = if (nativeReady) TraceboxHealth.READY else TraceboxHealth.DEGRADED
            PolicyUpdateResult.SUCCESS
        } catch (_: IOException) {
            mutableReadiness.value = Readiness.DEGRADED
            mutableHealth.value = TraceboxHealth.DEGRADED
            PolicyUpdateResult.FAILED
        } catch (_: IllegalStateException) {
            mutableReadiness.value = Readiness.DEGRADED
            mutableHealth.value = TraceboxHealth.DEGRADED
            PolicyUpdateResult.FAILED
        }
    }

    private fun commitPolicy(page: ControlPage, previous: PolicySnapshot, next: PolicySnapshot) {
        val mirror = directBootMirror()
        val beforeMoreRestrictive = next.disabled ||
            (next.denyMask and previous.denyMask) != previous.denyMask
        val state = DenyState(next.epoch, next.disabled, next.denyMask)
        if (beforeMoreRestrictive) {
            mirror.writePending(state)
            page.commit(next)
            mirror.promotePending()
        } else {
            page.commit(next)
            mirror.writePending(state)
            mirror.promotePending()
        }
    }

    private fun directBootMirror(): DenyMirror {
        val deviceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            applicationContext.createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }
        val directory = deviceContext.noBackupFilesDir.toPath().resolve(DIRECT_BOOT_DIRECTORY)
        return DenyMirror(directory.resolve("active-deny-v1"), directory.resolve("pending-deny-v1"))
    }

    private fun rotateWriter(snapshot: PolicySnapshot) {
        writer?.let {
            try {
                it.seal()
            } catch (_: IllegalStateException) {
                // The prior segment remains immutable and is excluded only when its epoch differs.
            }
        }
        val instances = root.resolve("instances")
        Files.createDirectories(instances)
        val instance = instances.resolve(randomToken())
        Files.createDirectories(instance)
        val processIdentity = randomBytes(32)
        val identityPath = instance.resolve("process-instance-id")
        reserveMetadata(identityPath, processIdentity.size.toLong())
        forceWrite(identityPath, processIdentity)
        val segments = instance.resolve("segments")
        Files.createDirectories(segments)
        val segmentIdentity = randomBytes(32)
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
            checkNotNull(uidQuota),
        )
        writer = created
        generatedAdapter = GeneratedRecordSegmentAdapter(created, checkNotNull(policyGate))
    }

    private fun ensureNativeAndWatchdog() {
        nativeReady = startNativeHandler()
        if (watchdog == null) {
            watchdog = AnrWatchdog(
                requester = { timeoutMillis ->
                    if (!nativeReady) false else requestNonFatal(timeoutMillis)
                },
                onCandidate = ::recordAnrCandidate,
            ).also {
                it.start()
                it.setEligible(visibleActivities > 0)
            }
        }
    }

    private fun startNativeHandler(): Boolean {
        return try {
            val nativeDirectory = applicationContext.noBackupFilesDir
            if (!NativeRuntime.initializeEmergency(nativeDirectory.absolutePath, configuration.processRole)) return false
            applicationContext.startService(Intent(applicationContext, TraceboxHandlerService::class.java))
            NativeRuntime.connectClient(
                nativeDirectory.toPath().resolve(TraceboxHandlerService.SOCKET_NAME).toString(),
                configuration.processRole,
            )
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun requestNonFatal(timeoutMillis: Int): Boolean =
        try {
            NativeRuntime.requestNonFatal(ANR_NON_FATAL_REASON, timeoutMillis)
        } catch (_: UnsatisfiedLinkError) {
            nativeReady = false
            false
        }

    private fun recordAnrCandidate(candidate: AnrCandidate) {
        val code = if (candidate.nonFatalRequested) ANR_NON_FATAL_REASON else ANR_CANDIDATE_REASON
        GeneratedDiagnostics.structuralSummary(
            diagnostics,
            stream_count = 0u,
            thread_count = candidate.mainFrames.size.toUInt(),
            module_count = 0u,
            exception_code = code.toUInt(),
            processor_architecture = 0u.toUShort(),
        )
    }

    private fun installJvmWrapper() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            dev.tracebox.core.TraceboxUncaughtExceptionHandler(
                previous,
                dev.tracebox.core.JvmCapturePolicy(),
            ) { captured ->
                val first = captured.causes.firstOrNull()
                GeneratedDiagnostics.handledError(
                    diagnostics,
                    kind = (first?.type?.hashCode() ?: 0).toUInt(),
                    frame_count = (first?.frames?.size ?: 0).toUShort(),
                )
            },
        )
    }

    private fun installVisibilityCallbacks() {
        val application = applicationContext as? Application ?: return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                visibleActivities += 1
                watchdog?.setEligible(true)
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
                if (visibleActivities == 0) watchdog?.setEligible(false)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun reconcileExitHistory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ledger = try {
            ExitTombstoneLedger(root.resolve("exit-tombstones-v1"), EXIT_TOMBSTONE_LIMIT, EXIT_TOMBSTONE_BYTES)
        } catch (_: IllegalStateException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            return
        }
        ApplicationExitInfoAdapter().anrHistory(applicationContext, EXIT_HISTORY_LIMIT).forEach { exit ->
            when (ledger.record(ExitSourceKey.derive(exit))) {
                ExitImportResult.IMPORTED -> GeneratedDiagnostics.structuralSummary(
                    diagnostics,
                    stream_count = 0u,
                    thread_count = 0u,
                    module_count = 0u,
                    exception_code = exit.reason.toUInt(),
                    processor_architecture = 0u.toUShort(),
                )

                ExitImportResult.ALREADY_IMPORTED, ExitImportResult.DISABLED_EXHAUSTED -> Unit
            }
        }
    }

    private fun deleteAllTraceboxData(): DeleteReport {
        mutableHealth.value = TraceboxHealth.DELETING
        applyProfile(DiagnosticsProfile.DISABLED)
        packageSurface.deleteAllStaging()
        var complete = true
        val protected = setOf("policy-control-v1", "requested-profile-v1")
        if (Files.isDirectory(root)) {
            Files.list(root).use { children ->
                children.filter { it.fileName.toString() !in protected }.forEach { child ->
                    if (!deleteTree(child)) complete = false
                }
            }
        }
        val deviceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            applicationContext.createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }
        if (!deleteTree(deviceContext.noBackupFilesDir.toPath().resolve(DIRECT_BOOT_DIRECTORY))) complete = false
        mutableHealth.value = if (complete) TraceboxHealth.DISABLED else TraceboxHealth.DEGRADED
        return if (complete) DeleteReport.COMPLETE else DeleteReport.PENDING_FAILURE
    }

    private fun deleteTree(path: Path): Boolean {
        if (!Files.exists(path)) return true
        return try {
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    internal fun prepareStandardPackage(): PackagePreparationResult {
        if (isMainThread() || activeProfile == DiagnosticsProfile.DISABLED || closed.get()) {
            return PackagePreparationResult.NotReady
        }
        return call {
            val policy = controlPage?.committed() ?: return@call PackagePreparationResult.NotReady
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
            if (segmentPaths.isEmpty()) return@call PackagePreparationResult.NotReady
            val stagingAccounting = UidAccounting(
                UidQuota(mapOf(UidBucket.SNAPSHOTS to SNAPSHOT_LIMIT)),
                mapOf(UidBucket.SNAPSHOTS to 1),
            )
            val pipeline = StandardPackagePipeline(
                SnapshotPreparer(stagingAccounting, root.resolve("snapshot-reservation")),
            )
            val request = RecoveredSnapshotRequestAdapter().build(
                policy.epoch,
                configuration.processRole,
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
                        RuntimePackageRegistry.put(preview, result.packageBytes.exactBytes())
                        PackagePreparationResult.Ready(preview)
                    }
                }
            }
        } ?: PackagePreparationResult.Rejected
    }

    private fun preview(decoded: DisclosureDecodeResult.Decoded): PackagePreview {
        val facts = decoded.facts
        val transformations = if (facts.transformations.isEmpty() || facts.transformations.all { it == "none" }) {
            setOf(PackageTransformation.NONE)
        } else {
            emptySet()
        }
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

    internal fun consumeApproval(approval: ApprovalToken): ByteArray? =
        RuntimePackageRegistry.take(approval.opaqueBytes())

    internal fun reserveStaging(path: Path, bytes: Long): Boolean =
        uidQuota?.reserve(path, UidBucket.SNAPSHOTS, bytes) == true

    internal fun releaseStaging(path: Path) {
        uidQuota?.release(path)
    }

    internal fun stagingRoot(): Path = root.resolve("export-staging")

    private fun accepts(eventId: GeneratedEventId): Boolean = when (activeProfile) {
        DiagnosticsProfile.DISABLED -> false
        DiagnosticsProfile.MINIMAL_CRASH ->
            eventId == GeneratedEventId.STRUCTURALSUMMARY || eventId == GeneratedEventId.EMERGENCYRECORD

        DiagnosticsProfile.STANDARD_DIAGNOSTICS, DiagnosticsProfile.ENHANCED_DIAGNOSTIC_SESSION -> true
    }

    private fun policyFor(profile: DiagnosticsProfile, epoch: Long): PolicySnapshot = when (profile) {
        DiagnosticsProfile.DISABLED -> disabledPolicy(epoch)
        DiagnosticsProfile.MINIMAL_CRASH -> PolicySnapshot(epoch, BREADCRUMB_MASK or HANDLED_ERROR_MASK)
        DiagnosticsProfile.STANDARD_DIAGNOSTICS, DiagnosticsProfile.ENHANCED_DIAGNOSTIC_SESSION -> PolicySnapshot(epoch, 0)
    }

    private fun disabledPolicy(epoch: Long): PolicySnapshot = PolicySnapshot(epoch, Long.MAX_VALUE, disabled = true)

    private fun reserveMetadata(path: Path, bytes: Long) {
        check(uidQuota?.reserve(path, UidBucket.METADATA, bytes) == true) { "Tracebox metadata quota exhausted" }
    }

    private fun enqueue(action: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute(action)
        } catch (_: RejectedExecutionException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
        }
    }

    private fun <T> call(action: () -> T): T? {
        return try {
            executor.submit<T>(action).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            null
        } catch (_: TimeoutException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            mutableHealth.value = TraceboxHealth.DEGRADED
            null
        }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun forceWrite(path: Path, value: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            it.write(ByteBuffer.wrap(value))
            it.force(true)
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)
    private fun randomToken(): String = encode(randomBytes(32))
    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private companion object {
        internal const val ROOT_DIRECTORY = "tracebox"
        const val DIRECT_BOOT_DIRECTORY = "tracebox-directboot"
        const val WORK_QUEUE_CAPACITY = 64
        const val OPERATION_TIMEOUT_SECONDS = 5L
        const val BREADCRUMB_MASK = 4L
        const val HANDLED_ERROR_MASK = 8L
        const val ANR_CANDIDATE_REASON = 0x5442414e
        const val ANR_NON_FATAL_REASON = 0x54424152
        const val EXIT_HISTORY_LIMIT = 32
        const val EXIT_TOMBSTONE_LIMIT = 1024
        const val EXIT_TOMBSTONE_BYTES = 64 * 1024
        const val ROLE_SEGMENT_LIMIT = 8L * 1024 * 1024
        const val RAW_ARTIFACT_LIMIT = 16L * 1024 * 1024
        const val SUMMARY_SPOOL_LIMIT = 8L * 1024 * 1024
        const val SUMMARY_STAGING_LIMIT = 2L * 1024 * 1024
        const val SNAPSHOT_LIMIT = 64L * 1024 * 1024
        const val COMPACTION_WORKSPACE_LIMIT = 1L * 1024 * 1024
        const val EMERGENCY_RESERVE_LIMIT = 4L * 1024
        const val METADATA_LIMIT = 256L * 1024
        val MAX_FILES = UidBucket.entries.associateWith { bucket ->
            when (bucket) {
                UidBucket.ROLE_SEGMENTS -> 32
                UidBucket.RAW_ARTIFACTS -> 16
                UidBucket.SUMMARY_SPOOL -> 32
                UidBucket.SUMMARY_STAGING -> 8
                UidBucket.SNAPSHOTS -> 8
                UidBucket.COMPACTION -> 2
                UidBucket.EMERGENCY -> 2
                UidBucket.METADATA -> 64
            }
        }
    }
}

private class RuntimePackages(
    private val runtime: DefaultTraceboxHandle,
) : DiagnosticPackages {
    override fun prepare(request: PackageRequest): PackagePreparationResult =
        when (request) {
            PackageRequest.STANDARD -> runtime.prepareStandardPackage()
        }

    override fun approvalIntent(context: Context, preview: PackagePreview): Intent? =
        runtime.approvalIntent(context, preview)

    override fun create(request: PackageRequest, approval: ApprovalToken): PackageResult {
        if (request != PackageRequest.STANDARD) return PackageResult.Rejected
        val bytes = runtime.consumeApproval(approval) ?: return PackageResult.Rejected
        return PackageResult.Created(RuntimeDiagnosticPackage(runtime, bytes))
    }

    fun deleteExpiredStaging() {
        RuntimeDiagnosticPackage.cleanupExpired(runtime)
    }

    fun deleteAllStaging() {
        RuntimeDiagnosticPackage.deleteAll(runtime)
    }
}

private class RuntimeDiagnosticPackage(
    private val runtime: DefaultTraceboxHandle,
    bytes: ByteArray,
) : DiagnosticPackage {
    private val bytes = bytes.copyOf()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    private val mutableReceipt = MutableStateFlow(SharePackageResult.NOT_STARTED)
    private val staged = linkedSetOf<Path>()

    override val plaintextDigestSha256: ByteArray
        get() = digest.copyOf()
    override val sizeBytes: Long
        get() = bytes.size.toLong()
    override val receipt: StateFlow<SharePackageResult> = mutableReceipt.asStateFlow()

    override fun shareIntent(context: Context): Intent? {
        val path = synchronized(staged) {
            stage(context) ?: return null
        }
        val uri = TraceboxFileProvider.uriForFile(context, path)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("Tracebox package", uri)
        mutableReceipt.value = SharePackageResult.CHOOSER_OPENED
        return Intent.createChooser(send, "Share Tracebox package")
    }

    override fun createSaveIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_TITLE, "tracebox.tbdiag")

    override fun save(context: Context, destination: Uri, isCancelled: () -> Boolean): SavePackageResult {
        var written = 0L
        val output = try {
            context.contentResolver.openOutputStream(destination)
        } catch (_: SecurityException) {
            null
        }
        if (output == null) return SavePackageResult.Failed(SaveFailure.OUTPUT_UNAVAILABLE)
        return try {
            output.use { stream ->
                var offset = 0
                while (offset < bytes.size) {
                    if (isCancelled()) return SavePackageResult.PartialCopyWarning(written, true)
                    val count = minOf(SAVE_CHUNK_BYTES, bytes.size - offset)
                    stream.write(bytes, offset, count)
                    offset += count
                    written += count
                }
                stream.flush()
            }
            SavePackageResult.Complete(written)
        } catch (_: IOException) {
            SavePackageResult.Failed(SaveFailure.WRITE_FAILED)
        }
    }

    override fun deleteStaging(): Boolean = synchronized(staged) {
        val paths = staged.toList()
        var complete = true
        paths.forEach { path ->
            try {
                Files.deleteIfExists(path)
                runtime.releaseStaging(path)
                staged.remove(path)
            } catch (_: IOException) {
                complete = false
            }
        }
        complete
    }

    private fun stage(context: Context): Path? {
        val directory = runtime.stagingRoot()
        try {
            Files.createDirectories(directory)
        } catch (_: IOException) {
            return null
        }
        val path = directory.resolve("tbdiag-${UUID.randomUUID()}.tbdiag")
        if (!runtime.reserveStaging(path, bytes.size.toLong())) return null
        return try {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            staged.add(path)
            path
        } catch (_: IOException) {
            runtime.releaseStaging(path)
            null
        }
    }

    companion object {
        private const val SAVE_CHUNK_BYTES = 8 * 1024

        fun cleanupExpired(runtime: DefaultTraceboxHandle) {
            val directory = runtime.stagingRoot()
            if (!Files.isDirectory(directory)) return
            Files.list(directory).use { files ->
                files.filter { Files.isRegularFile(it) && Files.getLastModifiedTime(it).toMillis() <= System.currentTimeMillis() }
                    .forEach {
                        Files.deleteIfExists(it)
                        runtime.releaseStaging(it)
                    }
            }
        }

        fun deleteAll(runtime: DefaultTraceboxHandle) {
            val directory = runtime.stagingRoot()
            if (!Files.isDirectory(directory)) return
            Files.list(directory).use { files ->
                files.forEach {
                    Files.deleteIfExists(it)
                    runtime.releaseStaging(it)
                }
            }
        }
    }
}

private object RuntimePackageRegistry {
    private data class Prepared(val preview: PackagePreview, val bytes: ByteArray)

    private val prepared = linkedMapOf<String, Prepared>()
    private val approved = linkedMapOf<String, Prepared>()

    @Synchronized
    fun put(preview: PackagePreview, bytes: ByteArray) {
        prepared[key(preview.disclosure.plaintextDigestSha256)] = Prepared(preview, bytes.copyOf())
    }

    @Synchronized
    fun intent(context: Context, preview: PackagePreview): Intent? {
        val digest = preview.disclosure.plaintextDigestSha256
        if (prepared[key(digest)] == null) return null
        return Intent(context, TraceboxPackageDisclosureActivity::class.java)
            .putExtra(TraceboxPackageDisclosureActivity.EXTRA_DIGEST, digest.copyOf())
    }

    @Synchronized
    fun preview(digest: ByteArray): PackagePreview? = prepared[key(digest)]?.preview

    @Synchronized
    fun approve(digest: ByteArray): ByteArray? {
        val item = prepared.remove(key(digest)) ?: return null
        val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
        approved[key(nonce)] = item
        return nonce
    }

    @Synchronized
    fun take(nonce: ByteArray): ByteArray? = approved.remove(key(nonce))?.bytes?.copyOf()

    private fun key(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
        val details = buildString {
            append("Included values: ").append(disclosure.includedValueCount).append('\n')
            append("Included bytes: ").append(disclosure.includedBytes).append('\n')
            append("Privacy classes: ").append(disclosure.privacyClasses).append('\n')
            append("Transformations: ").append(disclosure.transformations).append('\n')
            append("Omissions: ").append(disclosure.omissionReasons).append('\n')
            append("Source time range: ").append(disclosure.sourceTimeRangeMillis).append('\n')
            append("Source processes: ").append(disclosure.sourceProcessCount).append('\n')
            append("Raw artifacts: ").append(disclosure.rawArtifactCount).append('\n')
            append("Warnings: ").append(disclosure.warnings).append('\n')
            append("SHA-256: ").append(disclosure.plaintextDigestSha256.joinToString("") { "%02x".format(it) })
        }
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(this@TraceboxPackageDisclosureActivity).apply { text = details })
            addView(android.widget.Button(this@TraceboxPackageDisclosureActivity).apply {
                text = "Approve package"
                setOnClickListener {
                    val nonce = RuntimePackageRegistry.approve(checkNotNull(digest)) ?: return@setOnClickListener
                    setResult(RESULT_OK, ApprovalToken.resultIntent(nonce))
                    finish()
                }
            })
        })
    }

    companion object {
        const val EXTRA_DIGEST = "dev.tracebox.preview.digest"
    }
}

private class ProfileStore(
    private val path: Path,
) {
    fun read(): DiagnosticsProfile? {
        if (!Files.exists(path)) return null
        val bytes = Files.readAllBytes(path)
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
        ).use {
            it.write(ByteBuffer.wrap(encoded))
            it.force(true)
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
    }
}
