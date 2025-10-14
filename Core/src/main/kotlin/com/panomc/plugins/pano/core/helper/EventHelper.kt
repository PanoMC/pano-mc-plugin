package com.panomc.plugins.pano.core.helper

import java.util.*

interface EventHelper {
    fun sendMessage(commandSender: Any, message: String)

    fun convertToPlayerData(player: Any): PlayerData

    companion object {
        data class PlayerData(
            val uuid: UUID,
            val username: String,
            val ping: Long,
            val loginTime: Long = System.currentTimeMillis()
        )
    }
}