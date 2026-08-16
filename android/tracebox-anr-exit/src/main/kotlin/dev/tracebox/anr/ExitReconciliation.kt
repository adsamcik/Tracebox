package dev.tracebox.anr

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import dev.tracebox.api.Crc32c

/** Minimal documented-API-shaped input; the Android adapter maps ApplicationExitInfo into this value. */
data class SyntheticApplicationExitInfo(
    val packageName: String,
    val processName: String,
    val definingUid: Int,
    val timestampMillis: Long,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val pid: Int,
    val processStateSummary: ByteArray?,
    val artifactKind: ExitArtifactKind,
) {
    init {
        require(packageName.isNotBlank() && packageName.toByteArray(StandardCharsets.UTF_8).size <= 256)
        require(processName.isNotBlank() && processName.toByteArray(StandardCharsets.UTF_8).size <= 256)
        require(definingUid >= 0)
        require(pid >= 0)
        require(processStateSummary == null || processStateSummary.size <= 128)
    }
}

/** The documented raw source associated with an OS exit record. */
enum class ExitArtifactKind {
    ANR_TRACE,
    NATIVE_TOMBSTONE,

    /** Structural exit metadata only; no OS-owned raw stream may be opened for this source. */
    NONE,
}

/** Installation-lifetime exact OS source key; no bounded-history record is imported twice. */
@JvmInline
value class ExitSourceKey(val encoded: String) {
    fun bytes(): ByteArray = Base64.getUrlDecoder().decode(encoded).also {
        require(it.size == SOURCE_KEY_BYTES)
    }

    companion object {
        const val SOURCE_KEY_BYTES = 32

        fun derive(exit: SyntheticApplicationExitInfo): ExitSourceKey {
            val digest = MessageDigest.getInstance("SHA-256")
            updateBytes(digest, "tracebox-exit-source-v1".toByteArray(StandardCharsets.UTF_8))
            updateBytes(digest, exit.packageName.toByteArray(StandardCharsets.UTF_8))
            updateBytes(digest, exit.processName.toByteArray(StandardCharsets.UTF_8))
            updateInt(digest, exit.definingUid)
            updateLong(digest, exit.timestampMillis)
            updateInt(digest, exit.reason)
            updateInt(digest, exit.status)
            updateInt(digest, exit.importance)
            updateInt(digest, exit.pid)
            updateInt(digest, exit.artifactKind.ordinal)
            val summaryDigest = exit.processStateSummary?.let {
                MessageDigest.getInstance("SHA-256").digest(it)
            }
            updateInt(digest, if (summaryDigest == null) 0 else 1)
            if (summaryDigest != null) updateBytes(digest, summaryDigest)
            return ExitSourceKey(Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest()))
        }

        private fun updateInt(digest: MessageDigest, value: Int) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
        }

        private fun updateLong(digest: MessageDigest, value: Long) {
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
        }

        private fun updateBytes(digest: MessageDigest, value: ByteArray) {
            updateInt(digest, value.size)
            digest.update(value)
        }
    }
}

/** Capture-time policy token stored through ActivityManager.setProcessStateSummary on API 30+. */
data class ExitPolicyToken(
    val epoch: Long,
    val rawArtifactAllowed: Boolean,
    val processInstanceId: ByteArray,
    /**
     * Originating Tracebox process role.
     *
     * `null` is reserved for decoded legacy-v1 tokens. Such tokens remain useful for metadata
     * linkage, but callers must not trust them to authorize raw-artifact import.
     */
    val processRole: Int? = null,
) {
    init {
        require(epoch >= 0)
        require(processInstanceId.size == ExitSourceKey.SOURCE_KEY_BYTES)
        require(processRole == null || processRole >= 0)
    }

    fun encode(): ByteArray {
        val boundProcessRole = requireNotNull(processRole) {
            "legacy ExitPolicyToken values cannot be published"
        }
        val bytes = ByteBuffer.allocate(ENCODED_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC)
            .putInt(VERSION)
            .putLong(epoch)
            .putInt(if (rawArtifactAllowed) 1 else 0)
            .put(processInstanceId)
            .putInt(boundProcessRole)
            .array()
        ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(ENCODED_SIZE - Int.SIZE_BYTES, Crc32c.value(bytes, 0, ENCODED_SIZE - Int.SIZE_BYTES))
        return bytes
    }

    companion object {
        const val ENCODED_SIZE = 60
        internal const val LEGACY_ENCODED_SIZE = 56
        private const val MAGIC = 0x54584245
        private const val VERSION = 2
        private const val LEGACY_VERSION = 1

        fun decode(bytes: ByteArray?): ExitPolicyToken? {
            if (bytes == null ||
                bytes.size != ENCODED_SIZE && bytes.size != LEGACY_ENCODED_SIZE
            ) {
                return null
            }
            val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != MAGIC) return null
            val version = buffer.int
            val expectedSize = when (version) {
                VERSION -> ENCODED_SIZE
                LEGACY_VERSION -> LEGACY_ENCODED_SIZE
                else -> return null
            }
            if (bytes.size != expectedSize) return null
            val epoch = buffer.long
            val raw = when (buffer.int) {
                0 -> false
                1 -> true
                else -> return null
            }
            val process = ByteArray(ExitSourceKey.SOURCE_KEY_BYTES).also(buffer::get)
            val processRole = if (version == VERSION) buffer.int else null
            val expected = buffer.int
            if (expected != Crc32c.value(bytes, 0, expectedSize - Int.SIZE_BYTES) ||
                epoch < 0 ||
                processRole != null && processRole < 0
            ) {
                return null
            }
            return ExitPolicyToken(epoch, raw, process, processRole)
        }
    }
}

enum class ExitImportStage { PREPARED, APPENDED }

/**
 * Durable binding between one OS-owned source and one independently allocated Tracebox raw ID.
 * Array access is defensive so equality cannot silently fall back to referential ByteArray
 * semantics.
 */
class ExitRawArtifactProvenance(
    val artifactKind: ExitArtifactKind,
    rawArtifactId: ByteArray,
    val acquisitionEpoch: Long,
    originProcessInstanceId: ByteArray,
    val originRole: Int,
) {
    private val rawId = rawArtifactId.copyOf()
    private val processId = originProcessInstanceId.copyOf()

    val rawArtifactId: ByteArray get() = rawId.copyOf()
    val originProcessInstanceId: ByteArray get() = processId.copyOf()

    init {
        require(artifactKind != ExitArtifactKind.NONE) {
            "raw artifact provenance requires an artifact-bearing exit"
        }
        require(rawId.size == ExitSourceKey.SOURCE_KEY_BYTES && rawId.any { it != 0.toByte() })
        require(processId.size == ExitSourceKey.SOURCE_KEY_BYTES)
        require(acquisitionEpoch >= 0)
        require(originRole >= 0)
    }

    override fun equals(other: Any?): Boolean =
        other is ExitRawArtifactProvenance &&
            artifactKind == other.artifactKind &&
            acquisitionEpoch == other.acquisitionEpoch &&
            originRole == other.originRole &&
            rawId.contentEquals(other.rawId) &&
            processId.contentEquals(other.processId)

    override fun hashCode(): Int {
        var result = artifactKind.hashCode()
        result = 31 * result + acquisitionEpoch.hashCode()
        result = 31 * result + originRole
        result = 31 * result + rawId.contentHashCode()
        result = 31 * result + processId.contentHashCode()
        return result
    }

    override fun toString(): String =
        "ExitRawArtifactProvenance(artifactKind=$artifactKind, " +
            "acquisitionEpoch=$acquisitionEpoch, originRole=$originRole)"
}

/**
 * Bounded metadata kept between journaling an exact source and forcing the generated `OsExit`
 * record. Raw bytes are never duplicated here, but their random ID and acquisition provenance are.
 */
data class ExitImportEntry(
    val sourceKey: ExitSourceKey,
    val stage: ExitImportStage,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val linkConfidence: ExitLinkConfidence,
    val artifactState: ExitRawReadState,
    val rawArtifact: ExitRawArtifactProvenance? = null,
) {
    init {
        require(artifactState != ExitRawReadState.AVAILABLE || rawArtifact != null) {
            "available exit raw bytes require durable provenance"
        }
    }
}

/**
 * Fixed-size, per-source import journal. The directory is a strict bounded namespace: malformed,
 * unknown, symlinked, or excess entries fail closed instead of being treated as an empty census.
 */
class ExitImportJournal(
    root: Path,
    private val maxEntries: Int,
    private val maxBytes: Int,
) {
    private val root = root.toAbsolutePath().normalize()

    init {
        require(maxEntries > 0)
        require(maxBytes >= ENTRY_BYTES)
    }

    @Synchronized
    fun prepare(entry: ExitImportEntry): Boolean {
        val existing = read(entry.sourceKey)
        if (existing != null) return existing.copy(stage = entry.stage) == entry
        val entries = pending()
        if (entries.size >= maxEntries || (entries.size + 1L) * ENTRY_BYTES > maxBytes) return false
        write(entry)
        return true
    }

    @Synchronized
    fun markAppended(sourceKey: ExitSourceKey): Boolean {
        val existing = read(sourceKey) ?: return false
        if (existing.stage == ExitImportStage.APPENDED) return true
        write(existing.copy(stage = ExitImportStage.APPENDED))
        return true
    }

    @Synchronized
    fun updateArtifactState(sourceKey: ExitSourceKey, state: ExitRawReadState): Boolean {
        val existing = read(sourceKey) ?: return false
        if (existing.artifactState == state) return true
        if (state == ExitRawReadState.AVAILABLE && existing.rawArtifact == null) return false
        write(existing.copy(artifactState = state))
        return true
    }

    @Synchronized
    fun read(sourceKey: ExitSourceKey): ExitImportEntry? =
        census().firstOrNull { it.sourceKey == sourceKey }

    @Synchronized
    fun pending(): List<ExitImportEntry> = census()

    @Synchronized
    fun complete(sourceKey: ExitSourceKey): Boolean {
        census()
        return try {
            val deleted = Files.deleteIfExists(recordPath(sourceKey))
            if (deleted) forceDirectory(root)
            true
        } catch (_: java.io.IOException) {
            false
        }
    }

    @Synchronized
    fun deleteAllOwned(): Boolean {
        return try {
            val entries = census()
            var deleted = false
            entries.forEach {
                deleted = Files.deleteIfExists(recordPath(it.sourceKey)) || deleted
            }
            if (deleted) forceDirectory(root)
            true
        } catch (_: java.io.IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun write(entry: ExitImportEntry) {
        ensureSafeDirectory(root)
        val raw = entry.rawArtifact
        val bytes = ByteBuffer.allocate(ENTRY_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(ENTRY_MAGIC)
            .putInt(ENTRY_VERSION)
            .putInt(entry.stage.ordinal)
            .putInt(entry.reason)
            .putInt(entry.status)
            .putInt(entry.importance)
            .putInt(entry.linkConfidence.ordinal)
            .putInt(entry.artifactState.ordinal)
            .put(entry.sourceKey.bytes())
            .putInt(if (raw == null) 0 else 1)
            .putInt(raw?.artifactKind?.ordinal ?: -1)
            .putLong(raw?.acquisitionEpoch ?: 0)
            .putInt(raw?.originRole ?: -1)
            .put(raw?.rawArtifactId ?: ByteArray(ExitSourceKey.SOURCE_KEY_BYTES))
            .put(raw?.originProcessInstanceId ?: ByteArray(ExitSourceKey.SOURCE_KEY_BYTES))
            .array()
        ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(ENTRY_BYTES - Int.SIZE_BYTES, Crc32c.value(bytes, 0, ENTRY_BYTES - Int.SIZE_BYTES))
        val target = recordPath(entry.sourceKey)
        val temporary = temporaryPath(entry.sourceKey)
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use {
            val source = ByteBuffer.wrap(bytes)
            while (source.hasRemaining()) {
                if (it.write(source) <= 0) throw java.io.IOException("short exit-import journal write")
            }
            it.force(true)
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        forceDirectory(root)
    }

    private fun decode(path: Path, expectedKey: ExitSourceKey): ExitImportEntry? {
        val bytes = readBoundedRegularFile(path, ENTRY_BYTES)
            ?.takeIf { it.size == ENTRY_BYTES || it.size == LEGACY_ENTRY_BYTES }
            ?: return null
        if (Crc32c.value(bytes, 0, bytes.size - Int.SIZE_BYTES) !=
            ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .getInt(bytes.size - Int.SIZE_BYTES)
        ) return null
        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != ENTRY_MAGIC) return null
        val version = buffer.int
        if (version == ENTRY_VERSION && bytes.size != ENTRY_BYTES ||
            version == LEGACY_ENTRY_VERSION && bytes.size != LEGACY_ENTRY_BYTES
        ) {
            return null
        }
        val stage = ExitImportStage.entries.getOrNull(buffer.int) ?: return null
        val reason = buffer.int
        val status = buffer.int
        val importance = buffer.int
        val confidence = ExitLinkConfidence.entries.getOrNull(buffer.int) ?: return null
        val artifact = ExitRawReadState.entries.getOrNull(buffer.int) ?: return null
        val keyBytes = ByteArray(ExitSourceKey.SOURCE_KEY_BYTES).also(buffer::get)
        if (!keyBytes.contentEquals(expectedKey.bytes())) return null
        if (version == LEGACY_ENTRY_VERSION) {
            return ExitImportEntry(
                expectedKey,
                stage,
                reason,
                status,
                importance,
                confidence,
                if (artifact == ExitRawReadState.AVAILABLE) {
                    ExitRawReadState.READ_FAILED
                } else {
                    artifact
                },
            )
        }
        if (version != ENTRY_VERSION) return null
        val rawPresent = buffer.int
        val kindOrdinal = buffer.int
        val acquisitionEpoch = buffer.long
        val originRole = buffer.int
        val rawId = ByteArray(ExitSourceKey.SOURCE_KEY_BYTES).also(buffer::get)
        val processId = ByteArray(ExitSourceKey.SOURCE_KEY_BYTES).also(buffer::get)
        val provenance = when (rawPresent) {
            0 -> {
                if (kindOrdinal != -1 ||
                    acquisitionEpoch != 0L ||
                    originRole != -1 ||
                    rawId.any { it != 0.toByte() } ||
                    processId.any { it != 0.toByte() }
                ) {
                    return null
                }
                null
            }

            1 -> {
                val kind = ExitArtifactKind.entries.getOrNull(kindOrdinal) ?: return null
                try {
                    ExitRawArtifactProvenance(
                        kind,
                        rawId,
                        acquisitionEpoch,
                        processId,
                        originRole,
                    )
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }

            else -> return null
        }
        return try {
            ExitImportEntry(
                expectedKey,
                stage,
                reason,
                status,
                importance,
                confidence,
                artifact,
                provenance,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun recordPath(sourceKey: ExitSourceKey): Path =
        root.resolve("${canonicalSourceKey(sourceKey)}$ENTRY_SUFFIX")

    private fun temporaryPath(sourceKey: ExitSourceKey): Path =
        root.resolve("${canonicalSourceKey(sourceKey)}$ENTRY_SUFFIX$TEMPORARY_SUFFIX")

    private fun census(): List<ExitImportEntry> {
        if (hasSymbolicLinkComponent(root)) {
            throw IllegalStateException("symbolic-link exit-import root is forbidden")
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("exit-import root is not a directory")
        }

        val targets = linkedMapOf<ExitSourceKey, Path>()
        val temporaries = linkedMapOf<ExitSourceKey, Path>()
        val physicalLimit = maxEntries.toLong() * 2L
        try {
            Files.list(root).use { paths ->
                val iterator = paths.iterator()
                var inspected = 0L
                while (iterator.hasNext()) {
                    if (inspected == physicalLimit) {
                        throw IllegalStateException("exit-import directory exceeds its bounded census")
                    }
                    inspected++
                    val path = iterator.next()
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(path)
                    ) {
                        throw IllegalStateException("unsafe exit-import directory entry")
                    }
                    val name = path.fileName.toString()
                    val temporary = name.endsWith("$ENTRY_SUFFIX$TEMPORARY_SUFFIX")
                    val encoded = when {
                        temporary -> name.removeSuffix("$ENTRY_SUFFIX$TEMPORARY_SUFFIX")
                        name.endsWith(ENTRY_SUFFIX) -> name.removeSuffix(ENTRY_SUFFIX)
                        else -> throw IllegalStateException("unknown exit-import directory entry")
                    }
                    val key = decodeCanonicalSourceKey(encoded)
                    val destination = if (temporary) temporaries else targets
                    if (destination.put(key, path) != null) {
                        throw IllegalStateException("duplicate exit-import directory identity")
                    }
                }
            }
        } catch (failure: java.io.IOException) {
            throw IllegalStateException("cannot enumerate exit-import directory", failure)
        }
        if ((targets.keys + temporaries.keys).size > maxEntries) {
            throw IllegalStateException("exit-import directory exceeds its entry bound")
        }

        temporaries.toSortedMap(compareBy(ExitSourceKey::encoded)).forEach { (key, temporary) ->
            if (decode(temporary, key) == null) {
                throw IllegalStateException("invalid temporary exit-import entry")
            }
            val target = recordPath(key)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && decode(target, key) == null) {
                throw IllegalStateException("invalid durable exit-import entry")
            }
            try {
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
                forceDirectory(root)
            } catch (failure: java.io.IOException) {
                throw IllegalStateException("cannot recover temporary exit-import entry", failure)
            }
            targets[key] = target
        }

        var durableBytes = 0L
        return targets.toSortedMap(compareBy(ExitSourceKey::encoded)).map { (key, path) ->
            val entry = decode(path, key)
                ?: throw IllegalStateException("invalid durable exit-import entry")
            durableBytes += try {
                Files.size(path)
            } catch (failure: java.io.IOException) {
                throw IllegalStateException("cannot account exit-import entry", failure)
            }
            if (durableBytes > maxBytes) {
                throw IllegalStateException("exit-import journal exceeds its byte bound")
            }
            entry
        }
    }

    companion object {
        const val ENTRY_BYTES = 152
        internal const val LEGACY_ENTRY_BYTES = 68
        const val ENTRY_MAGIC = 0x54425849
        const val ENTRY_VERSION = 2
        const val LEGACY_ENTRY_VERSION = 1
        const val ENTRY_SUFFIX = ".tbexitjournal"
        const val TEMPORARY_SUFFIX = ".new"
    }
}

enum class ExitImportResult { IMPORTED, ALREADY_IMPORTED, DISABLED_EXHAUSTED }

enum class ExitImportTerminalization {
    COMPLETED,
    DISABLED_AND_RETIRED,
    RETRY_REQUIRED,
}

/**
 * Orders the exactly-once boundary shared by first import and startup recovery.
 *
 * The installation tombstone is forced before the generated record. A durable-record probe closes
 * the append-before-journal-update crash window without relying on record append deduplication.
 */
object ExitImportTerminalizer {
    fun terminalize(
        entry: ExitImportEntry,
        recordTombstone: (ExitSourceKey) -> ExitImportResult,
        containsRecord: (ExitSourceKey) -> Boolean,
        appendRecord: (ExitImportEntry) -> Boolean,
        markAppended: (ExitSourceKey) -> Boolean,
        complete: (ExitSourceKey) -> Boolean,
        retireRaw: (ExitRawArtifactProvenance) -> Boolean,
    ): ExitImportTerminalization {
        return when (recordTombstone(entry.sourceKey)) {
            ExitImportResult.DISABLED_EXHAUSTED -> {
                if (entry.rawArtifact != null && !retireRaw(entry.rawArtifact)) {
                    ExitImportTerminalization.RETRY_REQUIRED
                } else if (complete(entry.sourceKey)) {
                    ExitImportTerminalization.DISABLED_AND_RETIRED
                } else {
                    ExitImportTerminalization.RETRY_REQUIRED
                }
            }

            ExitImportResult.IMPORTED,
            ExitImportResult.ALREADY_IMPORTED,
            -> {
                if (entry.stage != ExitImportStage.APPENDED &&
                    !containsRecord(entry.sourceKey) &&
                    !appendRecord(entry)
                ) {
                    return ExitImportTerminalization.RETRY_REQUIRED
                }
                if (!markAppended(entry.sourceKey)) {
                    ExitImportTerminalization.RETRY_REQUIRED
                } else if (!complete(entry.sourceKey)) {
                    ExitImportTerminalization.RETRY_REQUIRED
                } else {
                    ExitImportTerminalization.COMPLETED
                }
            }
        }
    }
}

/**
 * Bounded installation-lifetime exact source tombstones. Exhaustion deliberately disables new
 * imports rather than evicting an entry and breaking idempotency.
 */
class ExitTombstoneLedger(
    storagePath: Path,
    private val maxEntries: Int,
    private val maxBytes: Int,
) {
    private val storagePath = storagePath.toAbsolutePath().normalize()
    private val keys = linkedSetOf<ExitSourceKey>()
    private var bytes = 0
    private var disabled = false

    init {
        require(maxEntries in 1..MAX_LEDGER_ENTRIES)
        require(maxBytes in 1..MAX_LEDGER_FILE_BYTES)
        recoverTemporary()
        load()
    }

    @Synchronized
    fun record(key: ExitSourceKey): ExitImportResult {
        if (key in keys) return ExitImportResult.ALREADY_IMPORTED
        if (disabled) return ExitImportResult.DISABLED_EXHAUSTED
        val nextBytes = bytes + key.encoded.toByteArray(StandardCharsets.US_ASCII).size
        if (keys.size >= maxEntries || nextBytes > maxBytes) {
            val previousDisabled = disabled
            disabled = true
            try {
                persist()
            } catch (failure: RuntimeException) {
                disabled = previousDisabled
                throw failure
            } catch (failure: java.io.IOException) {
                disabled = previousDisabled
                throw failure
            }
            return ExitImportResult.DISABLED_EXHAUSTED
        }
        keys += key
        val previousBytes = bytes
        bytes = nextBytes
        try {
            persist()
        } catch (failure: RuntimeException) {
            keys.remove(key)
            bytes = previousBytes
            throw failure
        } catch (failure: java.io.IOException) {
            keys.remove(key)
            bytes = previousBytes
            throw failure
        }
        return ExitImportResult.IMPORTED
    }

    @Synchronized
    fun imported(key: ExitSourceKey): Boolean = key in keys

    @Synchronized
    fun entryCount(): Int = keys.size

    @Synchronized
    fun usedBytes(): Int = bytes

    @Synchronized
    fun deleteAllOwned(): Boolean {
        return try {
            ensureSafeParent(storagePath)
            listOf(storagePath, temporaryPath()).forEach { path ->
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                    (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(path))
                ) {
                    throw IllegalStateException("unsafe exit tombstone file")
                }
            }
            val deletedTarget = Files.deleteIfExists(storagePath)
            val deletedTemporary = Files.deleteIfExists(temporaryPath())
            if (deletedTarget || deletedTemporary) forceDirectory(storagePath.parent)
            keys.clear()
            bytes = 0
            disabled = false
            true
        } catch (_: java.io.IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun load() {
        if (!Files.exists(storagePath, LinkOption.NOFOLLOW_LINKS)) return
        val loaded = decodeLedger(storagePath)
        disabled = loaded.disabled
        keys += loaded.keys
        bytes = loaded.bytes
    }

    private fun decodeLedger(path: Path): LoadedExitTombstones {
        ensureSafeParent(path)
        val encoded = readBoundedRegularFile(path, MAX_LEDGER_FILE_BYTES)
            ?: throw IllegalStateException("exit tombstone ledger is oversized or not a regular file")
        if (encoded.any { it.toInt() !in 0..0x7f }) {
            throw IllegalStateException("exit tombstone ledger is not ASCII")
        }
        val decoded = encoded.toString(StandardCharsets.US_ASCII)
        val split = decoded.split('\n')
        val lines = if (split.lastOrNull().isNullOrEmpty()) split.dropLast(1) else split
        if (lines.firstOrNull() !in setOf(LEDGER_ENABLED, LEDGER_DISABLED)) {
            throw IllegalStateException("invalid exit tombstone ledger")
        }
        val decodedKeys = linkedSetOf<ExitSourceKey>()
        var decodedBytes = 0
        lines.drop(1).forEach { sourceKey ->
            if (sourceKey.isBlank()) {
                throw IllegalStateException("invalid blank exit tombstone")
            }
            val key = decodeCanonicalSourceKey(sourceKey)
            if (!decodedKeys.add(key)) {
                throw IllegalStateException("duplicate exit tombstone identity")
            }
            decodedBytes += sourceKey.toByteArray(StandardCharsets.US_ASCII).size
            if (decodedKeys.size > maxEntries || decodedBytes > maxBytes) {
                throw IllegalStateException("exit tombstone ledger exceeds configured bounds")
            }
        }
        return LoadedExitTombstones(
            disabled = lines.first() == LEDGER_DISABLED,
            keys = decodedKeys,
            bytes = decodedBytes,
        )
    }

    private fun persist() {
        ensureSafeParent(storagePath)
        ensureSafeDirectory(storagePath.parent)
        if (Files.exists(storagePath, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(storagePath, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IllegalStateException("unsafe exit tombstone ledger")
        }
        val temporary = temporaryPath()
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("unexpected temporary exit tombstone ledger")
        }
        val contents = buildString {
            append(if (disabled) LEDGER_DISABLED else LEDGER_ENABLED).append('\n')
            keys.forEach { append(it.encoded).append('\n') }
        }.toByteArray(StandardCharsets.US_ASCII)
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val source = ByteBuffer.wrap(contents)
            while (source.hasRemaining()) {
                if (channel.write(source) <= 0) throw java.io.IOException("short exit tombstone write")
            }
            channel.force(true)
        }
        try {
            Files.move(temporary, storagePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, storagePath, StandardCopyOption.REPLACE_EXISTING)
        }
        forceDirectory(storagePath.parent)
    }

    private fun recoverTemporary() {
        ensureSafeParent(storagePath)
        val temporary = temporaryPath()
        if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) return
        decodeLedger(temporary)
        if (Files.exists(storagePath, LinkOption.NOFOLLOW_LINKS)) decodeLedger(storagePath)
        try {
            try {
                Files.move(
                    temporary,
                    storagePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, storagePath, StandardCopyOption.REPLACE_EXISTING)
            }
            forceDirectory(storagePath.parent)
        } catch (failure: java.io.IOException) {
            throw IllegalStateException("cannot recover temporary exit tombstone ledger", failure)
        }
    }

    private fun temporaryPath(): Path =
        storagePath.resolveSibling("${storagePath.fileName}.new")

    private data class LoadedExitTombstones(
        val disabled: Boolean,
        val keys: LinkedHashSet<ExitSourceKey>,
        val bytes: Int,
    )

    private companion object {
        const val LEDGER_ENABLED = "tracebox-exit-tombstones-v1|enabled"
        const val LEDGER_DISABLED = "tracebox-exit-tombstones-v1|disabled"
        const val MAX_LEDGER_ENTRIES = 1_024
        const val MAX_LEDGER_FILE_BYTES = 64 * 1_024
    }
}

private fun canonicalSourceKey(key: ExitSourceKey): String {
    val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(key.bytes())
    require(key.encoded == canonical) { "non-canonical exit source key" }
    return canonical
}

private fun decodeCanonicalSourceKey(encoded: String): ExitSourceKey {
    val key = try {
        ExitSourceKey(encoded).also { it.bytes() }
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("invalid exit source identity", failure)
    }
    if (canonicalSourceKey(key) != encoded) {
        throw IllegalStateException("non-canonical exit source identity")
    }
    return key
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

private fun ensureSafeParent(path: Path) {
    val parent = path.toAbsolutePath().normalize().parent
        ?: throw IllegalStateException("exit storage path has no parent")
    if (hasSymbolicLinkComponent(parent)) {
        throw IllegalStateException("symbolic-link exit storage parent is forbidden")
    }
    if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) &&
        !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
    ) {
        throw IllegalStateException("exit storage parent is not a directory")
    }
}

private fun ensureSafeDirectory(directory: Path) {
    val normalized = directory.toAbsolutePath().normalize()
    if (hasSymbolicLinkComponent(normalized)) {
        throw IllegalStateException("symbolic-link exit storage directory is forbidden")
    }
    Files.createDirectories(normalized)
    if (hasSymbolicLinkComponent(normalized) ||
        !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
    ) {
        throw IllegalStateException("unsafe exit storage directory")
    }
}

/**
 * A forced file is not a committed directory entry until its parent is forced as well.
 * Windows' default provider cannot open directory channels, so host tests tolerate that provider;
 * Android/Linux must provide the durability boundary.
 */
private fun forceDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (failure: java.io.IOException) {
        if (!(System.getProperty("os.name", "") ?: "").startsWith("Windows", ignoreCase = true)) {
            throw failure
        }
    } catch (failure: UnsupportedOperationException) {
        if (!(System.getProperty("os.name", "") ?: "").startsWith("Windows", ignoreCase = true)) {
            throw failure
        }
    }
}

/**
 * Reads at most [maximumBytes] from a non-symlink regular file.
 *
 * The fixed destination allocation is the memory bound. A one-byte probe rejects an oversized
 * file without ever allocating from its on-disk length.
 */
private fun readBoundedRegularFile(path: Path, maximumBytes: Int): ByteArray? {
    require(maximumBytes > 0)
    return try {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return null
        }
        FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val storage = ByteArray(maximumBytes)
            val destination = ByteBuffer.wrap(storage)
            while (destination.hasRemaining()) {
                val count = channel.read(destination)
                if (count < 0) break
            }
            val bytesRead = destination.position()
            val probe = ByteBuffer.allocate(1)
            if (channel.read(probe) >= 0) {
                null
            } else {
                storage.copyOf(bytesRead)
            }
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }
}

private fun readExactRegularFile(path: Path, exactBytes: Int): ByteArray? =
    readBoundedRegularFile(path, exactBytes)?.takeIf { it.size == exactBytes }

enum class ExitLinkConfidence { UNMATCHED, POSSIBLE, PROBABLE, EXACT }

data class LocalExitEvidence(
    val processName: String,
    val timestampMillis: Long,
    val reason: Int,
    val pid: Int,
    val processInstanceToken: ByteArray?,
)

object ExitLinker {
    fun link(exit: SyntheticApplicationExitInfo, local: LocalExitEvidence?): ExitLinkConfidence {
        if (local == null) return ExitLinkConfidence.UNMATCHED
        if (exit.processStateSummary != null && local.processInstanceToken != null &&
            exit.processStateSummary.contentEquals(local.processInstanceToken)
        ) return ExitLinkConfidence.EXACT
        val sameName = exit.processName == local.processName
        val closeTime = kotlin.math.abs(exit.timestampMillis - local.timestampMillis) <= 300_000
        if (sameName && closeTime && exit.reason == local.reason) return ExitLinkConfidence.PROBABLE
        if (sameName && exit.pid == local.pid) return ExitLinkConfidence.POSSIBLE
        return ExitLinkConfidence.UNMATCHED
    }
}
