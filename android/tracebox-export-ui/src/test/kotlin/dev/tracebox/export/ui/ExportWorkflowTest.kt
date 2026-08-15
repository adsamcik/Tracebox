package dev.tracebox.export.ui

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedBreadcrumb
import dev.tracebox.export.CanonicalCbor
import dev.tracebox.export.CborValue
import dev.tracebox.export.DeterministicZip
import dev.tracebox.export.ManifestEncoder
import dev.tracebox.export.InternalIdentity
import dev.tracebox.export.OrdinarySourceRecord
import dev.tracebox.export.PackagePipelineResult
import dev.tracebox.export.PackagePrivacyClass
import dev.tracebox.export.SegmentSource
import dev.tracebox.export.SnapshotPreparer
import dev.tracebox.export.StandardPackagePipeline
import dev.tracebox.export.StandardSnapshotRequest
import dev.tracebox.storage.UidAccounting
import dev.tracebox.storage.UidBucket
import dev.tracebox.storage.UidQuota
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportWorkflowTest {
    private fun identity(seed: Byte) = InternalIdentity.fromBytes(ByteArray(32) { seed })
    private fun accounting(limit: Long = 1_000_000) =
        UidAccounting(UidQuota(mapOf(UidBucket.SNAPSHOTS to limit)), mapOf(UidBucket.SNAPSHOTS to 1))

    private fun request(): StandardSnapshotRequest {
        val segment = identity(2)
        return StandardSnapshotRequest(
            7,
            mapOf(segment to 10),
            listOf(SegmentSource(identity(1), segment, 3, listOf(
                OrdinarySourceRecord(0, GeneratedBreadcrumb(7u, 8u), 100, PackagePrivacyClass.C1),
            ))),
        )
    }

    private fun pipeline(request: StandardSnapshotRequest, limit: Long = 1_000_000): PackagePipelineResult =
        StandardPackagePipeline(
            SnapshotPreparer(accounting(limit), Path.of("build", "phase4-workflow", System.nanoTime().toString())),
            DeterministicZip(),
        ).finalize(request)

    @Test fun disclosure_decodes_the_exact_finalized_bytes() {
        val finalized = assertIs<PackagePipelineResult.Ready>(pipeline(request()))
        val disclosure = assertIs<DisclosureDecodeResult.Decoded>(DisclosureRenderer.render(finalized.packageBytes))
        assertContentEquals(finalized.packageBytes.plaintextSha256(), disclosure.facts.plaintextDigest)
        assertEquals(1, disclosure.facts.includedCount)
        assertEquals(emptyList(), disclosure.facts.rawC2Artifacts)
    }

    @Test fun disclosure_rejects_compressed_entries_before_decompression() {
        val archive = zip(mapOf("manifest.cbor" to byteArrayOf(0)), stored = false)

        assertIs<DisclosureDecodeResult.Invalid>(DisclosureDecoder.decode(archive))
    }

    @Test fun disclosure_rejects_more_entries_than_the_production_writer_allows() {
        val entries = (0..DeterministicZip.MAX_ENTRIES).associate { "entry-$it" to byteArrayOf() }

        assertIs<DisclosureDecodeResult.Invalid>(DisclosureDecoder.decode(zip(entries)))
    }

    @Test fun disclosure_rejects_excessively_nested_manifest_cbor() {
        val nestedManifest = ByteArray(33) { 0x81.toByte() } + byteArrayOf(0)

        assertIs<DisclosureDecodeResult.Invalid>(
            DisclosureDecoder.decode(zip(mapOf("manifest.cbor" to nestedManifest))),
        )
    }

    @Test fun disclosure_requires_exact_package_record_and_schema_versions() {
        assertIs<DisclosureDecodeResult.Decoded>(
            DisclosureDecoder.decode(zip(mapOf("manifest.cbor" to compatibleManifest()))),
        )

        assertIs<DisclosureDecodeResult.Invalid>(
            DisclosureDecoder.decode(
                zip(mapOf("manifest.cbor" to compatibleManifest(packageVersion = 2))),
            ),
        )
        assertIs<DisclosureDecodeResult.Invalid>(
            DisclosureDecoder.decode(
                zip(mapOf("manifest.cbor" to compatibleManifest(recordVersion = 2))),
            ),
        )
        assertIs<DisclosureDecodeResult.Invalid>(
            DisclosureDecoder.decode(
                zip(mapOf("manifest.cbor" to compatibleManifest(schema = ByteArray(32) { 0x5a }))),
            ),
        )
        assertIs<DisclosureDecodeResult.Invalid>(
            DisclosureDecoder.decode(
                zip(mapOf("manifest.cbor" to compatibleManifest(extraField = true))),
            ),
        )
    }

    @Test fun disclosure_registry_releases_terminal_flow_and_preserves_recreation_flow() {
        val materialized = assertIs<PackagePipelineResult.Ready>(pipeline(request())).packageBytes
        val registry = DisclosurePackageRegistryStore { 1L }
        val terminalHandle = registry.put(materialized)
        registry.remove(terminalHandle)
        assertNull(registry.find(terminalHandle))

        val recreationHandle = registry.put(materialized)
        assertTrue(registry.find(recreationHandle) != null)
        registry.remove(recreationHandle)
    }

    @Test fun failed_pipeline_receipt_has_no_shareable_fields() {
        val receipt = ReceiptFactory.fromPipeline(assertIs<PackagePipelineResult.Failed>(pipeline(request(), limit = 1)))
        val failed = assertIs<ExportReceipt.GenerationFailed>(receipt)
        assertTrue(failed.cause is dev.tracebox.export.PackagePipelineFailure.Snapshot)
        // ExportReceipt.GenerationFailed has no save or handoff property; Kotlin seals this state.
    }

    @Test fun staging_root_and_handoff_states_are_narrow() {
        val root = Path.of("build", "phase4-workflow", "tracebox-export-staging")
        val manager = StagingLeaseManager(root) { 100L }
        assertTrue(manager.cleanupExpired().isEmpty())
        assertEquals("tracebox-export-staging", StagingLeaseManager.STAGING_DIRECTORY)
        assertEquals(
            setOf("NOT_STARTED", "CHOOSER_OPENED", "TARGET_SELECTED", "DELIVERY_UNKNOWN"),
            ShareHandoffState.entries.map { it.name }.toSet(),
        )
        assertNull(ReceiptFactory.fromPipeline(assertIs<PackagePipelineResult.Ready>(pipeline(request()))) as? ExportReceipt.Approved)
    }

    @Test fun staging_write_without_exact_quota_reservation_is_rejected() {
        val root = Path.of("build", "phase4-workflow", "tracebox-export-staging")
        val manager = StagingLeaseManager(root) { 100L }
        assertFailsWith<IllegalStateException> {
            manager.stageReservedForHostTest(byteArrayOf(1), { null }, ttlMillis = 5)
        }

        assertTrue(!java.nio.file.Files.exists(root) || java.nio.file.Files.list(root).use { !it.findAny().isPresent })
    }

    @Test fun staging_post_write_failure_removes_file_and_releases_reservation_once() {
        val root = Path.of(
            "build",
            "phase4-workflow",
            "staging-rollback-${System.nanoTime()}",
            StagingLeaseManager.STAGING_DIRECTORY,
        )
        val releases = AtomicInteger()
        val manager = StagingLeaseManager(root, { 100L }) {
            throw IllegalStateException("injected registration failure")
        }

        assertFailsWith<IllegalStateException> {
            manager.stageReservedForHostTest(byteArrayOf(1, 2, 3), { { releases.incrementAndGet() } }, ttlMillis = 5)
        }

        assertEquals(1, releases.get())
        assertTrue(java.nio.file.Files.list(root).use { !it.findAny().isPresent })
    }

    @Test fun staging_delete_failure_releases_quota_reservation() {
        val root = Path.of(
            "build",
            "phase4-workflow",
            "staging-delete-failure-${System.nanoTime()}",
            StagingLeaseManager.STAGING_DIRECTORY,
        )
        val bytes = byteArrayOf(1, 2, 3)
        val stagingAccounting = UidAccounting(
            UidQuota(mapOf(UidBucket.SNAPSHOTS to 100L)),
            mapOf(UidBucket.SNAPSHOTS to 3),
        )
        val manager = StagingLeaseManager(
            root,
            { 100L },
            { throw IllegalStateException("injected registration failure") },
            { throw IOException("injected partial-file deletion failure") },
        )
        val reserveLease: (Path) -> (() -> Unit)? = { destination ->
            if (stagingAccounting.reserve(destination, UidBucket.SNAPSHOTS, bytes.size.toLong())) {
                { stagingAccounting.release(destination) }
            } else {
                null
            }
        }

        assertFailsWith<IOException> {
            manager.stageReservedForHostTest(bytes, reserveLease, ttlMillis = 5)
        }

        assertEquals(0L, stagingAccounting.used(UidBucket.SNAPSHOTS))
    }

    @Test fun simultaneous_staging_leases_hold_independent_exact_byte_reservations() {
        var now = 100L
        val root = Path.of("build", "phase4-workflow", "leases-${System.nanoTime()}", StagingLeaseManager.STAGING_DIRECTORY)
        val bytes = byteArrayOf(1, 2, 3)
        val stagingAccounting = UidAccounting(
            UidQuota(mapOf(UidBucket.SNAPSHOTS to 100L)),
            mapOf(UidBucket.SNAPSHOTS to 3),
        )
        val manager = StagingLeaseManager(root) { now }
        val reserveLease: (Path) -> (() -> Unit)? = { destination ->
            if (stagingAccounting.reserve(destination, UidBucket.SNAPSHOTS, bytes.size.toLong())) {
                { stagingAccounting.release(destination) }
            } else {
                null
            }
        }

        manager.stageReservedForHostTest(bytes, reserveLease, ttlMillis = 10)
        now = 101L
        manager.stageReservedForHostTest(bytes, reserveLease, ttlMillis = 10)
        assertEquals(6L, stagingAccounting.used(UidBucket.SNAPSHOTS))

        now = 110L
        assertEquals(1, manager.cleanupExpired().size)
        assertEquals(3L, stagingAccounting.used(UidBucket.SNAPSHOTS))
        now = 111L
        manager.cleanupExpired()
        assertEquals(0L, stagingAccounting.used(UidBucket.SNAPSHOTS))
    }

    @Test fun api_23_compatible_async_submission_uses_the_supplied_executor() {
        val scheduled = ArrayDeque<Runnable>()
        val future = executeFuture(
            executor = { command -> scheduled.addLast(command) },
            operation = { 42 },
        )

        assertFalse(future.isDone)
        assertEquals(1, scheduled.size)
        scheduled.removeFirst().run()
        assertEquals(42, future.get(5, TimeUnit.SECONDS))
    }

    @Test fun api_23_compatible_async_submission_completes_failures_exceptionally() {
        val future = executeFuture(
            executor = { command -> command.run() },
            operation = { throw IllegalStateException("injected copy failure") },
        )

        val failure = assertFailsWith<ExecutionException> {
            future.get(5, TimeUnit.SECONDS)
        }
        assertIs<IllegalStateException>(failure.cause)
    }

    @Test fun api_23_compatible_async_submission_preserves_executor_rejection() {
        assertFailsWith<RejectedExecutionException> {
            executeFuture<Int>(
                executor = { throw RejectedExecutionException("injected rejection") },
                operation = { 42 },
            )
        }
    }

    @Test fun staging_registration_and_expiry_cleanup_release_each_concurrent_lease_once() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { iteration ->
                val root = Path.of(
                    "build",
                    "phase4-workflow",
                    "lease-registration-race-$iteration-${System.nanoTime()}",
                    StagingLeaseManager.STAGING_DIRECTORY,
                )
                val cleanupClock = AtomicBoolean(false)
                val fileWritten = CountDownLatch(1)
                val cleanupEntered = CountDownLatch(1)
                val releases = AtomicInteger()
                val manager = StagingLeaseManager(root, { if (cleanupClock.get()) 2L else 0L }) {
                    fileWritten.countDown()
                    check(cleanupEntered.await(5, TimeUnit.SECONDS)) {
                        "iteration $iteration did not enter cleanup"
                    }
                }
                val stage = workers.submit {
                    manager.stageReservedForHostTest(
                        byteArrayOf(1),
                        { { releases.incrementAndGet() } },
                        ttlMillis = 1,
                    )
                }
                val cleanup = workers.submit<List<Path>> {
                    check(fileWritten.await(5, TimeUnit.SECONDS)) {
                        "iteration $iteration did not write its lease file"
                    }
                    cleanupClock.set(true)
                    cleanupEntered.countDown()
                    manager.cleanupExpired()
                }
                stage.get(5, TimeUnit.SECONDS)
                assertEquals(1, cleanup.get(5, TimeUnit.SECONDS).size, "iteration $iteration")
                assertEquals(1, releases.get(), "iteration $iteration")
            }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun failed_or_cancelled_approved_receipts_have_no_handoff_constructor_parameter() {
        val commonDigest = byteArrayOf(1)
        val failed = ExportReceipt.Approved.SaveFailed(
            commonDigest, commonDigest, 1, ProtectionMode.LOCAL_ONLY, RecipientSet.LocalOnly,
            SaveResult.Failed("SAF copy failed"), false, null,
        )
        val cancelled = ExportReceipt.Approved.SaveCancelled(
            commonDigest, commonDigest, 1, ProtectionMode.LOCAL_ONLY, RecipientSet.LocalOnly,
            SaveResult.PartialCopyWarning(0, cancelled = true), true, null,
        )
        assertIs<ExportReceipt.Approved.SaveFailed>(failed)
        assertIs<ExportReceipt.Approved.SaveCancelled>(cancelled)
        // SaveFailed and SaveCancelled constructors have no ShareHandoffState parameter; only
        // SaveSucceededPendingOrCompleteHandoff accepts (save: SaveResult.Complete, handoff).
    }

    private fun zip(entries: Map<String, ByteArray>, stored: Boolean = true): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { stream ->
            entries.forEach { (path, bytes) ->
                val entry = ZipEntry(path)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().also { it.update(bytes) }.value
                }
                stream.putNextEntry(entry)
                stream.write(bytes)
                stream.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun compatibleManifest(
        packageVersion: Long = 1,
        recordVersion: Long = 1,
        schema: ByteArray = ManifestEncoder.schemaFingerprint(),
        extraField: Boolean = false,
    ): ByteArray {
        val fields = linkedMapOf<String, CborValue>(
            "v" to CborValue.Unsigned(packageVersion),
            "record" to CborValue.Unsigned(recordVersion),
            "schema" to CborValue.Bytes(schema),
            "epoch" to CborValue.Unsigned(1),
            "privacy" to CborValue.Text(PackagePrivacyClass.C0.name),
            "range" to CborValue.Array(emptyList()),
            "entries" to CborValue.Array(emptyList()),
            "omissions" to CborValue.Array(emptyList()),
        )
        if (extraField) fields["future"] = CborValue.Unsigned(1)
        return CanonicalCbor.encode(CborValue.Map(fields))
    }

}
