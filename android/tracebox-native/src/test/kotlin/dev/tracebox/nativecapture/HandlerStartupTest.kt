package dev.tracebox.nativecapture

import dev.tracebox.core.ControlPage
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.PolicyTransitionJournal
import dev.tracebox.storage.TraceboxOwnedStorageRoot
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandlerStartupTest {
    @Test
    fun handler_paths_keep_ce_policy_and_capture_separate_from_the_shared_de_barrier() {
        val base = Path.of(
            "build",
            "handler-startup-tests",
            UUID.randomUUID().toString(),
        ).toAbsolutePath()
        val credentialNoBackup = base.resolve("ce").resolve("no_backup")
            .also(Files::createDirectories)
        val deviceProtectedNoBackup = base.resolve("de").resolve("no_backup")
            .also(Files::createDirectories)

        val paths = TraceboxHandlerService.handlerPaths(
            credentialNoBackup.toFile(),
            deviceProtectedNoBackup.toFile(),
        )

        assertEquals(
            credentialNoBackup.resolve(STORAGE_ROOT_DIRECTORY_NAME).toFile().canonicalFile,
            paths.storageRoot,
        )
        assertEquals(
            paths.storageRoot.toPath().resolve(HANDLER_DIRECTORY_NAME).toFile().canonicalFile,
            paths.handlerDirectory,
        )
        assertEquals(
            deviceProtectedNoBackup
                .resolve(TraceboxHandlerService.DIRECT_BOOT_ROOT_DIRECTORY_NAME)
                .toFile()
                .canonicalFile,
            paths.mutationBarrierRoot,
        )

        val legacy = TraceboxHandlerService.handlerPaths(
            credentialNoBackup.toFile(),
            credentialNoBackup.toFile(),
        )
        assertEquals(paths.storageRoot, legacy.storageRoot)
        assertEquals(
            credentialNoBackup
                .resolve(TraceboxHandlerService.DIRECT_BOOT_ROOT_DIRECTORY_NAME)
                .toFile()
                .canonicalFile,
            legacy.mutationBarrierRoot,
        )
        assertEquals(legacy.storageRoot.parentFile, legacy.mutationBarrierRoot.parentFile)
    }

    @Test
    fun handler_shutdown_uses_the_bounded_synchronous_native_drain_contract() {
        var observedTimeout = -1
        assertTrue(
            drainNativeHandlerCaptureWith {
                observedTimeout = it
                true
            },
        )
        assertEquals(NativeRuntime.DEFAULT_HANDLER_DRAIN_TIMEOUT_MILLIS, observedTimeout)
        assertFalse(drainNativeHandlerCaptureWith { false })
        assertFalse(
            drainNativeHandlerCaptureWith {
                throw UnsatisfiedLinkError("native unavailable")
            },
        )
        assertFalse(
            drainNativeHandlerCaptureWith {
                throw IllegalStateException("native drain rejected")
            },
        )
    }

    @Test
    fun exact_durable_policy_with_no_transition_or_repair_is_eligible() {
        val root = root()
        val expected = HandlerStartupExpectation(7, disabled = false, denyMask = 3)
        ControlPage(root.resolve(POLICY_CONTROL_FILE)).commit(expected.snapshot())

        assertEquals(HandlerStartupState.ELIGIBLE, handlerStartupState(root, expected))
    }

    @Test
    fun startup_state_fails_closed_for_every_non_exact_or_ambiguous_control_state() {
        run {
            val root = root()
            val expected = HandlerStartupExpectation(7, disabled = false, denyMask = 3)
            ControlPage(root.resolve(POLICY_CONTROL_FILE)).commit(expected.snapshot())
            assertEquals(
                HandlerStartupState.POLICY_MISMATCH,
                handlerStartupState(root, expected.copy(policyEpoch = 8)),
            )
            assertEquals(
                HandlerStartupState.POLICY_MISMATCH,
                handlerStartupState(root, expected.copy(disabled = true)),
            )
            assertEquals(
                HandlerStartupState.POLICY_MISMATCH,
                handlerStartupState(root, expected.copy(denyMask = 7)),
            )
        }
        run {
            val root = root()
            val expected = HandlerStartupExpectation(7, disabled = false, denyMask = 3)
            Files.write(root.resolve(POLICY_CONTROL_FILE), byteArrayOf(1, 2, 3))
            assertEquals(
                HandlerStartupState.POLICY_UNAVAILABLE,
                handlerStartupState(root, expected),
            )
        }
        run {
            val root = root()
            val expected = HandlerStartupExpectation(7, disabled = false, denyMask = 3)
            ControlPage(root.resolve(POLICY_CONTROL_FILE)).commit(expected.snapshot())
            Files.write(root.resolve(POLICY_REPAIR_MARKER_FILE), byteArrayOf(1))
            assertEquals(
                HandlerStartupState.REPAIR_REQUIRED,
                handlerStartupState(root, expected),
            )
        }
        run {
            val root = root()
            val expected = HandlerStartupExpectation(7, disabled = false, denyMask = 3)
            ControlPage(root.resolve(POLICY_CONTROL_FILE)).commit(expected.snapshot())
            Files.write(
                root.resolve(TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE),
                byteArrayOf(1),
            )
            assertEquals(
                HandlerStartupState.ROOT_INELIGIBLE,
                handlerStartupState(root, expected),
            )
        }
        run {
            val root = root()
            val expected = HandlerStartupExpectation(7, disabled = false, denyMask = 3)
            ControlPage(root.resolve(POLICY_CONTROL_FILE)).commit(expected.snapshot())
            PolicyTransitionJournal(root.resolve(POLICY_TRANSITION_FILE)).begin(
                expected.snapshot(),
                PolicySnapshot(8, 7, false),
            )
            assertEquals(
                HandlerStartupState.TRANSITION_ACTIVE,
                handlerStartupState(root, expected),
            )
        }
        run {
            val root = root()
            val expected = HandlerStartupExpectation(7, disabled = false, denyMask = 3)
            ControlPage(root.resolve(POLICY_CONTROL_FILE)).commit(expected.snapshot())
            Files.write(
                root.resolve("$POLICY_TRANSITION_FILE-a"),
                byteArrayOf(1),
            )
            assertEquals(
                HandlerStartupState.TRANSITION_CORRUPT,
                handlerStartupState(root, expected),
            )
        }
    }

    @Test
    fun permit_is_strictly_bound_and_only_an_exact_match_consumes_it_once() {
        val directory = root().resolve(HANDLER_DIRECTORY_NAME)
        val identity = ByteArray(32) { (it + 1).toByte() }
        val token = ByteArray(HandlerStartPermit.TOKEN_BYTES) { (it + 11).toByte() }
        assertTrue(HandlerStartPermit.write(directory, identity, 7, token))
        assertEquals(HandlerStartPermit.FILE_BYTES, Files.size(HandlerStartPermit.path(directory)))
        assertFalse(Files.exists(HandlerStartPermit.temporaryPath(directory)))

        assertEquals(
            HandlerStartPermitConsumeResult.EPOCH_MISMATCH,
            HandlerStartPermit.consume(directory, identity, 8, token),
        )
        assertTrue(Files.exists(HandlerStartPermit.path(directory)))

        val otherIdentity = identity.copyOf().also { it[0] = 99 }
        assertEquals(
            HandlerStartPermitConsumeResult.IDENTITY_MISMATCH,
            HandlerStartPermit.consume(directory, otherIdentity, 7, token),
        )
        assertTrue(Files.exists(HandlerStartPermit.path(directory)))

        val otherToken = token.copyOf().also { it[0] = 100 }
        assertEquals(
            HandlerStartPermitConsumeResult.TOKEN_MISMATCH,
            HandlerStartPermit.consume(directory, identity, 7, otherToken),
        )
        assertTrue(Files.exists(HandlerStartPermit.path(directory)))

        assertEquals(
            HandlerStartPermitConsumeResult.CONSUMED,
            HandlerStartPermit.consume(directory, identity, 7, token),
        )
        assertFalse(Files.exists(HandlerStartPermit.path(directory)))
        assertEquals(
            HandlerStartPermitConsumeResult.MISSING,
            HandlerStartPermit.consume(directory, identity, 7, token),
        )
    }

    @Test
    fun missing_malformed_and_corrupt_permits_never_authorize_startup() {
        val directory = root().resolve(HANDLER_DIRECTORY_NAME)
        Files.createDirectories(directory)
        val identity = ByteArray(32) { 3 }
        val token = ByteArray(HandlerStartPermit.TOKEN_BYTES) { 4 }
        assertEquals(
            HandlerStartPermitConsumeResult.MISSING,
            HandlerStartPermit.consume(directory, identity, 1, token),
        )

        Files.write(HandlerStartPermit.path(directory), byteArrayOf(1, 2, 3))
        assertEquals(
            HandlerStartPermitConsumeResult.MALFORMED,
            HandlerStartPermit.consume(directory, identity, 1, token),
        )
        assertTrue(Files.exists(HandlerStartPermit.path(directory)))

        assertTrue(HandlerStartPermit.write(directory, identity, 1, token))
        val corrupt = Files.readAllBytes(HandlerStartPermit.path(directory))
        corrupt[40] = (corrupt[40].toInt() xor 1).toByte()
        Files.write(HandlerStartPermit.path(directory), corrupt)
        assertEquals(
            HandlerStartPermitConsumeResult.MALFORMED,
            HandlerStartPermit.consume(directory, identity, 1, token),
        )
        assertTrue(HandlerStartPermit.invalidate(directory))
        assertFalse(Files.exists(HandlerStartPermit.path(directory)))
    }

    @Test
    fun generated_tokens_are_bounded_nonzero_and_defensively_copied_by_the_file() {
        val directory = root().resolve(HANDLER_DIRECTORY_NAME)
        val identity = ByteArray(32) { 5 }
        val token = HandlerStartPermit.newToken()
        assertEquals(HandlerStartPermit.TOKEN_BYTES, token.size)
        assertTrue(token.any { it != 0.toByte() })
        val original = token.copyOf()
        assertTrue(HandlerStartPermit.write(directory, identity, 9, token))
        token.fill(0)

        assertEquals(
            HandlerStartPermitConsumeResult.CONSUMED,
            HandlerStartPermit.consume(directory, identity, 9, original),
        )
        assertContentEquals(ByteArray(HandlerStartPermit.TOKEN_BYTES), token)
    }

    @Test
    fun android_user_path_detection_starts_at_the_package_directory() {
        assertEquals(
            3,
            androidPrivateStoragePackageIndex(
                listOf("data", "user", "0", "dev.tracebox.app", "no_backup", "tracebox"),
            ),
        )
        assertNull(
            androidPrivateStoragePackageIndex(
                listOf("tmp", "data", "user", "0", "dev.tracebox.app", "tracebox"),
            ),
        )
    }

    @Test
    fun handler_guard_ignores_only_the_prefix_above_its_boundary_and_rejects_descendant_symlinks() {
        val handlerDirectory = Path.of(
            "build",
            "handler-startup-tests",
            UUID.randomUUID().toString(),
            "data",
            "user",
            "0",
            "dev.tracebox.app",
        ).toAbsolutePath()
            .resolve("no_backup")
            .resolve(STORAGE_ROOT_DIRECTORY_NAME)
            .resolve(HANDLER_DIRECTORY_NAME)
        val packageBoundary = handlerDirectory.parent.parent.parent
        val platformAlias = packageBoundary.parent
        assertFalse(simulatedSymbolicLinkCheck(handlerDirectory, packageBoundary, setOf(platformAlias)))
        assertTrue(simulatedSymbolicLinkCheck(handlerDirectory, packageBoundary, setOf(packageBoundary)))
        assertTrue(simulatedSymbolicLinkCheck(handlerDirectory, packageBoundary, setOf(handlerDirectory)))

        val base = Files.createTempDirectory("tracebox-handler-generic-symlink")
        val outside = base.resolve("outside").also(Files::createDirectories)
        val genericStorageRoot = base.resolve("generic").resolve(STORAGE_ROOT_DIRECTORY_NAME)
        Files.createDirectories(genericStorageRoot.parent)
        if (runCatching { Files.createSymbolicLink(genericStorageRoot, outside) }.isFailure) return
        val identity = ByteArray(32) { 3 }
        val token = ByteArray(HandlerStartPermit.TOKEN_BYTES) { 4 }
        assertFailsWith<IllegalArgumentException> {
            HandlerStartPermit.write(
                genericStorageRoot.resolve(HANDLER_DIRECTORY_NAME),
                identity,
                1,
                token,
            )
        }
    }

    private fun simulatedSymbolicLinkCheck(
        path: Path,
        firstGuardedComponent: Path,
        symbolicLinks: Set<Path>,
    ): Boolean = hasSymbolicLinkComponentAtOrBelow(
        path = path,
        firstGuardedComponent = firstGuardedComponent,
        exists = { true },
        isSymbolicLink = { it in symbolicLinks },
    )

    private fun root(): Path =
        Path.of(
            "build",
            "handler-startup-tests",
            UUID.randomUUID().toString(),
            STORAGE_ROOT_DIRECTORY_NAME,
        ).toAbsolutePath().also {
            Files.createDirectories(it)
            TraceboxOwnedStorageRoot.claim(it)
        }
}
