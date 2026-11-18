package com.panomc.plugins.pano.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `pause-when-empty-seconds` read the way the game keeps it: a private `emptyTicks` counter on
 * `MinecraftServer` (here the superclass, as on a dedicated server) against
 * `pauseWhenEmptySeconds() * 20`.
 */
class PauseWhenEmptyTest {
    @Suppress("unused")
    open class FakeMinecraftServer(private var emptyTicks: Int, private val seconds: Int) {
        // Protected here, as on MinecraftServer in 26.3.
        protected fun pauseWhenEmptySeconds(): Int = seconds
    }

    class FakeDedicatedServer(emptyTicks: Int, seconds: Int) : FakeMinecraftServer(emptyTicks, seconds)

    @Test
    fun `paused once the empty ticks reach the limit`() {
        assertEquals(true, PauseWhenEmpty.isPaused(FakeDedicatedServer(emptyTicks = 1200, seconds = 60)))
        assertEquals(true, PauseWhenEmpty.isPaused(FakeDedicatedServer(emptyTicks = 5000, seconds = 60)))
    }

    @Test
    fun `not paused before the limit or with the setting off`() {
        assertEquals(false, PauseWhenEmpty.isPaused(FakeDedicatedServer(emptyTicks = 1199, seconds = 60)))
        assertEquals(false, PauseWhenEmpty.isPaused(FakeDedicatedServer(emptyTicks = 0, seconds = 60)))
        assertEquals(false, PauseWhenEmpty.isPaused(FakeDedicatedServer(emptyTicks = 9999, seconds = 0)))
    }
}
