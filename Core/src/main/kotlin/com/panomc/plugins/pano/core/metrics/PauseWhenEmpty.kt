package com.panomc.plugins.pano.core.metrics

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Whether a Minecraft server is paused because nobody is online (`pause-when-empty-seconds`,
 * vanilla 1.21.2+).
 *
 * The game counts empty ticks in `MinecraftServer.emptyTicks` and stops ticking once that reaches
 * `pauseWhenEmptySeconds() * 20`; there is no public getter for the state. Both names are Mojang's,
 * which is what an unobfuscated Fabric (26.x) and a Mojang-mapped Paper run with. Anywhere else the
 * lookup fails once and every later call answers null ("cannot tell"), never throws.
 */
object PauseWhenEmpty {
    private const val TICKS_PER_SECOND = 20

    @Volatile
    private var lookup: Lookup? = null

    @Volatile
    private var unavailable = false

    private class Lookup(val type: Class<*>, val emptyTicks: Field, val pauseSeconds: Method)

    /** [minecraftServer] is the game's `MinecraftServer` (or a subclass of it). */
    fun isPaused(minecraftServer: Any): Boolean? {
        if (unavailable) {
            return null
        }

        return try {
            val found = lookupFor(minecraftServer.javaClass) ?: return null
            val limit = (found.pauseSeconds.invoke(minecraftServer) as Number).toInt() * TICKS_PER_SECOND

            limit > 0 && found.emptyTicks.getInt(minecraftServer) >= limit
        } catch (_: Throwable) {
            null
        }
    }

    private fun lookupFor(type: Class<*>): Lookup? {
        lookup?.takeIf { it.type == type }?.let { return it }

        val field = findField(type, "emptyTicks")
        // Public on a dedicated server, protected on MinecraftServer itself (26.3).
        val method = try {
            type.getMethod("pauseWhenEmptySeconds")
        } catch (_: NoSuchMethodException) {
            findMethod(type, "pauseWhenEmptySeconds")
        }

        if (field == null || method == null) {
            unavailable = true

            return null
        }

        return Lookup(type, field, method).also { lookup = it }
    }

    private fun findMethod(type: Class<*>, name: String): Method? {
        var current: Class<*>? = type

        while (current != null) {
            try {
                return current.getDeclaredMethod(name).also { it.isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }

        return null
    }

    private fun findField(type: Class<*>, name: String): Field? {
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
