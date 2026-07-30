package dev.tracebox.nativecapture

import dev.tracebox.api.Crc32c
import dev.tracebox.core.ControlPage
import dev.tracebox.core.PolicyPageException
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.PolicyTransitionJournal
import dev.tracebox.core.PolicyTransitionLoad
import dev.tracebox.storage.TraceboxOwnedStorageRoot
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/**
 * Durable one-shot authorization for one handler-process launch.
 *
 * The primary façade reserves [FILE_BYTES], writes and forces this permit under the UID mutation
 * barrier, then sends the same opaque token in the service intent. The handler consumes the exact
 * identity/epoch/token tuple under that barrier before any directory or native mutation. Deleting
 * the permit before `stopService` therefore fences a delayed old start even when policy remains
 * enabled.
 */
object HandlerStartPermit {
    const val FILE_NAME = "tracebox-handler-start-permit-v1"
    const val TEMP_FILE_NAME = "$FILE_NAME.new"
    const val FILE_BYTES = 96L
    const val TOKEN_BYTES = 32

    /** Creates a non-zero opaque token suitable for one [write] and service intent. */
    fun newToken(): ByteArray {
        val token = ByteArray(TOKEN_BYTES)
        do {
            SecureRandom().nextBytes(token)
        } while (token.all { it == 0.toByte() })
        return token
    }

    fun path(handlerDirectory: Path): Path = safeHandlerDirectory(handlerDirectory).resolve(FILE_NAME)

    fun temporaryPath(handlerDirectory: Path): Path =
        safeHandlerDirectory(handlerDirectory).resolve(TEMP_FILE_NAME)

    /**
     * Atomically persists a new permit and verifies its exact bytes.
     *
     * Callers must reserve both [path] and [temporaryPath] first and hold the UID mutation
     * barrier. Handler launch must occur only after this returns `true`.
     */
    fun write(
        handlerDirectory: Path,
        processIdentity: ByteArray,
        policyEpoch: Long,
        token: ByteArray,
    ): Boolean {
        validateCredential(processIdentity, policyEpoch, token)
        val directory = safeHandlerDirectory(handlerDirectory)
        val target = directory.resolve(FILE_NAME)
        val temporary = directory.resolve(TEMP_FILE_NAME)
        val encoded = encode(processIdentity, policyEpoch, token)
        return try {
            requireNoSymbolicLinkComponent(directory)
            Files.createDirectories(directory)
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(target) ||
                Files.isSymbolicLink(temporary)
            ) {
                return false
            }
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                writeFully(channel, ByteBuffer.wrap(encoded))
                channel.force(true)
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            forceDirectory(directory)
            val verified = read(target)
            verified is PermitRead.Valid &&
                verified.record == PermitRecord(policyEpoch, processIdentity, token)
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Invalidates both a committed permit and any interrupted replacement without creating paths.
     *
     * The primary calls this before asking Android to stop the service.
     */
    fun invalidate(handlerDirectory: Path): Boolean {
        val directory = safeHandlerDirectory(handlerDirectory)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return true
        if (!safeExistingDirectory(directory)) return false
        val target = directory.resolve(FILE_NAME)
        val temporary = directory.resolve(TEMP_FILE_NAME)
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(temporary)) return false
        return try {
            val changed = Files.deleteIfExists(target) or Files.deleteIfExists(temporary)
            if (changed) forceDirectory(directory)
            !Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                !Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun consume(
        handlerDirectory: Path,
        processIdentity: ByteArray,
        policyEpoch: Long,
        token: ByteArray,
    ): HandlerStartPermitConsumeResult {
        validateCredential(processIdentity, policyEpoch, token)
        val directory = safeHandlerDirectory(handlerDirectory)
        if (!safeExistingDirectory(directory)) return HandlerStartPermitConsumeResult.MISSING
        val target = directory.resolve(FILE_NAME)
        return when (val loaded = read(target)) {
            PermitRead.Missing -> HandlerStartPermitConsumeResult.MISSING
            PermitRead.Malformed -> HandlerStartPermitConsumeResult.MALFORMED
            is PermitRead.Valid -> when {
                loaded.record.policyEpoch != policyEpoch ->
                    HandlerStartPermitConsumeResult.EPOCH_MISMATCH

                !loaded.record.processIdentity.contentEquals(processIdentity) ->
                    HandlerStartPermitConsumeResult.IDENTITY_MISMATCH

                !loaded.record.token.contentEquals(token) ->
                    HandlerStartPermitConsumeResult.TOKEN_MISMATCH

                invalidate(directory) -> HandlerStartPermitConsumeResult.CONSUMED
                else -> HandlerStartPermitConsumeResult.INVALIDATION_FAILED
            }
        }
    }

    private fun encode(
        processIdentity: ByteArray,
        policyEpoch: Long,
        token: ByteArray,
    ): ByteArray {
        val bytes = ByteBuffer.allocate(FILE_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(MAGIC)
            .putInt(VERSION)
            .putInt(FILE_BYTES.toInt())
            .putLong(policyEpoch)
            .put(processIdentity)
            .put(token)
            .putInt(COMPLETION)
            .array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(CRC_OFFSET, Crc32c.value(bytes, 0, CRC_OFFSET))
        return bytes
    }

    private fun read(path: Path): PermitRead {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return PermitRead.Missing
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return PermitRead.Malformed
        }
        val bytes = try {
            FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                if (channel.size() != FILE_BYTES) return PermitRead.Malformed
                val result = ByteBuffer.allocate(FILE_BYTES.toInt())
                while (result.hasRemaining()) {
                    if (channel.read(result) < 0) return PermitRead.Malformed
                }
                if (channel.size() != FILE_BYTES) return PermitRead.Malformed
                result.array()
            }
        } catch (_: IOException) {
            return PermitRead.Malformed
        } catch (_: UnsupportedOperationException) {
            return PermitRead.Malformed
        } catch (_: SecurityException) {
            return PermitRead.Malformed
        }
        if (Crc32c.value(bytes, 0, CRC_OFFSET) !=
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(CRC_OFFSET)
        ) {
            return PermitRead.Malformed
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.long != MAGIC ||
            buffer.int != VERSION ||
            buffer.int != FILE_BYTES.toInt()
        ) {
            return PermitRead.Malformed
        }
        val epoch = buffer.long
        val identity = ByteArray(PROCESS_IDENTITY_BYTES).also(buffer::get)
        val token = ByteArray(TOKEN_BYTES).also(buffer::get)
        if (buffer.int != COMPLETION ||
            epoch < 0 ||
            identity.all { it == 0.toByte() } ||
            token.all { it == 0.toByte() }
        ) {
            return PermitRead.Malformed
        }
        return PermitRead.Valid(PermitRecord(epoch, identity, token))
    }

    private fun validateCredential(
        processIdentity: ByteArray,
        policyEpoch: Long,
        token: ByteArray,
    ) {
        require(processIdentity.size == PROCESS_IDENTITY_BYTES)
        require(processIdentity.any { it != 0.toByte() })
        require(policyEpoch >= 0)
        require(token.size == TOKEN_BYTES)
        require(token.any { it != 0.toByte() })
    }

    private data class PermitRecord(
        val policyEpoch: Long,
        private val identityBytes: ByteArray,
        private val tokenBytes: ByteArray,
    ) {
        val processIdentity: ByteArray get() = identityBytes.copyOf()
        val token: ByteArray get() = tokenBytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is PermitRecord &&
                policyEpoch == other.policyEpoch &&
                identityBytes.contentEquals(other.identityBytes) &&
                tokenBytes.contentEquals(other.tokenBytes)

        override fun hashCode(): Int =
            31 * (31 * policyEpoch.hashCode() + identityBytes.contentHashCode()) +
                tokenBytes.contentHashCode()
    }

    private sealed interface PermitRead {
        data object Missing : PermitRead
        data object Malformed : PermitRead
        data class Valid(val record: PermitRecord) : PermitRead
    }

    private const val PROCESS_IDENTITY_BYTES = 32
    private const val MAGIC = 0x3156505358425454L
    private const val VERSION = 1
    private const val COMPLETION = 0x444f4e45
    private const val CRC_OFFSET = 92
}

internal enum class HandlerStartPermitConsumeResult {
    CONSUMED,
    MISSING,
    MALFORMED,
    EPOCH_MISMATCH,
    IDENTITY_MISMATCH,
    TOKEN_MISMATCH,
    INVALIDATION_FAILED,
}

internal data class HandlerStartupExpectation(
    val policyEpoch: Long,
    val disabled: Boolean,
    val denyMask: Long,
) {
    init {
        require(policyEpoch >= 0)
    }

    fun snapshot(): PolicySnapshot = PolicySnapshot(policyEpoch, denyMask, disabled)
}

internal enum class HandlerStartupState {
    ELIGIBLE,
    ROOT_INELIGIBLE,
    REPAIR_REQUIRED,
    POLICY_UNAVAILABLE,
    POLICY_MISMATCH,
    TRANSITION_ACTIVE,
    TRANSITION_CORRUPT,
}

/** Exact fail-closed state check performed only while the UID mutation lease is held. */
internal fun handlerStartupState(
    storageRoot: Path,
    expected: HandlerStartupExpectation,
): HandlerStartupState {
    val root = storageRoot.toAbsolutePath().normalize()
    if (!TraceboxOwnedStorageRoot.isEligible(root)) {
        return HandlerStartupState.ROOT_INELIGIBLE
    }
    if (Files.exists(root.resolve(POLICY_REPAIR_MARKER_FILE), LinkOption.NOFOLLOW_LINKS)) {
        return HandlerStartupState.REPAIR_REQUIRED
    }
    val committed = try {
        ControlPage(root.resolve(POLICY_CONTROL_FILE)).committed()
    } catch (_: PolicyPageException) {
        return HandlerStartupState.POLICY_UNAVAILABLE
    } catch (_: RuntimeException) {
        return HandlerStartupState.POLICY_UNAVAILABLE
    }
    if (committed != expected.snapshot()) return HandlerStartupState.POLICY_MISMATCH
    val transition = try {
        PolicyTransitionJournal(root.resolve(POLICY_TRANSITION_FILE)).load()
    } catch (_: RuntimeException) {
        return HandlerStartupState.TRANSITION_CORRUPT
    }
    return when (transition) {
        PolicyTransitionLoad.Empty -> HandlerStartupState.ELIGIBLE
        is PolicyTransitionLoad.Active -> HandlerStartupState.TRANSITION_ACTIVE
        PolicyTransitionLoad.Corrupt -> HandlerStartupState.TRANSITION_CORRUPT
    }
}

private fun safeHandlerDirectory(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    require(normalized.fileName?.toString() == HANDLER_DIRECTORY_NAME)
    require(normalized.parent?.fileName?.toString() == STORAGE_ROOT_DIRECTORY_NAME)
    return normalized
}

private fun safeExistingDirectory(path: Path): Boolean =
    runCatching {
        !hasSymbolicLinkComponent(path) &&
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)

private fun requireNoSymbolicLinkComponent(path: Path) {
    require(!hasSymbolicLinkComponent(path)) { "symbolic-link handler directory is forbidden" }
}

private fun hasSymbolicLinkComponent(path: Path): Boolean {
    val normalized = path.toAbsolutePath().normalize()
    if (normalized.nameCount == 0) return false
    val firstGuardedComponent = androidPrivateStorageBoundary(normalized)
        ?: normalized.root.resolve(normalized.getName(0))
    return hasSymbolicLinkComponentAtOrBelow(
        path = normalized,
        firstGuardedComponent = firstGuardedComponent,
        exists = { Files.exists(it, LinkOption.NOFOLLOW_LINKS) },
        isSymbolicLink = Files::isSymbolicLink,
    )
}

/**
 * Android may expose a package-private directory through a platform-owned compatibility alias
 * above the package boundary (for example `/data/user/0 -> /data/data`). Trust only that fixed
 * platform prefix: the package directory itself and every app-controlled descendant remain
 * subject to the no-symlink rule. Non-Android paths return `null` and are inspected from root.
 */
private fun androidPrivateStorageBoundary(path: Path): Path? {
    if (path.fileSystem.separator != "/") return null
    val packageIndex = androidPrivateStoragePackageIndex(path.map(Path::toString)) ?: return null
    return path.root.resolve(path.subpath(0, packageIndex + 1))
}

internal fun androidPrivateStoragePackageIndex(components: List<String>): Int? {
    if (components.size >= 4 &&
        components[0] == "data" &&
        components[1] in ANDROID_INTERNAL_USER_DIRECTORIES &&
        components[2].isAndroidUserId()
    ) {
        return 3
    }
    if (components.size >= 3 &&
        components[0] == "data" &&
        components[1] == "data"
    ) {
        return 2
    }
    if (components.size >= 6 &&
        components[0] == "mnt" &&
        components[1] == "expand" &&
        components[2].isNotBlank() &&
        components[3] in ANDROID_INTERNAL_USER_DIRECTORIES &&
        components[4].isAndroidUserId()
    ) {
        return 5
    }
    return null
}

internal fun hasSymbolicLinkComponentAtOrBelow(
    path: Path,
    firstGuardedComponent: Path,
    exists: (Path) -> Boolean,
    isSymbolicLink: (Path) -> Boolean,
): Boolean {
    val normalized = path.toAbsolutePath().normalize()
    val boundary = firstGuardedComponent.toAbsolutePath().normalize()
    require(normalized.startsWith(boundary)) { "symbolic-link boundary must contain the path" }
    var cursor = boundary.parent ?: return exists(boundary) && isSymbolicLink(boundary)
    for (part in cursor.relativize(normalized)) {
        cursor = cursor.resolve(part)
        if (exists(cursor) && isSymbolicLink(cursor)) return true
    }
    return false
}

private fun String.isAndroidUserId(): Boolean =
    isNotEmpty() && length <= 10 && all(Char::isDigit)

private val ANDROID_INTERNAL_USER_DIRECTORIES = setOf("user", "user_de")

private fun writeFully(channel: FileChannel, source: ByteBuffer) {
    while (source.hasRemaining()) channel.write(source)
}

private fun forceDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // The permit file was forced; some host and Android providers deny directory handles.
    } catch (_: UnsupportedOperationException) {
        // Directory fsync is unavailable on this provider.
    } catch (_: SecurityException) {
        // A later exact permit read still fails closed.
    }
}

internal const val STORAGE_ROOT_DIRECTORY_NAME = "tracebox"
internal const val HANDLER_DIRECTORY_NAME = "native-handler"
internal const val POLICY_CONTROL_FILE = "policy-control-v1"
internal const val POLICY_TRANSITION_FILE = "policy-native-transition-v1"
internal const val POLICY_REPAIR_MARKER_FILE = "policy-repair-required-v1"
