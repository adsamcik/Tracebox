package dev.tracebox

import dev.tracebox.storage.OwnedStorageRoot
import dev.tracebox.storage.StorageOwnershipReport
import dev.tracebox.storage.TraceboxOwnedStorageRoot
import dev.tracebox.storage.UidBucket
import dev.tracebox.storage.UidQuota
import dev.tracebox.storage.UidWideQuotaCoordinator
import dev.tracebox.storage.UidWideStorageReconciler
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManagedIdentityRestartTest {
    @Test
    fun managed_identity_allocation_survives_repeated_startup_reconciliation() {
        val root = createTempDirectory("tracebox-managed-restart")
        TraceboxOwnedStorageRoot.claim(root)
        val journal = root.resolve("identity-lifecycle-managed-v1")
        val identities = mutableSetOf<List<Byte>>()

        repeat(3) {
            // Reopen the durable quota ledger and identity journal as a new process would.
            val quota = coordinator(root)
            val before = if (Files.exists(journal)) Files.readAllBytes(journal) else byteArrayOf()
            val reconciler = UidWideStorageReconciler(
                accountingRoot = root,
                quota = quota,
                roots = listOf(
                    OwnedStorageRoot(
                        id = "ce",
                        path = root,
                        reservationSizer = ::credentialProtectedReservationBytes,
                        classifier = ::classifyCredentialProtectedStorage,
                    ),
                ),
            )
            assertIs<StorageOwnershipReport.Complete>(reconciler.reconcile())
            if (before.isNotEmpty()) {
                // Allocation itself must not be needed to repair the normal restart path.
                assertTrue(quota.owns(journal, UidBucket.METADATA, reservationBytes))
            }
            assertTrue(ensureIdentityJournalReservation(quota, journal))
            val store = ManagedIdentityStore(journal)
            assertTrue(identities.add(store.allocate(1).toList()))
            assertTrue(identities.add(store.allocate(2).toList()))
            val after = Files.readAllBytes(journal)
            assertContentEquals(before, after.copyOf(before.size))
            assertTrue(after.size > before.size)
            assertTrue(after.size < reservationBytes)
        }
        assertEquals(6, identities.size)
    }

    @Test
    fun allocation_repairs_a_legacy_shrunken_reservation_without_losing_identity_history() {
        val root = createTempDirectory("tracebox-managed-reservation-repair")
        val journal = root.resolve("identity-lifecycle-managed-v1")
        val quota = coordinator(root)
        assertTrue(ensureIdentityJournalReservation(quota, journal))
        val originalIdentity = ManagedIdentityStore(journal).allocate(1)
        val originalBytes = Files.readAllBytes(journal)
        // alpha.7 reconciled the managed journal to its physical length on restart.
        assertTrue(quota.resize(journal, UidBucket.METADATA, originalBytes.size.toLong()))

        val restartedQuota = coordinator(root)
        assertFalse(restartedQuota.owns(journal, UidBucket.METADATA, reservationBytes))
        assertFalse(restartedQuota.reserve(journal, UidBucket.METADATA, reservationBytes))
        assertTrue(ensureIdentityJournalReservation(restartedQuota, journal))
        assertTrue(restartedQuota.owns(journal, UidBucket.METADATA, reservationBytes))
        assertContentEquals(originalBytes, Files.readAllBytes(journal))
        val nextIdentity = ManagedIdentityStore(journal).allocate(2)
        assertFalse(originalIdentity.contentEquals(nextIdentity))
        assertContentEquals(originalBytes, Files.readAllBytes(journal).copyOf(originalBytes.size))
    }

    private fun coordinator(root: Path) = UidWideQuotaCoordinator(
        root,
        UidQuota(UidBucket.entries.associateWith { 16L * 1024 * 1024 }),
        UidBucket.entries.associateWith { 512 },
    )

    private companion object {
        const val reservationBytes = 64L * 1024
    }
}
