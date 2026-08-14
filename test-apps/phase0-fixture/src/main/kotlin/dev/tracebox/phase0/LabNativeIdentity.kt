package dev.tracebox.phase0

import android.content.Context
import dev.tracebox.nativecapture.NativeRuntime
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Fixture-owned native identity bootstrap.
 *
 * Production Tracebox has its own lifecycle-journal integration. This helper exists only in the
 * test application and persists every fixed-size value before passing it to native capture.
 */
object LabNativeIdentity {
    data class Binding(
        val processIdentity: ByteArray,
        val rawArtifactIdentity: ByteArray,
        val policyEpoch: Long,
    )

    fun initialize(context: Context, processRole: Int): Boolean {
        val binding = binding(context, processRole) ?: return false
        val initialized =
            NativeRuntime.initializeEmergency(
                context.noBackupFilesDir.absolutePath,
                processRole,
                binding.processIdentity,
                binding.policyEpoch,
            )
        return initialized &&
            NativeRuntime.updatePolicy(
                binding.policyEpoch,
                disabled = false,
                denyMask = 0,
            )
    }

    fun connect(context: Context, socketPath: String, processRole: Int): Boolean {
        val binding = binding(context, processRole) ?: return false
        return NativeRuntime.connectClient(
            socketPath,
            processRole,
            binding.processIdentity,
            binding.rawArtifactIdentity,
            binding.policyEpoch,
        )
    }

    @Synchronized
    fun binding(context: Context, processRole: Int): Binding? {
        require(processRole in MIN_PROCESS_ROLE..MAX_PROCESS_ROLE)
        val roleRoot =
            context.noBackupFilesDir.toPath()
                .resolve(ROOT_DIRECTORY)
                .resolve("role-$processRole")
        return try {
            Files.createDirectories(roleRoot)
            val journal = roleRoot.resolve(IDENTITY_JOURNAL)
            val process =
                LabIdentityFiles.loadOrCreate(
                    roleRoot.resolve(PROCESS_IDENTITY_FILE),
                    IDENTITY_BYTES,
                ) {
                    NativeRuntime.allocateIdentity(
                        journal.toString(),
                        PROCESS_IDENTITY_KIND,
                    )
                } ?: return null
            val rawArtifact =
                LabIdentityFiles.loadOrCreate(
                    roleRoot.resolve(RAW_ARTIFACT_IDENTITY_FILE),
                    IDENTITY_BYTES,
                ) {
                    NativeRuntime.allocateIdentity(
                        journal.toString(),
                        RAW_ARTIFACT_IDENTITY_KIND,
                    )
                } ?: return null
            val epochBytes =
                LabIdentityFiles.loadOrCreate(
                    roleRoot.resolve(POLICY_EPOCH_FILE),
                    Long.SIZE_BYTES,
                ) {
                    ByteBuffer.allocate(Long.SIZE_BYTES).putLong(INITIAL_POLICY_EPOCH).array()
                } ?: return null
            val epoch = ByteBuffer.wrap(epochBytes).long
            if (epoch <= 0) return null
            Binding(process.copyOf(), rawArtifact.copyOf(), epoch)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private const val ROOT_DIRECTORY = "tracebox-lab-native-identity-v1"
    private const val IDENTITY_JOURNAL = "identity-lifecycle-v1.log"
    private const val PROCESS_IDENTITY_FILE = "process-identity.bin"
    private const val RAW_ARTIFACT_IDENTITY_FILE = "raw-artifact-identity.bin"
    private const val POLICY_EPOCH_FILE = "policy-epoch.bin"
    private const val IDENTITY_BYTES = 32
    private const val PROCESS_IDENTITY_KIND = 1
    private const val RAW_ARTIFACT_IDENTITY_KIND = 2
    private const val MIN_PROCESS_ROLE = 1
    private const val MAX_PROCESS_ROLE = 3
    private const val INITIAL_POLICY_EPOCH = 1L
}

/** Fixed-size, force-before-publish persistence isolated for local JVM tests. */
internal object LabIdentityFiles {
    fun loadOrCreate(path: Path, expectedBytes: Int, create: () -> ByteArray?): ByteArray? {
        require(expectedBytes in 1..MAX_FIXED_BYTES)
        readFixed(path, expectedBytes)?.let { return it }
        if (Files.exists(path)) return null
        val created = create() ?: return null
        if (created.size != expectedBytes) return null
        val temporary = path.resolveSibling("${path.fileName}.new")
        return try {
            Files.deleteIfExists(temporary)
            FileOutputStream(temporary.toFile()).channel.use { channel ->
                val buffer = ByteBuffer.wrap(created)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            publishWithoutReplace(temporary, path)
            readFixed(path, expectedBytes)
        } catch (_: IOException) {
            try {
                Files.deleteIfExists(temporary)
            } catch (_: IOException) {
                Unit
            }
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun readFixed(path: Path, expectedBytes: Int): ByteArray? {
        if (!Files.isRegularFile(path)) return null
        return try {
            Files.readAllBytes(path).takeIf { it.size == expectedBytes }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun publishWithoutReplace(temporary: Path, path: Path) {
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(temporary, path)
            } catch (_: FileAlreadyExistsException) {
                Files.deleteIfExists(temporary)
            }
        } catch (_: FileAlreadyExistsException) {
            Files.deleteIfExists(temporary)
        }
    }

    private const val MAX_FIXED_BYTES = 64
}
