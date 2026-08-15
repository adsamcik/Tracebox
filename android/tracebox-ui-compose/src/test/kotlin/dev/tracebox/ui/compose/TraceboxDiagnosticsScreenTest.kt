package dev.tracebox.ui.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.tracebox.api.ApprovalToken
import dev.tracebox.api.CaptureKind
import dev.tracebox.api.CrashReporter
import dev.tracebox.api.DeleteReport
import dev.tracebox.api.DeleteRequest
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.DiagnosticPackages
import dev.tracebox.api.DiagnosticSummary
import dev.tracebox.api.Diagnostics
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.LogLevel
import dev.tracebox.api.PackageDisclosure
import dev.tracebox.api.PackagePreparationResult
import dev.tracebox.api.PackagePreview
import dev.tracebox.api.PackagePrivacyClass
import dev.tracebox.api.PackageRequest
import dev.tracebox.api.PackageResult
import dev.tracebox.api.PackageTransformation
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.Readiness
import dev.tracebox.api.SavePackageResult
import dev.tracebox.api.SharePackageResult
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.TraceboxHealth
import dev.tracebox.api.TraceboxLogger
import dev.tracebox.api.TraceboxPolicy
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraceboxDiagnosticsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun policy_controls_update_runtime_policy_and_capture_sources() {
        val handle = FakeTraceboxHandle()
        setScreen(
            handle,
            configuration = configuration(
                advanced = TraceboxAdvancedControls(initiallyExpanded = true),
            ),
        )

        composeRule.onNodeWithText("Diagnostics")
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))

        composeRule.onNodeWithText("Minimum log level").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            handle.policy.value.minimumLogLevel == LogLevel.WARN
        }

        composeRule.onNodeWithText("ANR watchdog").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            CaptureKind.ANR !in handle.policy.value.captures
        }

        composeRule.onNodeWithText("Diagnostics").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { !handle.policy.value.enabled }
        composeRule.onNodeWithText("Diagnostics")
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        composeRule.onNodeWithText("Runtime controls updated.")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(3, handle.policyUpdates.size)
    }

    @Test
    fun upload_without_host_transport_fails_closed_to_review_only() {
        val diagnosticPackage = FakeUiDiagnosticPackage()
        val handle = FakeTraceboxHandle(diagnosticPackage)
        setScreen(
            handle,
            configuration = configuration(
                primaryAction = TraceboxPrimaryAction.UPLOAD,
                actions = TraceboxPackageActions(
                    upload = true,
                    share = false,
                    save = false,
                    deleteAllData = false,
                ),
            ),
        )

        composeRule.onNodeWithText("Review diagnostics").assertIsEnabled().performClick()
        approveLaunchedReview(handle)

        composeRule.onNodeWithText("Diagnostics are ready.").assertIsDisplayed()
        composeRule.onNodeWithText("Send to developer").assertIsNotDisplayed()
        assertEquals(0, diagnosticPackage.deleteStagingCalls)
    }

    @Test
    fun pending_upload_survives_recreation_and_approved_success_cleans_staging() {
        val diagnosticPackage = FakeUiDiagnosticPackage()
        val handle = FakeTraceboxHandle(diagnosticPackage)
        var uploads = 0
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            Screen(
                handle = handle,
                configuration = configuration(
                    primaryAction = TraceboxPrimaryAction.UPLOAD,
                    actions = TraceboxPackageActions(
                        upload = true,
                        share = false,
                        save = false,
                        deleteAllData = false,
                    ),
                ),
                uploader = TraceboxDiagnosticUploader {
                    uploads += 1
                    TraceboxUploadResult.Uploaded()
                },
            )
        }

        composeRule.onNodeWithText("Review and send to developer").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            handle.fakePackages.prepareCalls == 1
        }
        composeRule.waitForIdle()
        val launched = assertNotNull(shadowOf(composeRule.activity).nextStartedActivityForResult)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.activity.activityResultRegistry.dispatchResult(
            launched.requestCode,
            Activity.RESULT_OK,
            ApprovalToken.resultIntent(ByteArray(32) { 7 }),
        )

        composeRule.waitUntil(timeoutMillis = 5_000) { uploads == 1 }
        composeRule.onNodeWithText("Diagnostics were sent to the developer.").assertIsDisplayed()
        assertEquals(1, diagnosticPackage.deleteStagingCalls)
    }

    @Test
    fun retryable_upload_keeps_package_for_retry_then_success_cleans_it() {
        val diagnosticPackage = FakeUiDiagnosticPackage()
        val handle = FakeTraceboxHandle(diagnosticPackage)
        var uploads = 0
        setScreen(
            handle,
            configuration = configuration(
                primaryAction = TraceboxPrimaryAction.UPLOAD,
                actions = TraceboxPackageActions(
                    upload = true,
                    share = false,
                    save = false,
                    deleteAllData = false,
                ),
            ),
            uploader = TraceboxDiagnosticUploader {
                uploads += 1
                if (uploads == 1) {
                    TraceboxUploadResult.RetryableFailure
                } else {
                    TraceboxUploadResult.Uploaded()
                }
            },
        )

        composeRule.onNodeWithText("Review and send to developer").performClick()
        approveLaunchedReview(handle)
        composeRule.waitUntil(timeoutMillis = 5_000) { uploads == 1 }

        composeRule.onNodeWithText(
            "Could not send diagnostics. Check your connection and try again.",
        ).performScrollTo().assertIsDisplayed()
        assertEquals(0, diagnosticPackage.deleteStagingCalls)

        composeRule.onNodeWithText("Send to developer").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { uploads == 2 }
        assertEquals(1, diagnosticPackage.deleteStagingCalls)
    }

    @Test
    fun approved_package_can_share_then_save_and_reports_quantity() {
        val diagnosticPackage = FakeUiDiagnosticPackage(
            saveResult = SavePackageResult.Complete(1),
        )
        val handle = FakeTraceboxHandle(diagnosticPackage)
        setScreen(
            handle,
            configuration = configuration(
                actions = TraceboxPackageActions(
                    upload = false,
                    share = true,
                    save = true,
                    deleteAllData = false,
                ),
            ),
        )

        composeRule.onNodeWithText("Review diagnostics").performClick()
        approveLaunchedReview(handle)

        composeRule.onNodeWithText("Share with another app").performClick()
        val shared = assertNotNull(shadowOf(composeRule.activity).nextStartedActivity)
        assertEquals(TEST_SHARE_ACTION, shared.action)
        assertEquals(0, diagnosticPackage.deleteStagingCalls)

        composeRule.onNodeWithText("Save a copy").performClick()
        composeRule.waitForIdle()
        val save = assertNotNull(shadowOf(composeRule.activity).nextStartedActivityForResult)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, save.intent.action)
        composeRule.activity.activityResultRegistry.dispatchResult(
            save.requestCode,
            Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://tracebox-test/diagnostics.tbdiag")),
        )

        composeRule.waitUntil(timeoutMillis = 5_000) { diagnosticPackage.saveCalls == 1 }
        composeRule.onNodeWithText("Saved 1 byte.").assertIsDisplayed()
        assertEquals(1, diagnosticPackage.deleteStagingCalls)
    }

    @Test
    fun delete_confirmation_survives_recreation_and_disposal_cleans_staging() {
        val diagnosticPackage = FakeUiDiagnosticPackage()
        val handle = FakeTraceboxHandle(diagnosticPackage)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            Screen(
                handle = handle,
                configuration = configuration(
                    actions = TraceboxPackageActions(
                        upload = false,
                        share = false,
                        save = false,
                        deleteAllData = true,
                    ),
                    advanced = TraceboxAdvancedControls(initiallyExpanded = true),
                ),
            )
        }

        composeRule.onNodeWithText("Review diagnostics").performClick()
        approveLaunchedReview(handle)
        composeRule.onNodeWithText("Delete all diagnostic data").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete diagnostics?").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Delete diagnostics?").assertIsDisplayed()
        assertEquals(1, diagnosticPackage.deleteStagingCalls)
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { handle.deleteCalls == 1 }
        composeRule.onNodeWithText("All diagnostic data was deleted.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun rtl_controls_remain_operable() {
        val handle = FakeTraceboxHandle()
        setScreen(
            handle,
            configuration = configuration(
                advanced = TraceboxAdvancedControls(
                    initiallyExpanded = true,
                    statusDetails = false,
                    logLevels = emptyList(),
                    logcatMirroring = false,
                    performanceLogging = false,
                    captureKinds = emptySet(),
                    resetToDefaults = false,
                ),
            ),
            wrapper = { content ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    content()
                }
            },
        )

        composeRule.onNodeWithText("Diagnostics").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { !handle.policy.value.enabled }
        composeRule.onNodeWithText("Diagnostics").assertIsOff()
    }

    @Test
    fun large_fonts_keep_long_controls_scrollable_and_visible() {
        val handle = FakeTraceboxHandle()
        setScreen(
            handle,
            configuration = configuration(
                advanced = TraceboxAdvancedControls(initiallyExpanded = true),
            ),
            wrapper = { content ->
                val current = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(current.density, fontScale = 2f),
                ) {
                    Box(Modifier.width(320.dp).height(640.dp)) { content() }
                }
            },
        )

        composeRule.onNodeWithText("Minimum performance duration")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Native crashes (optional module)")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun talkback_semantics_cover_headings_sections_status_and_progress() {
        val handle = FakeTraceboxHandle()
        val prepareGate = CountDownLatch(1)
        handle.fakePackages.prepareGate = prepareGate
        setScreen(
            handle,
            configuration = configuration(
                showHeading = true,
                advanced = TraceboxAdvancedControls(
                    statusDetails = false,
                    diagnosticsEnabled = false,
                    logLevels = emptyList(),
                    logcatMirroring = false,
                    performanceLogging = false,
                    captureKinds = emptySet(),
                    resetToDefaults = false,
                ),
            ),
        )

        composeRule.onNodeWithText("Help improve this app")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("Advanced options")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Collapsed",
                ),
            )
        composeRule.onNodeWithText("Diagnostics are ready")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )

        composeRule.onNodeWithText("Review diagnostics").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            handle.fakePackages.prepareCalls == 1
        }
        composeRule.onNodeWithContentDescription("Diagnostic operation in progress")
            .assertContentDescriptionEquals("Diagnostic operation in progress")
        prepareGate.countDown()
    }

    @Test
    fun host_resource_ids_replace_visible_contract_strings() {
        val strings = TraceboxDiagnosticsUiStrings(
            title = R.string.tracebox_ui_delete_complete,
            supportTitle = R.string.tracebox_ui_status_title,
        )
        setScreen(
            FakeTraceboxHandle(),
            configuration = configuration(showHeading = true).copy(strings = strings),
        )

        composeRule.onNodeWithText("All diagnostic data was deleted.").assertIsDisplayed()
        composeRule.onNodeWithText("Technical status").assertIsDisplayed()
    }

    private fun approveLaunchedReview(handle: FakeTraceboxHandle) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            handle.fakePackages.prepareCalls == 1
        }
        composeRule.waitForIdle()
        val launched = assertNotNull(shadowOf(composeRule.activity).nextStartedActivityForResult)
        assertEquals(TEST_REVIEW_ACTION, launched.intent.action)
        composeRule.activity.activityResultRegistry.dispatchResult(
            launched.requestCode,
            Activity.RESULT_OK,
            ApprovalToken.resultIntent(ByteArray(32) { 3 }),
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            handle.fakePackages.createCalls == 1
        }
    }

    private fun setScreen(
        handle: FakeTraceboxHandle,
        configuration: TraceboxDiagnosticsUiConfiguration,
        uploader: TraceboxDiagnosticUploader? = null,
        wrapper: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) {
        composeRule.setContent {
            wrapper {
                Screen(handle, configuration, uploader)
            }
        }
    }
}

@Composable
private fun Screen(
    handle: FakeTraceboxHandle,
    configuration: TraceboxDiagnosticsUiConfiguration,
    uploader: TraceboxDiagnosticUploader? = null,
) {
    MaterialTheme {
        TraceboxDiagnosticsScreen(
            handle = handle,
            configuration = configuration,
            uploader = uploader,
        )
    }
}

private fun configuration(
    showHeading: Boolean = false,
    primaryAction: TraceboxPrimaryAction = TraceboxPrimaryAction.REVIEW_ONLY,
    actions: TraceboxPackageActions = TraceboxPackageActions(
        upload = false,
        share = false,
        save = false,
        deleteAllData = false,
    ),
    advanced: TraceboxAdvancedControls = TraceboxAdvancedControls(visible = false),
): TraceboxDiagnosticsUiConfiguration = TraceboxDiagnosticsUiConfiguration(
    showHeading = showHeading,
    showCasualStatus = true,
    primaryAction = primaryAction,
    packageActions = actions,
    advancedControls = advanced,
)

private class FakeTraceboxHandle(
    diagnosticPackage: FakeUiDiagnosticPackage = FakeUiDiagnosticPackage(),
) : TraceboxHandle {
    val mutablePolicy = MutableStateFlow(TraceboxPolicy.standard())
    val fakePackages = FakeUiDiagnosticPackages(diagnosticPackage)
    val policyUpdates = mutableListOf<TraceboxPolicy>()
    var policyUpdateResult: PolicyUpdateResult = PolicyUpdateResult.SUCCESS
    var deleteResult: DeleteReport = DeleteReport.COMPLETE
    var deleteCalls: Int = 0

    override val diagnostics: Diagnostics
        get() = error("not used by UI")
    override val log: TraceboxLogger
        get() = error("not used by UI")
    override val crashes: CrashReporter
        get() = error("not used by UI")
    override val policy: StateFlow<TraceboxPolicy> = mutablePolicy
    override val summary: StateFlow<DiagnosticSummary> = MutableStateFlow(DiagnosticSummary())
    override val readiness: StateFlow<Readiness> = MutableStateFlow(Readiness.DURABLE)
    override val health: StateFlow<TraceboxHealth> = MutableStateFlow(TraceboxHealth.READY)
    override val packages: DiagnosticPackages = fakePackages

    override fun updateProfile(profile: DiagnosticsProfile): PolicyUpdateResult = policyUpdateResult

    override fun updatePolicy(policy: TraceboxPolicy): PolicyUpdateResult {
        policyUpdates += policy
        if (policyUpdateResult == PolicyUpdateResult.SUCCESS) mutablePolicy.value = policy
        return policyUpdateResult
    }

    override fun delete(request: DeleteRequest): DeleteReport {
        deleteCalls += 1
        return deleteResult
    }

    override fun close() = Unit
}

private class FakeUiDiagnosticPackages(
    private val diagnosticPackage: FakeUiDiagnosticPackage,
) : DiagnosticPackages {
    var prepareCalls: Int = 0
    var createCalls: Int = 0
    var prepareGate: CountDownLatch? = null

    override fun prepare(request: PackageRequest): PackagePreparationResult {
        prepareCalls += 1
        prepareGate?.await()
        return PackagePreparationResult.Ready(PREVIEW)
    }

    override fun approvalIntent(context: Context, preview: PackagePreview): Intent =
        Intent(TEST_REVIEW_ACTION)

    override fun create(request: PackageRequest, approval: ApprovalToken): PackageResult {
        createCalls += 1
        return PackageResult.Created(diagnosticPackage)
    }
}

private class FakeUiDiagnosticPackage(
    var saveResult: SavePackageResult = SavePackageResult.Complete(4),
) : DiagnosticPackage {
    var deleteStagingCalls: Int = 0
    var saveCalls: Int = 0

    override val plaintextDigestSha256: ByteArray = ByteArray(32) { 1 }
    override val sizeBytes: Long = 4
    override val receipt: StateFlow<SharePackageResult> =
        MutableStateFlow(SharePackageResult.NOT_STARTED)

    override fun shareIntent(context: Context): Intent = Intent(TEST_SHARE_ACTION)

    override fun createSaveIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .setType("application/zip")

    override fun save(
        context: Context,
        destination: Uri,
        isCancelled: () -> Boolean,
    ): SavePackageResult {
        saveCalls += 1
        return saveResult
    }

    override fun <T> useInputStream(block: (InputStream) -> T): T =
        byteArrayOf(1, 2, 3, 4).inputStream().use(block)

    override fun deleteStaging(): Boolean {
        deleteStagingCalls += 1
        return true
    }
}

private val PREVIEW = PackagePreview(
    PackageDisclosure(
        includedValueCount = 1,
        includedBytes = 4,
        privacyClasses = setOf(PackagePrivacyClass.C0),
        transformations = setOf(PackageTransformation.NONE),
        omissionReasons = emptySet(),
        sourceTimeRangeMillis = null,
        sourceProcessCount = 1,
        plaintextDigestSha256 = ByteArray(32) { 1 },
        rawArtifactCount = 0,
        warnings = emptySet(),
    ),
)

private const val TEST_REVIEW_ACTION = "dev.tracebox.test.REVIEW"
private const val TEST_SHARE_ACTION = "dev.tracebox.test.SHARE"
