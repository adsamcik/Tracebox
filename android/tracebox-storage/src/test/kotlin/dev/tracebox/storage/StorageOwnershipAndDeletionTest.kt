package dev.tracebox.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageOwnershipAndDeletionTest {
    @Test
    fun reconciliation_preserves_fixed_append_ceiling_instead_of_shrinking_to_physical_prefix() {
        val root = Path.of("build", "storage-ownership-tests", UUID.randomUUID().toString(), "ce")
            .toAbsolutePath()
            .also(Files::createDirectories)
        TraceboxOwnedStorageRoot.claim(root)
        val quota = UidWideQuotaCoordinator(
            root,
            UidQuota(UidBucket.entries.associateWith {
                if (it == UidBucket.METADATA) METADATA_LIMIT else DATA_LIMIT
            }),
            UidBucket.entries.associateWith { 4_096 },
        )
        val journal = root.resolve("identity-lifecycle-v1")
        write(journal, 168)
        val reconciler = UidWideStorageReconciler(
            root,
            quota,
            listOf(
                OwnedStorageRoot(
                    "ce",
                    root,
                    reservationSizer = { path, physicalBytes ->
                        if (path.fileName == "identity-lifecycle-v1") 64L * 1024 else physicalBytes
                    },
                    classifier = { path ->
                        if (path.fileName == "identity-lifecycle-v1") UidBucket.METADATA else null
                    },
                ),
            ),
        )

        assertIs<StorageOwnershipReport.Complete>(reconciler.reconcile())
        assertTrue(quota.owns(journal, UidBucket.METADATA, 64L * 1024))
        assertFalse(quota.owns(journal, UidBucket.METADATA, 168))

        write(journal, 64 * 1024 + 1)
        val oversized = assertIs<StorageOwnershipReport.Partial>(reconciler.reconcile())
        assertTrue(oversized.failures.any {
            it.relativePath == "identity-lifecycle-v1" &&
                it.reason == StorageOwnershipFailureReason.FILE_SIZE_LIMIT
        })
    }

    @Test
    fun exact_ownership_check_reuses_fixed_lifecycle_reservation_without_duplicate_reserve() {
        val fixture = fixture()
        val lifecycle = fixture.ce.resolve(".tracebox-control").resolve("identity-lifecycle-v1")
        assertTrue(fixture.coordinator.reserve(lifecycle, UidBucket.METADATA, 64L * 1024))
        assertTrue(fixture.coordinator.owns(lifecycle, UidBucket.METADATA, 64L * 1024))
        assertFalse(fixture.coordinator.owns(lifecycle, UidBucket.METADATA, 63L * 1024))
        assertFalse(fixture.coordinator.owns(lifecycle, UidBucket.SNAPSHOTS, 64L * 1024))
        assertFalse(fixture.coordinator.reserve(lifecycle, UidBucket.METADATA, 64L * 1024))

        val restarted = UidWideQuotaCoordinator(
            fixture.ce,
            UidQuota(UidBucket.entries.associateWith {
                if (it == UidBucket.METADATA) METADATA_LIMIT else DATA_LIMIT
            }),
            UidBucket.entries.associateWith { 4_096 },
            fixture.directBoot,
        )
        assertTrue(restarted.owns(lifecycle, UidBucket.METADATA, 64L * 1024))
    }

    @Test
    fun typed_direct_boot_gate_validates_and_accounts_external_mutations() {
        val fixture = fixture()
        val relative = "records.tbemergency"
        assertEquals(
            ExternalOwnedStorageMutationResult.Rejected(
                ExternalOwnedStorageMutationFailureReason.ROOT_NOT_DEVICE_PROTECTED,
            ),
            fixture.reconciler.reserveExternal("handler", "native.tbraw", UidBucket.RAW_ARTIFACTS, 4),
        )
        assertEquals(
            ExternalOwnedStorageMutationResult.Rejected(
                ExternalOwnedStorageMutationFailureReason.INVALID_RELATIVE_PATH,
            ),
            fixture.reconciler.reserveExternal(
                "direct_boot",
                "../escape.tbemergency",
                UidBucket.EMERGENCY,
                4,
            ),
        )
        assertEquals(
            ExternalOwnedStorageMutationResult.Rejected(
                ExternalOwnedStorageMutationFailureReason.BUCKET_MISMATCH,
            ),
            fixture.reconciler.reserveExternal(
                "direct_boot",
                relative,
                UidBucket.RAW_ARTIFACTS,
                4,
            ),
        )

        assertEquals(
            ExternalOwnedStorageMutationResult.Applied,
            fixture.reconciler.reserveExternal("direct_boot", relative, UidBucket.EMERGENCY, 10),
        )
        val shadow = fixture.coordinator.allocations().entries.single {
            it.value.bucket == UidBucket.EMERGENCY
        }.key
        assertTrue(shadow.startsWith(fixture.ce.resolve(".tracebox-external-ownership")))
        assertFalse(Files.exists(shadow))
        val physical = fixture.directBoot.resolve(relative)
        write(physical, 10)
        assertEquals(
            ExternalOwnedStorageMutationResult.Applied,
            fixture.reconciler.growExternal("direct_boot", relative, UidBucket.EMERGENCY, 5),
        )
        Files.write(physical, ByteArray(5), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(15L, fixture.coordinator.used(UidBucket.EMERGENCY))
        Files.newByteChannel(physical, java.nio.file.StandardOpenOption.WRITE).use {
            it.truncate(12)
        }
        assertEquals(
            ExternalOwnedStorageMutationResult.Applied,
            fixture.reconciler.resizeExternal("direct_boot", relative, UidBucket.EMERGENCY, 12),
        )
        assertEquals(
            ExternalOwnedStorageMutationResult.Rejected(
                ExternalOwnedStorageMutationFailureReason.PATH_STILL_EXISTS,
            ),
            fixture.reconciler.releaseExternal("direct_boot", relative, UidBucket.EMERGENCY),
        )
        Files.delete(physical)
        assertEquals(
            ExternalOwnedStorageMutationResult.Applied,
            fixture.reconciler.releaseExternal("direct_boot", relative, UidBucket.EMERGENCY),
        )

        TraceboxOwnedStorageRoot.markIneligible(fixture.directBoot)
        assertEquals(
            ExternalOwnedStorageMutationResult.Rejected(
                ExternalOwnedStorageMutationFailureReason.ROOT_INELIGIBLE,
            ),
            fixture.reconciler.reserveExternal("direct_boot", relative, UidBucket.EMERGENCY, 10),
        )
        assertEquals(
            ExternalOwnedStorageMutationResult.Applied,
            fixture.reconciler.reserveExternal(
                "direct_boot",
                "deny.tbtemp",
                UidBucket.METADATA,
                8,
            ),
        )
    }

    @Test
    fun credential_protected_external_gate_admits_only_exact_preserved_metadata_controls() {
        val fixture = fixture()
        val fallback = fixture.base.resolve("api23-policy-fallback").also(Files::createDirectories)
        TraceboxOwnedStorageRoot.claim(fallback)
        val root = OwnedStorageRoot(
            id = "api23",
            path = fallback,
            preservedRelativePaths = setOf("active-deny-v1"),
            domain = OwnedStorageDomain.CREDENTIAL_PROTECTED,
            classifier = { path ->
                if (path.relativePath == "active-deny-v1") UidBucket.METADATA
                else if (path.relativePath == "ordinary.tbraw") UidBucket.RAW_ARTIFACTS
                else null
            },
        )
        val reconciler = UidWideStorageReconciler(
            fixture.ce,
            fixture.coordinator,
            fixture.roots + root,
        )

        assertEquals(
            ExternalOwnedStorageMutationResult.Applied,
            reconciler.reserveExternal("api23", "active-deny-v1", UidBucket.METADATA, 32),
        )
        val control = fallback.resolve("active-deny-v1")
        write(control, 32)
        assertEquals(
            ExternalOwnedStorageMutationResult.Applied,
            reconciler.resizeExternal("api23", "active-deny-v1", UidBucket.METADATA, 32),
        )
        Files.delete(control)
        assertEquals(
            ExternalOwnedStorageMutationResult.Applied,
            reconciler.releaseExternal("api23", "active-deny-v1", UidBucket.METADATA),
        )
        assertEquals(
            ExternalOwnedStorageMutationResult.Rejected(
                ExternalOwnedStorageMutationFailureReason.ROOT_NOT_DEVICE_PROTECTED,
            ),
            reconciler.reserveExternal("api23", "ordinary.tbraw", UidBucket.RAW_ARTIFACTS, 32),
        )
    }

    @Test
    fun ce_and_direct_boot_mutation_locks_are_internal_and_preserved_by_deletion() {
        val fixture = fixture()
        val credentialCoordinator = UidWideQuotaCoordinator(
            fixture.ce,
            UidQuota(UidBucket.entries.associateWith { DATA_LIMIT }),
            UidBucket.entries.associateWith { 16 },
        )
        val directBootCoordinator = UidWideQuotaCoordinator(
            fixture.directBoot,
            UidQuota(UidBucket.entries.associateWith { DATA_LIMIT }),
            UidBucket.entries.associateWith { 16 },
        )
        credentialCoordinator.withStorageMutation { Unit }
        directBootCoordinator.withStorageMutation { Unit }
        val credentialLock = fixture.ce.resolve(
            UidWideStorageMutationBarrier.LOCK_FILE_NAME,
        )
        val directBootLock = fixture.directBoot.resolve(
            UidWideStorageMutationBarrier.LOCK_FILE_NAME,
        )
        assertTrue(Files.isRegularFile(credentialLock))
        assertTrue(Files.isRegularFile(directBootLock))

        val reconciled = assertIs<StorageOwnershipReport.Complete>(
            fixture.reconciler.reconcile(),
        )
        assertEquals(0, reconciled.scannedFiles)
        assertTrue(Files.isRegularFile(credentialLock))
        assertTrue(Files.isRegularFile(directBootLock))

        assertIs<StorageTreeDeletionReport.Complete>(
            fixture.deletion("preserve-mutation-locks").deleteAll(),
        )
        assertTrue(Files.isRegularFile(credentialLock))
        assertTrue(Files.isRegularFile(directBootLock))
        assertFalse(TraceboxOwnedStorageRoot.isEligible(fixture.directBoot))
    }

    @Test
    fun post_delete_reactivation_requires_empty_data_and_preserves_deny_control() {
        val fixture = fixture()
        val data = fixture.directBoot.resolve("records.tbemergency")
        val deny = fixture.directBoot.resolve("deny.tbtemp")
        write(data, 5)
        write(deny, 4)
        TraceboxOwnedStorageRoot.markIneligible(fixture.directBoot)
        assertIs<StorageOwnershipReport.Complete>(fixture.reconciler.reconcile())

        val blocked = assertIs<StorageRootReactivationResult.Rejected>(
            fixture.reconciler.reactivateRoot("direct_boot"),
        )
        assertEquals(StorageRootReactivationFailureReason.OWNED_FILES_REMAIN, blocked.reason)
        assertEquals("records.tbemergency", blocked.relativePath)
        assertFalse(TraceboxOwnedStorageRoot.isEligible(fixture.directBoot))

        Files.delete(data)
        assertIs<StorageOwnershipReport.Complete>(fixture.reconciler.reconcile())
        val root = fixture.roots.single { it.id == "direct_boot" }
        val markerKey = fixture.reconciler.accountingKey(
            root,
            fixture.directBoot.resolve(TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE),
        )
        assertTrue(fixture.coordinator.owns(markerKey, UidBucket.METADATA, MARKER_RESERVE))

        assertEquals(
            StorageRootReactivationResult.Reactivated,
            fixture.reconciler.reactivateRoot("direct_boot"),
        )
        assertTrue(TraceboxOwnedStorageRoot.isEligible(fixture.directBoot))
        assertTrue(Files.exists(deny))
        assertFalse(fixture.coordinator.allocations().containsKey(markerKey))
        assertEquals(
            StorageRootReactivationResult.Rejected(StorageRootReactivationFailureReason.NOT_INELIGIBLE),
            fixture.reconciler.reactivateRoot("direct_boot"),
        )
    }

    @Test
    fun reactivation_resumes_from_atomically_renamed_marker() {
        val fixture = fixture()
        TraceboxOwnedStorageRoot.markIneligible(fixture.directBoot)
        assertIs<StorageOwnershipReport.Complete>(fixture.reconciler.reconcile())
        Files.move(
            fixture.directBoot.resolve(TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE),
            fixture.directBoot.resolve(TraceboxOwnedStorageRoot.REACTIVATING_MARKER_FILE),
        )
        assertFalse(TraceboxOwnedStorageRoot.isEligible(fixture.directBoot))
        assertIs<StorageOwnershipReport.Complete>(fixture.reconciler.reconcile())

        assertEquals(
            StorageRootReactivationResult.Reactivated,
            fixture.reconciler.reactivateRoot("direct_boot"),
        )
        assertTrue(TraceboxOwnedStorageRoot.isEligible(fixture.directBoot))
    }

    @Test
    fun reconciles_ce_handler_and_direct_boot_files_into_one_durable_uid_ledger() {
        val fixture = fixture()
        val expected = linkedMapOf(
            fixture.ce.resolve("ordinary.tbseg") to UidBucket.ROLE_SEGMENTS,
            fixture.handler.resolve("native.tbraw") to UidBucket.RAW_ARTIFACTS,
            fixture.handler.resolve("pending.tbsummary") to UidBucket.SUMMARY_SPOOL,
            fixture.ce.resolve("import.tbstaging") to UidBucket.SUMMARY_STAGING,
            fixture.ce.resolve("shared.tbdiag") to UidBucket.SNAPSHOTS,
            fixture.ce.resolve("rewrite.tbcompact") to UidBucket.COMPACTION,
            fixture.directBoot.resolve("emergency.tbemergency") to UidBucket.EMERGENCY,
            fixture.ce.resolve("records.tbtombstone") to UidBucket.METADATA,
        )
        expected.keys.forEachIndexed { index, path -> write(path, index + 1) }

        val initial = fixture.reconciler.reconcile()
        val report = assertIs<StorageOwnershipReport.Complete>(initial, initial.toString())

        assertEquals(expected.size, report.scannedFiles)
        expected.values.toSet().forEach { bucket ->
            val expectedBytes = expected.filterValues { it == bucket }.keys.sumOf(Files::size)
            assertEquals(expectedBytes, report.bytesByBucket[bucket])
            if (bucket != UidBucket.METADATA) {
                assertEquals(expectedBytes, fixture.coordinator.used(bucket))
            }
        }
        val classifiedMetadata = Files.size(fixture.ce.resolve("records.tbtombstone"))
        assertEquals(
            COORDINATOR_RESERVE + 2L * CATALOG_SLOT_BYTES + 3L * MARKER_RESERVE + classifiedMetadata,
            fixture.coordinator.used(UidBucket.METADATA),
        )

        val restarted = fixture.restartReconciler()
        val restartReport = assertIs<StorageOwnershipReport.Complete>(restarted.reconcile())
        assertEquals(expected.size, restartReport.scannedFiles)
        assertEquals(report.bytesByBucket, restartReport.bytesByBucket)
    }

    @Test
    fun reconciliation_releases_stale_external_ownership_and_tracks_growth() {
        val fixture = fixture()
        val raw = fixture.handler.resolve("native.tbraw")
        write(raw, 9)
        val initial = fixture.reconciler.reconcile()
        assertIs<StorageOwnershipReport.Complete>(initial, initial.toString())
        assertEquals(9L, fixture.coordinator.used(UidBucket.RAW_ARTIFACTS))

        write(raw, 23)
        assertIs<StorageOwnershipReport.Complete>(fixture.reconciler.reconcile())
        assertEquals(23L, fixture.coordinator.used(UidBucket.RAW_ARTIFACTS))

        Files.delete(raw)
        val released = assertIs<StorageOwnershipReport.Complete>(fixture.restartReconciler().reconcile())
        assertEquals(1, released.releasedReservations)
        assertEquals(0L, fixture.coordinator.used(UidBucket.RAW_ARTIFACTS))
    }

    @Test
    fun reconciliation_reclaims_local_and_external_reserve_before_create_crash_windows() {
        val fixture = fixture()
        val missingLocal = fixture.ce.resolve("never-created.tbseg")
        val missingExternal = fixture.directBoot.resolve("never-created.tbemergency")
        assertTrue(fixture.coordinator.reserve(missingLocal, UidBucket.ROLE_SEGMENTS, 31))
        assertEquals(
            ExternalOwnedStorageMutationResult.Applied,
            fixture.reconciler.reserveExternal(
                "direct_boot",
                "never-created.tbemergency",
                UidBucket.EMERGENCY,
                29,
            ),
        )
        val externalKey = fixture.coordinator.allocations().entries.single {
            it.value.bucket == UidBucket.EMERGENCY
        }.key
        assertFalse(Files.exists(missingLocal))
        assertFalse(Files.exists(missingExternal))

        val report = assertIs<StorageOwnershipReport.Complete>(fixture.reconciler.reconcile())

        assertEquals(2, report.releasedReservations)
        assertEquals(0L, fixture.coordinator.used(UidBucket.ROLE_SEGMENTS))
        assertEquals(0L, fixture.coordinator.used(UidBucket.EMERGENCY))
        assertFalse(fixture.coordinator.allocations().containsKey(missingLocal))
        assertFalse(fixture.coordinator.allocations().containsKey(externalKey))
    }

    @Test
    fun production_metadata_reserve_supports_a_bounded_multi_root_inventory() {
        val fixture = fixture()
        repeat(160) { index ->
            write(fixture.handler.resolve("raw-$index.tbraw"), 1)
        }

        val report = assertIs<StorageOwnershipReport.Complete>(fixture.reconciler.reconcile())
        assertEquals(160, report.scannedFiles)
        assertEquals(160L, report.bytesByBucket[UidBucket.RAW_ARTIFACTS])
        assertEquals(160L, fixture.coordinator.used(UidBucket.RAW_ARTIFACTS))
        assertEquals(160, assertIs<StorageOwnershipReport.Complete>(fixture.restartReconciler().reconcile()).scannedFiles)
    }

    @Test
    fun corrupt_catalog_is_rebuilt_but_reported_partial_once() {
        val fixture = fixture()
        write(fixture.ce.resolve("ordinary.tbseg"), 8)
        assertIs<StorageOwnershipReport.Complete>(fixture.reconciler.reconcile())
        val control = fixture.ce.resolve(".tracebox-control")
        Files.write(control.resolve("ownership-a"), byteArrayOf(1, 2, 3, 4))
        Files.write(control.resolve("ownership-b"), byteArrayOf(5, 6, 7, 8))

        val rebuilt = assertIs<StorageOwnershipReport.Partial>(fixture.restartReconciler().reconcile())
        assertTrue(rebuilt.failures.any { it.reason == StorageOwnershipFailureReason.CATALOG_CORRUPT_REBUILT })
        assertIs<StorageOwnershipReport.Complete>(fixture.restartReconciler().reconcile())
    }

    @Test
    fun unknown_and_over_quota_files_are_honest_partial_results() {
        val unknownFixture = fixture()
        val unknown = unknownFixture.handler.resolve("surprise.bin")
        write(unknown, 4)
        val unknownReport = assertIs<StorageOwnershipReport.Partial>(unknownFixture.reconciler.reconcile())
        assertTrue(unknownReport.failures.any { it.reason == StorageOwnershipFailureReason.UNCLASSIFIED_FILE })
        assertTrue(Files.exists(unknown))

        val constrained = fixture(rawLimit = 3)
        val large = constrained.handler.resolve("native.tbraw")
        write(large, 4)
        val quotaReport = assertIs<StorageOwnershipReport.Partial>(constrained.reconciler.reconcile())
        assertTrue(quotaReport.failures.any { it.reason == StorageOwnershipFailureReason.QUOTA_REJECTED })
        assertEquals(0L, constrained.coordinator.used(UidBucket.RAW_ARTIFACTS))
        assertTrue(Files.exists(large))
    }

    @Test
    fun partial_quota_adoption_rolls_back_new_reservations_without_a_catalog_leak() {
        val fixture = fixture(rawLimit = 3)
        val first = fixture.handler.resolve("first.tbraw")
        val second = fixture.handler.resolve("second.tbraw")
        write(first, 2)
        write(second, 2)

        val partial = assertIs<StorageOwnershipReport.Partial>(fixture.reconciler.reconcile())
        assertTrue(partial.failures.any { it.reason == StorageOwnershipFailureReason.QUOTA_REJECTED })
        assertEquals(0L, fixture.coordinator.used(UidBucket.RAW_ARTIFACTS))

        Files.delete(second)
        assertIs<StorageOwnershipReport.Complete>(fixture.restartReconciler().reconcile())
        assertEquals(2L, fixture.coordinator.used(UidBucket.RAW_ARTIFACTS))
    }

    @Test
    fun deletion_repairs_a_corrupt_fail_closed_marker_after_fresh_quiesce() {
        val fixture = fixture()
        val raw = fixture.handler.resolve("native.tbraw")
        write(raw, 4)
        Files.write(
            fixture.handler.resolve(TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE),
            byteArrayOf(1, 2, 3),
        )

        val ownership = assertIs<StorageOwnershipReport.Partial>(fixture.reconciler.reconcile())
        assertTrue(ownership.failures.any {
            it.reason == StorageOwnershipFailureReason.INELIGIBLE_MARKER_CORRUPT
        })
        assertIs<StorageTreeDeletionReport.Complete>(
            fixture.deletion("corrupt-ineligible").deleteAll(),
        )
        assertFalse(Files.exists(raw))
        assertFalse(TraceboxOwnedStorageRoot.isEligible(fixture.handler))
    }

    @Test
    fun root_claims_reject_filesystem_roots_and_unclaimed_trees() {
        val fixture = fixture()
        assertFailsWith<IllegalArgumentException> {
            OwnedStorageRoot("broad", fixture.ce.root) { UidBucket.METADATA }
        }
        val unclaimed = fixture.base.resolve("unclaimed").also(Files::createDirectories)
        val reconciler = UidWideStorageReconciler(
            fixture.ce,
            fixture.coordinator,
            listOf(OwnedStorageRoot("unclaimed", unclaimed, classifier = ::classify)),
        )
        val report = assertIs<StorageOwnershipReport.Partial>(reconciler.reconcile())
        assertEquals(StorageOwnershipFailureReason.ROOT_NOT_CLAIMED, report.failures.single().reason)
    }

    @Test
    fun failed_global_deny_commit_stops_before_quiesce_or_storage_changes() {
        val fixture = fixture()
        val raw = fixture.handler.resolve("native.tbraw")
        write(raw, 7)
        var quiesceCalled = false
        val report = assertIs<StorageTreeDeletionReport.Pending>(
            fixture.deletion(
                transaction = "deny-rejected",
                denyCommit = StorageDeletionDenyCommit { false },
                participants = listOf(StorageQuiesceParticipant("writers") {
                    quiesceCalled = true
                    true
                }),
            ).deleteAll(),
        )

        assertTrue(report.failures.any {
            it.reason == StorageTreeDeletionFailureReason.DENY_COMMIT_REJECTED
        })
        assertFalse(quiesceCalled)
        assertTrue(Files.exists(raw))
        assertTrue(TraceboxOwnedStorageRoot.isEligible(fixture.handler))
    }

    @Test
    fun failed_quiesce_changes_nothing_and_partial_ownership_never_deletes_data() {
        val fixture = fixture()
        val raw = fixture.handler.resolve("native.tbraw")
        write(raw, 7)
        val deletion = fixture.deletion(
            transaction = "quiesce-denied",
            participants = listOf(
                StorageQuiesceParticipant("jvm") { true },
                StorageQuiesceParticipant("handler") { false },
            ),
        )
        val denied = assertIs<StorageTreeDeletionReport.Pending>(deletion.deleteAll())
        assertTrue(denied.failures.any { it.reason == StorageTreeDeletionFailureReason.QUIESCE_REJECTED })
        assertTrue(Files.exists(raw))
        assertTrue(TraceboxOwnedStorageRoot.isEligible(fixture.handler))

        val unknownFixture = fixture()
        val unknown = unknownFixture.handler.resolve("surprise.bin")
        write(unknown, 3)
        val partial = assertIs<StorageTreeDeletionReport.Pending>(
            unknownFixture.deletion("unknown-file").deleteAll(),
        )
        assertTrue(partial.failures.any { it.reason == StorageTreeDeletionFailureReason.OWNERSHIP_PARTIAL })
        assertTrue(Files.exists(unknown))
        assertFalse(TraceboxOwnedStorageRoot.isEligible(unknownFixture.handler))
    }

    @Test
    fun bounded_retries_delete_every_owned_family_and_keep_fail_closed_markers() {
        val fixture = fixture()
        val owned = listOf(
            fixture.ce.resolve("ordinary.tbseg"),
            fixture.handler.resolve("native.tbraw"),
            fixture.handler.resolve("pending.tbsummary"),
            fixture.ce.resolve("records.tbtombstone"),
            fixture.ce.resolve("shared.tbdiag"),
            fixture.ce.resolve("records.tbidx"),
            fixture.ce.resolve("rewrite.tbtemp"),
            fixture.directBoot.resolve("emergency.tbemergency"),
        )
        owned.forEachIndexed { index, path -> write(path, index + 1) }
        var quiesceCalls = 0
        val deletion = fixture.deletion(
            transaction = "bounded",
            maxDeletes = 2,
            participants = listOf(StorageQuiesceParticipant("all-writers") {
                quiesceCalls++
                true
            }),
        )

        var totalDeleted = 0
        var report: StorageTreeDeletionReport
        do {
            report = deletion.deleteAll()
            totalDeleted += report.deletedFiles
            if (report is StorageTreeDeletionReport.Pending) {
                assertTrue(report.failures.any { it.reason == StorageTreeDeletionFailureReason.BATCH_LIMIT })
            }
        } while (report is StorageTreeDeletionReport.Pending && quiesceCalls < 10)

        assertIs<StorageTreeDeletionReport.Complete>(report)
        assertEquals(owned.size, totalDeleted)
        assertEquals(4, quiesceCalls)
        owned.forEach { assertFalse(Files.exists(it), it.toString()) }
        fixture.roots.forEach {
            assertTrue(TraceboxOwnedStorageRoot.isClaimed(it.path))
            assertFalse(TraceboxOwnedStorageRoot.isEligible(it.path))
        }
        val empty = assertIs<StorageOwnershipReport.Complete>(fixture.restartReconciler().reconcile())
        assertTrue(empty.bytesByBucket.filterKeys { it != UidBucket.METADATA }.values.all { it == 0L })
        UidBucket.entries.filter { it != UidBucket.METADATA }.forEach {
            assertEquals(0L, fixture.coordinator.used(it))
        }
    }

    @Test
    fun process_death_after_physical_delete_is_recovered_from_ownership_catalog() {
        val fixture = fixture()
        val raw = fixture.handler.resolve("native.tbraw")
        write(raw, 13)
        val deletion = fixture.deletion("physical-crash")
        var injected = false

        assertFailsWith<StorageTreeDeletionInterrupted> {
            deletion.deleteAll { boundary ->
                if (!injected && boundary.rootId != null) {
                    injected = true
                    false
                } else {
                    true
                }
            }
        }
        assertFalse(Files.exists(raw))
        assertEquals(13L, fixture.coordinator.used(UidBucket.RAW_ARTIFACTS))

        assertIs<StorageTreeDeletionReport.Complete>(fixture.deletion("physical-crash").deleteAll())
        assertEquals(0L, fixture.coordinator.used(UidBucket.RAW_ARTIFACTS))
    }

    @Test
    fun every_durable_phase_can_resume_after_process_death() {
        val boundaries = listOf(
            StorageTreeDeletionState.REQUESTED,
            StorageTreeDeletionState.DENY_COMMITTED,
            StorageTreeDeletionState.QUIESCED,
            StorageTreeDeletionState.STORES_MARKED_INELIGIBLE,
            StorageTreeDeletionState.DELETING,
        )
        for (boundaryState in boundaries) {
            val fixture = fixture()
            val raw = fixture.handler.resolve("native.tbraw")
            write(raw, 5)
            var injected = false
            val deletion = fixture.deletion("phase-${boundaryState.name.lowercase().replace('_', '-')}")
            assertFailsWith<StorageTreeDeletionInterrupted>(boundaryState.name) {
                deletion.deleteAll { boundary ->
                    if (!injected && boundary.rootId == null && boundary.state == boundaryState) {
                        injected = true
                        false
                    } else {
                        true
                    }
                }
            }
            assertEquals(boundaryState, deletion.currentState())
            assertIs<StorageTreeDeletionReport.Complete>(
                fixture.deletion("phase-${boundaryState.name.lowercase().replace('_', '-')}").deleteAll(),
            )
            assertFalse(Files.exists(raw))
        }
    }

    @Test
    fun corrupt_journals_restart_safely_with_fresh_quiesce() {
        val fixture = fixture()
        val raw = fixture.handler.resolve("native.tbraw")
        write(raw, 6)
        var allow = false
        var acknowledgements = 0
        val deletion = fixture.deletion(
            "corrupt-journal",
            participants = listOf(StorageQuiesceParticipant("writers") {
                acknowledgements++
                allow
            }),
        )
        assertIs<StorageTreeDeletionReport.Pending>(deletion.deleteAll())
        val control = fixture.ce.resolve(".tracebox-control")
        Files.write(control.resolve("delete-corrupt-journal-a"), byteArrayOf(1, 2, 3, 4))
        Files.write(control.resolve("delete-corrupt-journal-b"), byteArrayOf(5, 6, 7, 8))
        assertNull(deletion.currentState())
        assertTrue(Files.exists(raw))

        allow = true
        assertIs<StorageTreeDeletionReport.Complete>(deletion.deleteAll())
        assertEquals(2, acknowledgements)
        assertFalse(Files.exists(raw))
    }

    private data class Fixture(
        val base: Path,
        val ce: Path,
        val handler: Path,
        val directBoot: Path,
        val coordinator: UidWideQuotaCoordinator,
        val roots: List<OwnedStorageRoot>,
        val reconciler: UidWideStorageReconciler,
    ) {
        fun restartReconciler(): UidWideStorageReconciler =
            UidWideStorageReconciler(ce, coordinator, roots)

        fun deletion(
            transaction: String,
            maxDeletes: Int = 128,
            denyCommit: StorageDeletionDenyCommit = StorageDeletionDenyCommit { true },
            participants: List<StorageQuiesceParticipant> =
                listOf(StorageQuiesceParticipant("all-writers") { true }),
        ): JournaledStorageTreeDeletion =
            JournaledStorageTreeDeletion(
                restartReconciler(),
                transaction,
                denyCommit,
                StorageDeletionDenyVerification { true },
                participants,
                maxDeletes,
            )
    }

    private fun fixture(rawLimit: Long = DATA_LIMIT): Fixture {
        val base = Path.of("build", "storage-ownership-tests", UUID.randomUUID().toString())
            .toAbsolutePath()
            .also(Files::createDirectories)
        val ce = base.resolve("credential").also(Files::createDirectories)
        val handler = base.resolve("handler").also(Files::createDirectories)
        val directBoot = base.resolve("device-protected").also(Files::createDirectories)
        listOf(ce, handler, directBoot).forEach(TraceboxOwnedStorageRoot::claim)
        val quota = UidQuota(
            UidBucket.entries.associateWith {
                when (it) {
                    UidBucket.RAW_ARTIFACTS -> rawLimit
                    UidBucket.METADATA -> METADATA_LIMIT
                    else -> DATA_LIMIT
                }
            },
        )
        val coordinator = UidWideQuotaCoordinator(
            ce,
            quota,
            UidBucket.entries.associateWith { 4_096 },
            directBoot,
        )
        val roots = listOf(
            OwnedStorageRoot("ce", ce, classifier = ::classify),
            OwnedStorageRoot("handler", handler, classifier = ::classify),
            OwnedStorageRoot(
                "direct_boot",
                directBoot,
                preservedRelativePaths = setOf("deny.tbtemp"),
                domain = OwnedStorageDomain.DEVICE_PROTECTED,
                classifier = ::classify,
            ),
        )
        val reconciler = UidWideStorageReconciler(ce, coordinator, roots)
        return Fixture(base, ce, handler, directBoot, coordinator, roots, reconciler)
    }

    private fun write(path: Path, bytes: Int) {
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(bytes) { it.toByte() })
    }

    private companion object {
        const val DATA_LIMIT = 1024L * 1024
        const val METADATA_LIMIT = 1024L * 1024
        const val COORDINATOR_RESERVE = 128L * 1024
        const val CATALOG_SLOT_BYTES = 64L * 1024
        const val MARKER_RESERVE = 64L

        fun classify(path: OwnedStoragePath): UidBucket? = when {
            path.fileName.endsWith(".tbseg") -> UidBucket.ROLE_SEGMENTS
            path.fileName.endsWith(".tbraw") -> UidBucket.RAW_ARTIFACTS
            path.fileName.endsWith(".tbsummary") -> UidBucket.SUMMARY_SPOOL
            path.fileName.endsWith(".tbstaging") -> UidBucket.SUMMARY_STAGING
            path.fileName.endsWith(".tbdiag") -> UidBucket.SNAPSHOTS
            path.fileName.endsWith(".tbcompact") -> UidBucket.COMPACTION
            path.fileName.endsWith(".tbemergency") -> UidBucket.EMERGENCY
            path.fileName.endsWith(".tbtombstone") ||
                path.fileName.endsWith(".tbidx") ||
                path.fileName.endsWith(".tbtemp") -> UidBucket.METADATA
            else -> null
        }
    }
}
