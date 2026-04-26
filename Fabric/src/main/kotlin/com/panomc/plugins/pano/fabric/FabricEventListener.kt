package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer

class FabricEventListener(
    private val pluginMain: PanoPluginMain,
    internal val listeners: MutableSet<com.panomc.plugins.pano.core.event.Listener>
) : EventHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        val text = FabricTextHelper.parseColoredText(message)
        when (commandSender) {
            is ServerPlayer -> commandSender.sendSystemMessage(text)
            is CommandSourceStack -> commandSender.sendSystemMessage(text)
        }
    }

    override fun kick(commandSender: Any, message: String) {
        (commandSender as? ServerPlayer)?.connection?.disconnect(
            FabricTextHelper.parseColoredText(message)
        )
    }

    override fun disallow(event: Any, message: String) {
        (event as? ServerPlayer)?.connection?.disconnect(
            FabricTextHelper.parseColoredText(message)
        )
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val playerInstance = player as ServerPlayer

        val playerName = try {
            val profile = playerInstance.getGameProfile()
            try {
                profile.javaClass.getMethod("name").invoke(profile) as String
            } catch (_: NoSuchMethodException) {
                profile.javaClass.getMethod("getName").invoke(profile) as String
            }
        } catch (_: Exception) {
            playerInstance.getName().string
        }

        val ping = try {
            playerInstance.connection.latency().toLong()
        } catch (_: Exception) {
            0L
        }

        val ipAddress = try {
            val handler = playerInstance.connection
            val connectionField = handler.javaClass.superclass.getDeclaredField("connection")
            connectionField.isAccessible = true
            val connection = connectionField.get(handler)
            val addressMethod = connection.javaClass.getMethod("getAddress")
            val address = addressMethod.invoke(connection)
            address.toString().replace("/", "").split(":")[0]
        } catch (_: Exception) {
            try {
                val handler = playerInstance.connection
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
            uuid = playerInstance.getUUID(),
            username = playerName,
            ping = ping,
            ipAddress = ipAddress
        )
    }

    fun onPlayerPreLogin(player: ServerPlayer) {
        val username = try {
            val profile = player.getGameProfile()
            try {
                profile.javaClass.getMethod("name").invoke(profile) as String
            } catch (_: NoSuchMethodException) {
                profile.javaClass.getMethod("getName").invoke(profile) as String
            }
        } catch (_: Exception) {
            player.getName().string
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

    fun onPlayerJoin(player: ServerPlayer) {
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

    fun onPlayerDisconnect(player: ServerPlayer) {
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
