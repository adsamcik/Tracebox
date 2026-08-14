package dev.tracebox.storage

import dev.tracebox.core.CommittedPolicyProvider
import dev.tracebox.core.GateResult
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.WriterPolicyGate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageMutationBarrierTest {
    @Test
    fun existing_lease_never_creates_a_missing_root_or_lock_and_rejects_reentry() {
        val missingRoot = Path.of(
            "build",
            "storage-mutation-barrier-tests",
            UUID.randomUUID().toString(),
            "missing-ce",
        ).toAbsolutePath()
        assertNull(ExistingUidStorageMutationLease.tryAcquire(missingRoot, 50))
        assertFalse(Files.exists(missingRoot))

        Files.createDirectories(missingRoot)
        assertNull(ExistingUidStorageMutationLease.tryAcquire(missingRoot, 50))
        assertFalse(
            Files.exists(
                missingRoot.resolve(UidWideStorageMutationBarrier.LOCK_FILE_NAME),
            ),
        )

        coordinator(missingRoot).withStorageMutation { Unit }
        val lease = assertNotNull(
            ExistingUidStorageMutationLease.tryAcquire(missingRoot, 500),
        )
        lease.use {
            assertNull(ExistingUidStorageMutationLease.tryAcquire(missingRoot, 50))
        }
        assertNotNull(ExistingUidStorageMutationLease.tryAcquire(missingRoot, 500))
            .close()
    }

    @Test
    fun existing_lease_times_out_behind_an_active_uid_mutation() {
        val fixture = fixture()
        fixture.coordinator.withStorageMutation { Unit }
        val held = assertNotNull(
            ExistingUidStorageMutationLease.tryAcquire(fixture.root, 500),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val blocked = executor.submit<ExistingUidStorageMutationLease?> {
                ExistingUidStorageMutationLease.tryAcquire(fixture.root, 100)
            }
            assertNull(blocked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            held.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
        assertNotNull(ExistingUidStorageMutationLease.tryAcquire(fixture.root, 500))
            .close()
    }

    @Test
    fun distinct_accounting_roots_share_one_explicit_device_protected_barrier() {
        val base = Path.of(
            "build",
            "storage-mutation-barrier-tests",
            UUID.randomUUID().toString(),
        ).toAbsolutePath()
        val sharedBarrierRoot = base.resolve("de").also(Files::createDirectories)
        val first = coordinator(
            base.resolve("ce-first").also(Files::createDirectories),
            sharedBarrierRoot,
        )
        val second = coordinator(
            base.resolve("ce-second").also(Files::createDirectories),
            sharedBarrierRoot,
        )
        first.withStorageMutation { Unit }

        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstMutation = executor.submit {
                first.withStorageMutation {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val secondMutation = executor.submit {
                second.withStorageMutation {
                    secondEntered.countDown()
                }
            }
            assertFalse(
                secondEntered.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS),
                "coordinators with distinct ledgers crossed the shared DE barrier",
            )
            assertNull(ExistingUidStorageMutationLease.tryAcquire(sharedBarrierRoot, 100))

            releaseFirst.countDown()
            firstMutation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            secondMutation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val lease = assertNotNull(
                ExistingUidStorageMutationLease.tryAcquire(sharedBarrierRoot, 500),
            )
            lease.use {
                val blocked = executor.submit<ExistingUidStorageMutationLease?> {
                    ExistingUidStorageMutationLease.tryAcquire(sharedBarrierRoot, 100)
                }
                assertNull(blocked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun reconciliation_waits_for_reserve_create_finalize_and_preserves_the_live_allocation() {
        val fixture = fixture()
        val path = fixture.root.resolve("concurrent.tbraw")
        val reserved = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val reconcileStarted = CountDownLatch(1)
        val reconcileFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val create = executor.submit {
                fixture.coordinator.withStorageMutation {
                    assertTrue(fixture.coordinator.reserve(path, UidBucket.RAW_ARTIFACTS, 8))
                    reserved.countDown()
                    assertTrue(allowCreate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    Files.write(path, ByteArray(8), java.nio.file.StandardOpenOption.CREATE_NEW)
                    assertTrue(fixture.coordinator.owns(path, UidBucket.RAW_ARTIFACTS, 8))
                }
            }
            assertTrue(reserved.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val reconcile = executor.submit<StorageOwnershipReport> {
                reconcileStarted.countDown()
                try {
                    fixture.reconciler.reconcile()
                } finally {
                    reconcileFinished.countDown()
                }
            }
            assertTrue(reconcileStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(
                reconcileFinished.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS),
                "reconcile crossed an active reserve-before-create transaction",
            )

            allowCreate.countDown()
            create.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertIs<StorageOwnershipReport.Complete>(
                reconcile.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertTrue(Files.isRegularFile(path))
            assertTrue(fixture.coordinator.owns(path, UidBucket.RAW_ARTIFACTS, 8))
        } finally {
            allowCreate.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun segment_create_rechecks_eligibility_inside_the_barrier_and_holds_it_through_file_creation() {
        val fixture = fixture()
        val path = fixture.root.resolve("guarded.tbseg")
        val eligibilityEntered = CountDownLatch(1)
        val allowEligibility = CountDownLatch(1)
        val reconcileStarted = CountDownLatch(1)
        val reconcileFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit<SegmentWriter> {
                SegmentWriter.create(
                    path,
                    header(7),
                    allowAllWriterGate(),
                    RoleQuotaLedger(
                        RoleQuotaPolicy(mapOf(PROCESS_ROLE to DATA_LIMIT)),
                        fixture.root,
                    ),
                    fixture.coordinator,
                    StorageMutationEligibility {
                        eligibilityEntered.countDown()
                        check(allowEligibility.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        TraceboxOwnedStorageRoot.isEligible(fixture.root)
                    },
                )
            }
            assertTrue(eligibilityEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val reconcile = executor.submit<StorageOwnershipReport> {
                reconcileStarted.countDown()
                try {
                    fixture.reconciler.reconcile()
                } finally {
                    reconcileFinished.countDown()
                }
            }
            assertTrue(reconcileStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(
                reconcileFinished.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS),
                "reconcile crossed SegmentWriter.create after its eligibility check",
            )

            allowEligibility.countDown()
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).close()
            assertIs<StorageOwnershipReport.Complete>(
                reconcile.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertTrue(Files.isRegularFile(path))
            assertTrue(
                fixture.coordinator.owns(
                    path,
                    UidBucket.ROLE_SEGMENTS,
                    Files.size(path) + SEGMENT_SEAL_BYTES,
                ),
            )
        } finally {
            allowEligibility.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun deletion_holds_the_destructive_phase_and_stale_writer_fails_after_marker_recheck() {
        val fixture = fixture()
        val captureAllowed = AtomicBoolean(true)
        val deletionHoldingBarrier = CountDownLatch(1)
        val finishDeletion = CountDownLatch(1)
        val writerAttemptStarted = CountDownLatch(1)
        val writerEligibilityEntered = CountDownLatch(1)
        val writerPath = fixture.root.resolve("late.tbseg")
        val deletion = JournaledStorageTreeDeletion(
            ownership = fixture.reconciler,
            transactionId = "barrier-race",
            denyCommit = StorageDeletionDenyCommit {
                captureAllowed.set(false)
                true
            },
            denyVerification = StorageDeletionDenyVerification { !captureAllowed.get() },
            quiesceParticipants = listOf(StorageQuiesceParticipant("writers") { true }),
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val deleteFuture = executor.submit<StorageTreeDeletionReport> {
                deletion.deleteAll { boundary ->
                    if (boundary.state == StorageTreeDeletionState.STORES_MARKED_INELIGIBLE) {
                        deletionHoldingBarrier.countDown()
                        check(finishDeletion.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                    true
                }
            }
            assertTrue(deletionHoldingBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val writerFuture = executor.submit<SegmentWriter> {
                writerAttemptStarted.countDown()
                SegmentWriter.create(
                    writerPath,
                    header(8),
                    allowAllWriterGate(),
                    RoleQuotaLedger(
                        RoleQuotaPolicy(mapOf(PROCESS_ROLE to DATA_LIMIT)),
                        fixture.root,
                    ),
                    fixture.coordinator,
                    StorageMutationEligibility {
                        writerEligibilityEntered.countDown()
                        captureAllowed.get() && TraceboxOwnedStorageRoot.isEligible(fixture.root)
                    },
                )
            }
            assertTrue(writerAttemptStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(
                writerEligibilityEntered.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS),
                "late writer evaluated eligibility before deletion released its destructive barrier",
            )

            finishDeletion.countDown()
            assertIs<StorageTreeDeletionReport.Complete>(
                deleteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertTrue(writerEligibilityEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val failure = runCatching {
                writerFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.exceptionOrNull()
            assertIs<ExecutionException>(failure)
            assertIs<SegmentException.StorageIneligible>(failure.cause)
            assertFalse(Files.exists(writerPath))
            assertFalse(fixture.coordinator.allocations().containsKey(writerPath))
        } finally {
            finishDeletion.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun deletion_rechecks_durable_deny_after_waiting_for_the_mutation_barrier() {
        val fixture = fixture()
        val durableDeny = AtomicBoolean(false)
        val allowDenyCommitReturn = CountDownLatch(1)
        val barrierHeld = CountDownLatch(1)
        val releaseBarrier = CountDownLatch(1)
        val initialDenyCommitted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val deletion = JournaledStorageTreeDeletion(
                ownership = fixture.reconciler,
                transactionId = "deny-recheck",
                denyCommit = StorageDeletionDenyCommit {
                    durableDeny.set(true)
                    initialDenyCommitted.countDown()
                    check(allowDenyCommitReturn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    true
                },
                denyVerification = StorageDeletionDenyVerification { durableDeny.get() },
                quiesceParticipants = listOf(StorageQuiesceParticipant("writers") { true }),
            )
            val deleteFuture = executor.submit<StorageTreeDeletionReport> { deletion.deleteAll() }
            assertTrue(initialDenyCommitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val holder = executor.submit {
                fixture.coordinator.withStorageMutation {
                    barrierHeld.countDown()
                    check(releaseBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
            }
            assertTrue(barrierHeld.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            allowDenyCommitReturn.countDown()

            durableDeny.set(false)
            releaseBarrier.countDown()
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val report = assertIs<StorageTreeDeletionReport.Pending>(
                deleteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertTrue(
                report.failures.any {
                    it.reason == StorageTreeDeletionFailureReason.DENY_COMMIT_REJECTED
                },
            )
            assertTrue(TraceboxOwnedStorageRoot.isEligible(fixture.root))
        } finally {
            allowDenyCommitReturn.countDown()
            releaseBarrier.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun mutation_barrier_is_reentrant_across_coordinator_instances_in_one_process() {
        val fixture = fixture()
        val restarted = coordinator(fixture.root)
        fixture.coordinator.withStorageMutation {
            restarted.withStorageMutation {
                assertTrue(
                    fixture.coordinator.reserve(
                        fixture.root.resolve("nested.tbseg"),
                        UidBucket.ROLE_SEGMENTS,
                        1,
                    ),
                )
            }
        }
    }

    private data class Fixture(
        val root: Path,
        val coordinator: UidWideQuotaCoordinator,
        val reconciler: UidWideStorageReconciler,
    )

    private fun fixture(): Fixture {
        val root = Path.of(
            "build",
            "storage-mutation-barrier-tests",
            UUID.randomUUID().toString(),
            "ce",
        ).toAbsolutePath().also(Files::createDirectories)
        TraceboxOwnedStorageRoot.claim(root)
        val coordinator = coordinator(root)
        val reconciler = UidWideStorageReconciler(
            root,
            coordinator,
            listOf(
                OwnedStorageRoot(
                    id = "ce",
                    path = root,
                    reservationSizer = { owned, physicalBytes ->
                        if (owned.fileName.endsWith(".tbseg")) {
                            physicalBytes + SEGMENT_SEAL_BYTES
                        } else {
                            physicalBytes
                        }
                    },
                    classifier = { owned ->
                        when {
                            owned.fileName.endsWith(".tbseg") -> UidBucket.ROLE_SEGMENTS
                            owned.fileName.endsWith(".tbraw") -> UidBucket.RAW_ARTIFACTS
                            owned.fileName == ".tracebox-role-quota.lock" -> UidBucket.METADATA
                            else -> null
                        }
                    },
                ),
            ),
        )
        return Fixture(root, coordinator, reconciler)
    }

    private fun coordinator(
        root: Path,
        mutationBarrierRoot: Path = root,
    ): UidWideQuotaCoordinator =
        UidWideQuotaCoordinator(
            root,
            UidQuota(
                UidBucket.entries.associateWith {
                    if (it == UidBucket.METADATA) METADATA_LIMIT else DATA_LIMIT
                },
            ),
            UidBucket.entries.associateWith { 4_096 },
            mutationBarrierRoot,
        )

    private fun allowAllWriterGate(): WriterPolicyGate =
        WriterPolicyGate(
            CommittedPolicyProvider { PolicySnapshot(epoch = 1, denyMask = 0, disabled = false) },
        ).also {
            assertEquals(GateResult.Reloaded, it.reload())
        }

    private fun header(seed: Byte): SegmentHeader =
        SegmentHeader(
            PersistedSegmentIdentity(
                ByteArray(PersistedSegmentIdentity.ID_SIZE) { seed },
                ByteArray(PersistedSegmentIdentity.ID_SIZE) { (seed + 1).toByte() },
            ),
            ByteArray(PersistedSegmentIdentity.ID_SIZE) { 9 },
            policyGeneration = 1,
            flags = 0,
            processRole = PROCESS_ROLE,
        )

    private companion object {
        const val PROCESS_ROLE = 1
        const val DATA_LIMIT = 1024L * 1024
        const val METADATA_LIMIT = 1024L * 1024
        const val SEGMENT_SEAL_BYTES = 52L
        const val TIMEOUT_SECONDS = 5L
        const val BLOCKED_ASSERT_MILLIS = 200L
    }
}
