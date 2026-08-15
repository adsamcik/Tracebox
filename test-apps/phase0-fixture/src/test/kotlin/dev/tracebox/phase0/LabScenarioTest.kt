package dev.tracebox.phase0

import dev.tracebox.api.DeleteReport
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.Readiness
import dev.tracebox.api.TraceboxHealth
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabScenarioTest {
    @Test
    fun stableIds_are_unique_bounded_and_match_the_host_manifest() {
        val ids = LabScenario.entries.map(LabScenario::stableId)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[A-Z][A-Z0-9_]*(\\.[A-Z0-9_]+)+")) })
        assertTrue(ids.all { it.length <= 48 })

        val manifest = Files.readString(repositoryRoot().resolve("tooling/fixtures/personal-release-scenarios.json"))
        val hostIds = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(ids.toSet(), hostIds)
    }

    @Test
    fun host_manifest_metadata_matches_the_compiled_registry() {
        val manifest = Files.readString(
            repositoryRoot().resolve("tooling/fixtures/personal-release-scenarios.json"),
        )
        val rowPattern = Regex(
            """\{"id":"([^"]+)","transport":"([^"]+)","variant":"([^"]+)","action":"([^"]+)","expected":"([^"]+)"}""",
        )
        val rows = rowPattern.findAll(manifest).associate { match ->
            match.groupValues[1] to HostScenario(
                transport = match.groupValues[2],
                variant = match.groupValues[3],
                action = match.groupValues[4],
                expected = match.groupValues[5],
            )
        }
        assertEquals(LabScenario.entries.size, rows.size)

        LabScenario.entries.forEach { scenario ->
            val row = rows.getValue(scenario.stableId)
            assertEquals(scenario.transport.name.lowercase(), row.transport)
            assertEquals(
                when (scenario.requiredVariant) {
                    LabVariant.NO_INTERNET -> "noInternet"
                    LabVariant.HOST_NETWORK -> "hostNetwork"
                    LabVariant.EITHER -> "either"
                },
                row.variant,
            )
            assertEquals(scenario.expectedTermination, row.expected.startsWith("process_death"))
            assertTrue(row.action.matches(Regex("[a-z][a-z0-9_]*")))
            assertTrue(row.expected.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }

    @Test
    fun emulator_controller_has_exactly_one_implementation_for_every_scenario() {
        val runner = Files.readString(
            repositoryRoot().resolve("tools/verify/Invoke-PersonalReleaseEmulator.ps1"),
        )
        val explicitIds = Regex("""Invoke-CertScenario\s+'([^']+)'""")
            .findAll(runner)
            .map { it.groupValues[1] }
            .toList()
        val groupedIds = Regex("""\[pscustomobject]@\{\s*id = '(CORPUS\.[^']+)'""")
            .findAll(runner)
            .map { it.groupValues[1] }
            .toList()
        val implementedIds = explicitIds + groupedIds

        assertEquals(implementedIds.size, implementedIds.toSet().size)
        assertEquals(
            LabScenario.entries.map(LabScenario::stableId).toSet(),
            implementedIds.toSet(),
        )
        assertTrue(runner.contains("[int] \$ExpectedApi = 36"))
        assertTrue(runner.contains("[int] \$ExpectedPageSize = 4096"))
        assertTrue(runner.contains("\$abi -ne 'x86_64'"))
        assertTrue(
            runner.contains(
                "\$productionActivity = 'dev.tracebox.phase0.MainActivity'",
            ),
        )
        assertTrue(
            runner.contains(
                "\$labPackageActivity = 'dev.tracebox.phase0.LabPackageActivity'",
            ),
        )
        assertFalse(runner.contains("/.MainActivity"))
        assertFalse(runner.contains("/.LabPackageActivity"))
        assertFalse(
            runner.lineSequence().any { line ->
                val normalized = line.trimStart()
                normalized.startsWith("\$pid =", ignoreCase = true) ||
                    normalized.startsWith("\$matches =", ignoreCase = true)
            },
        )
        assertTrue(runner.contains("shell readlink '-f' \$Path"))
        assertTrue(runner.contains("'^/data/user/0/', '/data/data/'"))
        assertTrue(
            runner.contains(
                "Select-String \"\$([regex]::Escape(\$candidatePath))\\s*\$\"",
            ),
        )
        assertTrue(runner.contains("working_tree_patch_sha256"))
        assertTrue(runner.contains("scenario_manifest_sha256"))
        assertTrue(runner.contains("locksettings set-pin"))
        assertTrue(runner.contains("getprop sys.user.0.ce_available"))
        assertTrue(runner.contains("dumpsys user"))
        assertTrue(runner.contains("RUNNING_UNLOCKED"))
        assertTrue(runner.contains("RUNNING_LOCKED"))
        assertTrue(runner.contains("locksettings clear --old"))
        assertTrue(runner.contains("input keyevent KEYCODE_HOME"))
        assertTrue(runner.contains("am make-uid-idle \$noInternetPackage"))
        assertTrue(runner.contains("cmd deviceidle force-idle"))
        assertTrue(runner.contains("cmd deviceidle get deep"))
        assertTrue(runner.contains("cmd deviceidle unforce"))
        assertTrue(runner.contains("dumpsys activity top-resumed"))
        assertFalse(runner.contains("mResumedActivity"))
        assertTrue(runner.contains("\$noInternetPackage`:tracebox_handler"))
        assertTrue(runner.contains("kill '-6' \$mainPid"))
        assertTrue(runner.contains("Wait-NewHandlerDump"))
        assertTrue(runner.contains("Wait-RecoveredSegment"))
        val handlerDumpReader = runner
            .substringAfter("function Get-TraceboxHandlerDumps")
            .substringBefore("function Get-TraceboxCrashpadPendingEntries")
        assertTrue(handlerDumpReader.contains("tracebox-handler-handoff"))
        assertTrue(handlerDumpReader.contains("[0-9a-f]{64}\\.dmp\$"))
        assertTrue(handlerDumpReader.contains("crashpad-db/pending"))
        assertTrue(handlerDumpReader.contains("'.meta'"))
        assertTrue(handlerDumpReader.contains("'.lock'"))
        assertTrue(handlerDumpReader.contains("\$metadataBytes -eq '32'"))
        assertTrue(runner.contains("Wait-CrashpadPendingRetired"))
        assertTrue(
            runner.contains(
                "Assert-ProcessDeathAction 'FAULT.CPP_SEGV' 'segv' -RequireHandlerDump",
            ),
        )
        assertTrue(runner.contains("scenario_share_handoff"))
        assertTrue(runner.contains("ChooserActivity|ResolverActivity"))
        assertTrue(runner.contains("phase=post_delete_restart"))
        assertTrue(runner.contains("phase=explicit_reenable"))
        assertTrue(runner.contains("'APPROVE PACKAGE'"))
        assertTrue(runner.contains("scenario_anr_stall_started"))
        assertTrue(runner.contains("keyevent '--async' KEYCODE_DPAD_CENTER"))
        assertTrue(runner.contains("Wait-AndroidAnr"))
        assertTrue(runner.contains("am_anr"))
        assertTrue(runner.contains("android:id/aerr_close"))
        assertTrue(runner.contains("anr_auto_terminated=true"))
        assertFalse(runner.contains("show_first_crash_dialog"))
        assertFalse(runner.contains("anr_show_background"))
        val installReadiness = runner
            .substringAfter("Invoke-CertScenario 'INSTALL.READINESS'")
            .substringBefore("Invoke-CertScenario 'HANDLER.COLD_START'")
        assertTrue(installReadiness.contains("Reset-And-Launch -ClearData"))
        assertTrue(installReadiness.contains("Wait-Log 'scenario_result id=INSTALL\\.READINESS outcome=PASS'"))
        assertFalse(installReadiness.contains("Start-LabAction"))
        assertFalse(installReadiness.contains("Clear-DeviceLog"))
        val fatalRestart = runner
            .substringAfter("function Complete-FatalCaptureAndRestart")
            .substringBefore("function Assert-ProductionFixtureFaultCapture")
        assertFalse(fatalRestart.contains("am force-stop"))
        val recoveredIndex = fatalRestart.indexOf("Wait-RecoveredSegment")
        val handlerReadyIndex = fatalRestart.indexOf("Wait-ProductionHandlerReady")
        val finalReadinessIndex = fatalRestart.indexOf(
            "Start-LabAction \$noInternetPackage \$Scenario 'readiness' -Wait",
        )
        assertTrue(recoveredIndex >= 0)
        assertTrue(handlerReadyIndex > recoveredIndex)
        assertTrue(finalReadinessIndex > handlerReadyIndex)
        assertTrue(fatalRestart.contains("Wait-ProductionReadiness"))
    }

    @Test
    fun anr_fixture_stalls_after_the_production_startup_grace() {
        val activity = Files.readString(
            repositoryRoot().resolve(
                "test-apps/phase0-fixture/src/main/kotlin/dev/tracebox/phase0/" +
                    "LabPackageActivity.kt",
            ),
        )

        assertTrue(activity.contains("ANR_SETTLE_MILLIS = 10_500L"))
        assertTrue(activity.contains("scenario_anr_stall_started"))
    }

    @Test
    fun release_scenarios_never_launch_the_historical_phase0_lane() {
        val runner = Files.readString(
            repositoryRoot().resolve("tools/verify/Invoke-PersonalReleaseEmulator.ps1"),
        )
        val releaseScenarios = runner.substringAfter("Invoke-CertScenario 'INSTALL.READINESS'")
        listOf(
            "LegacyPhase0Activity",
            "FaultReceiver",
            ":phase0_main",
            ":phase0_handler",
            ":worker",
            "-Lane Legacy",
            "Send-LabFault",
        ).forEach { legacyControl ->
            assertFalse(
                releaseScenarios.contains(legacyControl),
                "Release gate launches historical control $legacyControl",
            )
        }
        listOf(
            ":tracebox_handler",
            ":production_participant",
            "Assert-ProductionFixtureFaultCapture",
            "scenario_anr_armed",
            "Get-TraceboxSegmentFingerprints",
        ).forEach { productionEvidence ->
            assertTrue(
                releaseScenarios.contains(productionEvidence),
                "Release gate lacks production evidence $productionEvidence",
            )
        }
    }

    @Test
    fun fixture_build_model_separates_network_capability_and_keeps_release_minified() {
        val root = repositoryRoot()
        val build = Files.readString(root.resolve("test-apps/phase0-fixture/build.gradle.kts"))
        assertTrue(build.contains("create(\"${LabBuildModel.NO_INTERNET_FLAVOR}\")"))
        assertTrue(build.contains("create(\"${LabBuildModel.HOST_NETWORK_FLAVOR}\")"))
        assertTrue(build.contains("${LabBuildModel.MINIFIED_BUILD_TYPE} {"))
        assertTrue(build.contains("isMinifyEnabled = true"))
        assertTrue(build.contains("create(\"${LabBuildModel.MINIFIED_TEST_BUILD_TYPE}\")"))
        assertTrue(build.contains("initWith(getByName(\"release\"))"))

        val noInternet = Files.readString(
            root.resolve("test-apps/phase0-fixture/src/noInternet/AndroidManifest.xml"),
        )
        val hostNetwork = Files.readString(
            root.resolve("test-apps/phase0-fixture/src/hostNetwork/AndroidManifest.xml"),
        )
        assertFalse(noInternet.contains("android.permission.INTERNET"))
        assertTrue(hostNetwork.contains("android.permission.INTERNET"))

        val noInternetSource = Files.readString(
            root.resolve(
                "test-apps/phase0-fixture/src/noInternet/kotlin/" +
                    "dev/tracebox/phase0/HostNetworkControl.kt",
            ),
        )
        val hostNetworkSource = Files.readString(
            root.resolve(
                "test-apps/phase0-fixture/src/hostNetwork/kotlin/" +
                    "dev/tracebox/phase0/HostNetworkControl.kt",
            ),
        )
        assertFalse(noInternetSource.contains("java.net"))
        assertTrue(hostNetworkSource.contains("java.net"))
    }

    @Test
    fun native_and_emulator_qualification_verify_fixture_rust_panic_probe_isolation() {
        val root = repositoryRoot()
        val isolationTask = ":test-apps:phase0-fixture:verifyFixtureRustPanicProbeIsolation"
        val nativeQualification = Files.readString(
            root.resolve(".github/workflows/native-qualification.yml"),
        )
        assertTrue(nativeQualification.contains(isolationTask))

        val emulator = Files.readString(
            root.resolve("tools/verify/Invoke-PersonalReleaseEmulator.ps1"),
        )
        val emulatorBuildGraph = emulator
            .substringAfter("\$qualificationTasks = @(")
            .substringBefore("if (-not \$SkipBuild)")
        assertTrue(emulatorBuildGraph.contains(isolationTask))
    }

    @Test
    fun resource_probe_samples_main_loop_while_generated_capture_is_in_flight() {
        val activity = Files.readString(
            repositoryRoot().resolve(
                "test-apps/phase0-fixture/src/main/kotlin/" +
                    "dev/tracebox/phase0/LabPackageActivity.kt",
            ),
        )
        val resourceProbe = activity
            .substringAfter("private fun runResourceProbe()")
            .substringBefore("private fun logResult")
        val heartbeatPost = resourceProbe.indexOf("mainHandler.postDelayed(")
        val captureInvocation =
            resourceProbe.indexOf("captureInvocations.incrementAndGet()", heartbeatPost)
        val generatedCapture =
            resourceProbe.indexOf("GeneratedDiagnostics.breadcrumb(", captureInvocation)
        val heartbeatAwait = resourceProbe.indexOf("completed.await(", generatedCapture)

        assertTrue(heartbeatPost >= 0)
        assertTrue(heartbeatPost < captureInvocation)
        assertTrue(captureInvocation < generatedCapture)
        assertTrue(generatedCapture < heartbeatAwait)
        listOf(
            "elapsed -",
            "RESOURCE_HEARTBEAT_INTERVAL_MILLIS",
            "captureInvocations.get() > capturesBeforeHeartbeat",
            "captureOverlapSamples.incrementAndGet()",
            "capture_overlap_heartbeat_samples=",
            "capture_overlap_target_pause_ms=",
        ).forEach { evidence -> assertTrue(resourceProbe.contains(evidence)) }

        val runner = Files.readString(
            repositoryRoot().resolve("tools/verify/Invoke-PersonalReleaseEmulator.ps1"),
        )
        val resourceGate = runner
            .substringAfter("Invoke-CertScenario 'RESOURCE.BASELINE'")
            .substringBefore("\$corpusResult =")
        listOf(
            "capture_overlap_heartbeat_samples=(\\d+)",
            "capture_overlap_target_pause_ms=(\\d+)",
            "\$captureOverlapSamples = [int]\$Matches[1]",
            "\$captureOverlapTargetPauseMillis = [long]\$Matches[2]",
            "\$captureOverlapSamples -ne 16",
            "\$captureRecords -ne 32",
            "\$captureOverlapTargetPauseMillis -gt 2000",
        ).forEach { evidence -> assertTrue(resourceGate.contains(evidence)) }
    }

    @Test
    fun fixture_components_are_explicit_and_production_sources_do_not_own_lab_controls() {
        val root = repositoryRoot()
        val manifest = Files.readString(
            root.resolve("test-apps/phase0-fixture/src/main/AndroidManifest.xml"),
        )
        assertTrue(
            manifest.contains(
                """<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />""",
            ),
        )
        assertTrue(manifest.contains("""android:name=".MainActivity""""))
        assertTrue(manifest.contains("""android:name=".LegacyPhase0Activity""""))
        assertTrue(manifest.contains("""android:name=".LabPackageActivity""""))
        assertTrue(manifest.contains("""android:name=".FaultReceiver""""))
        assertEquals(4, Regex("""android:exported="true"""").findAll(manifest).count())
        assertTrue(
            Regex(
                """android:name="\.LegacyPhase0Activity"[\s\S]*?android:exported="true"[\s\S]*?android:process=":phase0_main"""",
            ).containsMatchIn(manifest),
        )
        assertTrue(
            Regex(
                """android:name="\.HandlerService"[\s\S]*?android:exported="false"[\s\S]*?android:process=":phase0_handler"""",
            ).containsMatchIn(manifest),
        )
        assertTrue(
            Regex(
                """android:name="\.WorkerService"[\s\S]*?android:exported="false"[\s\S]*?android:process=":worker"""",
            ).containsMatchIn(manifest),
        )
        assertTrue(
            Regex(
                """android:name="\.ProductionParticipantService"[\s\S]*?android:exported="false"[\s\S]*?android:process=":production_participant"""",
            ).containsMatchIn(manifest),
        )
        assertTrue(
            Regex(
                """android:name="\.FaultReceiver"[\s\S]*?android:exported="true"[\s\S]*?android:process=":phase0_main"""",
            ).containsMatchIn(manifest),
        )
        assertTrue(
            Regex(
                """android:name="\.LabDirectBootReceiver"[\s\S]*?android:directBootAware="true"[\s\S]*?android:exported="false"""",
            ).containsMatchIn(manifest),
        )

        val mainActivity = Files.readString(
            root.resolve(
                "test-apps/phase0-fixture/src/main/kotlin/" +
                    "dev/tracebox/phase0/MainActivity.kt",
            ),
        )
        listOf(
            "LabNativeIdentity",
            "HandlerService",
            "WorkerService",
            "AnrWatchdog",
            "NativeRuntime",
            "Phase0WatchdogRegistry",
        ).forEach { legacyControl ->
            assertFalse(
                mainActivity.contains(legacyControl),
                "Production activity references legacy control $legacyControl",
            )
        }
        val legacyActivity = Files.readString(
            root.resolve(
                "test-apps/phase0-fixture/src/main/kotlin/" +
                    "dev/tracebox/phase0/LegacyPhase0Activity.kt",
            ),
        )
        listOf("LabNativeIdentity", "HandlerService", "WorkerService", "AnrWatchdog")
            .forEach { legacyControl -> assertTrue(legacyActivity.contains(legacyControl)) }

        val labRuntime = Files.readString(
            root.resolve(
                "test-apps/phase0-fixture/src/main/kotlin/" +
                    "dev/tracebox/phase0/LabRuntime.kt",
            ),
        )
        val participantService = Files.readString(
            root.resolve(
                "test-apps/phase0-fixture/src/main/kotlin/" +
                    "dev/tracebox/phase0/ProductionParticipantService.kt",
            ),
        )
        assertTrue(labRuntime.contains(".setNativeCaptureEnabled(true)"))
        assertTrue(participantService.contains(".setNativeCaptureEnabled(true)"))

        Files.walk(root.resolve("android")).use { paths ->
            val productionMentions = paths
                .filter(Files::isRegularFile)
                .filter { path ->
                    path.toString().contains("${java.io.File.separator}src${java.io.File.separator}") &&
                        path.fileName.toString().substringAfterLast('.', "") in
                        setOf("kt", "java", "xml", "c", "cc", "h")
                }
                .filter { path -> Files.readString(path).contains("dev.tracebox.phase0") }
                .toList()
            assertTrue(productionMentions.isEmpty(), "Lab namespace leaked into production: $productionMentions")
        }
    }

    @Test
    fun rust_panic_probe_is_fixture_only_and_production_has_no_fault_injection_surface() {
        val root = repositoryRoot()
        val workspace = Files.readString(root.resolve("Cargo.toml"))
        val fixtureBuild = Files.readString(
            root.resolve("test-apps/phase0-fixture/build.gradle.kts"),
        )
        val probeManifest = Files.readString(
            root.resolve("rust/tracebox-fixture-panic-probe/Cargo.toml"),
        )
        val probeSource = Files.readString(
            root.resolve("rust/tracebox-fixture-panic-probe/src/lib.rs"),
        )
        val labRuntime = Files.readString(
            root.resolve(
                "test-apps/phase0-fixture/src/main/kotlin/" +
                    "dev/tracebox/phase0/LabRuntime.kt",
            ),
        )

        assertTrue(workspace.contains("\"rust/tracebox-fixture-panic-probe\""))
        assertTrue(workspace.contains("[profile.fixture-panic-probe]"))
        assertTrue(workspace.contains("panic = \"unwind\""))
        assertTrue(probeManifest.contains("publish = false"))
        assertTrue(probeManifest.contains("crate-type = [\"cdylib\", \"rlib\"]"))
        assertTrue(probeSource.contains("install_bounded_panic_hook"))
        assertTrue(probeSource.contains("catch_unwind"))
        assertTrue(probeSource.contains("take_panic_record_v1"))
        assertTrue(fixtureBuild.contains("buildFixtureRustPanicProbe"))
        assertTrue(fixtureBuild.contains("verifyFixtureRustPanicProbeIsolation"))
        assertTrue(fixtureBuild.contains("androidComponents.sdkComponents.sdkDirectory"))
        assertFalse(fixtureBuild.contains("environmentVariable(\"ANDROID_HOME\")"))
        assertTrue(fixtureBuild.contains("ZipInputStream"))
        assertTrue(fixtureBuild.contains("negativeNestedName"))
        assertTrue(fixtureBuild.contains("negativeNestedContent"))
        assertTrue(fixtureBuild.contains("negativeJniName"))
        assertTrue(fixtureBuild.contains("fixture-panic-probe"))
        assertTrue(fixtureBuild.contains("x86_64-linux-android"))
        assertTrue(fixtureBuild.contains("aarch64-linux-android"))
        listOf(
            "val probe = LabRustPanicProbe.capture()",
            "payload_class = probe.payloadClass",
            "location_code = probe.locationCode",
            "flags = probe.flags",
            "reason=rust_panic_record_not_persisted",
        ).forEach { evidence -> assertTrue(labRuntime.contains(evidence)) }
        val probeCapture = labRuntime.indexOf("val probe = LabRustPanicProbe.capture()")
        val realRecord = labRuntime.indexOf("GeneratedDiagnostics.rustPanic(", probeCapture)
        val durableProgress = labRuntime.indexOf("!storageProgressed(before, after)", realRecord)
        val deliberateTermination = labRuntime.indexOf("LabNativeFaults.abortProcess()", realRecord)
        assertTrue(probeCapture < realRecord)
        assertTrue(realRecord < durableProgress)
        assertTrue(durableProgress < deliberateTermination)

        val forbiddenProductionTokens = listOf(
            "tracebox_fixture_panic_probe",
            "LabRustPanicProbe",
            "nativeRunBoundedPanicProbe",
            "fixture-only bounded Rust panic probe",
        )
        Files.walk(root.resolve("android")).use { paths ->
            val leaks = paths
                .filter(Files::isRegularFile)
                .filter { path ->
                    path.toString().contains(
                        "${java.io.File.separator}src${java.io.File.separator}main" +
                            java.io.File.separator,
                    )
                }
                .filter { path ->
                    path.fileName.toString().substringAfterLast('.', "") in
                        setOf("kt", "java", "xml", "c", "cc", "h")
                }
                .filter { path ->
                    val source = Files.readString(path)
                    forbiddenProductionTokens.any(source::contains)
                }
                .toList()
            assertTrue(leaks.isEmpty(), "Fixture panic injection leaked into production: $leaks")
        }
    }

    @Test
    fun rust_panic_probe_decoder_accepts_only_the_bounded_success_contract() {
        val packed =
            1uL or
                (1uL shl 8) or
                (0x3456_789auL shl 16) or
                (7uL shl 48)
        assertEquals(
            RustPanicProbeMetadata(
                payloadClass = 1u,
                locationCode = 0x3456_789au,
                flags = 7u,
            ),
            decodeRustPanicProbe(packed.toLong()),
        )
        assertNull(decodeRustPanicProbe(0))
        assertNull(decodeRustPanicProbe((3uL shl 8 or packed).toLong()))
        assertNull(decodeRustPanicProbe((packed and (7uL shl 48).inv()).toLong()))
        assertNull(decodeRustPanicProbe((packed or (1uL shl 56)).toLong()))
    }

    @Test
    fun production_readiness_pass_requires_durable_and_ready() {
        Readiness.entries.forEach { readiness ->
            TraceboxHealth.entries.forEach { health ->
                assertEquals(
                    readiness == Readiness.DURABLE && health == TraceboxHealth.READY,
                    isProductionReady(readiness, health),
                )
            }
        }
    }

    @Test
    fun disabled_delete_and_policy_results_fail_closed() {
        Readiness.entries.forEach { readiness ->
            TraceboxHealth.entries.forEach { health ->
                assertEquals(
                    readiness == Readiness.DURABLE && health == TraceboxHealth.DISABLED,
                    isDurablyDisabled(readiness, health),
                )
            }
        }
        assertTrue(
            deleteSucceeded(
                DeleteReport.COMPLETE,
                Readiness.DURABLE,
                TraceboxHealth.DISABLED,
            ),
        )
        DeleteReport.entries
            .filterNot { it == DeleteReport.COMPLETE }
            .forEach { report ->
                assertFalse(
                    deleteSucceeded(report, Readiness.DURABLE, TraceboxHealth.DISABLED),
                )
            }
        assertFalse(
            deleteSucceeded(DeleteReport.COMPLETE, Readiness.DURABLE, TraceboxHealth.READY),
        )

        assertTrue(
            policyBarrierSucceeded(
                PolicyUpdateResult.SUCCESS,
                true,
                PolicyUpdateResult.SUCCESS,
                true,
            ),
        )
        PolicyUpdateResult.entries
            .filterNot { it == PolicyUpdateResult.SUCCESS }
            .forEach { result ->
                assertFalse(
                    policyBarrierSucceeded(result, true, PolicyUpdateResult.SUCCESS, true),
                )
                assertFalse(
                    policyBarrierSucceeded(PolicyUpdateResult.SUCCESS, true, result, true),
                )
            }
        assertFalse(
            policyBarrierSucceeded(
                PolicyUpdateResult.SUCCESS,
                false,
                PolicyUpdateResult.SUCCESS,
                true,
            ),
        )
    }

    @Test
    fun storage_pressure_requires_observable_persisted_progress() {
        val baseline = StorageSnapshot(1, 4096, "00")
        assertFalse(storageProgressed(baseline, baseline))
        assertFalse(storageProgressed(baseline, StorageSnapshot(0, 0, "11")))
        assertFalse(storageProgressed(baseline, StorageSnapshot(1, 4096, "11")))
        assertFalse(storageProgressed(baseline, StorageSnapshot(1, 8192, "00")))
        assertTrue(storageProgressed(baseline, StorageSnapshot(1, 8192, "11")))
        assertTrue(storageProgressed(baseline, StorageSnapshot(2, 8192, "22")))
    }

    @Test
    fun save_share_certification_requires_real_copy_readback_and_observable_chooser() {
        val root = repositoryRoot()
        val activity = Files.readString(
            root.resolve(
                "test-apps/phase0-fixture/src/main/kotlin/" +
                    "dev/tracebox/phase0/LabPackageActivity.kt",
            ),
        )
        listOf(
            "diagnosticPackage.save(this, destination)",
            "destinationDigest(destination)",
            "contentEquals(diagnosticPackage.plaintextDigestSha256)",
            "startActivityForResult(share, SHARE_REQUEST)",
            "chooser_returned=true",
        ).forEach { evidence -> assertTrue(activity.contains(evidence)) }
        assertFalse(
            activity.contains(
                "share != null &&\n                        save.action == Intent.ACTION_CREATE_DOCUMENT",
            ),
        )
    }

    @Test
    fun network_control_behavior_matches_the_compiled_flavor() {
        val result = HostNetworkControl.probe("127.0.0.1", 9)
        when (BuildConfig.FLAVOR) {
            LabBuildModel.NO_INTERNET_FLAVOR -> {
                assertEquals(NetworkCapability.ABSENT, result.capability)
                assertFalse(result.dnsAttempted)
                assertFalse(result.connectAttempted)
            }
            LabBuildModel.HOST_NETWORK_FLAVOR -> {
                assertEquals(NetworkCapability.HOST_CONTROL, result.capability)
                assertTrue(result.dnsAttempted)
                assertTrue(result.connectAttempted)
            }
            else -> error("Unexpected network flavor: ${BuildConfig.FLAVOR}")
        }
    }

    @Test
    fun every_required_lane_has_a_stable_scenario() {
        assertTrue(LabScenario.entries.any { it.requiredVariant == LabVariant.NO_INTERNET })
        assertTrue(LabScenario.entries.any { it.requiredVariant == LabVariant.HOST_NETWORK })
        assertTrue(LabScenario.entries.any { it.transport == LabTransport.DIRECT_BOOT })
        assertTrue(LabScenario.entries.count { it.expectedTermination } >= 7)
        assertTrue(LabScenario.entries.count { it.transport == LabTransport.HOST } >= 5)
        assertEquals(
            LabTransport.RUNNER,
            LabScenario.HANDLER_BACKGROUND_LIFETIME.transport,
        )
        assertTrue(LabScenario.HANDLER_BACKGROUND_LIFETIME.expectedTermination)
    }

    @Test
    fun native_identity_files_are_forced_to_fixed_size_and_never_silently_replaced() {
        val directory = Files.createTempDirectory("tracebox-lab-identity")
        try {
            val path = directory.resolve("process-identity.bin")
            var allocations = 0
            val expected = ByteArray(32) { index -> index.toByte() }
            val first =
                LabIdentityFiles.loadOrCreate(path, 32) {
                    allocations += 1
                    expected
                }
            assertContentEquals(expected, first)
            val second =
                LabIdentityFiles.loadOrCreate(path, 32) {
                    allocations += 1
                    ByteArray(32) { 0x7f }
                }
            assertContentEquals(expected, second)
            assertEquals(1, allocations)
            assertFalse(Files.exists(directory.resolve("process-identity.bin.new")))

            Files.write(path, ByteArray(31))
            assertNull(LabIdentityFiles.readFixed(path, 32))
            assertNull(
                LabIdentityFiles.loadOrCreate(path, 32) {
                    error("A corrupt existing identity must fail closed")
                },
            )
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        repeat(8) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: error("Repository root not found")
        }
        error("Repository root not found")
    }

    private data class HostScenario(
        val transport: String,
        val variant: String,
        val action: String,
        val expected: String,
    )
}
