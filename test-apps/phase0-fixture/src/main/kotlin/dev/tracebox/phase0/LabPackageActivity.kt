package dev.tracebox.phase0

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import dev.tracebox.api.ApprovalToken
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.PackagePreparationResult
import dev.tracebox.api.PackageRequest
import dev.tracebox.api.PackageResult
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.SavePackageResult
import dev.tracebox.api.SharePackageResult
import dev.tracebox.api.generated.GeneratedDiagnostics
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixture-only coordinator for production public-API certification.
 *
 * Package scenarios use the real disclosure, one-time approval, SAF, and Sharesheet surfaces.
 * Destructive fixture scenarios arm the installed production Tracebox path before the fixture
 * injects a fault; no fault-injection surface is added to a production AAR.
 */
class LabPackageActivity : Activity() {
    private var requestedScenario = "PACKAGE.DISCLOSURE"
    private var ready: PackagePreparationResult.Ready? = null
    private var pendingPackage: DiagnosticPackage? = null
    private var savedBytes = 0L
    private var savedDigestMatches = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedScenario =
            intent.getStringExtra(SCENARIO_EXTRA)
                ?.takeIf { value -> value in SUPPORTED_SCENARIOS || value in RUNTIME_SCENARIOS }
                ?: "PACKAGE.DISCLOSURE"
        setContentView(TextView(this).apply { text = "Preparing Tracebox certification action..." })
        val action = intent.getStringExtra(ACTION_EXTRA)
        if (action != null && requestedScenario in RUNTIME_SCENARIOS) {
            executeRuntimeAction(action)
            return
        }
        Thread(::prepareAndShowDisclosure, "tracebox-lab-package-ui").start()
    }

    @Suppress("DEPRECATION")
    private fun prepareAndShowDisclosure() {
        val runtime = LabRuntime.install(this)
        val policy = runtime.updateProfile(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
        if (policy != PolicyUpdateResult.SUCCESS) {
            logResult("FAIL", "policy=$policy")
            return
        }
        GeneratedDiagnostics.breadcrumb(
            runtime.diagnostics,
            code = 1u,
            monotonic_time_ns = SystemClock.elapsedRealtimeNanos().toULong(),
        )
        val prepared = runtime.packages.prepare(PackageRequest.STANDARD)
        if (prepared !is PackagePreparationResult.Ready) {
            logResult("NOT_READY", "preparation=${prepared::class.java.simpleName}")
            return
        }
        ready = prepared
        val approval = runtime.packages.approvalIntent(this, prepared.preview)
        if (approval == null) {
            logResult("FAIL", "approval_intent=false")
            return
        }
        val disclosure = prepared.preview.disclosure
        Log.i(
            TAG,
            "scenario_ready id=$requestedScenario values=${disclosure.includedValueCount} " +
                "bytes=${disclosure.includedBytes} raw=${disclosure.rawArtifactCount}",
        )
        runOnUiThread {
            startActivityForResult(approval, APPROVAL_REQUEST)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            APPROVAL_REQUEST -> handleApprovalResult(resultCode, data)
            SAVE_REQUEST -> handleSaveResult(resultCode, data)
            SHARE_REQUEST -> handleShareResult()
        }
    }

    private fun handleApprovalResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) {
            if (requestedScenario == "PACKAGE.DISCLOSURE") {
                logResult("PASS", "disclosure_reviewed=true approved=false")
            } else {
                logResult("FAIL", "approved=false")
            }
            return
        }
        val token = ApprovalToken.fromActivityResult(data)
        if (token == null || ready == null) {
            logResult("FAIL", "token_valid=false")
            return
        }
        val result = LabRuntime.install(this).packages.create(PackageRequest.STANDARD, token)
        if (result !is PackageResult.Created) {
            logResult("FAIL", "package_created=false")
            return
        }
        val diagnosticPackage = result.diagnosticPackage
        when (requestedScenario) {
            "PACKAGE.DISCLOSURE" ->
                logResult("PASS", "disclosure_reviewed=true approved=true")
            "PACKAGE.EXACT_APPROVAL" ->
                logResult(
                    "PASS",
                    "package_created=true size=${diagnosticPackage.sizeBytes}",
                )
            "PACKAGE.SAVE_SHARE" -> launchSavePicker(diagnosticPackage)
        }
    }

    @Suppress("DEPRECATION")
    private fun launchSavePicker(diagnosticPackage: DiagnosticPackage) {
        pendingPackage = diagnosticPackage
        val save = diagnosticPackage.createSaveIntent()
            .putExtra(
                Intent.EXTRA_TITLE,
                "tracebox-cert-${SystemClock.elapsedRealtime()}.tbdiag",
            )
        if (
            save.action != Intent.ACTION_CREATE_DOCUMENT ||
            save.type != "application/zip" ||
            diagnosticPackage.receipt.value != SharePackageResult.NOT_STARTED
        ) {
            logResult(
                "FAIL",
                "save_action=${save.action} receipt=${diagnosticPackage.receipt.value}",
            )
            diagnosticPackage.deleteStaging()
            pendingPackage = null
            return
        }
        Log.i(
            TAG,
            "scenario_save_picker id=$requestedScenario expected_bytes=" +
                diagnosticPackage.sizeBytes,
        )
        startActivityForResult(save, SAVE_REQUEST)
    }

    private fun handleSaveResult(resultCode: Int, data: Intent?) {
        val diagnosticPackage = pendingPackage
        val destination = data?.data
        if (resultCode != RESULT_OK || diagnosticPackage == null || destination == null) {
            logResult("FAIL", "save_destination=false")
            diagnosticPackage?.deleteStaging()
            pendingPackage = null
            return
        }
        Thread(
            {
                val saved = diagnosticPackage.save(this, destination)
                val digestMatches =
                    saved is SavePackageResult.Complete &&
                        saved.bytesWritten == diagnosticPackage.sizeBytes &&
                        destinationDigest(destination)
                            ?.contentEquals(diagnosticPackage.plaintextDigestSha256) == true
                if (!digestMatches) {
                    logResult(
                        "FAIL",
                        "save_result=${saved::class.java.simpleName} " +
                            "expected_bytes=${diagnosticPackage.sizeBytes}",
                    )
                    diagnosticPackage.deleteStaging()
                    pendingPackage = null
                    return@Thread
                }
                savedBytes = saved.bytesWritten
                savedDigestMatches = true
                val share = diagnosticPackage.shareIntent(this)
                if (
                    share == null ||
                    diagnosticPackage.receipt.value != SharePackageResult.CHOOSER_OPENED
                ) {
                    logResult(
                        "FAIL",
                        "share_intent=${share != null} receipt=${diagnosticPackage.receipt.value}",
                    )
                    diagnosticPackage.deleteStaging()
                    pendingPackage = null
                    return@Thread
                }
                Log.i(
                    TAG,
                    "scenario_share_handoff id=$requestedScenario saved_bytes=$savedBytes " +
                        "expected_bytes=${diagnosticPackage.sizeBytes} digest_match=true " +
                        "receipt=${diagnosticPackage.receipt.value}",
                )
                runOnUiThread {
                    startActivityForResult(share, SHARE_REQUEST)
                }
            },
            "tracebox-lab-save",
        ).start()
    }

    private fun handleShareResult() {
        val diagnosticPackage = pendingPackage
        val valid =
            diagnosticPackage != null &&
                savedBytes == diagnosticPackage.sizeBytes &&
                savedDigestMatches &&
                diagnosticPackage.receipt.value == SharePackageResult.CHOOSER_OPENED
        val stagingDeleted = diagnosticPackage?.deleteStaging() == true
        logResult(
            if (valid && stagingDeleted) "PASS" else "FAIL",
            "saved_bytes=$savedBytes expected_bytes=${diagnosticPackage?.sizeBytes ?: -1} " +
                "digest_match=$savedDigestMatches chooser_returned=true " +
                "receipt=${diagnosticPackage?.receipt?.value} staging_deleted=$stagingDeleted",
        )
        pendingPackage = null
    }

    private fun destinationDigest(destination: Uri): ByteArray? =
        try {
            contentResolver.openInputStream(destination)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DIGEST_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest()
            }
        } catch (_: SecurityException) {
            null
        } catch (_: java.io.IOException) {
            null
        }

    private fun executeRuntimeAction(action: String) {
        when (action) {
            "recursive_fault" ->
                armFault("recursive") { LabNativeFaults.recursiveSignal() }
            "stack_overflow" ->
                armFault("stack_overflow") { LabNativeFaults.overflowStack() }
            "anr_stall" -> armAnrWindow(stall = true)
            "anr_responsive" -> armAnrWindow(stall = false)
            "anr_exit" -> armAnrWindow(stall = true, stallMillis = ANR_EXIT_STALL_MILLIS)
            "resource_probe" -> runResourceProbe()
            "disabled_state" ->
                LabRuntime.reportDisabledAfterRestart(this, requestedScenario)
            "explicit_reenable" ->
                LabRuntime.enableAfterDelete(this, requestedScenario)
            "handler_timeout_policy" ->
                LabRuntime.runHandlerTimeoutPolicyProbe(this)
            else -> logResult("FAIL", "unsupported_action=$action")
        }
    }

    private fun armFault(kind: String, fault: () -> Nothing) {
        Thread(
            {
                val runtime = LabRuntime.install(this)
                val policy = runtime.updateProfile(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
                if (policy != PolicyUpdateResult.SUCCESS) {
                    logResult("FAIL", "fault=$kind policy=$policy")
                    return@Thread
                }
                Log.i(
                    TAG,
                    "scenario_fault_armed id=$requestedScenario fault=$kind policy=$policy",
                )
                Handler(Looper.getMainLooper()).postDelayed(
                    { fault() },
                    FAULT_ARM_DELAY_MILLIS,
                )
            },
            "tracebox-lab-fault-arm",
        ).start()
    }

    private fun armAnrWindow(
        stall: Boolean,
        stallMillis: Long = ANR_STALL_MILLIS,
    ) {
        Thread(
            {
                val runtime = LabRuntime.install(this)
                val policy = runtime.updateProfile(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
                if (policy != PolicyUpdateResult.SUCCESS) {
                    logResult("FAIL", "policy=$policy")
                    return@Thread
                }
                SystemClock.sleep(ANR_SETTLE_MILLIS)
                Log.i(
                    TAG,
                    "scenario_anr_armed id=$requestedScenario stall=$stall policy=$policy",
                )
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        if (stall) {
                            Log.i(
                                TAG,
                                "scenario_anr_stall_started id=$requestedScenario stall=true",
                            )
                            SystemClock.sleep(stallMillis)
                        }
                        Log.i(
                            TAG,
                            "scenario_anr_window_complete id=$requestedScenario stall=$stall",
                        )
                    },
                    if (stall) ANR_ARM_DELAY_MILLIS else ANR_RESPONSIVE_WINDOW_MILLIS,
                )
            },
            "tracebox-lab-anr-arm",
        ).start()
    }

    private fun runResourceProbe() {
        Thread(
            {
                val runtime = LabRuntime.install(this)
                val policy = runtime.updateProfile(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
                if (policy != PolicyUpdateResult.SUCCESS) {
                    logResult("FAIL", "resource_probe=true policy=$policy")
                    return@Thread
                }

                val mainHandler = Handler(Looper.getMainLooper())
                val captureInvocations = AtomicInteger()
                val captureOverlapSamples = AtomicInteger()
                val capturesPerHeartbeat =
                    RESOURCE_CAPTURE_RECORDS / RESOURCE_HEARTBEAT_SAMPLES
                check(
                    RESOURCE_CAPTURE_RECORDS % RESOURCE_HEARTBEAT_SAMPLES == 0 &&
                        capturesPerHeartbeat > 0,
                )
                var maximumCaptureOverlapPauseNanos = 0L
                Log.i(
                    TAG,
                    "scenario_resource_capture_started id=$requestedScenario " +
                        "heartbeat_samples=$RESOURCE_HEARTBEAT_SAMPLES " +
                        "capture_records=$RESOURCE_CAPTURE_RECORDS",
                )
                repeat(RESOURCE_HEARTBEAT_SAMPLES) { sample ->
                    val observedPause = LongArray(1)
                    val completed = CountDownLatch(1)
                    val capturesBeforeHeartbeat = captureInvocations.get()
                    val postedAt = SystemClock.elapsedRealtimeNanos()
                    mainHandler.postDelayed(
                        {
                            val elapsed = SystemClock.elapsedRealtimeNanos() - postedAt
                            observedPause[0] =
                                (
                                    elapsed -
                                        TimeUnit.MILLISECONDS.toNanos(
                                            RESOURCE_HEARTBEAT_INTERVAL_MILLIS,
                                        )
                                ).coerceAtLeast(0L)
                            if (captureInvocations.get() > capturesBeforeHeartbeat) {
                                captureOverlapSamples.incrementAndGet()
                            }
                            completed.countDown()
                        },
                        RESOURCE_HEARTBEAT_INTERVAL_MILLIS,
                    )
                    repeat(capturesPerHeartbeat) { captureWithinSample ->
                        val captureIndex =
                            sample * capturesPerHeartbeat + captureWithinSample
                        captureInvocations.incrementAndGet()
                        GeneratedDiagnostics.breadcrumb(
                            runtime.diagnostics,
                            code = (captureIndex % 256).toUInt(),
                            monotonic_time_ns = SystemClock.elapsedRealtimeNanos().toULong(),
                        )
                    }
                    if (!completed.await(RESOURCE_HEARTBEAT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        logResult(
                            "FAIL",
                            "resource_probe=true capture_overlap_heartbeat_timeout=true " +
                                "capture_overlap_sample=$sample",
                        )
                        return@Thread
                    }
                    maximumCaptureOverlapPauseNanos =
                        maxOf(maximumCaptureOverlapPauseNanos, observedPause[0])
                }
                val maximumCaptureOverlapPauseMillis =
                    TimeUnit.NANOSECONDS
                        .toMillis(maximumCaptureOverlapPauseNanos)
                        .coerceAtLeast(0L)
                if (
                    captureOverlapSamples.get() != RESOURCE_HEARTBEAT_SAMPLES ||
                    captureInvocations.get() != RESOURCE_CAPTURE_RECORDS
                ) {
                    logResult(
                        "FAIL",
                        "resource_probe=true " +
                            "capture_overlap_heartbeat_samples=${captureOverlapSamples.get()} " +
                            "capture_records=${captureInvocations.get()}",
                    )
                    return@Thread
                }
                if (
                    maximumCaptureOverlapPauseMillis >
                    RESOURCE_CAPTURE_OVERLAP_MAXIMUM_PAUSE_MILLIS
                ) {
                    logResult(
                        "FAIL",
                        "resource_probe=true " +
                            "capture_overlap_target_pause_ms=" +
                            maximumCaptureOverlapPauseMillis,
                    )
                    return@Thread
                }
                logResult(
                    "PASS",
                    "resource_probe=true " +
                        "capture_overlap_heartbeat_samples=${captureOverlapSamples.get()} " +
                        "capture_overlap_target_pause_ms=$maximumCaptureOverlapPauseMillis " +
                        "capture_records=${captureInvocations.get()}",
                )
            },
            "tracebox-lab-resource-probe",
        ).start()
    }

    private fun logResult(outcome: String, detail: String) {
        Log.i(TAG, "scenario_result id=$requestedScenario outcome=$outcome $detail")
        runOnUiThread {
            (findViewById<android.view.View>(android.R.id.content) as? android.view.ViewGroup)
                ?.let { root ->
                    (root.getChildAt(0) as? TextView)?.text =
                        "$requestedScenario: $outcome"
                }
        }
    }

    private companion object {
        const val ACTION_EXTRA = "tracebox.action"
        const val SCENARIO_EXTRA = "tracebox.scenario_id"
        const val APPROVAL_REQUEST = 41
        const val SAVE_REQUEST = 42
        const val SHARE_REQUEST = 43
        const val DIGEST_BUFFER_BYTES = 16 * 1024
        const val ANR_SETTLE_MILLIS = 10_500L
        const val FAULT_ARM_DELAY_MILLIS = 750L
        const val ANR_ARM_DELAY_MILLIS = 1_000L
        const val ANR_STALL_MILLIS = 8_000L
        const val ANR_EXIT_STALL_MILLIS = 60_000L
        const val ANR_RESPONSIVE_WINDOW_MILLIS = 8_000L
        const val RESOURCE_HEARTBEAT_SAMPLES = 16
        const val RESOURCE_HEARTBEAT_INTERVAL_MILLIS = 16L
        const val RESOURCE_HEARTBEAT_TIMEOUT_MILLIS = 2_000L
        const val RESOURCE_CAPTURE_OVERLAP_MAXIMUM_PAUSE_MILLIS = 2_000L
        const val RESOURCE_CAPTURE_RECORDS = 32
        const val TAG = "TraceboxLab"
        val SUPPORTED_SCENARIOS = setOf(
            "PACKAGE.DISCLOSURE",
            "PACKAGE.EXACT_APPROVAL",
            "PACKAGE.SAVE_SHARE",
        )
        val RUNTIME_SCENARIOS = setOf(
            "HANDLER.TIMEOUT",
            "FAULT.RECURSIVE",
            "FAULT.STACK_OVERFLOW",
            "ANR.CANDIDATE",
            "ANR.RESPONSIVE",
            "ANR.TIMEOUT",
            "ANR.LIFECYCLE_SUPPRESSION",
            "EXIT.RESTART_RECONCILIATION",
            "RESOURCE.BASELINE",
            "DELETE.ALL_RESTART",
            "DELETE.NO_ACCESSIBLE_DATA",
        )
    }
}
