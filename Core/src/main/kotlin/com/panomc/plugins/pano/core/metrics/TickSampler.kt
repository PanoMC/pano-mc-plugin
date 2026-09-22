package com.panomc.plugins.pano.core.metrics

/**
 * Counts server ticks per wall-clock second so a 1/5/15-minute TPS average can be read back at
 * any time.
 *
 * Used as the fallback on servers that do not publish TPS themselves (a plain Spigot/CraftBukkit
 * build without Paper's `Server.getTPS()` and without a readable `MinecraftServer.recentTps`) and
 * as the only source on Fabric, where the tick hook is a fabric-api event.
 *
 * [onTick] runs on the server's main thread once per tick, so it does the cheapest thing that
 * works: increment one slot of a fixed ring of per-second counters. The ring covers
 * [WINDOW_SECONDS] seconds - fifteen minutes - and never allocates.
 *
 * Both entry points advance the ring against the wall clock first, so a server that has stopped
 * ticking reports a TPS that falls towards zero instead of the healthy figure it had when it
 * froze.
 */
class TickSampler {
    companion object {
        /** Longest averaging window, in seconds. */
        const val WINDOW_SECONDS = 15 * 60

        /** Vanilla's tick rate; a sampled average is clamped to it so rounding cannot report 20.04. */
        const val MAX_TPS = 20.0
    }

    private val lock = Any()
    private val ticksPerSecond = IntArray(WINDOW_SECONDS)

    // Epoch second the newest counter belongs to, and the first second ever counted. Both zero
    // until the first tick arrives.
    private var currentSecond = 0L
    private var firstSecond = 0L

    /** Records one tick. Call from the server's own tick loop. */
    fun onTick(nowMillis: Long = System.currentTimeMillis()) {
        val second = nowMillis / 1000

        synchronized(lock) {
            if (currentSecond == 0L) {
                currentSecond = second
                firstSecond = second
                ticksPerSecond[slot(second)] = 0
            } else if (second > currentSecond) {
                advanceToLocked(second)
            } else if (second < currentSecond) {
                // The clock stepped backwards; keep counting into the newest slot rather than
                // corrupting an older one.
                ticksPerSecond[slot(currentSecond)]++

                return
            }

            ticksPerSecond[slot(second)]++
        }
    }

    /**
     * Average ticks per second over the last minute, five minutes and fifteen minutes, or null
     * before a single whole second has elapsed.
     *
     * The second in progress is excluded: it is only partially elapsed and would drag every
     * average down.
     */
    fun getTps(nowMillis: Long = System.currentTimeMillis()): DoubleArray? {
        synchronized(lock) {
            if (currentSecond == 0L) {
                return null
            }

            val second = nowMillis / 1000

            if (second > currentSecond) {
                advanceToLocked(second)
            }

            val latestComplete = second - 1
            val completed = latestComplete - firstSecond + 1

            if (completed < 1) {
                return null
            }

            return doubleArrayOf(
                average(latestComplete, completed, 60),
                average(latestComplete, completed, 5 * 60),
                average(latestComplete, completed, WINDOW_SECONDS)
            )
        }
    }

    /** Zeroes every second that went by without a tick, up to and including [second]. Call under [lock]. */
    private fun advanceToLocked(second: Long) {
        if (second - currentSecond >= WINDOW_SECONDS) {
            // A gap longer than the whole ring: clearing it slot by slot is pointless.
            ticksPerSecond.fill(0)
        } else {
            var missed = currentSecond + 1

            while (missed <= second) {
                ticksPerSecond[slot(missed)] = 0
                missed++
            }
        }

        currentSecond = second
    }

    private fun average(latestComplete: Long, completed: Long, windowSeconds: Int): Double {
        val span = minOf(completed, windowSeconds.toLong(), WINDOW_SECONDS.toLong())
        var total = 0L

        for (offset in 0 until span) {
            total += ticksPerSecond[slot(latestComplete - offset)]
        }

        return (total.toDouble() / span).coerceIn(0.0, MAX_TPS)
    }

    private fun slot(second: Long) = (second % WINDOW_SECONDS).toInt()
}
