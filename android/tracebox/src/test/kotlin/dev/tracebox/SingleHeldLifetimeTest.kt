package dev.tracebox

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleHeldLifetimeTest {
    @Test
    fun successful_binding_is_held_and_released_exactly_once() {
        val calls = mutableListOf<String>()
        val lifetime = lifetime(calls)

        assertTrue(lifetime.startAndHold("first"))
        assertTrue(lifetime.isHeld())
        assertFalse(lifetime.startAndHold("second"))
        assertTrue(lifetime.release())
        assertFalse(lifetime.isHeld())
        assertTrue(lifetime.release())
        assertEquals(listOf("start:first", "bind:first", "unbind:first"), calls)
    }

    @Test
    fun failed_bind_stops_the_started_service_and_allows_retry() {
        val calls = mutableListOf<String>()
        var acceptBind = false
        val lifetime = SingleHeldLifetime<String>(
            start = { calls += "start:$it"; true },
            bind = { calls += "bind:$it"; acceptBind },
            stop = { calls += "stop:$it" },
            unbind = { calls += "unbind:$it" },
        )

        assertFalse(lifetime.startAndHold("first"))
        assertFalse(lifetime.isHeld())
        acceptBind = true
        assertTrue(lifetime.startAndHold("second"))
        assertEquals(
            listOf("start:first", "bind:first", "stop:first", "start:second", "bind:second"),
            calls,
        )
    }

    @Test
    fun throwing_bind_still_compensates_and_clears_ownership() {
        val calls = mutableListOf<String>()
        val lifetime = SingleHeldLifetime<String>(
            start = { calls += "start:$it"; true },
            bind = { calls += "bind:$it"; throw SecurityException("denied") },
            stop = { calls += "stop:$it" },
            unbind = { calls += "unbind:$it" },
        )

        assertFailsWith<SecurityException> { lifetime.startAndHold("first") }
        assertFalse(lifetime.isHeld())
        assertEquals(listOf("start:first", "bind:first", "stop:first"), calls)
    }

    @Test
    fun failed_start_never_binds_or_stops() {
        val calls = mutableListOf<String>()
        val lifetime = SingleHeldLifetime<String>(
            start = { calls += "start:$it"; false },
            bind = { calls += "bind:$it"; true },
            stop = { calls += "stop:$it" },
            unbind = { calls += "unbind:$it" },
        )

        assertFalse(lifetime.startAndHold("first"))
        assertFalse(lifetime.isHeld())
        assertEquals(listOf("start:first"), calls)
    }

    @Test
    fun release_serializes_with_an_inflight_start_and_cannot_lose_the_binding() {
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val releaseAttempted = CountDownLatch(1)
        val acquired = AtomicBoolean()
        val released = AtomicBoolean()
        val calls = mutableListOf<String>()
        val lifetime = SingleHeldLifetime<String>(
            start = {
                calls += "start:$it"
                startEntered.countDown()
                check(allowStart.await(2, TimeUnit.SECONDS))
                true
            },
            bind = { calls += "bind:$it"; true },
            stop = { calls += "stop:$it" },
            unbind = { calls += "unbind:$it" },
        )
        val starter = Thread {
            acquired.set(lifetime.startAndHold("first"))
        }
        val releaser = Thread {
            releaseAttempted.countDown()
            released.set(lifetime.release())
        }

        starter.start()
        assertTrue(startEntered.await(2, TimeUnit.SECONDS))
        releaser.start()
        assertTrue(releaseAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(released.get())
        allowStart.countDown()
        starter.join(2_000)
        releaser.join(2_000)

        assertTrue(acquired.get())
        assertTrue(released.get())
        assertFalse(lifetime.isHeld())
        assertEquals(listOf("start:first", "bind:first", "unbind:first"), calls)
    }

    private fun lifetime(calls: MutableList<String>) = SingleHeldLifetime<String>(
        start = { calls += "start:$it"; true },
        bind = { calls += "bind:$it"; true },
        stop = { calls += "stop:$it" },
        unbind = { calls += "unbind:$it" },
    )
}
