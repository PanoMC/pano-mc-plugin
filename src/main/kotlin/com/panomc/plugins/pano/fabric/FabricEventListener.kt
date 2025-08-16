package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.event.EventType
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

class FabricEventListener(
    private val pluginMain: PanoPluginMain,
    private val listeners: List<Listener>
) : EventHelper {
    private val joinListener = ServerPlayConnectionEvents.Join { handler, _, _ ->
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_JOIN }
            .forEach { l -> l.handle(this, handler.player) }
    }
    private val disconnectListener = ServerPlayConnectionEvents.Disconnect { handler, _ ->
        listeners
            .filter { it.eventType == EventType.ON_PLAYER_DISCONNECT }
            .forEach { l -> l.handle(this, handler.player) }
    }

    init {
        ServerPlayConnectionEvents.JOIN.register(joinListener)
        ServerPlayConnectionEvents.DISCONNECT.register(disconnectListener)
    }

    fun unregister() {
        ServerPlayConnectionEvents.JOIN.unregister(joinListener)
        ServerPlayConnectionEvents.DISCONNECT.unregister(disconnectListener)
    }

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as ServerPlayerEntity).sendMessage(Text.literal(pluginMain.translateColor(message)))
    }

    override fun convertToPlayerData(player: Any): EventHelper.Companion.PlayerData {
        val p = player as ServerPlayerEntity
        return EventHelper.Companion.PlayerData(
            uuid = p.uuid,
            username = p.gameProfile.name,
            ping = p.ping.toLong()
        )
    }
}
