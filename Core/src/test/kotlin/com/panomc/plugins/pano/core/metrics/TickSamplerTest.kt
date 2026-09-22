package com.panomc.plugins.pano.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TickSamplerTest {
    private val base = 1_700_000_000_000L

    @Test
    fun `reports nothing before a whole second has elapsed`() {
        val sampler = TickSampler()

        assertNull(sampler.getTps(base))

        repeat(20) { sampler.onTick(base + it * 50L) }

        assertNull(sampler.getTps(base + 999L))
    }

    @Test
    fun `reports twenty tps for a healthy server`() {
        val sampler = TickSampler()

        tick(sampler, seconds = 120, ticksPerSecond = 20)

        val tps = sampler.getTps(base + 120_000L)!!

        assertEquals(listOf(20.0, 20.0, 20.0), tps.toList())
    }

    @Test
    fun `clamps a fast server to twenty`() {
        val sampler = TickSampler()

        tick(sampler, seconds = 70, ticksPerSecond = 25)

        assertTrue(sampler.getTps(base + 70_000L)!!.all { it == 20.0 })
    }

    @Test
    fun `averages a lagging minute against the healthier five minute window`() {
        val sampler = TickSampler()

        tick(sampler, seconds = 300, ticksPerSecond = 20)
        tick(sampler, seconds = 60, ticksPerSecond = 10, startSecond = 300)

        val tps = sampler.getTps(base + 360_000L)!!

        assertEquals(10.0, tps[0])
        assertTrue(tps[1] in 18.0..19.0, "five-minute average was ${tps[1]}")
        assertTrue(tps[2] > tps[1], "fifteen-minute average should still be the healthiest")
    }

    @Test
    fun `counts seconds without a tick as zero`() {
        val sampler = TickSampler()

        tick(sampler, seconds = 60, ticksPerSecond = 20)

        // The server froze: no onTick() at all for the next 30 seconds.
        val tps = sampler.getTps(base + 90_000L)!!

        assertEquals(10.0, tps[0], "half of the last minute had no ticks at all")
    }

    @Test
    fun `survives a gap longer than the whole window`() {
        val sampler = TickSampler()

        tick(sampler, seconds = 60, ticksPerSecond = 20)

        val hourLater = base + 3_600_000L

        tick(sampler, seconds = 60, ticksPerSecond = 20, startSecond = 3_600)

        assertEquals(20.0, sampler.getTps(hourLater + 60_000L)!![0])
    }

    private fun tick(sampler: TickSampler, seconds: Int, ticksPerSecond: Int, startSecond: Int = 0) {
        for (second in startSecond until startSecond + seconds) {
            val step = 1000L / ticksPerSecond

            repeat(ticksPerSecond) { index ->
                sampler.onTick(base + second * 1000L + index * step)
            }
        }
    }
}
