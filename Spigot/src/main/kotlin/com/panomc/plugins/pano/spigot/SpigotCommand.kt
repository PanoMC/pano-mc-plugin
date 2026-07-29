package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import kotlinx.coroutines.launch
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.entity.Player
import org.bukkit.command.Command as BukkitPluginCommand

// pluginMain is the concrete SpigotMain (not the PanoPluginMain interface) so execute() can reach
// its long-lived coroutineScope (see SpigotMain), mirroring BungeeCommand.
class SpigotCommand(private val command: Command, private val pluginMain: SpigotMain) : BukkitCommand(command.name),
    CommandHelper, CommandExecutor {
    init {
        description = command.description
        usage = command.usage
        permission = command.permission
    }

    override fun sendMessage(commandSender: Any, message: String) {
        val sender = commandSender as CommandSender

        // The handler now replies asynchronously (see execute()), so by the time a message is
        // ready to send the player may have already disconnected — skip rather than message a
        // stale Player instance.
        if (sender is Player && !sender.isOnline) {
            return
        }

        sender.sendMessage(pluginMain.translateColor(message))
    }

    override fun isPlayer(commandSender: Any): Boolean {
        return commandSender is Player
    }

    override fun getUsername(commandSender: Any): String {
        return (commandSender as Player).name
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        // command.handler() can hit the network (connect/disconnect/link) with multi-second
        // timeouts; running it under runBlocking here would freeze the dispatching thread
        // (the main server thread for both players and console). Dispatch on the plugin's
        // long-lived scope (created in onEnable, cancelled in onDisable — see SpigotMain) instead
        // of a throwaway per-invocation scope, so a handler can't outlive the plugin, and let the
        // handler's own commandHelper.sendMessage calls report the result whenever it lands.
        pluginMain.coroutineScope.launch {
            try {
                command.handler(sender, args, this@SpigotCommand)
            } catch (exception: Exception) {
                pluginMain.getPanoLogger().warning("Command '${command.name}' failed: ${exception.message}")
                // sendMessage() already skips a Player who has since gone offline.
                sendMessage(sender, "&cAn error occurred while executing this command.")
            }
        }

        return true
    }

    override fun onCommand(
        sender: CommandSender,
        command: BukkitPluginCommand,
        label: String,
        args: Array<out String>
    ): Boolean = execute(sender, label, args)
}