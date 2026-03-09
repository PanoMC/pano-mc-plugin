package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity

class FabricEventListener(
    private val pluginMain: PanoPluginMain,
    internal val listeners: MutableSet<com.panomc.plugins.pano.core.event.Listener>
) : EventHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        val text = FabricTextHelper.parseColoredText(message)
        when (commandSender) {
            is ServerPlayerEntity -> commandSender.sendMessage(text)
            is ServerCommandSource -> commandSender.sendMessage(text)
        }
    }

    override fun kick(commandSender: Any, message: String) {
        (commandSender as? ServerPlayerEntity)?.networkHandler?.disconnect(
            FabricTextHelper.parseColoredText(message)
        )
    }

    override fun disallow(event: Any, message: String) {
        // In Fabric, there's no direct event cancellation like Spigot's PlayerLoginEvent.
        // We kick the player instead if needed.
        (event as? ServerPlayerEntity)?.networkHandler?.disconnect(
            FabricTextHelper.parseColoredText(message)
        )
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val playerInstance = player as ServerPlayerEntity

        val playerName = try {
            val profile = playerInstance.gameProfile
            // Try Record-style accessor first (MC 1.21.11+), then legacy getter
            try {
                profile.javaClass.getMethod("name").invoke(profile) as String
            } catch (_: NoSuchMethodException) {
                profile.javaClass.getMethod("getName").invoke(profile) as String
            }
        } catch (_: Exception) {
            playerInstance.name.string
        }

        val ping = try {
            playerInstance.networkHandler.latency.toLong()
        } catch (_: Exception) {
            0L
        }

        val ipAddress = try {
            val handler = playerInstance.networkHandler
            // connection field is protected, use reflection
            val connectionField = handler.javaClass.superclass.getDeclaredField("connection")
            connectionField.isAccessible = true
            val connection = connectionField.get(handler)
            val addressMethod = connection.javaClass.getMethod("getAddress")
            val address = addressMethod.invoke(connection)
            address.toString().replace("/", "").split(":")[0]
        } catch (_: Exception) {
            try {
                // Fallback: try getIp() or similar methods
                val handler = playerInstance.networkHandler
                handler.javaClass.methods.find {
                    it.name.contains(
                        "address",
                        ignoreCase = true
                    ) || it.name.contains("Ip", ignoreCase = false)
                }
                    ?.invoke(handler)?.toString()?.replace("/", "")?.split(":")?.get(0) ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
        }

        return EventHelper.Companion.PlayerData(
            uuid = playerInstance.uuid,
            username = playerName,
            ping = ping,
            ipAddress = ipAddress
        )
    }

    fun onPlayerPreLogin(player: ServerPlayerEntity) {
        val username = try {
            val profile = player.gameProfile
            try {
                profile.javaClass.getMethod("name").invoke(profile) as String
            } catch (_: NoSuchMethodException) {
                profile.javaClass.getMethod("getName").invoke(profile) as String
            }
        } catch (_: Exception) {
            player.name.string
        }

        runBlocking {
            try {
                listeners.filterIsInstance<OnPlayerPreLogin>().forEach {
                    it.handle(this@FabricEventListener, player, username)
                }
            } catch (e: Exception) {
                org.slf4j.LoggerFactory.getLogger("Pano").error("Error handling player pre-login", e)
            }
        }
    }

    fun onPlayerJoin(player: ServerPlayerEntity) {
        runBlocking {
            try {
                listeners.filterIsInstance<OnPlayerJoin>().forEach {
                    it.handle(this@FabricEventListener, player)
                }
            } catch (e: Exception) {
                org.slf4j.LoggerFactory.getLogger("Pano").error("Error handling player join", e)
            }
        }
    }

    fun onPlayerDisconnect(player: ServerPlayerEntity) {
        runBlocking {
            try {
                listeners.filterIsInstance<OnPlayerDisconnect>().forEach {
                    it.handle(this@FabricEventListener, player)
                }
            } catch (e: Exception) {
                org.slf4j.LoggerFactory.getLogger("Pano").error("Error handling player disconnect", e)
            }
        }
    }
}
