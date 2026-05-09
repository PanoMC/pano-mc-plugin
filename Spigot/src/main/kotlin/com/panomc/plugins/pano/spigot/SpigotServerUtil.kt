package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.ServerType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

object SpigotServerUtil {

    fun isFolia(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    fun detectServerType(): ServerType {
        return if (isFolia()) {
            ServerType.FOLIA
        } else {
            try {
                Class.forName("com.destroystokyo.paper.PaperConfig")
                ServerType.PAPER
            } catch (_: ClassNotFoundException) {
                val name = org.bukkit.Bukkit.getName().lowercase()
                val version = org.bukkit.Bukkit.getVersion().lowercase()
                when {
                    name.contains("paper", true) -> ServerType.PAPER
                    name.contains("spigot", true) || version.contains("spigot", true) -> ServerType.SPIGOT
                    name.contains("bukkit", true) -> ServerType.BUKKIT
                    else -> ServerType.SPIGOT
                }
            }
        }
    }

    fun getPlayerIp(playerName: String): String? {
        val player = Bukkit.getPlayer(playerName) ?: return null
        if (!player.isOnline) return null

        return getPlayerIp(player)
    }

    fun getPlayerIp(player: Player): String? {
        val address = player.address ?: return null
        return address.address?.hostAddress
    }

    fun getPlayerPing(player: Player): Long {
        invokeNoArg(player, "getPing")?.let { ping ->
            return (ping as? Number)?.toLong() ?: 0L
        }

        val handle = invokeNoArg(player, "getHandle") ?: return 0L
        return readNumberField(handle, "ping")?.toLong() ?: 0L
    }

    fun kickPlayer(plugin: Plugin, player: Player, message: String) {
        runPlayerTask(plugin, player) {
            player.kickPlayer(message)
        }
    }

    fun runGlobalTask(plugin: Plugin, task: () -> Unit) {
        if (isFolia()) {
            try {
                val scheduler = plugin.server.javaClass.getMethod("getGlobalRegionScheduler").invoke(plugin.server)
                val run = scheduler.javaClass.getMethod(
                    "run",
                    Plugin::class.java,
                    Consumer::class.java
                )
                run.invoke(scheduler, plugin, Consumer<Any> { task() })
                return
            } catch (_: Exception) {
                // Fall back to Bukkit scheduling below.
            }
        }

        plugin.server.scheduler.runTask(plugin, Runnable { task() })
    }

    fun runPlayerTask(plugin: Plugin, player: Player, task: () -> Unit) {
        if (isFolia()) {
            try {
                val scheduler = player.javaClass.getMethod("getScheduler").invoke(player)
                val run = scheduler.javaClass.methods.firstOrNull { method ->
                    method.name == "run" &&
                            method.parameterTypes.size == 3 &&
                            Plugin::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                            Consumer::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                            Runnable::class.java.isAssignableFrom(method.parameterTypes[2])
                }
                if (run != null) {
                    run.invoke(scheduler, plugin, Consumer<Any> { task() }, Runnable {})
                    return
                }
            } catch (_: Exception) {
                // Fall back to Bukkit scheduling below.
            }
        }

        plugin.server.scheduler.runTask(plugin, Runnable { task() })
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        return try {
            target.javaClass.getMethod(methodName).invoke(target)
        } catch (_: Exception) {
            null
        }
    }

    private fun readNumberField(target: Any, fieldName: String): Number? {
        return try {
            val field = try {
                target.javaClass.getField(fieldName)
            } catch (_: NoSuchFieldException) {
                target.javaClass.getDeclaredField(fieldName).also { it.isAccessible = true }
            }
            field.get(target) as? Number
        } catch (_: Exception) {
            null
        }
    }
}
