package dev.tracebox

import dev.tracebox.api.PackageDisclosure
import dev.tracebox.api.PackagePreview
import dev.tracebox.api.PackagePrivacyClass
import dev.tracebox.api.PackageWarning
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimePackageRegistryTest {
    @Test
    fun repeatedPrepareKeepsOneSlotAndZeroesEveryReplacement() {
        val now = 1L
        val retired = mutableListOf<ByteArray>()
        val registry = BoundedRuntimePackageRegistry(
            clockMillis = { now },
            nonceFactory = { ByteArray(32) { 1 } },
            retiredObserver = { retired += it.copyOf() },
        )

        repeat(64) { index ->
            registry.put(preview(index), byteArrayOf((index + 1).toByte()))
            assertEquals(1 to 0, registry.activeSlotCounts())
        }

        assertEquals(63, retired.size)
        assertTrue(retired.all { bytes -> bytes.all { it == 0.toByte() } })
        assertNull(registry.preview(digest(0)))
        assertNotNull(registry.preview(digest(63)))
    }

    @Test
    fun repeatedApprovalKeepsOneApprovedSlotAndConsumeIsOneTime() {
        var nonceValue = 0
        val retired = mutableListOf<ByteArray>()
        val registry = BoundedRuntimePackageRegistry(
            clockMillis = { 10L },
            nonceFactory = {
                ByteArray(32) { (++nonceValue).toByte() }
            },
            retiredObserver = { retired += it.copyOf() },
        )

        registry.put(preview(1), byteArrayOf(11))
        val firstNonce = assertNotNull(registry.approve(digest(1)))
        assertEquals(0 to 1, registry.activeSlotCounts())

        registry.put(preview(2), byteArrayOf(22))
        val secondNonce = assertNotNull(registry.approve(digest(2)))
        assertEquals(0 to 1, registry.activeSlotCounts())
        assertNull(registry.take(firstNonce))
        assertContentEquals(byteArrayOf(22), registry.take(secondNonce))
        assertNull(registry.take(secondNonce))
        assertEquals(0 to 0, registry.activeSlotCounts())
        assertEquals(2, retired.size)
        assertTrue(retired.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun expiryAndClearRetireAllOwnedBytesWithoutGrowingState() {
        var now = 100L
        var nonce = 1
        val retired = mutableListOf<ByteArray>()
        val registry = BoundedRuntimePackageRegistry(
            ttlMillis = 5,
            clockMillis = { now },
            nonceFactory = { ByteArray(32) { nonce.toByte() }.also { nonce++ } },
            retiredObserver = { retired += it.copyOf() },
        )

        registry.put(preview(3), byteArrayOf(33))
        now = 105L
        assertEquals(0 to 0, registry.activeSlotCounts())

        registry.put(preview(4), byteArrayOf(44))
        assertNotNull(registry.approve(digest(4)))
        registry.clear()
        assertEquals(0 to 0, registry.activeSlotCounts())
        assertEquals(2, retired.size)
        assertTrue(retired.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun policyChangesInvalidateBothPreparedAndApprovedCapabilities() {
        val registry = BoundedRuntimePackageRegistry(
            clockMillis = { 10L },
            nonceFactory = { ByteArray(32) { 7 } },
        )

        registry.put(preview(5), byteArrayOf(55))
        invalidatePackageCapabilitiesForPolicyChange(registry::clear)
        assertNull(registry.preview(digest(5)))
        assertEquals(0 to 0, registry.activeSlotCounts())

        registry.put(preview(6), byteArrayOf(66))
        val approved = assertNotNull(registry.approve(digest(6)))
        invalidatePackageCapabilitiesForPolicyChange(registry::clear)
        assertNull(registry.take(approved))
        assertEquals(0 to 0, registry.activeSlotCounts())
    }

    @Test
    fun createdPackageCapabilitiesCannotRunAfterTheirGenerationIsInvalidated() {
        val fence = RuntimePackageCapabilityFence()
        var clearCalls = 0
        val created = assertNotNull(fence.bind { byteArrayOf(1, 2, 3) })

        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fence.use(created.generation) { created.value.copyOf() },
        )
        fence.invalidate { clearCalls++ }

        assertEquals(1, clearCalls)
        assertNull(fence.use(created.generation) { created.value.copyOf() })
        val replacement = assertNotNull(fence.bind { byteArrayOf(4) })
        assertTrue(replacement.generation != created.generation)
        assertContentEquals(
            byteArrayOf(4),
            fence.use(replacement.generation) { replacement.value.copyOf() },
        )
    }

    @Test
    fun concurrentPreparationAndCloseCannotRepopulateRegistryForALaterInstall() {
        val registry = BoundedRuntimePackageRegistry(
            clockMillis = { 10L },
            nonceFactory = { ByteArray(32) { 9 } },
        )
        val fence = RuntimePackageCapabilityFence()
        val closed = AtomicBoolean(false)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val publicationWaitCompleted = AtomicBoolean(false)
        val published = AtomicBoolean(false)

        val publisher = thread(name = "tracebox-test-package-publisher") {
            published.set(
                fence.publishIf(
                    isAllowed = { !closed.get() },
                    publish = {
                        publicationEntered.countDown()
                        publicationWaitCompleted.set(
                            releasePublication.await(5, TimeUnit.SECONDS),
                        )
                        registry.put(preview(7), byteArrayOf(77))
                    },
                ),
            )
        }
        assertTrue(publicationEntered.await(5, TimeUnit.SECONDS))

        val closer = thread(name = "tracebox-test-package-close") {
            closed.set(true)
            closeStarted.countDown()
            fence.invalidate(registry::clear)
            closeFinished.countDown()
        }
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        releasePublication.countDown()
        assertTrue(closeFinished.await(5, TimeUnit.SECONDS))
        publisher.join(5_000)
        closer.join(5_000)

        assertFalse(publisher.isAlive)
        assertFalse(closer.isAlive)
        assertTrue(publicationWaitCompleted.get())
        assertTrue(published.get())
        assertEquals(0 to 0, registry.activeSlotCounts())
        assertNull(registry.preview(digest(7)))
        assertNull(registry.approve(digest(7)))

        val laterPublicationRan = AtomicBoolean(false)
        val laterInstallCouldPublish = fence.publishIf(
            isAllowed = { !closed.get() },
            publish = {
                laterPublicationRan.set(true)
                registry.put(preview(8), byteArrayOf(88))
            },
        )
        assertFalse(laterInstallCouldPublish)
        assertFalse(laterPublicationRan.get())
        assertEquals(0 to 0, registry.activeSlotCounts())
    }

    private fun preview(seed: Int): PackagePreview =
        PackagePreview(
            PackageDisclosure(
                includedValueCount = 1,
                includedBytes = 1,
                privacyClasses = setOf(PackagePrivacyClass.C0),
                transformations = emptySet(),
                omissionReasons = emptySet(),
                sourceTimeRangeMillis = null,
                sourceProcessCount = 1,
                plaintextDigestSha256 = digest(seed),
                rawArtifactCount = 0,
                warnings = setOf(PackageWarning.RAW_CRASH_ARTIFACTS_EXCLUDED),
            ),
        )

    private fun digest(seed: Int): ByteArray = ByteArray(32) { (seed + it).toByte() }
}
