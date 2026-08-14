package dev.tracebox.ui.compose

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tracebox.api.ApprovalToken
import dev.tracebox.api.CaptureKind
import dev.tracebox.api.DeleteReport
import dev.tracebox.api.DeleteRequest
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.LogLevel
import dev.tracebox.api.PackagePreparationResult
import dev.tracebox.api.PackageRequest
import dev.tracebox.api.PackageResult
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.Readiness
import dev.tracebox.api.SavePackageResult
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.TraceboxHealth
import dev.tracebox.api.TraceboxPolicy
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Simple-first diagnostics UI. Casual users get one reviewed send/share flow; host-selected
 * technical controls stay behind an advanced disclosure.
 */
@Composable
fun TraceboxDiagnosticsScreen(
    handle: TraceboxHandle,
    modifier: Modifier = Modifier,
    configuration: TraceboxDiagnosticsUiConfiguration = TraceboxDiagnosticsUiConfiguration(),
    uploader: TraceboxDiagnosticUploader? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val policy by handle.policy.collectAsStateWithLifecycle()
    val readiness by handle.readiness.collectAsStateWithLifecycle()
    val health by handle.health.collectAsStateWithLifecycle()
    val summary by handle.summary.collectAsStateWithLifecycle()
    val strings = configuration.strings
    val advanced = configuration.advancedControls
    val actions = configuration.packageActions
    val primaryAction = resolvePrimaryAction(
        configured = configuration.primaryAction,
        actions = actions,
        uploaderAvailable = uploader != null,
    )
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var currentPackage by remember { mutableStateOf<DiagnosticPackage?>(null) }
    // Preserve the fail-closed delivery choice if Android recreates this screen while the
    // disclosure activity is open. Enums are directly saveable by Compose.
    var pendingPrimaryAction by rememberSaveable {
        mutableStateOf(ResolvedPrimaryAction.REVIEW_ONLY)
    }
    var advancedExpanded by rememberSaveable { mutableStateOf(advanced.initiallyExpanded) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    fun runOperation(operation: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            message = try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                strings.operationFailure
            } finally {
                busy = false
            }
        }
    }

    suspend fun uploadPackage(diagnosticPackage: DiagnosticPackage): String {
        val transport = uploader ?: return strings.uploadUnavailable
        return when (withContext(Dispatchers.IO) {
            transport.upload(TraceboxUploadRequest(diagnosticPackage))
        }) {
            is TraceboxUploadResult.Uploaded -> strings.uploadSuccess
            TraceboxUploadResult.RetryableFailure -> strings.uploadRetryableFailure
            TraceboxUploadResult.Rejected -> strings.uploadRejected
            TraceboxUploadResult.Failed -> strings.uploadFailure
        }
    }

    fun applyPolicy(next: TraceboxPolicy) = runOperation {
        currentPackage?.deleteStaging()
        currentPackage = null
        when (withContext(Dispatchers.IO) { handle.updatePolicy(next) }) {
            PolicyUpdateResult.SUCCESS -> "Runtime controls updated."
            PolicyUpdateResult.LOCAL_ONLY_RESTRICTED ->
                "Restrictions are durable locally; another process is still stopping."
            PolicyUpdateResult.PARTIAL ->
                "The policy is durable, but one runtime participant is degraded."
            PolicyUpdateResult.FAILED -> "The policy was not changed."
        }
    }

    val approvalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val delivery = pendingPrimaryAction
        pendingPrimaryAction = ResolvedPrimaryAction.REVIEW_ONLY
        runOperation {
            val token = result.data
                .takeIf { result.resultCode == Activity.RESULT_OK }
                ?.let(ApprovalToken::fromActivityResult)
                ?: return@runOperation "Package review was cancelled."
            val diagnosticPackage = when (val created = withContext(Dispatchers.IO) {
                handle.packages.create(PackageRequest.STANDARD, token)
            }) {
                is PackageResult.Created -> created.diagnosticPackage
                PackageResult.NotReady -> return@runOperation "Diagnostics are not ready for packaging."
                PackageResult.Rejected -> return@runOperation "The approval did not match the reviewed package."
            }
            currentPackage?.deleteStaging()
            currentPackage = diagnosticPackage
            when (delivery) {
                ResolvedPrimaryAction.UPLOAD -> uploadPackage(diagnosticPackage)
                ResolvedPrimaryAction.SHARE -> {
                    val intent = diagnosticPackage.shareIntent(context)
                        ?: return@runOperation "The package could not be shared."
                    context.startActivity(intent)
                    strings.packageReady
                }
                ResolvedPrimaryAction.REVIEW_ONLY -> strings.packageReady
            }
        }
    }

    fun requestReview(delivery: ResolvedPrimaryAction) = runOperation {
        when (val prepared = withContext(Dispatchers.IO) {
            handle.packages.prepare(PackageRequest.STANDARD)
        }) {
            is PackagePreparationResult.Ready -> {
                val intent = handle.packages.approvalIntent(context, prepared.preview)
                    ?: return@runOperation "The review activity is unavailable."
                pendingPrimaryAction = delivery
                approvalLauncher.launch(intent)
                strings.privacyNotice
            }
            PackagePreparationResult.NotReady -> "Diagnostics are not ready for packaging."
            PackagePreparationResult.Rejected -> "Package preparation was rejected."
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val destination = result.data?.data
        if (destination == null) {
            message = "Save was cancelled."
        } else {
            runOperation {
                val diagnosticPackage = currentPackage
                    ?: return@runOperation "Review diagnostics first."
                when (val saved = withContext(Dispatchers.IO) {
                    diagnosticPackage.save(context, destination)
                }) {
                    is SavePackageResult.Complete -> "Saved ${saved.bytesWritten} bytes."
                    is SavePackageResult.PartialCopyWarning ->
                        "The destination retained ${saved.bytesWritten} bytes from a partial copy."
                    is SavePackageResult.Failed -> "Saving the package failed."
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (configuration.showHeading) {
            Text(strings.title, style = MaterialTheme.typography.headlineSmall)
            Text(strings.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (configuration.showCasualStatus) {
            CasualStatusCard(
                ready = readiness == Readiness.DURABLE && health == TraceboxHealth.READY,
                readyText = strings.statusReady,
                unavailableText = strings.statusUnavailable,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(strings.supportTitle, style = MaterialTheme.typography.titleLarge)
                Text(strings.supportDescription, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    strings.privacyNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Button(
                    onClick = { requestReview(primaryAction) },
                    enabled = !busy && policy.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(primaryButtonLabel(primaryAction, strings))
                }
            }
        }

        currentPackage?.let { diagnosticPackage ->
            if ((actions.upload && uploader != null) || actions.share || actions.save) {
                ControlCard(strings.moreSharingOptions) {
                    if (actions.upload && uploader != null) {
                        OutlinedButton(
                            onClick = { runOperation { uploadPackage(diagnosticPackage) } },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(strings.sendPackage) }
                    }
                    if (actions.share) {
                        OutlinedButton(
                            onClick = {
                                val intent = diagnosticPackage.shareIntent(context)
                                if (intent == null) {
                                    message = "The package could not be shared."
                                } else {
                                    context.startActivity(intent)
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(strings.sharePackage) }
                    }
                    if (actions.save) {
                        OutlinedButton(
                            onClick = { saveLauncher.launch(diagnosticPackage.createSaveIntent()) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(strings.savePackage) }
                    }
                }
            }
        }

        if (advanced.visible) {
            OutlinedButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (advancedExpanded) strings.hideAdvancedOptions else strings.advancedOptions)
            }
            AnimatedVisibility(advancedExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (advanced.statusDetails) {
                        StatusCard(
                            title = strings.statusTitle,
                            readiness = readiness.name.lowercase(Locale.ROOT),
                            health = health.name.lowercase(Locale.ROOT),
                            count = summary.recordedValueCount,
                        )
                    }
                    if (advanced.hasRuntimeControls()) {
                        ControlCard(strings.runtimeTitle) {
                            if (advanced.diagnosticsEnabled) {
                                ToggleRow(strings.diagnosticsEnabled, policy.enabled, !busy) {
                                    applyPolicy(policy.copy(enabled = it))
                                }
                            }
                            if (advanced.logLevels.isNotEmpty()) {
                                CycleRow(
                                    label = strings.minimumLogLevel,
                                    value = policy.minimumLogLevel.name,
                                    enabled = !busy && policy.enabled,
                                ) {
                                    applyPolicy(policy.copy(
                                        minimumLogLevel = nextValue(
                                            policy.minimumLogLevel,
                                            advanced.logLevels,
                                        ),
                                    ))
                                }
                            }
                            if (advanced.logcatMirroring) {
                                ToggleRow(
                                    strings.mirrorToLogcat,
                                    policy.mirrorToLogcat,
                                    !busy && policy.enabled,
                                ) { applyPolicy(policy.copy(mirrorToLogcat = it)) }
                            }
                            if (advanced.performanceLogging) {
                                ToggleRow(
                                    strings.performanceTimings,
                                    policy.performanceLoggingEnabled,
                                    !busy && policy.enabled,
                                ) { applyPolicy(policy.copy(performanceLoggingEnabled = it)) }
                                if (advanced.performanceThresholdsNanos.isNotEmpty()) {
                                    CycleRow(
                                        label = strings.minimumPerformanceDuration,
                                        value = formatDuration(policy.minimumPerformanceDurationNanos),
                                        enabled = !busy && policy.enabled &&
                                            policy.performanceLoggingEnabled,
                                    ) {
                                        applyPolicy(policy.copy(
                                            minimumPerformanceDurationNanos = nextValue(
                                                policy.minimumPerformanceDurationNanos,
                                                advanced.performanceThresholdsNanos,
                                            ),
                                        ))
                                    }
                                }
                            }
                        }
                    }
                    if (advanced.captureKinds.isNotEmpty()) {
                        ControlCard(strings.captureSourcesTitle) {
                            CaptureKind.entries
                                .filter(advanced.captureKinds::contains)
                                .forEach { kind ->
                                    ToggleRow(
                                        captureLabel(kind),
                                        kind in policy.captures,
                                        !busy && policy.enabled,
                                    ) { enabled ->
                                        val captures = policy.captures.toMutableSet().apply {
                                            if (enabled) add(kind) else remove(kind)
                                        }
                                        applyPolicy(policy.copy(captures = captures))
                                    }
                                }
                        }
                    }
                    if (advanced.resetToDefaults) {
                        OutlinedButton(
                            onClick = { applyPolicy(configuration.defaultPolicy) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(strings.restoreDefaults) }
                    }
                    if (actions.deleteAllData) {
                        TextButton(
                            onClick = { showDeleteConfirmation = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(strings.deleteAllData) }
                    }
                }
            }
        } else if (actions.deleteAllData) {
            TextButton(
                onClick = { showDeleteConfirmation = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(strings.deleteAllData) }
        }

        if (busy) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(24.dp))
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(strings.deleteDialogTitle) },
            text = { Text(strings.deleteDialogBody) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    runOperation {
                        currentPackage?.deleteStaging()
                        currentPackage = null
                        when (withContext(Dispatchers.IO) {
                            handle.delete(DeleteRequest.ALL_TRACEBOX_DATA)
                        }) {
                            DeleteReport.COMPLETE -> "All diagnostic data was deleted."
                            DeleteReport.PENDING_FAILURE ->
                                "Some handler-owned data remains and will be retried."
                            DeleteReport.REJECTED -> "Deletion could not start."
                        }
                    }
                }) { Text(strings.deleteConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}

@Composable
private fun CasualStatusCard(
    ready: Boolean,
    readyText: String,
    unavailableText: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = if (ready) readyText else unavailableText,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun StatusCard(title: String, readiness: String, health: String, count: Long) {
    ControlCard(title) {
        Text("Readiness: $readiness")
        Text("Health: $health")
        Text("Recorded in this process session: $count")
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun CycleRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick, enabled = enabled) { Text(value) }
    }
}

private fun TraceboxAdvancedControls.hasRuntimeControls(): Boolean =
    diagnosticsEnabled || logLevels.isNotEmpty() || logcatMirroring || performanceLogging

private fun primaryButtonLabel(
    action: ResolvedPrimaryAction,
    strings: TraceboxDiagnosticsUiStrings,
): String = when (action) {
    ResolvedPrimaryAction.UPLOAD -> strings.reviewAndUpload
    ResolvedPrimaryAction.SHARE -> strings.reviewAndShare
    ResolvedPrimaryAction.REVIEW_ONLY -> strings.reviewOnly
}

private fun <T> nextValue(current: T, values: List<T>): T {
    require(values.isNotEmpty())
    val currentIndex = values.indexOf(current)
    return values[if (currentIndex < 0) 0 else (currentIndex + 1) % values.size]
}

private fun captureLabel(kind: CaptureKind): String = when (kind) {
    CaptureKind.JVM_CRASH -> "JVM crashes"
    CaptureKind.HANDLED_EXCEPTION -> "Handled exceptions"
    CaptureKind.ANR -> "ANR watchdog"
    CaptureKind.OS_EXIT -> "Android exit history"
    CaptureKind.NATIVE_CRASH -> "Native crashes (optional module)"
    CaptureKind.RUST_PANIC -> "Rust panics (optional module)"
}

private fun formatDuration(nanos: Long): String = when (nanos) {
    0L -> "Any duration"
    in 1 until 1_000_000L -> "$nanos ns"
    else -> "${nanos / 1_000_000L} ms"
}
