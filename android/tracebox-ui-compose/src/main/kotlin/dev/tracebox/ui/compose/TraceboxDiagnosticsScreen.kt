package dev.tracebox.ui.compose

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.tracebox.api.SavePackageResult
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.TraceboxPolicy
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reusable diagnostics screen; hosts may embed it directly or launch [TraceboxDiagnosticsActivity]. */
@Composable
fun TraceboxDiagnosticsScreen(
    handle: TraceboxHandle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val policy by handle.policy.collectAsStateWithLifecycle()
    val readiness by handle.readiness.collectAsStateWithLifecycle()
    val health by handle.health.collectAsStateWithLifecycle()
    val summary by handle.summary.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var currentPackage by remember { mutableStateOf<DiagnosticPackage?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    fun runOperation(operation: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            message = try {
                operation()
            } catch (_: RuntimeException) {
                "Operation failed. Tracebox remains fail-closed."
            } finally {
                busy = false
            }
        }
    }

    fun applyPolicy(next: TraceboxPolicy) = runOperation {
        currentPackage?.deleteStaging()
        currentPackage = null
        when (withContext(Dispatchers.IO) { handle.updatePolicy(next) }) {
            PolicyUpdateResult.SUCCESS -> "Runtime controls updated."
            PolicyUpdateResult.LOCAL_ONLY_RESTRICTED -> "Restrictions are durable locally; another process is still stopping."
            PolicyUpdateResult.PARTIAL -> "The policy is durable, but one runtime participant is degraded."
            PolicyUpdateResult.FAILED -> "The policy was not changed."
        }
    }

    val approvalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        runOperation {
            val token = result.data
                .takeIf { result.resultCode == Activity.RESULT_OK }
                ?.let(ApprovalToken::fromActivityResult)
                ?: return@runOperation "Package approval was cancelled."
            when (val created = withContext(Dispatchers.IO) {
                handle.packages.create(PackageRequest.STANDARD, token)
            }) {
                is PackageResult.Created -> {
                    currentPackage?.deleteStaging()
                    currentPackage = created.diagnosticPackage
                    "Diagnostic package is ready to save or share."
                }
                PackageResult.NotReady -> "Diagnostics are not ready for packaging."
                PackageResult.Rejected -> "The approval did not match the finalized package."
            }
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
                    ?: return@runOperation "Create a package first."
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
        Text("Tracebox diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Offline diagnostics stay on this device until you explicitly save or share a reviewed package.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusCard(
            readiness = readiness.name.lowercase(Locale.ROOT),
            health = health.name.lowercase(Locale.ROOT),
            count = summary.recordedValueCount,
        )
        ControlCard("Runtime") {
            ToggleRow("Diagnostics", policy.enabled, !busy) {
                applyPolicy(policy.copy(enabled = it))
            }
            CycleRow(
                label = "Minimum log level",
                value = policy.minimumLogLevel.name,
                enabled = !busy && policy.enabled,
            ) {
                val levels = LogLevel.entries
                applyPolicy(policy.copy(minimumLogLevel = levels[(policy.minimumLogLevel.ordinal + 1) % levels.size]))
            }
            ToggleRow("Mirror redacted logs to Logcat", policy.mirrorToLogcat, !busy && policy.enabled) {
                applyPolicy(policy.copy(mirrorToLogcat = it))
            }
            ToggleRow("Performance timings", policy.performanceLoggingEnabled, !busy && policy.enabled) {
                applyPolicy(policy.copy(performanceLoggingEnabled = it))
            }
            CycleRow(
                label = "Minimum performance duration",
                value = formatDuration(policy.minimumPerformanceDurationNanos),
                enabled = !busy && policy.enabled && policy.performanceLoggingEnabled,
            ) {
                val thresholds = longArrayOf(0L, 1_000_000L, 10_000_000L, 100_000_000L)
                val current = thresholds.indexOf(policy.minimumPerformanceDurationNanos)
                applyPolicy(policy.copy(minimumPerformanceDurationNanos = thresholds[(current + 1).coerceAtLeast(0) % thresholds.size]))
            }
        }
        ControlCard("Capture sources") {
            CaptureKind.entries.forEach { kind ->
                ToggleRow(captureLabel(kind), kind in policy.captures, !busy && policy.enabled) { enabled ->
                    val captures = policy.captures.toMutableSet().apply {
                        if (enabled) add(kind) else remove(kind)
                    }
                    applyPolicy(policy.copy(captures = captures))
                }
            }
        }
        ControlCard("Export") {
            Button(
                onClick = {
                    runOperation {
                        when (val prepared = withContext(Dispatchers.IO) {
                            handle.packages.prepare(PackageRequest.STANDARD)
                        }) {
                            is PackagePreparationResult.Ready -> {
                                val intent = handle.packages.approvalIntent(context, prepared.preview)
                                    ?: return@runOperation "The review activity is unavailable."
                                approvalLauncher.launch(intent)
                                "Review the exact package contents before approving."
                            }
                            PackagePreparationResult.NotReady -> "Diagnostics are not ready for packaging."
                            PackagePreparationResult.Rejected -> "Package preparation was rejected."
                        }
                    }
                },
                enabled = !busy && policy.enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Review and create package") }
            OutlinedButton(
                onClick = {
                    val intent = currentPackage?.createSaveIntent()
                    if (intent == null) message = "Create a package first." else saveLauncher.launch(intent)
                },
                enabled = !busy && currentPackage != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save package") }
            OutlinedButton(
                onClick = {
                    val intent = currentPackage?.shareIntent(context)
                    if (intent == null) message = "Create a package first." else context.startActivity(intent)
                },
                enabled = !busy && currentPackage != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share package") }
        }
        OutlinedButton(
            onClick = { showDeleteConfirmation = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete all Tracebox data") }
        if (busy) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(24.dp))
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete diagnostics?") },
            text = { Text("This removes Tracebox records and staged packages from this app.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    runOperation {
                        currentPackage?.deleteStaging()
                        currentPackage = null
                        when (withContext(Dispatchers.IO) { handle.delete(DeleteRequest.ALL_TRACEBOX_DATA) }) {
                            DeleteReport.COMPLETE -> "All Tracebox data was deleted."
                            DeleteReport.PENDING_FAILURE -> "Some handler-owned data remains and will be retried."
                            DeleteReport.REJECTED -> "Deletion could not start."
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusCard(readiness: String, health: String, count: Long) {
    ControlCard("Status") {
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
