package dev.tracebox.storage

import dev.tracebox.api.Crc32c
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

/** Bounded, root-relative input to an application's storage ownership classifier. */
data class OwnedStoragePath(
    val rootId: String,
    val relativePath: String,
    val fileName: String,
)

enum class OwnedStorageDomain {
    CREDENTIAL_PROTECTED,
    DEVICE_PROTECTED,
}

/**
 * One explicitly claimed Tracebox storage tree.
 *
 * The classifier must account for every regular file below this root. Unknown files make
 * reconciliation partial rather than silently escaping the UID-wide quota. Preserved paths are
 * only for non-diagnostic fail-closed policy controls (for example a Direct Boot deny mirror);
 * they remain classified and quota-accounted, but global deletion never removes them.
 */
class OwnedStorageRoot(
    val id: String,
    path: Path,
    val maxFiles: Int = 512,
    val maxDepth: Int = 16,
    val maxFileBytes: Long = 128L * 1024 * 1024,
    preservedRelativePaths: Set<String> = emptySet(),
    val domain: OwnedStorageDomain = OwnedStorageDomain.CREDENTIAL_PROTECTED,
    private val reservationSizer: (OwnedStoragePath, Long) -> Long = { _, physicalBytes ->
        physicalBytes
    },
    private val classifier: (OwnedStoragePath) -> UidBucket?,
) {
    internal val path: Path = safeStorageRoot(path)
    internal val preservedRelativePaths: Set<String> = preservedRelativePaths.map(::normalizeRelative).toSet()

    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}")))
        require(maxFiles in 1..MAX_FILES_PER_ROOT)
        require(maxDepth in 1..MAX_DEPTH)
        require(maxFileBytes in 1..MAX_FILE_BYTES)
        require(this.preservedRelativePaths.size <= MAX_PRESERVED_PATHS)
    }

    internal fun classify(relative: String): UidBucket? {
        val normalized = normalizeRelative(relative)
        return classifier(OwnedStoragePath(id, normalized, Path.of(normalized).fileName.toString()))
    }

    internal fun reservationBytes(relative: String, physicalBytes: Long): Long {
        val normalized = normalizeRelative(relative)
        return reservationSizer(
            OwnedStoragePath(id, normalized, Path.of(normalized).fileName.toString()),
            physicalBytes,
        )
    }

    internal fun relative(file: Path): String =
        normalizeRelative(path.relativize(file.toAbsolutePath().normalize()).joinToString("/") { it.toString() })

    private companion object {
        const val MAX_FILES_PER_ROOT = 4_096
        const val MAX_DEPTH = 32
        const val MAX_FILE_BYTES = 256L * 1024 * 1024
        const val MAX_PRESERVED_PATHS = 64
    }
}

/**
 * Explicit marker required before a tree can be reconciled or deleted.
 *
 * The marker prevents an accidentally broad caller path from becoming a deletion target.
 */
object TraceboxOwnedStorageRoot {
    const val OWNERSHIP_MARKER_FILE = ".tracebox-owned-root-v1"
    const val INELIGIBLE_MARKER_FILE = ".tracebox-delete-ineligible-v1"
    const val REACTIVATING_MARKER_FILE = ".tracebox-reactivating-v1"

    fun claim(path: Path): Path {
        val root = safeStorageRoot(path)
        requireNoSymbolicLinkComponent(root)
        Files.createDirectories(root)
        val marker = root.resolve(OWNERSHIP_MARKER_FILE)
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS))
            require(
                readBoundedOwnedFile(marker, OWNERSHIP_MARKER_BYTES.size)
                    ?.contentEquals(OWNERSHIP_MARKER_BYTES) == true,
            )
            return marker
        }
        writeAtomicOwnedFile(marker, OWNERSHIP_MARKER_BYTES)
        return marker
    }

    fun isClaimed(path: Path): Boolean {
        val root = runCatching { safeStorageRoot(path) }.getOrNull() ?: return false
        val marker = root.resolve(OWNERSHIP_MARKER_FILE)
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            readBoundedOwnedFile(marker, OWNERSHIP_MARKER_BYTES.size)
                ?.contentEquals(OWNERSHIP_MARKER_BYTES) == true
        }.getOrDefault(false)
    }

    fun isEligible(path: Path): Boolean {
        val root = runCatching { safeStorageRoot(path) }.getOrNull() ?: return false
        return isClaimed(root) &&
            !Files.exists(root.resolve(INELIGIBLE_MARKER_FILE), LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(root.resolve(REACTIVATING_MARKER_FILE), LinkOption.NOFOLLOW_LINKS)
    }

    internal fun isIneligible(path: Path): Boolean {
        val root = runCatching { safeStorageRoot(path) }.getOrNull() ?: return false
        return markerHasIneligibleBytes(root.resolve(INELIGIBLE_MARKER_FILE)) ||
            markerHasIneligibleBytes(root.resolve(REACTIVATING_MARKER_FILE))
    }

    internal fun isReactivating(path: Path): Boolean {
        val root = runCatching { safeStorageRoot(path) }.getOrNull() ?: return false
        return markerHasIneligibleBytes(root.resolve(REACTIVATING_MARKER_FILE))
    }

    internal fun hasIneligibleMarker(path: Path): Boolean {
        val root = runCatching { safeStorageRoot(path) }.getOrNull() ?: return false
        return markerHasIneligibleBytes(root.resolve(INELIGIBLE_MARKER_FILE))
    }

    internal fun hasAmbiguousIneligibleMarkers(path: Path): Boolean {
        val root = runCatching { safeStorageRoot(path) }.getOrNull() ?: return false
        return Files.exists(root.resolve(INELIGIBLE_MARKER_FILE), LinkOption.NOFOLLOW_LINKS) &&
            Files.exists(root.resolve(REACTIVATING_MARKER_FILE), LinkOption.NOFOLLOW_LINKS)
    }

    private fun markerHasIneligibleBytes(marker: Path): Boolean {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            readBoundedOwnedFile(marker, INELIGIBLE_MARKER_BYTES.size)
                ?.contentEquals(INELIGIBLE_MARKER_BYTES) == true
        }.getOrDefault(false)
    }

    internal fun markIneligible(path: Path) {
        val root = safeStorageRoot(path)
        check(isClaimed(root)) { "storage root is not Tracebox-owned" }
        writeAtomicOwnedFile(root.resolve(INELIGIBLE_MARKER_FILE), INELIGIBLE_MARKER_BYTES)
        Files.deleteIfExists(root.resolve(REACTIVATING_MARKER_FILE))
    }

    internal fun isInternalMarker(relative: String): Boolean =
        relative == OWNERSHIP_MARKER_FILE ||
            relative == INELIGIBLE_MARKER_FILE ||
            relative == REACTIVATING_MARKER_FILE

    private val OWNERSHIP_MARKER_BYTES = "tracebox-owned-root-v1\n".toByteArray(Charsets.US_ASCII)
    private val INELIGIBLE_MARKER_BYTES = "tracebox-delete-ineligible-v1\n".toByteArray(Charsets.US_ASCII)
}

enum class StorageOwnershipFailureReason {
    ROOT_NOT_CLAIMED,
    ROOT_OVERLAP,
    UNSAFE_SYMBOLIC_LINK,
    UNSUPPORTED_FILE_TYPE,
    UNCLASSIFIED_FILE,
    CLASSIFIER_FAILURE,
    ROOT_FILE_LIMIT,
    ROOT_DEPTH_LIMIT,
    FILE_SIZE_LIMIT,
    DUPLICATE_ACCOUNTING_KEY,
    QUOTA_REJECTED,
    CONCURRENT_FILE_CHANGE,
    INELIGIBLE_MARKER_CORRUPT,
    RESERVED_PATH_OCCUPIED,
    CATALOG_CORRUPT_REBUILT,
    CATALOG_CAPACITY,
    IO,
}

data class StorageOwnershipFailure(
    val rootId: String?,
    val relativePath: String?,
    val reason: StorageOwnershipFailureReason,
)

sealed interface StorageOwnershipReport {
    val scannedFiles: Int
    val failures: List<StorageOwnershipFailure>

    data class Complete(
        override val scannedFiles: Int,
        val releasedReservations: Int,
        val generation: Long,
        val bytesByBucket: Map<UidBucket, Long>,
    ) : StorageOwnershipReport {
        override val failures: List<StorageOwnershipFailure> = emptyList()
    }

    data class Partial(
        override val scannedFiles: Int,
        override val failures: List<StorageOwnershipFailure>,
    ) : StorageOwnershipReport
}

enum class ExternalOwnedStorageMutationFailureReason {
    ROOT_UNKNOWN,
    ROOT_NOT_DEVICE_PROTECTED,
    ROOT_NOT_EXTERNAL,
    ROOT_NOT_CLAIMED,
    ROOT_INELIGIBLE,
    INVALID_RELATIVE_PATH,
    RESERVED_PATH,
    CLASSIFIER_FAILURE,
    BUCKET_MISMATCH,
    FILE_SIZE_LIMIT,
    UNSAFE_SYMBOLIC_LINK,
    PATH_ALREADY_EXISTS,
    PATH_MISSING,
    PHYSICAL_SIZE_MISMATCH,
    PATH_STILL_EXISTS,
    NOT_RESERVED,
    QUOTA_REJECTED,
    LEDGER_UNAVAILABLE,
}

sealed interface ExternalOwnedStorageMutationResult {
    data object Applied : ExternalOwnedStorageMutationResult
    data class Rejected(
        val reason: ExternalOwnedStorageMutationFailureReason,
    ) : ExternalOwnedStorageMutationResult
}

enum class StorageRootReactivationFailureReason {
    ROOT_UNKNOWN,
    ROOT_NOT_CLAIMED,
    NOT_INELIGIBLE,
    AMBIGUOUS_MARKER,
    OWNED_FILES_REMAIN,
    ROOT_SCAN_LIMIT,
    UNSAFE_PATH,
    QUOTA_REJECTED,
    LEDGER_UNAVAILABLE,
    IO,
}

sealed interface StorageRootReactivationResult {
    data object Reactivated : StorageRootReactivationResult
    data class Rejected(
        val reason: StorageRootReactivationFailureReason,
        val relativePath: String? = null,
    ) : StorageRootReactivationResult
}

/**
 * Reconciles physical library files with one durable [UidWideQuotaCoordinator].
 *
 * Files below the accounting root use their physical path as the quota key. Handler/DE roots
 * outside it use a deterministic non-materialized shadow key, allowing one UID ledger to account
 * for all storage domains without treating those shadow keys as extra physical bytes. The full
 * scan, reservation repair, stale release, and catalog replacement run under the same UID-wide
 * storage-mutation barrier used by writers.
 */
class UidWideStorageReconciler(
    accountingRoot: Path,
    private val quota: UidWideQuotaCoordinator,
    roots: List<OwnedStorageRoot>,
    private val catalogSlotBytes: Int = DEFAULT_CATALOG_SLOT_BYTES,
    private val maxCatalogEntries: Int = DEFAULT_MAX_CATALOG_ENTRIES,
) {
    internal val accountingRoot = safeStorageRoot(accountingRoot)
    internal val roots: List<OwnedStorageRoot> = roots.toList()
    private val controlRoot = this.accountingRoot.resolve(CONTROL_DIRECTORY)
    private val externalOwnershipRoot = this.accountingRoot.resolve(EXTERNAL_OWNERSHIP_DIRECTORY)
    private val catalogA = controlRoot.resolve("ownership-a")
    private val catalogB = controlRoot.resolve("ownership-b")

    init {
        require(this.roots.isNotEmpty() && this.roots.size <= MAX_ROOTS)
        require(this.roots.map(OwnedStorageRoot::id).distinct().size == this.roots.size)
        require(catalogSlotBytes in MIN_CATALOG_SLOT_BYTES..MAX_CATALOG_SLOT_BYTES)
        require(maxCatalogEntries in 1..MAX_CATALOG_ENTRIES)
        for (left in this.roots.indices) {
            for (right in left + 1 until this.roots.size) {
                val first = this.roots[left].path
                val second = this.roots[right].path
                require(!first.startsWith(second) && !second.startsWith(first)) {
                    "owned storage roots must not overlap"
                }
            }
        }
    }

    fun reconcile(): StorageOwnershipReport =
        try {
            quota.withStorageMutation(::reconcileUnderMutationBarrier)
        } catch (_: StorageMutationBarrierException) {
            StorageOwnershipReport.Partial(
                0,
                listOf(StorageOwnershipFailure(null, null, StorageOwnershipFailureReason.IO)),
            )
        }

    private fun reconcileUnderMutationBarrier(): StorageOwnershipReport {
        val failures = mutableListOf<StorageOwnershipFailure>()
        val newReservations = mutableListOf<Path>()
        return try {
            prepareControlRoot()
            if (!ensureReservation(catalogA, UidBucket.METADATA, catalogSlotBytes.toLong()) ||
                !ensureReservation(catalogB, UidBucket.METADATA, catalogSlotBytes.toLong())
            ) {
                return StorageOwnershipReport.Partial(
                    0,
                    listOf(StorageOwnershipFailure(null, null, StorageOwnershipFailureReason.QUOTA_REJECTED)),
                )
            }

            val catalogLoad = loadCatalog()
            val discovered = discover(failures)
            if (failures.isNotEmpty()) return StorageOwnershipReport.Partial(discovered.size, failures)
            if (discovered.size > maxCatalogEntries) {
                return StorageOwnershipReport.Partial(
                    discovered.size,
                    listOf(StorageOwnershipFailure(null, null, StorageOwnershipFailureReason.CATALOG_CAPACITY)),
                )
            }

            for (file in discovered.values) {
                when (reserveOrAdopt(file.entry.accountingKey, file.entry.bucket, file.entry.bytes)) {
                    ReservationResult.EXISTING -> Unit
                    ReservationResult.NEW -> newReservations.add(file.entry.accountingKey)
                    ReservationResult.REJECTED -> {
                        failures += StorageOwnershipFailure(
                            file.rootId,
                            file.relativePath,
                            StorageOwnershipFailureReason.QUOTA_REJECTED,
                        )
                    }
                }
            }
            if (failures.isNotEmpty()) {
                rollbackNewReservations(newReservations)
                return StorageOwnershipReport.Partial(discovered.size, failures)
            }

            for (file in discovered.values) {
                val actual = runCatching {
                    if (Files.isRegularFile(file.physicalPath, LinkOption.NOFOLLOW_LINKS)) Files.size(file.physicalPath) else -1L
                }.getOrDefault(-1L)
                if (actual != file.physicalBytes) {
                    failures += StorageOwnershipFailure(
                        file.rootId,
                        file.relativePath,
                        StorageOwnershipFailureReason.CONCURRENT_FILE_CHANGE,
                    )
                }
            }
            if (failures.isNotEmpty()) {
                rollbackNewReservations(newReservations)
                return StorageOwnershipReport.Partial(discovered.size, failures)
            }

            val expectedKeys = discovered.keys.toMutableSet()
            roots.forEach { root ->
                expectedKeys.add(
                    accountingKey(
                        root,
                        root.path.resolve(TraceboxOwnedStorageRoot.OWNERSHIP_MARKER_FILE),
                    ),
                )
                if (TraceboxOwnedStorageRoot.isIneligible(root.path)) {
                    expectedKeys.add(
                        accountingKey(
                            root,
                            root.path.resolve(TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE),
                        ),
                    )
                }
            }
            var released = 0
            quota.allocations().keys
                .filter { isScopedOwnedAllocation(it) && it !in expectedKeys }
                .forEach { key -> if (quota.release(key)) released++ }

            val generation = (catalogLoad.latest?.generation ?: 0L) + 1
            persistCatalog(
                Catalog(
                    generation,
                    discovered.mapValues { it.value.entry },
                ),
                if (catalogLoad.latestPath == catalogA) catalogB else catalogA,
            )

            if (catalogLoad.hadCorruptWithoutValid) {
                StorageOwnershipReport.Partial(
                    discovered.size,
                    listOf(StorageOwnershipFailure(null, null, StorageOwnershipFailureReason.CATALOG_CORRUPT_REBUILT)),
                )
            } else {
                StorageOwnershipReport.Complete(
                    scannedFiles = discovered.size,
                    releasedReservations = released,
                    generation = generation,
                    bytesByBucket = discovered.values.groupBy { it.entry.bucket }
                        .mapValues { (_, files) -> files.sumOf { it.entry.bytes } },
                )
            }
        } catch (_: java.io.IOException) {
            rollbackNewReservations(newReservations)
            StorageOwnershipReport.Partial(
                0,
                listOf(StorageOwnershipFailure(null, null, StorageOwnershipFailureReason.IO)),
            )
        } catch (_: UncheckedIOException) {
            rollbackNewReservations(newReservations)
            StorageOwnershipReport.Partial(
                0,
                listOf(StorageOwnershipFailure(null, null, StorageOwnershipFailureReason.IO)),
            )
        } catch (_: UidWideQuotaCoordinator.UidQuotaLedgerException) {
            rollbackNewReservations(newReservations)
            StorageOwnershipReport.Partial(
                0,
                listOf(StorageOwnershipFailure(null, null, StorageOwnershipFailureReason.IO)),
            )
        } catch (_: CatalogCapacityException) {
            rollbackNewReservations(newReservations)
            StorageOwnershipReport.Partial(
                0,
                listOf(StorageOwnershipFailure(null, null, StorageOwnershipFailureReason.CATALOG_CAPACITY)),
            )
        } catch (_: ArithmeticException) {
            rollbackNewReservations(newReservations)
            StorageOwnershipReport.Partial(
                0,
                listOf(StorageOwnershipFailure(null, null, StorageOwnershipFailureReason.FILE_SIZE_LIMIT)),
            )
        }
    }

    /**
     * Reserves an exact externally owned file size before creation.
     *
     * Ordinary files require a device-protected root. The only credential-protected exception is
     * an exact, declared preserved metadata control; this accounts the API 23 policy-mirror
     * fallback without opening credential-protected ordinary data to this mutation surface.
     */
    fun reserveExternal(
        rootId: String,
        relativePath: String,
        bucket: UidBucket,
        bytes: Long,
    ): ExternalOwnedStorageMutationResult =
        mutateExternal(rootId, relativePath, bucket, bytes, ExternalMutation.RESERVE)

    /** Charges bytes before appending to an existing Direct Boot file. */
    fun growExternal(
        rootId: String,
        relativePath: String,
        bucket: UidBucket,
        additionalBytes: Long,
    ): ExternalOwnedStorageMutationResult =
        mutateExternal(rootId, relativePath, bucket, additionalBytes, ExternalMutation.GROW)

    /** Compensates the durable reservation after a completed, shortened, or failed write. */
    fun resizeExternal(
        rootId: String,
        relativePath: String,
        bucket: UidBucket,
        actualBytes: Long,
    ): ExternalOwnedStorageMutationResult =
        mutateExternal(rootId, relativePath, bucket, actualBytes, ExternalMutation.RESIZE)

    /** Releases ownership only after the Direct Boot path is physically absent. */
    fun releaseExternal(
        rootId: String,
        relativePath: String,
        bucket: UidBucket,
    ): ExternalOwnedStorageMutationResult =
        mutateExternal(rootId, relativePath, bucket, 0L, ExternalMutation.RELEASE)

    /**
     * Reactivates one root after `ALL_TRACEBOX_DATA` deletion and a later explicit profile enable.
     *
     * Only preserved fail-closed policy controls may remain. The marker rename keeps
     * [TraceboxOwnedStorageRoot.isEligible] false until the final removal, and the reservation is
     * released only after that removal succeeds.
     */
    fun reactivateRoot(rootId: String): StorageRootReactivationResult =
        try {
            quota.withStorageMutation { reactivateRootUnderMutationBarrier(rootId) }
        } catch (_: StorageMutationBarrierException) {
            reactivationRejected(StorageRootReactivationFailureReason.LEDGER_UNAVAILABLE)
        }

    private fun reactivateRootUnderMutationBarrier(rootId: String): StorageRootReactivationResult {
        val root = roots.singleOrNull { it.id == rootId }
            ?: return reactivationRejected(StorageRootReactivationFailureReason.ROOT_UNKNOWN)
        if (!TraceboxOwnedStorageRoot.isClaimed(root.path)) {
            return reactivationRejected(StorageRootReactivationFailureReason.ROOT_NOT_CLAIMED)
        }
        if (hasSymbolicLinkComponent(root.path)) {
            return reactivationRejected(StorageRootReactivationFailureReason.UNSAFE_PATH)
        }
        val ineligible = root.path.resolve(TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE)
        val reactivating = root.path.resolve(TraceboxOwnedStorageRoot.REACTIVATING_MARKER_FILE)
        val ineligibleExists = Files.exists(ineligible, LinkOption.NOFOLLOW_LINKS)
        val reactivatingExists = Files.exists(reactivating, LinkOption.NOFOLLOW_LINKS)
        if (ineligibleExists && reactivatingExists) {
            return reactivationRejected(StorageRootReactivationFailureReason.AMBIGUOUS_MARKER)
        }
        if (!TraceboxOwnedStorageRoot.isIneligible(root.path)) {
            return reactivationRejected(StorageRootReactivationFailureReason.NOT_INELIGIBLE)
        }
        scanReactivationBlocker(root)?.let { return it }

        val key = accountingKey(root, ineligible)
        return try {
            if (!quota.owns(key, UidBucket.METADATA, MARKER_RESERVATION_BYTES) &&
                !quota.reserve(key, UidBucket.METADATA, MARKER_RESERVATION_BYTES)
            ) {
                return reactivationRejected(StorageRootReactivationFailureReason.QUOTA_REJECTED)
            }
            if (ineligibleExists) {
                try {
                    Files.move(
                        ineligible,
                        reactivating,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(ineligible, reactivating, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            if (!TraceboxOwnedStorageRoot.isReactivating(root.path)) {
                return reactivationRejected(StorageRootReactivationFailureReason.UNSAFE_PATH)
            }
            Files.delete(reactivating)
            if (!quota.release(key)) {
                return reactivationRejected(StorageRootReactivationFailureReason.LEDGER_UNAVAILABLE)
            }
            StorageRootReactivationResult.Reactivated
        } catch (_: java.io.IOException) {
            reactivationRejected(StorageRootReactivationFailureReason.IO)
        } catch (_: UidWideQuotaCoordinator.UidQuotaLedgerException) {
            reactivationRejected(StorageRootReactivationFailureReason.LEDGER_UNAVAILABLE)
        }
    }

    internal fun accountingKey(root: OwnedStorageRoot, physicalPath: Path): Path {
        val physical = physicalPath.toAbsolutePath().normalize()
        require(physical.startsWith(root.path))
        if (physical.startsWith(accountingRoot)) return physical
        val relative = root.relative(physical)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${root.id}\u0000$relative".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return externalOwnershipRoot.resolve(digest)
    }

    internal fun ensureControlReservation(path: Path, bytes: Long): Boolean =
        ensureReservation(path.toAbsolutePath().normalize(), UidBucket.METADATA, bytes)

    internal fun prepareControlRoot() {
        if (hasSymbolicLinkComponent(accountingRoot)) {
            throw java.io.IOException("symbolic-link accounting root is forbidden")
        }
        Files.createDirectories(controlRoot)
        if (!Files.isDirectory(controlRoot, LinkOption.NOFOLLOW_LINKS) ||
            hasSymbolicLinkComponent(controlRoot)
        ) {
            throw java.io.IOException("unsafe ownership control root")
        }
    }

    internal fun releaseReservation(path: Path): Boolean =
        quota.release(path.toAbsolutePath().normalize())

    internal fun <T> withStorageMutationBarrier(block: () -> T): T =
        quota.withStorageMutation(block)

    internal fun controlPath(fileName: String): Path {
        require(fileName.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")))
        return controlRoot.resolve(fileName)
    }

    private fun mutateExternal(
        rootId: String,
        relativePath: String,
        bucket: UidBucket,
        bytes: Long,
        mutation: ExternalMutation,
    ): ExternalOwnedStorageMutationResult =
        try {
            quota.withStorageMutation {
                mutateExternalUnderMutationBarrier(rootId, relativePath, bucket, bytes, mutation)
            }
        } catch (_: StorageMutationBarrierException) {
            externalRejected(ExternalOwnedStorageMutationFailureReason.LEDGER_UNAVAILABLE)
        }

    private fun mutateExternalUnderMutationBarrier(
        rootId: String,
        relativePath: String,
        bucket: UidBucket,
        bytes: Long,
        mutation: ExternalMutation,
    ): ExternalOwnedStorageMutationResult {
        val root = roots.singleOrNull { it.id == rootId }
            ?: return externalRejected(ExternalOwnedStorageMutationFailureReason.ROOT_UNKNOWN)
        if (root.path.startsWith(accountingRoot)) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.ROOT_NOT_EXTERNAL)
        }
        if (!TraceboxOwnedStorageRoot.isClaimed(root.path)) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.ROOT_NOT_CLAIMED)
        }
        val normalized = try {
            normalizeRelative(relativePath)
        } catch (_: IllegalArgumentException) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.INVALID_RELATIVE_PATH)
        }
        val relativeDepth = Path.of(normalized).nameCount
        if (relativeDepth > root.maxDepth || TraceboxOwnedStorageRoot.isInternalMarker(normalized)) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.RESERVED_PATH)
        }
        val physical = root.path.resolve(normalized).normalize()
        if (!physical.startsWith(root.path) || physical == root.path) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.INVALID_RELATIVE_PATH)
        }
        if (hasSymbolicLinkComponent(root.path) || hasSymbolicLinkComponent(physical)) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.UNSAFE_SYMBOLIC_LINK)
        }
        val classifiedBucket = try {
            root.classify(normalized)
        } catch (_: RuntimeException) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.CLASSIFIER_FAILURE)
        }
        if (classifiedBucket != bucket) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.BUCKET_MISMATCH)
        }
        if (bytes < 0L || bytes > root.maxFileBytes) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.FILE_SIZE_LIMIT)
        }
        val preservedControl = root.preservedRelativePaths.any {
            normalized == it || normalized.startsWith("$it/")
        }
        val credentialProtectedControl =
            root.domain == OwnedStorageDomain.CREDENTIAL_PROTECTED &&
                normalized in root.preservedRelativePaths &&
                bucket == UidBucket.METADATA
        if (root.domain != OwnedStorageDomain.DEVICE_PROTECTED && !credentialProtectedControl) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.ROOT_NOT_DEVICE_PROTECTED)
        }
        if ((mutation == ExternalMutation.RESERVE || mutation == ExternalMutation.GROW) &&
            !preservedControl &&
            !TraceboxOwnedStorageRoot.isEligible(root.path)
        ) {
            return externalRejected(ExternalOwnedStorageMutationFailureReason.ROOT_INELIGIBLE)
        }
        val key = accountingKey(root, physical)
        return try {
            when (mutation) {
                ExternalMutation.RESERVE -> {
                    if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
                        externalRejected(ExternalOwnedStorageMutationFailureReason.PATH_ALREADY_EXISTS)
                    } else if (quota.reserve(key, bucket, bytes)) {
                        ExternalOwnedStorageMutationResult.Applied
                    } else {
                        externalRejected(ExternalOwnedStorageMutationFailureReason.QUOTA_REJECTED)
                    }
                }
                ExternalMutation.GROW -> {
                    if (!Files.isRegularFile(physical, LinkOption.NOFOLLOW_LINKS)) {
                        externalRejected(ExternalOwnedStorageMutationFailureReason.PATH_MISSING)
                    } else {
                        val current = quota.allocations()[key]
                        val finalBytes = try {
                            Math.addExact(current?.bytes ?: 0L, bytes)
                        } catch (_: ArithmeticException) {
                            return externalRejected(ExternalOwnedStorageMutationFailureReason.FILE_SIZE_LIMIT)
                        }
                        if (current == null) {
                            externalRejected(ExternalOwnedStorageMutationFailureReason.NOT_RESERVED)
                        } else if (current.bucket != bucket) {
                            externalRejected(ExternalOwnedStorageMutationFailureReason.BUCKET_MISMATCH)
                        } else if (finalBytes > root.maxFileBytes) {
                            externalRejected(ExternalOwnedStorageMutationFailureReason.FILE_SIZE_LIMIT)
                        } else if (Files.size(physical) != current.bytes) {
                            externalRejected(
                                ExternalOwnedStorageMutationFailureReason.PHYSICAL_SIZE_MISMATCH,
                            )
                        } else if (quota.grow(key, bucket, bytes)) {
                            ExternalOwnedStorageMutationResult.Applied
                        } else {
                            externalRejected(ExternalOwnedStorageMutationFailureReason.QUOTA_REJECTED)
                        }
                    }
                }
                ExternalMutation.RESIZE -> {
                    val physicalExists = Files.exists(physical, LinkOption.NOFOLLOW_LINKS)
                    val physicalMatches = physicalExists &&
                        Files.isRegularFile(physical, LinkOption.NOFOLLOW_LINKS) &&
                        Files.size(physical) == bytes
                    if (!physicalExists && bytes != 0L) {
                        externalRejected(ExternalOwnedStorageMutationFailureReason.PATH_MISSING)
                    } else if (physicalExists && !physicalMatches) {
                        externalRejected(ExternalOwnedStorageMutationFailureReason.PHYSICAL_SIZE_MISMATCH)
                    } else if (quota.resize(key, bucket, bytes)) {
                        ExternalOwnedStorageMutationResult.Applied
                    } else {
                        externalRejected(ExternalOwnedStorageMutationFailureReason.NOT_RESERVED)
                    }
                }
                ExternalMutation.RELEASE -> {
                    if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
                        externalRejected(ExternalOwnedStorageMutationFailureReason.PATH_STILL_EXISTS)
                    } else if (quota.release(key)) {
                        ExternalOwnedStorageMutationResult.Applied
                    } else {
                        externalRejected(ExternalOwnedStorageMutationFailureReason.NOT_RESERVED)
                    }
                }
            }
        } catch (_: java.io.IOException) {
            externalRejected(ExternalOwnedStorageMutationFailureReason.LEDGER_UNAVAILABLE)
        } catch (_: UidWideQuotaCoordinator.UidQuotaLedgerException) {
            externalRejected(ExternalOwnedStorageMutationFailureReason.LEDGER_UNAVAILABLE)
        }
    }

    private fun externalRejected(
        reason: ExternalOwnedStorageMutationFailureReason,
    ): ExternalOwnedStorageMutationResult.Rejected =
        ExternalOwnedStorageMutationResult.Rejected(reason)

    private fun scanReactivationBlocker(root: OwnedStorageRoot): StorageRootReactivationResult.Rejected? {
        return try {
            var visited = 0
            Files.walk(root.path, root.maxDepth + 1).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    val path = iterator.next()
                    if (path == root.path) continue
                    visited++
                    val relative = root.relative(path)
                    if (visited > root.maxFiles * 2 ||
                        root.path.relativize(path).nameCount > root.maxDepth
                    ) {
                        return reactivationRejected(
                            StorageRootReactivationFailureReason.ROOT_SCAN_LIMIT,
                            relative,
                        )
                    }
                    if (TraceboxOwnedStorageRoot.isInternalMarker(relative) ||
                        isInternalControlPath(root, relative) ||
                        root.preservedRelativePaths.any {
                            relative == it || relative.startsWith("$it/")
                        }
                    ) {
                        continue
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                    if (Files.isSymbolicLink(path) ||
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        return reactivationRejected(
                            StorageRootReactivationFailureReason.UNSAFE_PATH,
                            relative,
                        )
                    }
                    return reactivationRejected(
                        StorageRootReactivationFailureReason.OWNED_FILES_REMAIN,
                        relative,
                    )
                }
            }
            null
        } catch (_: java.io.IOException) {
            reactivationRejected(StorageRootReactivationFailureReason.IO)
        } catch (_: UncheckedIOException) {
            reactivationRejected(StorageRootReactivationFailureReason.IO)
        }
    }

    private fun reactivationRejected(
        reason: StorageRootReactivationFailureReason,
        relativePath: String? = null,
    ): StorageRootReactivationResult.Rejected =
        StorageRootReactivationResult.Rejected(reason, relativePath)

    private fun discover(failures: MutableList<StorageOwnershipFailure>): LinkedHashMap<Path, DiscoveredFile> {
        val discovered = linkedMapOf<Path, DiscoveredFile>()
        for (root in roots) {
            val failuresBeforeRoot = failures.size
            if (hasSymbolicLinkComponent(root.path)) {
                failures += StorageOwnershipFailure(
                    root.id,
                    null,
                    StorageOwnershipFailureReason.UNSAFE_SYMBOLIC_LINK,
                )
                continue
            }
            if (!TraceboxOwnedStorageRoot.isClaimed(root.path)) {
                failures += StorageOwnershipFailure(root.id, null, StorageOwnershipFailureReason.ROOT_NOT_CLAIMED)
                continue
            }
            ensureFixedMarker(root, TraceboxOwnedStorageRoot.OWNERSHIP_MARKER_FILE, MARKER_RESERVATION_BYTES, failures)
            val ineligible = root.path.resolve(TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE)
            val reactivating = root.path.resolve(TraceboxOwnedStorageRoot.REACTIVATING_MARKER_FILE)
            val ineligibleExists = Files.exists(ineligible, LinkOption.NOFOLLOW_LINKS)
            val reactivatingExists = Files.exists(reactivating, LinkOption.NOFOLLOW_LINKS)
            val markerValid = when {
                ineligibleExists && reactivatingExists -> false
                ineligibleExists -> TraceboxOwnedStorageRoot.hasIneligibleMarker(root.path)
                reactivatingExists -> TraceboxOwnedStorageRoot.isReactivating(root.path)
                else -> true
            }
            if (!markerValid) {
                failures += StorageOwnershipFailure(
                    root.id,
                    if (reactivatingExists) {
                        TraceboxOwnedStorageRoot.REACTIVATING_MARKER_FILE
                    } else {
                        TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE
                    },
                    StorageOwnershipFailureReason.INELIGIBLE_MARKER_CORRUPT,
                )
            } else if (ineligibleExists || reactivatingExists) {
                ensureFixedMarker(
                    root,
                    TraceboxOwnedStorageRoot.INELIGIBLE_MARKER_FILE,
                    MARKER_RESERVATION_BYTES,
                    failures,
                )
            } else {
                runCatching { quota.release(accountingKey(root, ineligible)) }
            }
            if (failures.size != failuresBeforeRoot) continue

            var files = 0
            var visited = 0
            Files.walk(root.path, root.maxDepth + 1).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    val path = iterator.next()
                    if (path == root.path) continue
                    visited++
                    val relative = root.relative(path)
                    if (visited > root.maxFiles * 2) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.ROOT_FILE_LIMIT,
                        )
                        break
                    }
                    val depth = root.path.relativize(path).nameCount
                    if (depth > root.maxDepth) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.ROOT_DEPTH_LIMIT,
                        )
                        break
                    }
                    if (root.path == accountingRoot &&
                        (relative == EXTERNAL_OWNERSHIP_DIRECTORY ||
                            relative.startsWith("$EXTERNAL_OWNERSHIP_DIRECTORY/"))
                    ) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.RESERVED_PATH_OCCUPIED,
                        )
                        continue
                    }
                    if (isInternalControlPath(root, relative)) continue
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                    if (Files.isSymbolicLink(path)) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.UNSAFE_SYMBOLIC_LINK,
                        )
                        continue
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.UNSUPPORTED_FILE_TYPE,
                        )
                        continue
                    }
                    files++
                    if (files > root.maxFiles) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.ROOT_FILE_LIMIT,
                        )
                        break
                    }
                    val bytes = Files.size(path)
                    if (bytes > root.maxFileBytes) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.FILE_SIZE_LIMIT,
                        )
                        continue
                    }
                    val bucket = try {
                        root.classify(relative)
                    } catch (_: RuntimeException) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.CLASSIFIER_FAILURE,
                        )
                        continue
                    }
                    if (bucket == null) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.UNCLASSIFIED_FILE,
                        )
                        continue
                    }
                    val reservationBytes = try {
                        root.reservationBytes(relative, bytes)
                    } catch (_: RuntimeException) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.CLASSIFIER_FAILURE,
                        )
                        continue
                    }
                    if (reservationBytes < bytes || reservationBytes > root.maxFileBytes) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.FILE_SIZE_LIMIT,
                        )
                        continue
                    }
                    val key = accountingKey(root, path)
                    if (discovered.put(
                            key,
                            DiscoveredFile(
                                root.id,
                                relative,
                                path,
                                bytes,
                                CatalogEntry(key, bucket, reservationBytes),
                            ),
                        ) != null
                    ) {
                        failures += StorageOwnershipFailure(
                            root.id,
                            relative,
                            StorageOwnershipFailureReason.DUPLICATE_ACCOUNTING_KEY,
                        )
                    }
                }
            }
        }
        return discovered
    }

    private fun ensureFixedMarker(
        root: OwnedStorageRoot,
        relative: String,
        bytes: Long,
        failures: MutableList<StorageOwnershipFailure>,
    ) {
        val path = root.path.resolve(relative)
        if (!ensureReservation(accountingKey(root, path), UidBucket.METADATA, bytes)) {
            failures += StorageOwnershipFailure(root.id, relative, StorageOwnershipFailureReason.QUOTA_REJECTED)
        }
    }

    private fun ensureReservation(path: Path, bucket: UidBucket, bytes: Long): Boolean =
        quota.resize(path, bucket, bytes) || quota.reserve(path, bucket, bytes)

    private fun reserveOrAdopt(path: Path, bucket: UidBucket, bytes: Long): ReservationResult {
        if (quota.resize(path, bucket, bytes)) return ReservationResult.EXISTING
        return if (quota.reserve(path, bucket, bytes)) ReservationResult.NEW else ReservationResult.REJECTED
    }

    private fun rollbackNewReservations(paths: List<Path>) {
        paths.asReversed().forEach { runCatching { quota.release(it) } }
    }

    private fun isInternalControlPath(root: OwnedStorageRoot, relative: String): Boolean {
        if (TraceboxOwnedStorageRoot.isInternalMarker(relative)) return true
        if (relative == UidWideStorageMutationBarrier.LOCK_FILE_NAME) return true
        if (root.path == accountingRoot &&
            (relative == CONTROL_DIRECTORY || relative.startsWith("$CONTROL_DIRECTORY/"))
        ) {
            return true
        }
        return root.path == accountingRoot && relative in QUOTA_COORDINATOR_FILES
    }

    private fun isScopedOwnedAllocation(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized.startsWith(controlRoot)) return false
        if (normalized.startsWith(externalOwnershipRoot)) return true
        return roots.any { root ->
            root.path.startsWith(accountingRoot) && normalized.startsWith(root.path)
        }
    }

    private fun loadCatalog(): CatalogLoad {
        val existsA = Files.exists(catalogA, LinkOption.NOFOLLOW_LINKS)
        val existsB = Files.exists(catalogB, LinkOption.NOFOLLOW_LINKS)
        val first = readCatalog(catalogA)
        val second = readCatalog(catalogB)
        val latest = listOfNotNull(first?.let { catalogA to it }, second?.let { catalogB to it })
            .maxByOrNull { it.second.generation }
        return CatalogLoad(
            latestPath = latest?.first,
            latest = latest?.second,
            hadCorruptWithoutValid = latest == null && (existsA || existsB),
        )
    }

    private fun readCatalog(path: Path): Catalog? {
        val bytes = readBoundedOwnedFile(path, catalogSlotBytes) ?: return null
        if (bytes.size < Int.SIZE_BYTES) return null
        val contentSize = bytes.size - Int.SIZE_BYTES
        val expected = ByteBuffer.wrap(bytes, contentSize, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
        if (Crc32c.value(bytes, 0, contentSize) != expected) return null
        val lines = bytes.copyOf(contentSize).toString(Charsets.US_ASCII).lines()
        if (lines.size < 2 || lines[0] != CATALOG_MAGIC) return null
        val header = lines[1].split('|')
        if (header.size != 2) return null
        val generation = header[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val count = header[1].toIntOrNull()?.takeIf { it in 0..maxCatalogEntries } ?: return null
        val entries = linkedMapOf<Path, CatalogEntry>()
        for (line in lines.drop(2).filter(String::isNotBlank)) {
            val fields = line.split('|')
            if (fields.size != 3) return null
            val bucket = runCatching { UidBucket.valueOf(fields[0]) }.getOrNull() ?: return null
            val bytesCount = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val relative = runCatching {
                Base64.getUrlDecoder().decode(fields[2]).toString(Charsets.UTF_8)
            }.getOrNull() ?: return null
            val key = accountingRoot.resolve(relative).normalize()
            if (Path.of(relative).isAbsolute || !key.startsWith(accountingRoot)) return null
            if (entries.put(key, CatalogEntry(key, bucket, bytesCount)) != null) return null
        }
        if (entries.size != count) return null
        return Catalog(generation, entries)
    }

    private fun persistCatalog(catalog: Catalog, path: Path) {
        val content = buildString {
            append(CATALOG_MAGIC).append('\n')
            append(catalog.generation).append('|').append(catalog.entries.size).append('\n')
            catalog.entries.toSortedMap(compareBy(Path::toString)).forEach { (key, entry) ->
                val relative = accountingRoot.relativize(key).joinToString("/") { it.toString() }
                append(entry.bucket.name).append('|').append(entry.bytes).append('|')
                append(Base64.getUrlEncoder().withoutPadding().encodeToString(relative.toByteArray(Charsets.UTF_8)))
                    .append('\n')
            }
        }.toByteArray(Charsets.US_ASCII)
        if (content.size + Int.SIZE_BYTES > catalogSlotBytes) {
            throw CatalogCapacityException
        }
        val encoded = ByteBuffer.allocate(content.size + Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .put(content)
            .putInt(Crc32c.value(content))
            .array()
        if (Files.isSymbolicLink(path)) {
            throw java.io.IOException("symbolic-link ownership catalog is forbidden")
        }
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use {
            writeFully(it, ByteBuffer.wrap(encoded))
            it.force(true)
        }
        if (readCatalog(path) != catalog) {
            throw java.io.IOException("ownership catalog verification failed")
        }
    }

    private data class CatalogEntry(val accountingKey: Path, val bucket: UidBucket, val bytes: Long)
    private data class Catalog(val generation: Long, val entries: Map<Path, CatalogEntry>)
    private data class CatalogLoad(val latestPath: Path?, val latest: Catalog?, val hadCorruptWithoutValid: Boolean)
    private data class DiscoveredFile(
        val rootId: String,
        val relativePath: String,
        val physicalPath: Path,
        val physicalBytes: Long,
        val entry: CatalogEntry,
    )
    private enum class ReservationResult { EXISTING, NEW, REJECTED }
    private enum class ExternalMutation { RESERVE, GROW, RESIZE, RELEASE }

    private data object CatalogCapacityException : IllegalStateException()

    private companion object {
        const val CONTROL_DIRECTORY = ".tracebox-control"
        const val EXTERNAL_OWNERSHIP_DIRECTORY = ".tracebox-external-ownership"
        const val CATALOG_MAGIC = "tracebox-ownership-catalog-v1"
        const val DEFAULT_CATALOG_SLOT_BYTES = 64 * 1024
        const val MIN_CATALOG_SLOT_BYTES = 1_024
        const val MAX_CATALOG_SLOT_BYTES = 128 * 1024
        const val DEFAULT_MAX_CATALOG_ENTRIES = 1_024
        const val MAX_CATALOG_ENTRIES = 2_048
        const val MAX_ROOTS = 8
        const val MARKER_RESERVATION_BYTES = 64L
        val QUOTA_COORDINATOR_FILES = setOf(
            ".tracebox-uid-quota.lock",
            UidWideStorageMutationBarrier.LOCK_FILE_NAME,
            "tracebox-uid-quota-v1",
            "tracebox-uid-quota-v1.new",
        )
    }
}

internal fun safeStorageRoot(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    require(normalized.parent != null && normalized.nameCount >= 2) {
        "storage root is too broad"
    }
    require(normalized != normalized.root)
    return normalized
}

private fun normalizeRelative(value: String): String {
    val normalized = value.replace('\\', '/')
    require(normalized.isNotBlank() && !normalized.startsWith('/'))
    require(normalized.split('/').none { it == ".." || it == "." })
    val parsed = Path.of(normalized).normalize()
    require(!parsed.isAbsolute && parsed.toString().isNotBlank() && parsed.none { it.toString() == ".." })
    return parsed.joinToString("/") { it.toString() }
}

private fun requireNoSymbolicLinkComponent(path: Path) {
    var cursor = path.root
    for (part in path) {
        cursor = cursor.resolve(part)
        if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(cursor)) { "symbolic-link storage root is forbidden" }
        }
    }
}

internal fun hasSymbolicLinkComponent(path: Path): Boolean {
    var cursor = path.root
    for (part in path) {
        cursor = cursor.resolve(part)
        if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
            return true
        }
    }
    return false
}

private fun writeAtomicOwnedFile(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent)
    val temporary = path.resolveSibling("${path.fileName}.new")
    if (Files.isSymbolicLink(path) || Files.isSymbolicLink(temporary)) {
        throw java.io.IOException("symbolic-link control file is forbidden")
    }
    FileChannel.open(
        temporary,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
        LinkOption.NOFOLLOW_LINKS,
    ).use {
        writeFully(it, ByteBuffer.wrap(bytes))
        it.force(true)
    }
    try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun readBoundedOwnedFile(path: Path, maxBytes: Int): ByteArray? {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
    FileChannel.open(
        path,
        StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS,
    ).use { channel ->
        val size = channel.size()
        if (size < 0L || size > maxBytes.toLong()) return null
        val buffer = ByteBuffer.allocate(size.toInt())
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0) return null
        }
        if (channel.size() != size) return null
        return buffer.array()
    }
}

private fun writeFully(channel: FileChannel, bytes: ByteBuffer) {
    while (bytes.hasRemaining()) channel.write(bytes)
}
