package dev.tracebox.ui.compose

import android.app.Activity
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val policy by handle.policy.collectAsStateWithLifecycle()
    val readiness by handle.readiness.collectAsStateWithLifecycle()
    val health by handle.health.collectAsStateWithLifecycle()
    val summary by handle.summary.collectAsStateWithLifecycle()
    val strings = configuration.strings
    fun uiString(resourceId: Int, vararg arguments: Any): String =
        resources.getString(resourceId, *arguments)
    fun uiQuantity(resourceId: Int, quantity: Long): String = resources.getQuantityString(
        resourceId,
        quantity.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
        quantity,
    )
    val advanced = configuration.advancedControls
    val actions = configuration.packageActions
    val primaryAction = resolvePrimaryAction(
        configured = configuration.primaryAction,
        actions = actions,
        uploaderAvailable = uploader != null,
    )
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val packageOwner = remember(handle) { DiagnosticPackageOwner() }
    var currentPackage by remember(handle) { mutableStateOf<DiagnosticPackage?>(null) }
    // Preserve the fail-closed delivery choice if Android recreates this screen while the
    // disclosure activity is open. Enums are directly saveable by Compose.
    var pendingPrimaryAction by rememberSaveable(handle) {
        mutableStateOf(ResolvedPrimaryAction.REVIEW_ONLY)
    }
    var advancedExpanded by rememberSaveable { mutableStateOf(advanced.initiallyExpanded) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(packageOwner) {
        onDispose { packageOwner.close() }
    }

    fun replaceCurrentPackage(replacement: DiagnosticPackage) {
        packageOwner.replace(replacement)
        currentPackage = replacement
    }

    fun retireCurrentPackage(expected: DiagnosticPackage? = null) {
        packageOwner.retire(expected)
        if (expected == null || currentPackage === expected) currentPackage = null
    }

    fun runOperation(operation: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            message = try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                uiString(strings.operationFailure)
            } finally {
                busy = false
            }
        }
    }

    suspend fun uploadPackage(diagnosticPackage: DiagnosticPackage): String {
        val transport = uploader ?: return uiString(strings.uploadUnavailable)
        return when (withContext(Dispatchers.IO) {
            transport.upload(TraceboxUploadRequest(diagnosticPackage))
        }) {
            is TraceboxUploadResult.Uploaded -> {
                retireCurrentPackage(diagnosticPackage)
                uiString(strings.uploadSuccess)
            }
            TraceboxUploadResult.RetryableFailure -> uiString(strings.uploadRetryableFailure)
            TraceboxUploadResult.Rejected -> uiString(strings.uploadRejected)
            TraceboxUploadResult.Failed -> uiString(strings.uploadFailure)
        }
    }

    fun applyPolicy(next: TraceboxPolicy) = runOperation {
        retireCurrentPackage()
        when (withContext(Dispatchers.IO) { handle.updatePolicy(next) }) {
            PolicyUpdateResult.SUCCESS -> uiString(strings.policyUpdated)
            PolicyUpdateResult.LOCAL_ONLY_RESTRICTED -> uiString(strings.policyRestrictedLocally)
            PolicyUpdateResult.PARTIAL -> uiString(strings.policyPartiallyApplied)
            PolicyUpdateResult.FAILED -> uiString(strings.policyFailed)
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
                ?: return@runOperation uiString(strings.packageReviewCancelled)
            val diagnosticPackage = when (val created = withContext(Dispatchers.IO) {
                handle.packages.create(PackageRequest.STANDARD, token)
            }) {
                is PackageResult.Created -> created.diagnosticPackage
                PackageResult.NotReady -> return@runOperation uiString(strings.diagnosticsNotReady)
                PackageResult.Rejected -> return@runOperation uiString(strings.packageApprovalMismatch)
            }
            replaceCurrentPackage(diagnosticPackage)
            when (delivery) {
                ResolvedPrimaryAction.UPLOAD -> uploadPackage(diagnosticPackage)
                ResolvedPrimaryAction.SHARE -> {
                    val intent = diagnosticPackage.shareIntent(context)
                        ?: return@runOperation uiString(strings.packageShareFailed)
                    context.startActivity(intent)
                    uiString(strings.packageReady)
                }
                ResolvedPrimaryAction.REVIEW_ONLY -> uiString(strings.packageReady)
            }
        }
    }

    fun requestReview(delivery: ResolvedPrimaryAction) = runOperation {
        when (val prepared = withContext(Dispatchers.IO) {
            handle.packages.prepare(PackageRequest.STANDARD)
        }) {
            is PackagePreparationResult.Ready -> {
                val intent = handle.packages.approvalIntent(context, prepared.preview)
                    ?: return@runOperation uiString(strings.reviewUnavailable)
                pendingPrimaryAction = delivery
                approvalLauncher.launch(intent)
                uiString(strings.privacyNotice)
            }
            PackagePreparationResult.NotReady -> uiString(strings.diagnosticsNotReady)
            PackagePreparationResult.Rejected -> uiString(strings.packagePreparationRejected)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val destination = result.data?.data
        if (destination == null) {
            message = uiString(strings.saveCancelled)
        } else {
            runOperation {
                val diagnosticPackage = currentPackage
                    ?: return@runOperation uiString(strings.reviewFirst)
                when (val saved = withContext(Dispatchers.IO) {
                    diagnosticPackage.save(context, destination)
                }) {
                    is SavePackageResult.Complete -> {
                        retireCurrentPackage(diagnosticPackage)
                        uiQuantity(strings.savedBytes, saved.bytesWritten)
                    }
                    is SavePackageResult.PartialCopyWarning -> {
                        retireCurrentPackage(diagnosticPackage)
                        uiQuantity(strings.partialCopyBytes, saved.bytesWritten)
                    }
                    is SavePackageResult.Failed -> uiString(strings.saveFailed)
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
            Text(
                uiString(strings.title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(uiString(strings.description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (configuration.showCasualStatus) {
            CasualStatusCard(
                ready = readiness == Readiness.DURABLE && health == TraceboxHealth.READY,
                readyText = uiString(strings.statusReady),
                unavailableText = uiString(strings.statusUnavailable),
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
                Text(
                    uiString(strings.supportTitle),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    uiString(strings.supportDescription),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    uiString(strings.privacyNotice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Button(
                    onClick = { requestReview(primaryAction) },
                    enabled = !busy && policy.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(uiString(primaryButtonLabel(primaryAction, strings)))
                }
            }
        }

        currentPackage?.let { diagnosticPackage ->
            if ((actions.upload && uploader != null) || actions.share || actions.save) {
                ControlCard(uiString(strings.moreSharingOptions)) {
                    if (actions.upload && uploader != null) {
                        OutlinedButton(
                            onClick = { runOperation { uploadPackage(diagnosticPackage) } },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(uiString(strings.sendPackage)) }
                    }
                    if (actions.share) {
                        OutlinedButton(
                            onClick = {
                                val intent = diagnosticPackage.shareIntent(context)
                                if (intent == null) {
                                    message = uiString(strings.packageShareFailed)
                                } else {
                                    context.startActivity(intent)
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(uiString(strings.sharePackage)) }
                    }
                    if (actions.save) {
                        OutlinedButton(
                            onClick = { saveLauncher.launch(diagnosticPackage.createSaveIntent()) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(uiString(strings.savePackage)) }
                    }
                }
            }
        }

        if (advanced.visible) {
            OutlinedButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        stateDescription = uiString(
                            if (advancedExpanded) {
                                strings.sectionExpanded
                            } else {
                                strings.sectionCollapsed
                            },
                        )
                    },
            ) {
                Text(
                    uiString(
                        if (advancedExpanded) strings.hideAdvancedOptions else strings.advancedOptions,
                    ),
                )
            }
            AnimatedVisibility(advancedExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (advanced.statusDetails) {
                        StatusCard(
                            title = uiString(strings.statusTitle),
                            readiness = uiString(readinessLabel(readiness, strings)),
                            health = uiString(healthLabel(health, strings)),
                            count = summary.recordedValueCount,
                            strings = strings,
                        )
                    }
                    if (advanced.hasRuntimeControls()) {
                        ControlCard(uiString(strings.runtimeTitle)) {
                            if (advanced.diagnosticsEnabled) {
                                ToggleRow(
                                    label = uiString(strings.diagnosticsEnabled),
                                    checked = policy.enabled,
                                    enabled = !busy,
                                    onText = uiString(strings.controlOn),
                                    offText = uiString(strings.controlOff),
                                ) {
                                    applyPolicy(policy.copy(enabled = it))
                                }
                            }
                            if (advanced.logLevels.isNotEmpty()) {
                                CycleRow(
                                    label = uiString(strings.minimumLogLevel),
                                    value = uiString(logLevelLabel(policy.minimumLogLevel, strings)),
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
                                    label = uiString(strings.mirrorToLogcat),
                                    checked = policy.mirrorToLogcat,
                                    enabled = !busy && policy.enabled,
                                    onText = uiString(strings.controlOn),
                                    offText = uiString(strings.controlOff),
                                ) { applyPolicy(policy.copy(mirrorToLogcat = it)) }
                            }
                            if (advanced.performanceLogging) {
                                ToggleRow(
                                    label = uiString(strings.performanceTimings),
                                    checked = policy.performanceLoggingEnabled,
                                    enabled = !busy && policy.enabled,
                                    onText = uiString(strings.controlOn),
                                    offText = uiString(strings.controlOff),
                                ) { applyPolicy(policy.copy(performanceLoggingEnabled = it)) }
                                if (advanced.performanceThresholdsNanos.isNotEmpty()) {
                                    CycleRow(
                                        label = uiString(strings.minimumPerformanceDuration),
                                        value = formatDuration(
                                            policy.minimumPerformanceDurationNanos,
                                            strings,
                                            resources,
                                        ),
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
                        ControlCard(uiString(strings.captureSourcesTitle)) {
                            CaptureKind.entries
                                .filter(advanced.captureKinds::contains)
                                .forEach { kind ->
                                    ToggleRow(
                                        label = uiString(captureLabel(kind, strings)),
                                        checked = kind in policy.captures,
                                        enabled = !busy && policy.enabled,
                                        onText = uiString(strings.controlOn),
                                        offText = uiString(strings.controlOff),
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
                        ) { Text(uiString(strings.restoreDefaults)) }
                    }
                    if (actions.deleteAllData) {
                        TextButton(
                            onClick = { showDeleteConfirmation = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(uiString(strings.deleteAllData)) }
                    }
                }
            }
        } else if (actions.deleteAllData) {
            TextButton(
                onClick = { showDeleteConfirmation = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(uiString(strings.deleteAllData)) }
        }

        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .semantics { contentDescription = uiString(strings.operationInProgress) },
            )
        }
        message?.let {
            Text(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(
                    uiString(strings.deleteDialogTitle),
                    modifier = Modifier.semantics { heading() },
                )
            },
            text = { Text(uiString(strings.deleteDialogBody)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    runOperation {
                        retireCurrentPackage()
                        when (withContext(Dispatchers.IO) {
                            handle.delete(DeleteRequest.ALL_TRACEBOX_DATA)
                        }) {
                            DeleteReport.COMPLETE -> uiString(strings.deleteComplete)
                            DeleteReport.PENDING_FAILURE -> uiString(strings.deletePending)
                            DeleteReport.REJECTED -> uiString(strings.deleteRejected)
                        }
                    }
                }) { Text(uiString(strings.deleteConfirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(uiString(strings.cancel))
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
            modifier = Modifier
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun StatusCard(
    title: String,
    readiness: String,
    health: String,
    count: Long,
    strings: TraceboxDiagnosticsUiStrings,
) {
    ControlCard(title) {
        Text(stringResource(strings.readinessValue, readiness))
        Text(stringResource(strings.healthValue, health))
        Text(stringResource(strings.recordedCount, count))
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onText: String,
    offText: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (checked) onText else offText
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun CycleRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label)
        Text(
            text = value,
            modifier = Modifier.align(Alignment.End),
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun TraceboxAdvancedControls.hasRuntimeControls(): Boolean =
    diagnosticsEnabled || logLevels.isNotEmpty() || logcatMirroring || performanceLogging

private fun primaryButtonLabel(
    action: ResolvedPrimaryAction,
    strings: TraceboxDiagnosticsUiStrings,
): Int = when (action) {
    ResolvedPrimaryAction.UPLOAD -> strings.reviewAndUpload
    ResolvedPrimaryAction.SHARE -> strings.reviewAndShare
    ResolvedPrimaryAction.REVIEW_ONLY -> strings.reviewOnly
}

private fun <T> nextValue(current: T, values: List<T>): T {
    require(values.isNotEmpty())
    val currentIndex = values.indexOf(current)
    return values[if (currentIndex < 0) 0 else (currentIndex + 1) % values.size]
}

private fun readinessLabel(
    readiness: Readiness,
    strings: TraceboxDiagnosticsUiStrings,
): Int = when (readiness) {
    Readiness.VOLATILE_CAPTURE -> strings.readinessVolatileCapture
    Readiness.DURABLE -> strings.readinessDurable
    Readiness.DEGRADED -> strings.readinessDegraded
    Readiness.CLOSED -> strings.readinessClosed
}

private fun healthLabel(
    health: TraceboxHealth,
    strings: TraceboxDiagnosticsUiStrings,
): Int = when (health) {
    TraceboxHealth.DISABLED -> strings.healthDisabled
    TraceboxHealth.INITIALIZING -> strings.healthInitializing
    TraceboxHealth.READY -> strings.healthReady
    TraceboxHealth.DEGRADED -> strings.healthDegraded
    TraceboxHealth.DELETING -> strings.healthDeleting
    TraceboxHealth.CLOSED -> strings.healthClosed
}

private fun logLevelLabel(level: LogLevel, strings: TraceboxDiagnosticsUiStrings): Int =
    when (level) {
        LogLevel.VERBOSE -> strings.logLevelVerbose
        LogLevel.DEBUG -> strings.logLevelDebug
        LogLevel.INFO -> strings.logLevelInfo
        LogLevel.WARN -> strings.logLevelWarn
        LogLevel.ERROR -> strings.logLevelError
        LogLevel.OFF -> strings.logLevelOff
    }

private fun captureLabel(kind: CaptureKind, strings: TraceboxDiagnosticsUiStrings): Int =
    when (kind) {
        CaptureKind.JVM_CRASH -> strings.captureJvmCrash
        CaptureKind.HANDLED_EXCEPTION -> strings.captureHandledException
        CaptureKind.ANR -> strings.captureAnr
        CaptureKind.OS_EXIT -> strings.captureOsExit
        CaptureKind.NATIVE_CRASH -> strings.captureNativeCrash
        CaptureKind.RUST_PANIC -> strings.captureRustPanic
    }

private fun formatDuration(
    nanos: Long,
    strings: TraceboxDiagnosticsUiStrings,
    resources: Resources,
): String = when (nanos) {
    0L -> resources.getString(strings.durationAny)
    in 1 until 1_000_000L -> resources.getString(strings.durationNanos, nanos)
    else -> resources.getString(strings.durationMillis, nanos / 1_000_000L)
}
