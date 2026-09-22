package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.metrics.TickSampler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * Reads TPS and MSPT off a Bukkit-family server, whatever flavour it is.
 *
 * Three sources, in order of preference:
 *
 * 1. **Paper's API** - `Server.getTPS()` and `Server.getAverageTickTime()`. Present on Paper,
 *    Purpur and Folia. Reached reflectively because this module compiles against the oldest
 *    supported Spigot API on purpose (see Spigot/build.gradle.kts) and those methods are not in it.
 * 2. **`MinecraftServer.recentTps`** - the field CraftBukkit itself maintains, reachable on a
 *    plain Spigot/CraftBukkit build through `CraftServer.getServer()`. TPS only; the tick-time
 *    array next to it is obfuscated and renamed between versions, so it is deliberately not read.
 * 3. **[TickSampler]** - a one-tick repeating task counting ticks per second. Started only when
 *    neither of the above works, so an ordinary server pays nothing for it.
 *
 * MSPT stays null unless source 1 answers: a tick counter measures the period between ticks, not
 * the work inside one, so deriving MSPT from it would report a flat 50 ms on any healthy server -
 * worse than reporting nothing.
 */
class SpigotMetrics(private val plugin: Plugin) {
    private val tickSampler = TickSampler()

    @Volatile
    private var samplerTaskId = -1

    /** Starts the fallback tick sampler if - and only if - the server publishes no TPS of its own. */
    fun start() {
        if (samplerTaskId != -1) {
            return
        }

        if (readApiTps() != null || readNmsRecentTps() != null) {
            return
        }

        // Folia has Paper's API, so it never reaches here; its regionised scheduler has no
        // equivalent of a plain repeating server task, and guessing at one is not worth it.
        if (SpigotServerUtil.isFolia()) {
            return
        }

        samplerTaskId = try {
            plugin.server.scheduler
                .runTaskTimer(plugin, Runnable { tickSampler.onTick() }, 1L, 1L)
                .taskId
        } catch (_: Throwable) {
            -1
        }
    }

    fun stop() {
        val taskId = samplerTaskId

        samplerTaskId = -1

        if (taskId != -1) {
            try {
                plugin.server.scheduler.cancelTask(taskId)
            } catch (_: Throwable) {
                // The scheduler is already gone if the server itself is shutting down.
            }
        }
    }

    fun getTps(): DoubleArray? = readApiTps()
        ?: readNmsRecentTps()
        ?: if (samplerTaskId != -1) tickSampler.getTps() else null

    fun getMspt(): Double? = readApiMspt()

    private fun readApiTps(): DoubleArray? = try {
        val server = Bukkit.getServer()

        (server.javaClass.getMethod("getTPS").invoke(server) as? DoubleArray)
            ?.takeIf { it.size >= 3 }
            ?.let { doubleArrayOf(it[0], it[1], it[2]) }
    } catch (_: Throwable) {
        null
    }

    private fun readApiMspt(): Double? = try {
        val server = Bukkit.getServer()

        (server.javaClass.getMethod("getAverageTickTime").invoke(server) as? Number)?.toDouble()
    } catch (_: Throwable) {
        null
    }

    private fun readNmsRecentTps(): DoubleArray? = try {
        val server = Bukkit.getServer()
        val minecraftServer = server.javaClass.getMethod("getServer").invoke(server)

        minecraftServer?.let { nms ->
            val field = try {
                nms.javaClass.getField("recentTps")
            } catch (_: NoSuchFieldException) {
                findDeclaredField(nms.javaClass, "recentTps")
            }

            (field?.get(nms) as? DoubleArray)
                ?.takeIf { it.size >= 3 }
                ?.let { doubleArrayOf(it[0], it[1], it[2]) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun findDeclaredField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type

        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }

        return null
    }
}
