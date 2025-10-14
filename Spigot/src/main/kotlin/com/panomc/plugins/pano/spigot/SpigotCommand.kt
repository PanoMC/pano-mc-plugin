package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand

class SpigotCommand(private val command: Command, private val pluginMain: PanoPluginMain) : BukkitCommand(command.name),
    CommandHelper {
    init {
        description = command.description
        usage = command.usage
        permission = command.permission
    }

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(pluginMain.translateColor(message))
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        return runBlocking {
            return@runBlocking command.handler(sender, args, this@SpigotCommand)
        }
    }
}