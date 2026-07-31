package dev.tracebox

import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.anr.ExitPolicyToken
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.directboot.DenyState
import dev.tracebox.directboot.DirectBootLayout
import dev.tracebox.directboot.DirectBootWriteResult
import dev.tracebox.nativecapture.TraceboxHandlerService
import dev.tracebox.storage.OwnedStoragePath
import dev.tracebox.storage.UidBucket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceboxStorageLifecycleTest {
    @Test
    fun initial_profile_is_honored_and_persisted_choice_only_wins_when_enabled() {
        val defaults = TraceboxConfiguration.Builder().build()
        assertFalse(defaults.directBootC0Enabled)
        assertEquals(
            DiagnosticsProfile.DISABLED,
            resolveRequestedProfile(defaults, DiagnosticsProfile.STANDARD_DIAGNOSTICS),
        )

        val explicit = TraceboxConfiguration.Builder()
            .setInitialProfile(DiagnosticsProfile.MINIMAL_CRASH)
            .build()
        assertEquals(
            DiagnosticsProfile.MINIMAL_CRASH,
            resolveRequestedProfile(explicit, DiagnosticsProfile.STANDARD_DIAGNOSTICS),
        )

        val persistent = TraceboxConfiguration.Builder()
            .setInitialProfile(DiagnosticsProfile.MINIMAL_CRASH)
            .setPersistRequestedProfile(true)
            .build()
        assertEquals(
            DiagnosticsProfile.MINIMAL_CRASH,
            resolveRequestedProfile(persistent, null),
        )
        assertEquals(
            DiagnosticsProfile.STANDARD_DIAGNOSTICS,
            resolveRequestedProfile(persistent, DiagnosticsProfile.STANDARD_DIAGNOSTICS),
        )

        val directBoot = TraceboxConfiguration.Builder()
            .setDirectBootC0Enabled(true)
            .build()
        assertTrue(directBoot.directBootC0Enabled)
        assertFalse(defaults.equivalentTo(directBoot))
    }

    @Test
    fun configuration_schema_fingerprint_is_defensively_copied_on_every_access() {
        val configuration = TraceboxConfiguration.Builder().build()
        val expected = configuration.generatedSchemaFingerprint
        val callerOwned = configuration.generatedSchemaFingerprint
        callerOwned.fill(0)

        assertContentEquals(expected, configuration.generatedSchemaFingerprint)
        assertTrue(expected.any { it != 0.toByte() })
        assertTrue(configuration.equivalentTo(TraceboxConfiguration.Builder().build()))
    }

    @Test
    fun credential_protected_classifier_accepts_only_bounded_production_paths() {
        val id = "A".repeat(43)
        val rawHex = "a".repeat(64)
        val accepted = mapOf(
            "policy-control-v1" to UidBucket.METADATA,
            ".tracebox-primary.lock" to UidBucket.METADATA,
            "policy-repair-required-v1" to UidBucket.METADATA,
            "policy-native-transition-v1-a" to UidBucket.METADATA,
            "policy-native-transition-v1-b" to UidBucket.METADATA,
            "requested-profile-v1.new" to UidBucket.METADATA,
            "identity-lifecycle-v1" to UidBucket.METADATA,
            "exit-tombstones-v1.new" to UidBucket.METADATA,
            "exit-import-journal/$id.tbexitjournal.new" to UidBucket.METADATA,
            "instances/$id/process-instance-id" to UidBucket.METADATA,
            "instances/$id/segments/.tracebox-role-quota.lock" to UidBucket.METADATA,
            "instances/$id/segments/$id.tbseg" to UidBucket.ROLE_SEGMENTS,
            "raw-artifacts/$id.tbraw" to UidBucket.RAW_ARTIFACTS,
            "raw-artifacts/$id.tbrawjournal" to UidBucket.METADATA,
            "summary-spool/$id.tbsummary" to UidBucket.SUMMARY_SPOOL,
            "summary-import-acks/$id.tbimportack" to UidBucket.METADATA,
            "summary-staging/$id.tbstaging" to UidBucket.SUMMARY_STAGING,
            "compaction/$id.tbcompact" to UidBucket.COMPACTION,
            "export-staging/tbdiag-12345678-1234-1234-1234-123456789abc.tbdiag" to
                UidBucket.SNAPSHOTS,
            "native-handler/tracebox-emergency-1.bin" to UidBucket.EMERGENCY,
            "native-handler/tracebox-rust-panic-2.bin" to UidBucket.EMERGENCY,
            "native-handler/tracebox-handler-clients/client-r123-${"a".repeat(64)}.tbclient" to
                UidBucket.METADATA,
            "native-handler/tracebox-handler-start-permit-v1" to UidBucket.METADATA,
            "native-handler/tracebox-handler-start-permit-v1.new" to UidBucket.METADATA,
            "native-handler/tracebox-handler-handoff/$rawHex.dmp" to UidBucket.RAW_ARTIFACTS,
            "native-handler/crashpad-db/pending/report.dmp" to UidBucket.RAW_ARTIFACTS,
            "native-handler/crashpad-db/pending/report.meta" to UidBucket.RAW_ARTIFACTS,
        )

        accepted.forEach { (relative, bucket) ->
            assertEquals(bucket, classifyCredentialProtectedStorage(owned("ce", relative)), relative)
        }
        listOf(
            "native-handler/tracebox-handler.sock",
            "native-handler/tracebox-handler-clients/client-r01-${"a".repeat(64)}.tbclient",
            "native-handler/tracebox-handler-clients/client-r1-${"A".repeat(64)}.tbclient",
            "native-handler/tracebox-handler-clients/client-r1-${"a".repeat(63)}.tbclient",
            "native-handler/tracebox-handler-handoff/${"A".repeat(64)}.dmp",
            "instances/$id/segments/not-an-id.tbseg",
            "export-staging/arbitrary.tbdiag",
            "unknown/owned-looking.tbraw",
            "../escape.tbraw",
            "native-handler/crashpad-db/${"a".repeat(129)}",
        ).forEach { relative ->
            assertNull(classifyCredentialProtectedStorage(owned("ce", relative)), relative)
        }
        assertNull(classifyCredentialProtectedStorage(owned("de", "policy-control-v1")))
    }

    @Test
    fun only_the_declared_application_process_can_own_global_coordination() {
        assertEquals(true, isPrimaryProcessName("dev.example.app", "dev.example.app"))
        assertEquals(false, isPrimaryProcessName("dev.example.app:worker", "dev.example.app"))
        assertEquals(false, isPrimaryProcessName(null, "dev.example.app"))
        assertEquals(false, isPrimaryProcessName("dev.example.app", ""))
    }

    @Test
    fun handler_stop_recovers_only_after_conclusive_cleanup_removes_the_socket() {
        val directory = Files.createTempDirectory("tracebox-handler-stop")
        val socket = directory.resolve("tracebox-handler.sock")
        var cleanupCalls = 0

        assertTrue(
            recoverStoppedHandlerSocket(socket) {
                cleanupCalls += 1
                false
            },
        )
        assertEquals(0, cleanupCalls)

        Files.write(socket, byteArrayOf(1))
        assertFalse(
            recoverStoppedHandlerSocket(socket) {
                cleanupCalls += 1
                false
            },
        )
        assertTrue(Files.exists(socket))

        assertFalse(
            recoverStoppedHandlerSocket(socket) {
                cleanupCalls += 1
                true
            },
        )
        assertTrue(Files.exists(socket))

        assertTrue(
            recoverStoppedHandlerSocket(socket) { observed ->
                cleanupCalls += 1
                assertEquals(socket.toString(), observed)
                Files.delete(socket)
                true
            },
        )
        assertFalse(Files.exists(socket))
        assertEquals(3, cleanupCalls)
    }

    @Test
    fun handler_stop_fails_closed_when_native_cleanup_is_unavailable() {
        val socket = Files.createTempDirectory("tracebox-handler-stop-linkage")
            .resolve("tracebox-handler.sock")
        Files.write(socket, byteArrayOf(1))

        assertFalse(
            recoverStoppedHandlerSocket(socket) {
                throw UnsatisfiedLinkError("native cleanup unavailable")
            },
        )
        assertTrue(Files.exists(socket))
    }

    @Test
    fun healthy_native_observer_tick_drains_rust_panics_once() {
        var drains = 0
        assertFalse(drainRustPanicRingIfHealthy(healthy = false) { drains += 1 })
        assertEquals(0, drains)
        assertTrue(drainRustPanicRingIfHealthy(healthy = true) { drains += 1 })
        assertEquals(1, drains)
    }

    @Test
    fun early_managed_crashes_are_bounded_and_resolved_by_durable_policy() {
        val enabled = BoundedManagedCrashBuffer<Int>(capacity = 2)
        assertEquals(BoundedManagedCrashOffer.QUEUED, enabled.offer(1, sinkReady = false))
        assertEquals(BoundedManagedCrashOffer.QUEUED, enabled.offer(2, sinkReady = false))
        assertEquals(BoundedManagedCrashOffer.DROPPED, enabled.offer(3, sinkReady = false))
        assertEquals(listOf(1, 2), enabled.resolve(enabled = true, sinkReady = true))
        assertEquals(0, enabled.pendingCount())
        assertEquals(BoundedManagedCrashOffer.DELIVER, enabled.offer(4, sinkReady = true))

        assertEquals(BoundedManagedCrashOffer.QUEUED, enabled.offer(5, sinkReady = false))
        assertEquals(emptyList(), enabled.resolve(enabled = true, sinkReady = false))
        assertEquals(1, enabled.pendingCount())
        assertEquals(listOf(5), enabled.resolve(enabled = true, sinkReady = true))

        val disabled = BoundedManagedCrashBuffer<Int>(capacity = 2)
        assertEquals(BoundedManagedCrashOffer.QUEUED, disabled.offer(6, sinkReady = false))
        assertEquals(emptyList(), disabled.resolve(enabled = false, sinkReady = false))
        assertEquals(0, disabled.pendingCount())
        assertEquals(BoundedManagedCrashOffer.DROPPED, disabled.offer(7, sinkReady = true))
    }

    @Test
    fun app_process_roles_are_positive_and_do_not_reuse_the_handler_role() {
        assertFailsWith<IllegalArgumentException> {
            TraceboxConfiguration.Builder().setProcessRole(0)
        }
        assertFailsWith<IllegalArgumentException> {
            TraceboxConfiguration.Builder().setProcessRole(2)
        }
        TraceboxConfiguration.Builder().setProcessRole(11).build()
    }

    @Test
    fun post_durability_failures_distinguish_restriction_from_partial_enablement() {
        val standard = PolicySnapshot(4, 0L, disabled = false)
        val minimal = PolicySnapshot(5, 12L, disabled = false)
        val disabled = PolicySnapshot(6, Long.MAX_VALUE, disabled = true)

        assertEquals(
            PolicyUpdateResult.LOCAL_ONLY_RESTRICTED,
            postDurabilityPolicyResult(standard, minimal),
        )
        assertEquals(
            PolicyUpdateResult.LOCAL_ONLY_RESTRICTED,
            postDurabilityPolicyResult(minimal, disabled),
        )
        assertEquals(
            PolicyUpdateResult.PARTIAL,
            postDurabilityPolicyResult(disabled, PolicySnapshot(7, 0L, disabled = false)),
        )
        assertEquals(
            PolicyUpdateResult.PARTIAL,
            postDurabilityPolicyResult(minimal, PolicySnapshot(6, 0L, disabled = false)),
        )
    }

    @Test
    fun repair_marker_requires_an_exact_primary_explicit_enable_and_never_clears_for_disabled() {
        assertEquals(true, repairMarkerAllowsEnable(false, false, null, 7))
        assertEquals(false, repairMarkerAllowsEnable(true, false, 7, 7))
        assertEquals(false, repairMarkerAllowsEnable(true, true, null, 7))
        assertEquals(false, repairMarkerAllowsEnable(true, true, 6, 7))
        assertEquals(true, repairMarkerAllowsEnable(true, true, 7, 7))

        assertEquals(
            false,
            shouldClearPolicyRepairMarker(
                DiagnosticsProfile.DISABLED,
                PolicyUpdateResult.SUCCESS,
            ),
        )
        assertEquals(
            false,
            shouldClearPolicyRepairMarker(
                DiagnosticsProfile.STANDARD_DIAGNOSTICS,
                PolicyUpdateResult.PARTIAL,
            ),
        )
        assertEquals(
            true,
            shouldClearPolicyRepairMarker(
                DiagnosticsProfile.STANDARD_DIAGNOSTICS,
                PolicyUpdateResult.SUCCESS,
            ),
        )
    }

    @Test
    fun credential_and_direct_boot_policy_must_match_the_exact_tuple() {
        val credential = PolicySnapshot(epoch = 42, denyMask = 0x51L, disabled = false)

        assertTrue(
            localPolicyTuplesConverge(
                credential,
                DenyState(epoch = 42, disabled = false, c0DenyMask = 0x51L),
            ),
        )
        assertFalse(localPolicyTuplesConverge(credential, null))
        assertFalse(
            localPolicyTuplesConverge(
                credential,
                DenyState(epoch = 41, disabled = false, c0DenyMask = 0x51L),
            ),
        )
        assertFalse(
            localPolicyTuplesConverge(
                credential,
                DenyState(epoch = 42, disabled = true, c0DenyMask = 0x51L),
            ),
        )
        assertFalse(
            localPolicyTuplesConverge(
                credential,
                DenyState(epoch = 42, disabled = false, c0DenyMask = 0x50L),
            ),
        )
    }

    @Test
    fun raw_exit_import_requires_current_role_bound_permission() {
        val category = 1L shl 17
        val identity = ByteArray(32) { (it * 3).toByte() }
        val current = PolicySnapshot(epoch = 42, denyMask = 0L, disabled = false)
        val authorized = ExitPolicyToken(
            epoch = 42,
            rawArtifactAllowed = true,
            processInstanceId = identity,
            processRole = 11,
        )

        assertTrue(rawExitTokenAuthorizes(authorized, current, category))
        assertFalse(
            rawExitTokenAuthorizes(
                authorized.copy(processRole = null),
                current,
                category,
            ),
        )
        assertFalse(
            rawExitTokenAuthorizes(
                authorized.copy(epoch = 41),
                current,
                category,
            ),
        )
        assertFalse(
            rawExitTokenAuthorizes(
                authorized.copy(epoch = 43),
                current,
                category,
            ),
        )
        assertFalse(
            rawExitTokenAuthorizes(
                authorized.copy(rawArtifactAllowed = false),
                current,
                category,
            ),
        )
        assertFalse(
            rawExitTokenAuthorizes(
                authorized,
                current.copy(disabled = true),
                category,
            ),
        )
        assertFalse(
            rawExitTokenAuthorizes(
                authorized,
                current.copy(denyMask = category),
                category,
            ),
        )
    }

    @Test
    fun native_slot_paths_are_parametric_for_app_and_reserved_handler_roles() {
        val directory = Files.createTempDirectory("tracebox-native-slot-path")
        val roles = listOf(11, TraceboxHandlerService.PROCESS_ROLE_HANDLER)

        roles.forEach { role ->
            assertEquals(
                directory.resolve("tracebox-emergency-$role.bin"),
                nativeSlotPath(directory, NativeSlotKind.EMERGENCY, role),
            )
            assertEquals(
                directory.resolve("tracebox-rust-panic-$role.bin"),
                nativeSlotPath(directory, NativeSlotKind.RUST_PANIC, role),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            nativeSlotPath(directory, NativeSlotKind.EMERGENCY, role = -1)
        }
    }

    @Test
    fun native_client_lifecycle_path_binds_role_and_raw_identity_canonically() {
        val directory = Files.createTempDirectory("tracebox-native-client-lifecycle")
        val rawIdentity = ByteArray(32) { index -> (index + 1).toByte() }
        assertEquals(
            directory.resolve(
                "client-r11-0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20" +
                    ".tbclient",
            ),
            nativeClientLifecyclePath(directory, 11, rawIdentity),
        )
        assertFailsWith<IllegalArgumentException> {
            nativeClientLifecyclePath(directory, 0, rawIdentity)
        }
        assertFailsWith<IllegalArgumentException> {
            nativeClientLifecyclePath(directory, 1, ByteArray(31) { 1 })
        }
        assertFailsWith<IllegalArgumentException> {
            nativeClientLifecyclePath(directory, 1, ByteArray(32))
        }
    }

    @Test
    fun primary_coordinator_lease_is_exclusive_and_reacquirable() {
        val path = Files.createTempDirectory("tracebox-primary-lease")
            .resolve(".tracebox-primary.lock")
        val first = assertNotNull(PrimaryCoordinatorLease.tryAcquire(path))
        try {
            assertNull(PrimaryCoordinatorLease.tryAcquire(path))
        } finally {
            first.close()
        }
        assertNotNull(PrimaryCoordinatorLease.tryAcquire(path)).close()
    }

    @Test
    fun fixed_append_reservations_and_device_protected_controls_are_exact() {
        assertEquals(
            64L * 1024,
            credentialProtectedReservationBytes(owned("ce", "identity-lifecycle-v1"), 168L),
        )
        assertEquals(
            64L * 1024,
            credentialProtectedReservationBytes(owned("ce", "exit-tombstones-v1"), 42L),
        )
        assertEquals(
            152L,
            credentialProtectedReservationBytes(
                owned("ce", "exit-import-journal/${"A".repeat(43)}.tbexitjournal"),
                68L,
            ),
        )
        assertEquals(
            UidBucket.METADATA,
            classifyDeviceProtectedStorage(owned("de", "active-deny-v1.new")),
        )
        assertEquals(
            UidBucket.EMERGENCY,
            classifyDeviceProtectedStorage(
                owned("de", DirectBootLayout.RECORDS_FILE_NAME),
            ),
        )
        assertEquals(
            UidBucket.METADATA,
            classifyDeviceProtectedStorage(
                owned("de", DirectBootLayout.ACTIVATION_FILE_NAME),
            ),
        )
        assertEquals(
            UidBucket.METADATA,
            classifyDeviceProtectedStorage(
                owned("de", DirectBootLayout.ACTIVATION_TEMP_FILE_NAME),
            ),
        )
        assertNull(classifyDeviceProtectedStorage(owned("de", "arbitrary.records")))
        assertNull(classifyDeviceProtectedStorage(owned("ce", "active-deny-v1")))
    }

    @Test
    fun public_direct_boot_results_preserve_every_bounded_internal_outcome() {
        val mapped = DirectBootWriteResult.entries.associateWith(::mapDirectBootWriteResult)
        assertEquals(DirectBootWriteResult.entries.size, mapped.values.toSet().size)
        assertEquals(
            TraceboxDirectBootWriteResult.WRITTEN,
            mapped.getValue(DirectBootWriteResult.WRITTEN),
        )
        assertEquals(
            TraceboxDirectBootWriteResult.STORAGE_INELIGIBLE,
            mapped.getValue(DirectBootWriteResult.STORAGE_INELIGIBLE),
        )
        assertEquals(
            TraceboxDirectBootWriteResult.INVALID_STORAGE,
            mapped.getValue(DirectBootWriteResult.INVALID_STORAGE),
        )
    }

    private fun owned(rootId: String, relative: String): OwnedStoragePath =
        OwnedStoragePath(rootId, relative, relative.substringAfterLast('/'))
}
