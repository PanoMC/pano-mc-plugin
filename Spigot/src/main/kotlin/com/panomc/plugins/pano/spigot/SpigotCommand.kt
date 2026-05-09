package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.entity.Player
import org.bukkit.command.Command as BukkitPluginCommand

class SpigotCommand(private val command: Command, private val pluginMain: PanoPluginMain) : BukkitCommand(command.name),
    CommandHelper, CommandExecutor {
    init {
        description = command.description
        usage = command.usage
        permission = command.permission
    }

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(pluginMain.translateColor(message))
    }

    override fun isPlayer(commandSender: Any): Boolean {
        return commandSender is Player
    }

    override fun getUsername(commandSender: Any): String {
        return (commandSender as Player).name
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        return runBlocking {
            return@runBlocking command.handler(sender, args, this@SpigotCommand)
        }
    }

    override fun onCommand(
        sender: CommandSender,
        command: BukkitPluginCommand,
        label: String,
        args: Array<out String>
    ): Boolean = execute(sender, label, args)
}