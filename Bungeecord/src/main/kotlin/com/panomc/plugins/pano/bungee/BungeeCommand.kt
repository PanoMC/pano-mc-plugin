package com.panomc.plugins.pano.bungee

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import kotlinx.coroutines.launch
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer

class BungeeCommand(private val command: Command, private val pluginMain: BungeeMain) :
    net.md_5.bungee.api.plugin.Command(command.name, command.permission), CommandHelper {
    init {
        // setPermissionMessage is protected on the superclass, so only a subclass body can call it;
        // the constructor above has no way to pass it through.
        setPermissionMessage(pluginMain.translateColor(command.permissionMessage))
    }

    override fun execute(sender: CommandSender, args: Array<out String>) {
        // UpstreamBridge.handle(Chat) dispatches player commands inline on that connection's Netty
        // event-loop thread; runBlocking here would park it (and every other connection multiplexed
        // on the same worker) for the full handler, e.g. /pano connect's HTTP round trips.
        // pluginMain.coroutineScope is long-lived (created in onEnable, cancelled in onDisable) so
        // this can't outlive the plugin, and its CoroutineExceptionHandler catches anything that
        // escapes the try/catch below. The try/catch itself is what lets a throwing handler still
        // reply to the sender, which the scope-level handler alone can't do.
        pluginMain.coroutineScope.launch {
            try {
                command.handler(sender, args, this@BungeeCommand)
            } catch (e: Exception) {
                pluginMain.getPanoLogger().warning("Command '${command.name}' failed: ${e.message}")
                sendMessage(sender, "&cAn error occurred while executing this command.")
            }
        }
    }

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as CommandSender).sendMessage(TextComponent(pluginMain.translateColor(message)))
    }

    override fun isPlayer(commandSender: Any): Boolean {
        return commandSender is ProxiedPlayer
    }

    override fun getUsername(commandSender: Any): String {
        return (commandSender as ProxiedPlayer).name
    }
}