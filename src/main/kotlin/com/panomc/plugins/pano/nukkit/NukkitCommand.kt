package com.panomc.plugins.pano.nukkit

import cn.nukkit.command.CommandSender
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking

class NukkitCommand(private val command: Command, private val pluginMain: PanoPluginMain) :
    cn.nukkit.command.Command(command.name), CommandHelper {

    init {
        description = command.description
        usageMessage = command.usage
        setPermission(command.permission)
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        return runBlocking {
            command.handler(sender, args, this@NukkitCommand)
            true
        }
    }

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(pluginMain.translateColor(message))
    }
}
