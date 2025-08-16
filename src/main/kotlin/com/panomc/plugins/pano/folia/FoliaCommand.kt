package com.panomc.plugins.pano.folia

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand

/**
 * Wrapper that adapts core commands to Folia's command system. The
 * implementation mirrors the Spigot variant but lives in the Folia package for
 * clarity.
 */
class FoliaCommand(private val command: Command, private val pluginMain: PanoPluginMain) :
    BukkitCommand(command.name), CommandHelper {
    init {
        name = command.name
        description = command.description
        usage = command.usage
        permission = command.permission
    }

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(pluginMain.translateColor(message))
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        return runBlocking {
            return@runBlocking command.handler(sender, args, this@FoliaCommand)
        }
    }
}

