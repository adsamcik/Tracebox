package dev.tracebox.directboot

import dev.tracebox.api.Crc32c
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.core.BarrierAck
import dev.tracebox.core.ControlPage
import dev.tracebox.core.GlobalPolicyCoordinator
import dev.tracebox.core.PolicySnapshot
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DirectBootTest {
    private val fingerprint = ByteArray(32) { (it * 7 + 3).toByte() }
    private val policyEpoch = 11L

    private fun directory(): Path =
        Path.of("build", "directboot-tests", UUID.randomUUID().toString())
            .toAbsolutePath()
            .normalize()
            .also(Files::createDirectories)

    private val allowMutations = DirectBootStorageMutationGuard { _, mutation ->
        mutation()
        true
    }

    private data class Harness(
        val noBackup: Path,
        val paths: DirectBootPaths,
        val manager: DirectBootManager,
        val mirror: DenyMirror,
    )

    private fun harness(
        state: DenyState = DenyState(policyEpoch, disabled = false, c0DenyMask = 0),
        guard: DirectBootStorageMutationGuard = allowMutations,
        crashInjector: DirectBootPersistenceCrashInjector? = null,
        schemaFingerprint: ByteArray = fingerprint,
    ): Harness {
        val noBackup = directory()
        val paths = DirectBootLayout.fromDeviceProtectedNoBackupDirectory(noBackup)
        Files.createDirectories(paths.root)
        val mirror = DenyMirror(paths.activeDeny, paths.pendingDeny)
        mirror.writePending(state)
        mirror.promotePending()
        return Harness(
            noBackup,
            paths,
            DirectBootManager.forTest(noBackup, schemaFingerprint, guard, crashInjector),
            mirror,
        )
    }

    private fun restart(
        harness: Harness,
        guard: DirectBootStorageMutationGuard = allowMutations,
        crashInjector: DirectBootPersistenceCrashInjector? = null,
        schemaFingerprint: ByteArray = fingerprint,
    ): DirectBootManager =
        DirectBootManager.forTest(
            harness.noBackup,
            schemaFingerprint,
            guard,
            crashInjector,
        )

    private fun generated(
        schemaFingerprint: ByteArray = fingerprint,
        slotSequence: ULong = 7u,
        epoch: ULong = policyEpoch.toULong(),
        signalNumber: Int = 6,
        signalCode: Int = 1,
        processRole: UInt = 2u,
        threadRole: UInt = 3u,
        flags: ULong = 5u,
        elapsedMillis: Long = 13,
        readinessCode: Int = 4,
    ): GeneratedDirectBootRecord =
        GeneratedDirectBootRecord.fromEmergency(
            schemaFingerprint,
            GeneratedEmergencyRecord(
                slotSequence,
                epoch,
                signalNumber,
                signalCode,
                processRole,
                threadRole,
                flags,
            ),
            elapsedMillis,
            readinessCode,
        )

    @Test
    fun canonical_layout_exposes_exact_quota_and_metadata_reservations() {
        val noBackup = directory()
        val paths = DirectBootLayout.fromDeviceProtectedNoBackupDirectory(noBackup)

        assertEquals(19, DirectBootLayout.RECORD_CAPACITY)
        assertEquals(160, DirectBootLayout.FRAME_SIZE_BYTES)
        assertEquals(3_040, DirectBootLayout.RECORDS_BYTES)
        assertEquals(64, DirectBootLayout.ACTIVATION_BYTES)
        assertEquals(noBackup.resolve("tracebox-directboot"), paths.root)
        assertEquals(paths.root.resolve("tracebox-c0.records"), paths.records)
        assertEquals(paths.root.resolve("directboot-activation-v1"), paths.activation)
        assertEquals(
            paths.root.resolve("directboot-activation-v1.new"),
            paths.activationTemp,
        )
        assertTrue(
            listOf(
                paths.records,
                paths.activation,
                paths.activationTemp,
                paths.activeDeny,
                paths.pendingDeny,
            ).all { it.parent == paths.root },
        )
    }

    @Test
    fun setup_preallocates_one_fixed_records_file_then_durably_activates() {
        val requests = mutableListOf<DirectBootStorageMutationRequest>()
        val guard = DirectBootStorageMutationGuard { request, mutation ->
            requests += request
            mutation()
            true
        }
        val harness = harness(guard = guard)

        assertEquals(DirectBootActivationStatus.ABSENT, harness.manager.activationStatus())
        assertNull(harness.manager.openCapture())
        assertEquals(DirectBootSetupResult.ACTIVATED, harness.manager.setup())
        assertEquals(DirectBootActivationStatus.ACTIVE, harness.manager.activationStatus())
        assertNotNull(harness.manager.openCapture())
        assertEquals(DirectBootSetupResult.ALREADY_ACTIVE, harness.manager.setup())

        assertEquals(DirectBootLayout.RECORDS_BYTES.toLong(), Files.size(harness.paths.records))
        assertTrue(Files.readAllBytes(harness.paths.records).all { it == 0.toByte() })
        assertFalse(
            Files.exists(
                harness.paths.records.resolveSibling("${harness.paths.records.fileName}.new"),
            ),
        )
        val physicalEmergencyBytes = Files.list(harness.paths.root).use { children ->
            children
                .filter { it.fileName.toString().startsWith(DirectBootLayout.RECORDS_FILE_NAME) }
                .mapToLong(Files::size)
                .sum()
        }
        assertEquals(3_040L, physicalEmergencyBytes)
        assertTrue(
            requests.all {
                it.recordsPath == harness.paths.records &&
                    it.reservationBytes == 3_040L
            },
        )
        assertEquals(
            listOf(DirectBootMutation.SETUP, DirectBootMutation.SETUP),
            requests.map(DirectBootStorageMutationRequest::operation),
        )
    }

    @Test
    fun activation_is_bound_to_the_schema_and_fixed_layout() {
        val harness = harness()
        assertEquals(DirectBootSetupResult.ACTIVATED, harness.manager.setup())

        val wrongSchema = restart(
            harness,
            schemaFingerprint = ByteArray(32) { (it + 1).toByte() },
        )
        assertEquals(DirectBootActivationStatus.INVALID, wrongSchema.activationStatus())
        assertNull(wrongSchema.openCapture())
        assertEquals(DirectBootSetupResult.SCHEMA_MISMATCH, wrongSchema.setup())

        val corrupt = Files.readAllBytes(harness.paths.activation)
        corrupt[20] = (corrupt[20].toInt() xor 1).toByte()
        Files.write(harness.paths.activation, corrupt)
        assertEquals(DirectBootActivationStatus.INVALID, restart(harness).activationStatus())
        assertNull(restart(harness).openCapture())
    }

    @Test
    fun unlocked_schema_projection_upgrade_disables_old_c0_before_reprovisioning() {
        val harness = harness()
        assertEquals(DirectBootSetupResult.ACTIVATED, harness.manager.setup())
        assertEquals(
            DirectBootWriteResult.WRITTEN,
            assertNotNull(harness.manager.openCapture()).appendGenerated(generated()),
        )
        val replacementFingerprint = ByteArray(32) { (it + 1).toByte() }
        val replacement = restart(harness, schemaFingerprint = replacementFingerprint)
        assertEquals(DirectBootSetupResult.SCHEMA_MISMATCH, replacement.setup())

        assertEquals(DirectBootDisableResult.DISABLED, replacement.disable())
        assertEquals(DirectBootActivationStatus.ABSENT, replacement.activationStatus())
        assertFalse(Files.exists(harness.paths.records))
        assertEquals(DirectBootSetupResult.ACTIVATED, replacement.setup())
        assertEquals(DirectBootActivationStatus.ACTIVE, replacement.activationStatus())
        assertTrue(replacement.drain().records.isEmpty())
    }

    @Test
    fun v2_frame_round_trips_every_emergency_field_and_has_a_stable_source_id() {
        val harness = harness()
        assertEquals(DirectBootSetupResult.ACTIVATED, harness.manager.setup())
        val capture = assertNotNull(harness.manager.openCapture())
        val record = generated(
            slotSequence = ULong.MAX_VALUE,
            signalNumber = -6,
            signalCode = Int.MIN_VALUE,
            processRole = UInt.MAX_VALUE,
            threadRole = 0x8000_0000u,
            flags = ULong.MAX_VALUE,
            elapsedMillis = Long.MAX_VALUE,
            readinessCode = 255,
        )

        assertEquals(DirectBootWriteResult.WRITTEN, capture.appendGenerated(record))
        assertEquals(DirectBootWriteResult.ALREADY_PRESENT, capture.appendGenerated(record))
        assertEquals(3_040L, Files.size(harness.paths.records))

        val drained = harness.manager.drain().records.single()
        assertContentEquals(fingerprint, drained.schemaFingerprint)
        assertEquals(ULong.MAX_VALUE, drained.slotSequence)
        assertEquals(policyEpoch.toULong(), drained.policyEpoch)
        assertEquals(-6, drained.signalNumber)
        assertEquals(Int.MIN_VALUE, drained.signalCode)
        assertEquals(UInt.MAX_VALUE, drained.processRole)
        assertEquals(0x8000_0000u, drained.threadRole)
        assertEquals(ULong.MAX_VALUE, drained.flags)
        assertEquals(Long.MAX_VALUE, drained.elapsedMillis)
        assertEquals(255, drained.readinessCode)
        assertEquals(2L, drained.categoryMask)
        assertEquals(64, drained.sourceId.hex.length)
        assertEquals(
            "80a3c3b088b1965f508e7b290356d7fa2d66a21ec77541f40e2f6f2045c787bd",
            drained.sourceId.hex,
        )
        val projected = drained.toGeneratedEmergencyRecord()
        assertEquals(ULong.MAX_VALUE, projected.slot_sequence)
        assertEquals(policyEpoch.toULong(), projected.policy_epoch)
        assertEquals(-6, projected.signal_number)
        assertEquals(Int.MIN_VALUE, projected.signal_code)
        assertEquals(UInt.MAX_VALUE, projected.process_role)
        assertEquals(0x8000_0000u, projected.thread_role)
        assertEquals(ULong.MAX_VALUE, projected.flags)

        val restarted = restart(harness).drain().records.single()
        assertEquals(drained.sourceId, restarted.sourceId)
        assertContentEquals(drained.sourceId.toByteArray(), restarted.sourceId.toByteArray())
    }

    @Test
    @Suppress("DEPRECATION")
    fun generated_input_fixes_the_category_and_append_enforces_schema_epoch_and_policy() {
        assertFailsWith<IllegalArgumentException> {
            GeneratedDirectBootRecord.fromEmergency(
                fingerprint,
                GeneratedEmergencyRecord(1u, policyEpoch.toULong(), 1, 2, 3u, 4u, 5u),
                elapsedMillis = 6,
                readinessCode = 7,
                categoryMask = 1,
            )
        }

        val harness = harness()
        harness.manager.setup()
        val capture = assertNotNull(harness.manager.openCapture())
        assertEquals(
            DirectBootWriteResult.INVALID_ACTIVATION,
            capture.appendGenerated(generated(schemaFingerprint = ByteArray(32))),
        )
        assertEquals(
            DirectBootWriteResult.POLICY_MISMATCH,
            capture.appendGenerated(generated(epoch = (policyEpoch + 1).toULong())),
        )

        harness.mirror.writePending(DenyState(policyEpoch, false, 1))
        harness.mirror.promotePending()
        assertEquals(
            DirectBootWriteResult.WRITTEN,
            capture.appendGenerated(generated(slotSequence = 1u)),
        )

        harness.mirror.writePending(DenyState(policyEpoch, false, 2))
        harness.mirror.promotePending()
        assertEquals(
            DirectBootWriteResult.DENIED,
            capture.appendGenerated(generated(slotSequence = 2u)),
        )
        assertEquals(DirectBootDrainStatus.POLICY_DENIED, harness.manager.drain().status)

        harness.mirror.writePending(DenyState(policyEpoch, false, 1))
        harness.mirror.promotePending()
        assertEquals(DirectBootDrainStatus.READY, harness.manager.drain().status)

        harness.mirror.writePending(DenyState(policyEpoch, true, 0))
        harness.mirror.promotePending()
        assertEquals(
            DirectBootWriteResult.DISABLED,
            capture.appendGenerated(generated(slotSequence = 3u)),
        )
    }

    @Test
    fun capacity_is_nineteen_frames_while_physical_size_remains_exactly_3040_bytes() {
        val harness = harness()
        harness.manager.setup()
        val capture = assertNotNull(harness.manager.openCapture())

        repeat(DirectBootLayout.RECORD_CAPACITY) { index ->
            assertEquals(
                DirectBootWriteResult.WRITTEN,
                capture.appendGenerated(generated(slotSequence = index.toULong())),
            )
            assertEquals(3_040L, Files.size(harness.paths.records))
        }
        assertEquals(
            DirectBootWriteResult.QUOTA_EXHAUSTED,
            capture.appendGenerated(generated(slotSequence = 20u)),
        )
        assertEquals(19, harness.manager.drain().records.size)
    }

    @Test
    fun rejected_guard_never_creates_repairs_or_removes_storage() {
        val rejecting = DirectBootStorageMutationGuard { _, _ -> false }
        val harness = harness(guard = rejecting)

        assertEquals(DirectBootSetupResult.STORAGE_INELIGIBLE, harness.manager.setup())
        assertFalse(Files.exists(harness.paths.records))
        assertFalse(Files.exists(harness.paths.activation))

        val allowed = restart(harness)
        allowed.setup()
        val original = Files.readAllBytes(harness.paths.records)
        Files.write(harness.paths.records, original.copyOf(17))
        assertEquals(
            DirectBootDrainStatus.STORAGE_INELIGIBLE,
            restart(harness, guard = rejecting).drain().status,
        )
        assertEquals(17L, Files.size(harness.paths.records))
        assertEquals(
            DirectBootDisableResult.STORAGE_INELIGIBLE,
            restart(harness, guard = rejecting).disable(),
        )
        assertTrue(Files.exists(harness.paths.activation))
        assertTrue(Files.exists(harness.paths.records))
    }

    @Test
    fun mutation_barrier_prevents_a_stale_append_from_resurrecting_deleted_storage() {
        val barrier = ReentrantLock()
        val eligible = AtomicBoolean(true)
        val pauseAppend = AtomicBoolean(false)
        val appendEntered = CountDownLatch(1)
        val continueAppend = CountDownLatch(1)
        val guard = DirectBootStorageMutationGuard { request, mutation ->
            barrier.lock()
            try {
                if (!eligible.get()) {
                    false
                } else {
                    if (request.operation == DirectBootMutation.APPEND &&
                        pauseAppend.compareAndSet(true, false)
                    ) {
                        appendEntered.countDown()
                        check(continueAppend.await(5, TimeUnit.SECONDS))
                    }
                    mutation()
                    true
                }
            } finally {
                barrier.unlock()
            }
        }
        val harness = harness(guard = guard)
        harness.manager.setup()
        val capture = assertNotNull(harness.manager.openCapture())
        pauseAppend.set(true)
        val result = AtomicReference<DirectBootWriteResult>()
        val appender = Thread { result.set(capture.appendGenerated(generated())) }
        appender.start()
        assertTrue(appendEntered.await(5, TimeUnit.SECONDS))

        val deletion = Thread {
            barrier.lock()
            try {
                eligible.set(false)
                Files.deleteIfExists(harness.paths.activation)
                Files.deleteIfExists(harness.paths.records)
            } finally {
                barrier.unlock()
            }
        }
        deletion.start()
        continueAppend.countDown()
        appender.join(5_000)
        deletion.join(5_000)

        assertEquals(DirectBootWriteResult.WRITTEN, result.get())
        assertFalse(Files.exists(harness.paths.activation))
        assertFalse(Files.exists(harness.paths.records))
        assertEquals(DirectBootActivationStatus.ABSENT, harness.manager.activationStatus())
        assertNull(harness.manager.openCapture())
    }

    @Test
    fun drain_is_repeatable_and_retirement_requires_durable_ack_and_newest_first_order() {
        val harness = harness()
        harness.manager.setup()
        val capture = assertNotNull(harness.manager.openCapture())
        repeat(3) {
            assertEquals(
                DirectBootWriteResult.WRITTEN,
                capture.appendGenerated(generated(slotSequence = it.toULong())),
            )
        }

        val first = harness.manager.drain()
        val repeated = restart(harness).drain()
        assertEquals(DirectBootDrainStatus.READY, first.status)
        assertEquals(first.records.map { it.sourceId }, repeated.records.map { it.sourceId })
        assertEquals(listOf(0, 1, 2), first.records.map { it.token.slotIndex })
        val noAck = DirectBootDurableAck { false }
        val ack = DirectBootDurableAck { true }
        assertEquals(
            DirectBootRetireResult.NOT_DURABLY_ACKNOWLEDGED,
            harness.manager.retireAcknowledged(first.records.last().token, noAck),
        )
        assertEquals(
            DirectBootRetireResult.NOT_TAIL,
            harness.manager.retireAcknowledged(first.records.first().token, ack),
        )

        for (record in first.records.asReversed()) {
            assertEquals(
                DirectBootRetireResult.RETIRED,
                harness.manager.retireAcknowledged(record.token, ack),
            )
            assertEquals(
                DirectBootRetireResult.ALREADY_RETIRED,
                harness.manager.retireAcknowledged(record.token, ack),
            )
        }
        assertTrue(harness.manager.drain().records.isEmpty())
        assertEquals(3_040L, Files.size(harness.paths.records))
        assertTrue(Files.readAllBytes(harness.paths.records).all { it == 0.toByte() })
    }

    @Test
    fun a_retired_token_cannot_zero_a_new_record_reusing_its_slot() {
        val harness = harness()
        harness.manager.setup()
        val capture = assertNotNull(harness.manager.openCapture())
        capture.appendGenerated(generated(slotSequence = 1u))
        val old = harness.manager.drain().records.single()
        val ack = DirectBootDurableAck { true }
        assertEquals(DirectBootRetireResult.RETIRED, harness.manager.retireAcknowledged(old.token, ack))
        capture.appendGenerated(generated(slotSequence = 2u))

        assertEquals(
            DirectBootRetireResult.STALE_TOKEN,
            harness.manager.retireAcknowledged(old.token, ack),
        )
        assertEquals(2uL, harness.manager.drain().records.single().slotSequence)
    }

    @Test
    fun truncated_or_corrupt_tail_is_zero_repaired_to_the_fixed_preallocation() {
        val harness = harness()
        harness.manager.setup()
        val capture = assertNotNull(harness.manager.openCapture())
        capture.appendGenerated(generated(slotSequence = 1u))
        capture.appendGenerated(generated(slotSequence = 2u))

        val bytes = Files.readAllBytes(harness.paths.records)
        Files.write(
            harness.paths.records,
            bytes.copyOf(DirectBootLayout.FRAME_SIZE_BYTES + 37),
        )
        val truncated = restart(harness).drain()
        assertEquals(DirectBootDrainStatus.READY, truncated.status)
        assertEquals(1, truncated.records.size)
        assertEquals(
            DirectBootRecovery(1, DirectBootLayout.FRAME_SIZE_BYTES.toLong(), repaired = true),
            truncated.recovery,
        )
        assertEquals(3_040L, Files.size(harness.paths.records))

        capture.appendGenerated(generated(slotSequence = 3u))
        val corrupt = Files.readAllBytes(harness.paths.records)
        corrupt[DirectBootLayout.FRAME_SIZE_BYTES + 80] =
            (corrupt[DirectBootLayout.FRAME_SIZE_BYTES + 80].toInt() xor 1).toByte()
        Files.write(harness.paths.records, corrupt)
        val repaired = restart(harness).drain()
        assertEquals(listOf(1uL), repaired.records.map { it.slotSequence })
        assertTrue(repaired.recovery!!.repaired)
        assertEquals(3_040L, Files.size(harness.paths.records))
    }

    @Test
    fun setup_is_restart_safe_after_every_persistence_boundary() {
        for (boundary in listOf(
            DirectBootPersistenceBoundary.SETUP_RECORDS_INITIALIZED,
            DirectBootPersistenceBoundary.SETUP_ACTIVATION_TEMP_SYNCED,
            DirectBootPersistenceBoundary.SETUP_ACTIVATION_REPLACED,
        )) {
            val harness = harness(
                crashInjector = DirectBootPersistenceCrashInjector {
                    if (it == boundary) throw PersistenceCrash()
                },
            )
            assertFailsWith<PersistenceCrash>("boundary=$boundary") { harness.manager.setup() }
            assertFalse(
                Files.exists(
                    harness.paths.records.resolveSibling("${harness.paths.records.fileName}.new"),
                ),
            )
            assertEquals(3_040L, Files.size(harness.paths.records))

            val restarted = restart(harness)
            if (boundary == DirectBootPersistenceBoundary.SETUP_ACTIVATION_REPLACED) {
                assertEquals(DirectBootActivationStatus.ACTIVE, restarted.activationStatus())
                assertEquals(DirectBootSetupResult.ALREADY_ACTIVE, restarted.setup())
            } else {
                assertEquals(DirectBootActivationStatus.ABSENT, restarted.activationStatus())
                assertNull(restarted.openCapture())
                assertEquals(DirectBootSetupResult.ACTIVATED, restarted.setup())
            }
            assertEquals(DirectBootActivationStatus.ACTIVE, restarted.activationStatus())
            assertEquals(3_040L, Files.size(harness.paths.records))
        }
    }

    @Test
    fun append_is_idempotent_after_every_persistence_boundary() {
        for (boundary in listOf(
            DirectBootPersistenceBoundary.APPEND_FRAME_WRITTEN,
            DirectBootPersistenceBoundary.APPEND_FORCED,
        )) {
            val harness = harness()
            harness.manager.setup()
            val injected = restart(
                harness,
                crashInjector = DirectBootPersistenceCrashInjector {
                    if (it == boundary) throw PersistenceCrash()
                },
            )
            val record = generated(slotSequence = boundary.ordinal.toULong())
            assertFailsWith<PersistenceCrash>("boundary=$boundary") {
                assertNotNull(injected.openCapture()).appendGenerated(record)
            }

            val restarted = restart(harness)
            val recovered = restarted.drain().records.single()
            assertEquals(boundary.ordinal.toULong(), recovered.slotSequence)
            assertEquals(
                DirectBootWriteResult.ALREADY_PRESENT,
                assertNotNull(restarted.openCapture()).appendGenerated(record),
            )
            assertEquals(3_040L, Files.size(harness.paths.records))
        }
    }

    @Test
    fun retirement_is_idempotent_after_every_zero_boundary() {
        for (boundary in listOf(
            DirectBootPersistenceBoundary.RETIRE_SLOT_ZEROED,
            DirectBootPersistenceBoundary.RETIRE_FORCED,
        )) {
            val harness = harness()
            harness.manager.setup()
            assertNotNull(harness.manager.openCapture()).appendGenerated(generated())
            val token = harness.manager.drain().records.single().token
            val injected = restart(
                harness,
                crashInjector = DirectBootPersistenceCrashInjector {
                    if (it == boundary) throw PersistenceCrash()
                },
            )
            assertFailsWith<PersistenceCrash>("boundary=$boundary") {
                injected.retireAcknowledged(token, DirectBootDurableAck { true })
            }

            val restarted = restart(harness)
            assertTrue(restarted.drain().records.isEmpty())
            assertEquals(
                DirectBootRetireResult.ALREADY_RETIRED,
                restarted.retireAcknowledged(token, DirectBootDurableAck { true }),
            )
            assertEquals(3_040L, Files.size(harness.paths.records))
        }
    }

    @Test
    fun disable_removes_activation_before_records_and_retry_completes_opt_out() {
        for (boundary in listOf(
            DirectBootPersistenceBoundary.DISABLE_ACTIVATION_REMOVED,
            DirectBootPersistenceBoundary.DISABLE_RECORDS_REMOVED,
        )) {
            val harness = harness()
            harness.manager.setup()
            assertNotNull(harness.manager.openCapture()).appendGenerated(generated())
            val injected = restart(
                harness,
                crashInjector = DirectBootPersistenceCrashInjector {
                    if (it == boundary) throw PersistenceCrash()
                },
            )
            assertFailsWith<PersistenceCrash>("boundary=$boundary") { injected.disable() }

            val restarted = restart(harness)
            assertEquals(DirectBootActivationStatus.ABSENT, restarted.activationStatus())
            assertNull(restarted.openCapture())
            assertFalse(Files.exists(harness.paths.activation))
            val retry = restarted.disable()
            if (boundary == DirectBootPersistenceBoundary.DISABLE_ACTIVATION_REMOVED) {
                assertEquals(DirectBootDisableResult.DISABLED, retry)
            } else {
                assertEquals(DirectBootDisableResult.ALREADY_DISABLED, retry)
            }
            assertEquals(DirectBootActivationStatus.ABSENT, restarted.activationStatus())
            assertFalse(Files.exists(harness.paths.records))
            assertEquals(DirectBootDisableResult.ALREADY_DISABLED, restarted.disable())
        }
    }

    @Test
    fun capture_has_one_generated_append_and_does_not_use_reflection() {
        val publicMethods = DirectBootCapture::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
        assertEquals(listOf("appendGenerated"), publicMethods)

        val harness = harness()
        harness.manager.setup()
        assertEquals(
            DirectBootWriteResult.WRITTEN,
            assertNotNull(harness.manager.openCapture()).appendGenerated(generated()),
        )
        assertTrue(
            DirectBootManager::class.java.declaredMethods.none {
                it.name.contains("getDeclaredMethod") || it.name.contains("invoke")
            },
        )
    }

    @Test
    fun corrupt_truncated_and_unknown_mirror_states_fail_closed() {
        val sourceDirectory = directory()
        val sourceActive = sourceDirectory.resolve("active")
        val sourceMirror = DenyMirror(sourceActive, sourceDirectory.resolve("pending"))
        sourceMirror.writePending(DenyState(7, false, 0))
        sourceMirror.promotePending()
        val valid = Files.readAllBytes(sourceActive)
        val cases = directory()

        for (cut in 0 until valid.size) {
            val active = cases.resolve("active-$cut")
            Files.write(active, valid.copyOf(cut))
            assertNull(DenyMirror(active, cases.resolve("pending-$cut")).effective(), "cut=$cut")
        }

        val corruptCrc = valid.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        Files.write(cases.resolve("active-corrupt"), corruptCrc)
        assertNull(DenyMirror(cases.resolve("active-corrupt"), cases.resolve("pending-corrupt")).effective())

        val invalidBoolean = valid.copyOf()
        ByteBuffer.wrap(invalidBoolean).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 2)
        ByteBuffer.wrap(invalidBoolean).order(ByteOrder.LITTLE_ENDIAN).putInt(
            invalidBoolean.size - Int.SIZE_BYTES,
            Crc32c.value(invalidBoolean, 0, invalidBoolean.size - Int.SIZE_BYTES),
        )
        Files.write(cases.resolve("active-invalid-boolean"), invalidBoolean)
        assertNull(
            DenyMirror(
                cases.resolve("active-invalid-boolean"),
                cases.resolve("pending-invalid-boolean"),
            ).effective(),
        )
        val corruptForReconciliation = cases.resolve("active-reconcile-corrupt")
        Files.write(corruptForReconciliation, corruptCrc)
        val reconciled = DenyMirror(
            corruptForReconciliation,
            cases.resolve("pending-reconcile-corrupt"),
        ).reconcile(DenyState(8, disabled = false, c0DenyMask = 0))
        assertEquals(DenyState(8, disabled = true, c0DenyMask = Long.MAX_VALUE), reconciled)
    }

    @Test
    fun mirror_replacement_crash_before_move_preserves_previous_state_and_ignores_temp() {
        val directory = directory()
        val active = directory.resolve("active")
        val pending = directory.resolve("pending")
        val original = DenyMirror(active, pending)
        original.writePending(DenyState(1, false, 1))
        original.promotePending()
        val injected = DenyMirror.withCrashInjector(active, pending) { target, boundary ->
            if (target == pending.toAbsolutePath().normalize() &&
                boundary == DenyMirrorReplacementBoundary.TEMP_SYNCED
            ) {
                throw MirrorCrash()
            }
        }

        assertFailsWith<MirrorCrash> {
            injected.writePending(DenyState(2, true, 3))
        }
        val restarted = DenyMirror(active, pending)
        assertEquals(DenyState(1, false, 1), restarted.active())
        assertNull(restarted.pending())
        assertEquals(DenyState(1, false, 1), restarted.effective())
        assertTrue(Files.exists(pending.resolveSibling("${pending.fileName}.new")))
    }

    @Test
    fun promotion_crashes_leave_policy_at_least_as_restrictive() {
        for (boundary in DenyMirrorReplacementBoundary.entries) {
            val directory = directory()
            val active = directory.resolve("active")
            val pending = directory.resolve("pending")
            val mirror = DenyMirror(active, pending)
            mirror.writePending(DenyState(1, false, 1))
            mirror.promotePending()
            mirror.writePending(DenyState(2, true, 3))
            val injected = DenyMirror.withCrashInjector(active, pending) { target, observed ->
                if (target == active.toAbsolutePath().normalize() && observed == boundary) {
                    throw MirrorCrash()
                }
            }
            assertFailsWith<MirrorCrash>("boundary=$boundary") { injected.promotePending() }
            val effective = DenyMirror(active, pending).effective()
            assertNotNull(effective)
            assertTrue(effective.disabled)
            assertEquals(3L, effective.c0DenyMask)
            assertEquals(2L, effective.epoch)
        }
    }

    @Test
    fun reconciliation_from_absent_mirror_atomically_installs_ce_state() {
        val directory = directory()
        val mirror = DenyMirror(directory.resolve("active"), directory.resolve("pending"))
        val ce = DenyState(9, true, 7)

        assertEquals(ce, mirror.reconcile(ce))
        assertEquals(
            ce,
            DenyMirror(directory.resolve("active"), directory.resolve("pending")).effective(),
        )
        assertNull(mirror.pending())
    }

    @Test
    fun tightening_is_at_least_as_restrictive_after_every_policy_crash_boundary() {
        for (boundary in DirectBootBoundary.entries) {
            val directory = directory()
            val mirror = DenyMirror(directory.resolve("active"), directory.resolve("pending"))
            mirror.writePending(DenyState(1, false, 1))
            mirror.promotePending()
            var ce = DenyState(1, false, 1)
            val coordinator = DirectBootPolicyCoordinator(mirror) { ce = it }
            val target = DenyState(2, true, 3)
            try {
                coordinator.tighten(
                    target,
                    DirectBootCrashInjector { if (it == boundary) throw PolicyCrash() },
                )
            } catch (_: PolicyCrash) {
                val effective =
                    DenyMirror(directory.resolve("active"), directory.resolve("pending"))
                        .effective()
                assertNotNull(effective)
                assertTrue(effective.disabled)
                assertEquals(3L, effective.c0DenyMask)
            }
            assertTrue(ce.epoch <= target.epoch)
        }
    }

    @Test
    fun loosening_waits_for_ce_and_reconciliation_uses_most_restrictive_state() {
        val directory = directory()
        val mirror = DenyMirror(directory.resolve("active"), directory.resolve("pending"))
        mirror.writePending(DenyState(5, true, 3))
        mirror.promotePending()
        var ceCommitted = false
        DirectBootPolicyCoordinator(mirror) { ceCommitted = true }.loosen(DenyState(6, false, 0))
        assertTrue(ceCommitted)
        mirror.writePending(DenyState(7, false, 0))
        val reconciled = mirror.reconcile(DenyState(6, true, 1))
        assertTrue(reconciled.disabled)
        assertEquals(1L, reconciled.c0DenyMask)
        assertNull(mirror.pending())
    }

    @Test
    fun handler_coordinated_transition_keeps_pending_deny_on_partial_global_barrier() {
        val directory = directory()
        val mirror = DenyMirror(directory.resolve("active"), directory.resolve("pending"))
        mirror.writePending(DenyState(1, false, 0))
        mirror.promotePending()
        val page = ControlPage(directory.resolve("control"))
        page.commit(PolicySnapshot(1, 0))
        val global = GlobalPolicyCoordinator(directory.resolve("coordinator"), page, byteArrayOf(7))
        val coordinated =
            HandlerCoordinatedDirectBootPolicyCoordinator(mirror, global) { BarrierAck.Missing }

        val result = coordinated.tighten(DenyState(2, true, 3))

        assertEquals(DirectBootGlobalTransitionResult.PARTIAL, result)
        assertTrue(assertNotNull(mirror.effective()).disabled)
        assertEquals(1L, page.committed().epoch)
    }

    @Test
    fun source_ids_change_when_any_canonical_field_changes() {
        val harness = harness()
        harness.manager.setup()
        val capture = assertNotNull(harness.manager.openCapture())
        capture.appendGenerated(generated(slotSequence = 1u))
        capture.appendGenerated(generated(slotSequence = 2u))
        val ids = harness.manager.drain().records.map { it.sourceId }
        assertEquals(2, ids.toSet().size)
        assertNotEquals(ids[0], ids[1])
    }

    private class PersistenceCrash : RuntimeException()
    private class MirrorCrash : RuntimeException()
    private class PolicyCrash : RuntimeException()
}
