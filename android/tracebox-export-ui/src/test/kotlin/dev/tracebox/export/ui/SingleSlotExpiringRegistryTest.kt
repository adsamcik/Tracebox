package dev.tracebox.export.ui

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SingleSlotExpiringRegistryTest {
    @Test
    fun repeated_puts_keep_one_value_and_retire_every_replacement() {
        var nextHandle = 0
        val values = mutableListOf<ByteArray>()
        val registry = SingleSlotExpiringRegistry<ByteArray>(
            ttlMillis = 600_000,
            nowMillis = { 1L },
            retire = { it.fill(0) },
            newHandle = { "handle-${nextHandle++}" },
        )

        repeat(64) { index ->
            values += byteArrayOf((index + 1).toByte())
            registry.put(values.last())
            assertEquals(1, registry.activeCount())
        }

        values.dropLast(1).forEach { assertContentEquals(byteArrayOf(0), it) }
        assertContentEquals(byteArrayOf(64), values.last())
        assertNull(registry.find("handle-0"))
        assertSame(values.last(), registry.find("handle-63"))

        registry.clear()
        assertEquals(0, registry.activeCount())
        assertContentEquals(byteArrayOf(0), values.last())
    }

    @Test
    fun expiry_is_inclusive_and_retires_owned_value_exactly_once() {
        var now = 100L
        val retirements = AtomicInteger()
        val bytes = byteArrayOf(7)
        val registry = SingleSlotExpiringRegistry<ByteArray>(
            ttlMillis = 600_000,
            nowMillis = { now },
            retire = {
                it.fill(0)
                retirements.incrementAndGet()
            },
            newHandle = { "pending" },
        )

        registry.put(bytes)
        now += 599_999
        assertSame(bytes, registry.find("pending"))
        now += 1
        assertNull(registry.find("pending"))
        assertNull(registry.find("pending"))

        assertEquals(0, registry.activeCount())
        assertEquals(1, retirements.get())
        assertContentEquals(byteArrayOf(0), bytes)
    }

    @Test
    fun take_transfers_live_value_without_retiring_it() {
        var retirements = 0
        val bytes = byteArrayOf(9)
        val registry = SingleSlotExpiringRegistry<ByteArray>(
            ttlMillis = 600_000,
            nowMillis = { 0L },
            retire = {
                it.fill(0)
                retirements += 1
            },
            newHandle = { "approved" },
        )

        registry.put(bytes)
        assertNull(registry.take("wrong"))
        assertSame(bytes, registry.take("approved"))
        assertNull(registry.take("approved"))
        assertEquals(0, registry.activeCount())
        assertEquals(0, retirements)
        assertContentEquals(byteArrayOf(9), bytes)
    }

    @Test
    fun remove_ignores_an_old_handle_and_retires_the_current_handle() {
        val bytes = byteArrayOf(11)
        val registry = SingleSlotExpiringRegistry<ByteArray>(
            ttlMillis = 600_000,
            nowMillis = { 0L },
            retire = { it.fill(0) },
            newHandle = { "current" },
        )

        registry.put(bytes)
        registry.remove("old")
        assertSame(bytes, registry.find("current"))

        registry.remove("current")
        assertEquals(0, registry.activeCount())
        assertContentEquals(byteArrayOf(0), bytes)
    }
}
