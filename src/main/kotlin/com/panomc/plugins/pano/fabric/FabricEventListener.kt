package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.event.EventType
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import java.lang.reflect.Proxy
import java.util.UUID

class FabricEventListener(
    private val pluginMain: PanoPluginMain,
    private val listeners: List<Listener>
) : EventHelper {

    fun register() {
        try {
            val eventsClass = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents")
            val eventInterface = Class.forName("net.fabricmc.fabric.api.event.Event")
            val register = eventInterface.getMethod("register", Any::class.java)

            val joinField = eventsClass.getField("JOIN")
            val joinEvent = joinField.get(null)
            val joinInterface = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents\$Join")
            val joinProxy = Proxy.newProxyInstance(joinInterface.classLoader, arrayOf(joinInterface)) { _, _, args ->
                val handler = args[0]
                val player = handler.javaClass.getMethod("getPlayer").invoke(handler)
                listeners.filter { it.eventType == EventType.ON_PLAYER_JOIN }
                    .forEach { it.handle(this, player) }
                null
            }
            register.invoke(joinEvent, joinProxy)

            val disconnectField = eventsClass.getField("DISCONNECT")
            val disconnectEvent = disconnectField.get(null)
            val disconnectInterface = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents\$Disconnect")
            val disconnectProxy = Proxy.newProxyInstance(disconnectInterface.classLoader, arrayOf(disconnectInterface)) { _, _, args ->
                val handler = args[0]
                val player = handler.javaClass.getMethod("getPlayer").invoke(handler)
                listeners.filter { it.eventType == EventType.ON_PLAYER_DISCONNECT }
                    .forEach { it.handle(this, player) }
                null
            }
            register.invoke(disconnectEvent, disconnectProxy)
        } catch (_: Exception) {
            // ignore if Fabric classes are unavailable
        }
    }

    override fun sendMessage(commandSender: Any, message: String) {
        try {
            val sendMessage = commandSender.javaClass.getMethod("sendMessage", FabricTextUtil.TEXT_CLASS)
            val text = FabricTextUtil.toText(pluginMain.translateColor(message))
            sendMessage.invoke(commandSender, text)
        } catch (_: Exception) {
            // ignore
        }
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        return try {
            val uuid = player.javaClass.getMethod("getUuid").invoke(player) as UUID
            val gameProfile = player.javaClass.getMethod("getGameProfile").invoke(player)
            val username = gameProfile.javaClass.getMethod("getName").invoke(gameProfile) as String
            EventHelper.Companion.PlayerData(uuid, username, 0L)
        } catch (_: Exception) {
            EventHelper.Companion.PlayerData(UUID.randomUUID(), "unknown", 0L)
        }
    }
}
