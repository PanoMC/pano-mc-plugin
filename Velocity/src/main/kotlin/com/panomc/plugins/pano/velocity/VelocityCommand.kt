package com.panomc.plugins.pano.velocity

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.command.SimpleCommand.Invocation
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component

class VelocityCommand(private val command: Command, private val pluginMain: PanoPluginMain) : SimpleCommand,
    CommandHelper {
    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSource).sendMessage(Component.text(pluginMain.translateColor(message)))
    }

    override fun isPlayer(commandSender: Any): Boolean {
        return commandSender is Player
    }

    override fun getUsername(commandSender: Any): String {
        return (commandSender as Player).username
    }

    override fun execute(invocation: Invocation) {
        val commandSenderSource = invocation.source()
        val arguments = invocation.arguments()

        runBlocking {
            command.handler(commandSenderSource, arguments, this@VelocityCommand)
        }
    }

    override fun hasPermission(invocation: Invocation): Boolean {
        command.permission?.let {
            return invocation.source().hasPermission(it)
        }

        return true
    }
}