package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.TextComponent

class BungeeCommand(private val command: Command, private val pluginMain: PanoPluginMain) :
    net.md_5.bungee.api.plugin.Command(command.name, command.permission), CommandHelper {
    override fun execute(sender: CommandSender, args: Array<out String>) {
        runBlocking {
            command.handler(sender, args, this@BungeeCommand)
        }
    }

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(TextComponent(pluginMain.translateColor(message)))
    }
}