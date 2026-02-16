package com.panomc.plugins.pano.core.helper


interface CommandHelper {
    fun sendMessage(commandSender: Any, message: String)

    fun isPlayer(commandSender: Any): Boolean

    fun getUsername(commandSender: Any): String
}