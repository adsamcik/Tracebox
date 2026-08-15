package dev.tracebox.phase0

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.tracebox.Tracebox
import dev.tracebox.TraceboxConfiguration
import dev.tracebox.api.DeleteRequest
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.PackagePreparationResult
import dev.tracebox.api.PackageRequest
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.Readiness
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.TraceboxHealth
import dev.tracebox.api.generated.GeneratedDiagnostics
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Public-API-only production runtime controls used by the consolidated lab. */
object LabRuntime {
    @Volatile
    private var handle: TraceboxHandle? = null

    fun install(context: Context): TraceboxHandle = synchronized(this) {
        handle ?: Tracebox.install(
            context.applicationContext,
            TraceboxConfiguration.Builder()
                .setProcessRole(PROCESS_ROLE_LAB)
                .setInitialProfile(DiagnosticsProfile.MINIMAL_CRASH)
                .setPersistRequestedProfile(false)
                .setDirectBootC0Enabled(true)
                .build(),
        ).also { handle = it }
    }

    fun reportReadiness(context: Context) {
        val runtime = install(context)
        Thread(
            {
                val deadline = SystemClock.elapsedRealtime() + READINESS_TIMEOUT_MILLIS
                while (
                    !isProductionReady(runtime.readiness.value, runtime.health.value) &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    SystemClock.sleep(READINESS_POLL_MILLIS)
                }
                val readiness = runtime.readiness.value
                val health = runtime.health.value
                val outcome = if (isProductionReady(readiness, health)) "PASS" else "FAIL"
                Log.i(
                    TAG,
                    "scenario_result id=INSTALL.READINESS outcome=$outcome " +
                        "readiness=$readiness health=$health",
                )
            },
            "tracebox-lab-readiness",
        ).start()
    }

    fun runPolicyBarrier(context: Context) {
        Thread(
            {
                val runtime = install(context)
                val standard = runtime.updateProfile(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
                val standardReady = awaitReady(runtime)
                val minimal = runtime.updateProfile(DiagnosticsProfile.MINIMAL_CRASH)
                val minimalReady = awaitReady(runtime)
                val passed =
                    policyBarrierSucceeded(
                        standard,
                        standardReady,
                        minimal,
                        minimalReady,
                    )
                Log.i(
                    TAG,
                    "scenario_result id=MULTIPROCESS.POLICY_BARRIER " +
                        "outcome=${if (passed) "PASS" else "FAIL"} " +
                        "standard=$standard standard_ready=$standardReady " +
                        "minimal=$minimal minimal_ready=$minimalReady",
                )
            },
            "tracebox-lab-policy",
        ).start()
    }

    fun runHandlerTimeoutPolicyProbe(context: Context) {
        Thread(
            {
                val runtime = install(context)
                val started = SystemClock.elapsedRealtime()
                val result = runtime.updateProfile(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
                val elapsed = SystemClock.elapsedRealtime() - started
                val boundedFailure =
                    result != PolicyUpdateResult.SUCCESS &&
                        elapsed in 0..POLICY_TIMEOUT_BOUND_MILLIS
                Log.i(
                    TAG,
                    "scenario_result id=HANDLER.TIMEOUT " +
                        "outcome=${if (boundedFailure) "PASS" else "FAIL"} " +
                        "policy=$result elapsed_ms=$elapsed readiness=${runtime.readiness.value} " +
                        "health=${runtime.health.value}",
                )
            },
            "tracebox-lab-handler-timeout",
        ).start()
    }

    fun createStoragePressure(context: Context) {
        Thread(
            {
                val runtime = install(context)
                val policy = runtime.updateProfile(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
                if (policy != PolicyUpdateResult.SUCCESS || !awaitReady(runtime)) {
                    Log.i(
                        TAG,
                        "scenario_result id=STORAGE.PRESSURE outcome=FAIL " +
                            "policy=$policy readiness=${runtime.readiness.value} " +
                            "health=${runtime.health.value}",
                    )
                    return@Thread
                }
                val before = storageSnapshot(context)
                repeat(PRESSURE_RECORDS) { index ->
                    GeneratedDiagnostics.breadcrumb(
                        runtime.diagnostics,
                        code = (index % 256).toUInt(),
                        monotonic_time_ns = SystemClock.elapsedRealtimeNanos().toULong(),
                    )
                }
                val deadline = SystemClock.elapsedRealtime() + STORAGE_PROGRESS_TIMEOUT_MILLIS
                var after = storageSnapshot(context)
                while (!storageProgressed(before, after) &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    SystemClock.sleep(STORAGE_PROGRESS_POLL_MILLIS)
                    after = storageSnapshot(context)
                }
                val progressed = storageProgressed(before, after)
                Log.i(
                    TAG,
                    "scenario_result id=STORAGE.PRESSURE " +
                        "outcome=${if (progressed) "PASS" else "FAIL"} " +
                        "policy=$policy attempted=$PRESSURE_RECORDS " +
                        "before_segments=${before.segmentCount} after_segments=${after.segmentCount} " +
                        "before_bytes=${before.totalBytes} after_bytes=${after.totalBytes} " +
                        "persisted_delta=${after.totalBytes - before.totalBytes} " +
                        "before_digest=${before.digestHex} after_digest=${after.digestHex}",
                )
            },
            "tracebox-lab-pressure",
        ).start()
    }

    fun delete(context: Context, request: DeleteRequest, stableId: String) {
        Thread(
            {
                val runtime = install(context)
                val report = runtime.delete(request)
                val disabled = awaitDisabled(runtime)
                val passed =
                    deleteSucceeded(
                        report,
                        runtime.readiness.value,
                        runtime.health.value,
                    ) && disabled
                Log.i(
                    TAG,
                    "scenario_result id=$stableId outcome=${if (passed) "PASS" else "FAIL"} " +
                        "report=$report readiness=${runtime.readiness.value} " +
                        "health=${runtime.health.value}",
                )
            },
            "tracebox-lab-delete",
        ).start()
    }

    fun reportDisabledAfterRestart(context: Context, stableId: String) {
        Thread(
            {
                val runtime = install(context)
                val disabled = awaitDisabled(runtime)
                Log.i(
                    TAG,
                    "scenario_state id=$stableId phase=post_delete_restart " +
                        "outcome=${if (disabled) "PASS" else "FAIL"} " +
                        "readiness=${runtime.readiness.value} health=${runtime.health.value}",
                )
            },
            "tracebox-lab-disabled-state",
        ).start()
    }

    fun enableAfterDelete(context: Context, stableId: String) {
        Thread(
            {
                val runtime = install(context)
                val result = runtime.updateProfile(DiagnosticsProfile.MINIMAL_CRASH)
                val ready = awaitReady(runtime)
                val passed = result == PolicyUpdateResult.SUCCESS && ready
                Log.i(
                    TAG,
                    "scenario_state id=$stableId phase=explicit_reenable " +
                        "outcome=${if (passed) "PASS" else "FAIL"} " +
                        "policy=$result readiness=${runtime.readiness.value} " +
                        "health=${runtime.health.value}",
                )
            },
            "tracebox-lab-explicit-enable",
        ).start()
    }

    fun preparePackage(context: Context) {
        Thread(
            {
                val runtime = install(context)
                val policy = runtime.updateProfile(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
                if (policy != PolicyUpdateResult.SUCCESS || !awaitReady(runtime)) {
                    Log.i(
                        TAG,
                        "scenario_result id=PACKAGE.DISCLOSURE outcome=FAIL policy=$policy " +
                            "readiness=${runtime.readiness.value} health=${runtime.health.value}",
                    )
                    return@Thread
                }
                when (val result = runtime.packages.prepare(PackageRequest.STANDARD)) {
                    is PackagePreparationResult.Ready -> {
                        val disclosure = result.preview.disclosure
                        Log.i(
                            TAG,
                            "scenario_result id=PACKAGE.DISCLOSURE outcome=PASS " +
                                "values=${disclosure.includedValueCount} bytes=${disclosure.includedBytes} " +
                                "raw=${disclosure.rawArtifactCount}",
                        )
                    }
                    PackagePreparationResult.NotReady ->
                        Log.i(TAG, "scenario_result id=PACKAGE.DISCLOSURE outcome=NOT_READY")
                    PackagePreparationResult.Rejected ->
                        Log.i(TAG, "scenario_result id=PACKAGE.DISCLOSURE outcome=REJECTED")
                }
            },
            "tracebox-lab-package",
        ).start()
    }

    fun recordRustPanicThenAbort(context: Context) {
        val runtime = install(context)
        Thread(
            {
                val policy = runtime.updateProfile(DiagnosticsProfile.MINIMAL_CRASH)
                if (policy != PolicyUpdateResult.SUCCESS || !awaitReady(runtime)) {
                    Log.i(
                        TAG,
                        "scenario_result id=FAULT.RUST_PANIC outcome=FAIL policy=$policy " +
                            "readiness=${runtime.readiness.value} health=${runtime.health.value}",
                    )
                    return@Thread
                }
                val probe = LabRustPanicProbe.capture()
                if (probe == null) {
                    Log.i(
                        TAG,
                        "scenario_result id=FAULT.RUST_PANIC outcome=FAIL " +
                            "reason=fixture_rust_probe_failed",
                    )
                    return@Thread
                }
                val before = storageSnapshot(context)
                GeneratedDiagnostics.rustPanic(
                    runtime.diagnostics,
                    payload_class = probe.payloadClass,
                    thread_role = PROCESS_ROLE_LAB.toUInt(),
                    location_code = probe.locationCode,
                    flags = probe.flags,
                )
                val deadline =
                    SystemClock.elapsedRealtime() + RUST_PANIC_PERSIST_TIMEOUT_MILLIS
                var after = storageSnapshot(context)
                while (
                    !storageProgressed(before, after) &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    SystemClock.sleep(READINESS_POLL_MILLIS)
                    after = storageSnapshot(context)
                }
                if (!storageProgressed(before, after)) {
                    Log.i(
                        TAG,
                        "scenario_result id=FAULT.RUST_PANIC outcome=FAIL " +
                            "reason=rust_panic_record_not_persisted " +
                            "payload_class=${probe.payloadClass} " +
                            "location_code=${probe.locationCode} flags=${probe.flags}",
                    )
                    return@Thread
                }
                Log.i(
                    TAG,
                    "scenario_state id=FAULT.RUST_PANIC phase=rust_panic_probe " +
                        "outcome=PASS persisted=true payload_class=${probe.payloadClass} " +
                        "location_code=${probe.locationCode} flags=${probe.flags}",
                )
                LabNativeFaults.abortProcess()
            },
            "tracebox-lab-rust-panic",
        ).start()
    }

    private fun awaitReady(runtime: TraceboxHandle): Boolean {
        val deadline = SystemClock.elapsedRealtime() + READINESS_TIMEOUT_MILLIS
        while (
            !isProductionReady(runtime.readiness.value, runtime.health.value) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(READINESS_POLL_MILLIS)
        }
        return isProductionReady(runtime.readiness.value, runtime.health.value)
    }

    private fun awaitDisabled(runtime: TraceboxHandle): Boolean {
        val deadline = SystemClock.elapsedRealtime() + READINESS_TIMEOUT_MILLIS
        while (
            !isDurablyDisabled(runtime.readiness.value, runtime.health.value) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(READINESS_POLL_MILLIS)
        }
        return isDurablyDisabled(runtime.readiness.value, runtime.health.value)
    }

    private fun storageSnapshot(context: Context): StorageSnapshot {
        val root = File(context.noBackupFilesDir, TRACEBOX_DIRECTORY)
        val files = root
            .walkTopDown()
            .filter(File::isFile)
            .filter { file -> file.name.endsWith(SEGMENT_SUFFIX) }
            .take(MAX_SEGMENTS_TO_FINGERPRINT)
            .sortedBy { file -> file.absolutePath }
            .toList()
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        val buffer = ByteArray(FINGERPRINT_BUFFER_BYTES)
        files.forEach { file ->
            digest.update(file.name.toByteArray(Charsets.UTF_8))
            totalBytes += file.length()
            FileInputStream(file).use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return StorageSnapshot(
            segmentCount = files.size,
            totalBytes = totalBytes,
            digestHex = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
        )
    }

    private const val PROCESS_ROLE_LAB = 11
    private const val PRESSURE_RECORDS = 20_000
    private const val RECORD_FLUSH_MILLIS = 250L
    private const val READINESS_TIMEOUT_MILLIS = 20_000L
    private const val READINESS_POLL_MILLIS = 25L
    private const val POLICY_TIMEOUT_BOUND_MILLIS = 10_000L
    private const val STORAGE_PROGRESS_TIMEOUT_MILLIS = 10_000L
    private const val STORAGE_PROGRESS_POLL_MILLIS = 250L
    private const val RUST_PANIC_PERSIST_TIMEOUT_MILLIS = 10_000L
    private const val MAX_SEGMENTS_TO_FINGERPRINT = 64
    private const val FINGERPRINT_BUFFER_BYTES = 16 * 1024
    private const val TRACEBOX_DIRECTORY = "tracebox"
    private const val SEGMENT_SUFFIX = ".tbseg"
    private const val TAG = "TraceboxLab"
}

internal fun isProductionReady(readiness: Readiness, health: TraceboxHealth): Boolean =
    readiness == Readiness.DURABLE && health == TraceboxHealth.READY

internal fun isDurablyDisabled(readiness: Readiness, health: TraceboxHealth): Boolean =
    readiness == Readiness.DURABLE && health == TraceboxHealth.DISABLED

internal fun policyBarrierSucceeded(
    first: PolicyUpdateResult,
    firstReady: Boolean,
    second: PolicyUpdateResult,
    secondReady: Boolean,
): Boolean =
    first == PolicyUpdateResult.SUCCESS &&
        firstReady &&
        second == PolicyUpdateResult.SUCCESS &&
        secondReady

internal fun deleteSucceeded(
    report: dev.tracebox.api.DeleteReport,
    readiness: Readiness,
    health: TraceboxHealth,
): Boolean =
    report == dev.tracebox.api.DeleteReport.COMPLETE &&
        isDurablyDisabled(readiness, health)

internal data class StorageSnapshot(
    val segmentCount: Int,
    val totalBytes: Long,
    val digestHex: String,
)

internal fun storageProgressed(before: StorageSnapshot, after: StorageSnapshot): Boolean =
    after.segmentCount > 0 &&
        after.totalBytes > before.totalBytes &&
        after.digestHex != before.digestHex
