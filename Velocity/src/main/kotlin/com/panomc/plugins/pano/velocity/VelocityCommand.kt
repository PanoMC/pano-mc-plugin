package com.panomc.plugins.pano.velocity

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.command.SimpleCommand.Invocation
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

// pluginMain is typed VelocityMain (not the PanoPluginMain interface) so this can reach the
// shared coroutineScope/getServer() below — VelocityCommand is only ever built by VelocityMain.
class VelocityCommand(private val command: Command, private val pluginMain: VelocityMain) : SimpleCommand,
    CommandHelper {
    override fun sendMessage(commandSender: Any, message: String) {
        // translateColor() emits console ANSI escapes; deserialize the raw '&'-coded string
        // into a Component instead, or players see literal escape bytes in chat.
        (commandSender as CommandSource).sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message))
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

        // command.handler performs blocking network I/O (mcping + platform HTTP calls with no/loose
        // timeouts); don't park the invoking thread on it via runBlocking. Launched on VelocityMain's
        // long-lived scope (not a throwaway per-call one) so this coroutine is cancelled on plugin
        // disable instead of leaking past it. The per-launch CoroutineExceptionHandler logs the
        // failure and replies to the sender — without it, a throwing handler would only produce a
        // silent default-uncaught-handler dump with no feedback to whoever ran the command.
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            pluginMain.getPanoLogger().severe("Command '${command.name}' failed: $throwable")

            // A Player sender may have disconnected while the command was still running; only
            // reply if they're still present on the proxy (mirrors VelocityMain.kickPlayer's check).
            if (commandSenderSource !is Player || pluginMain.getServer().getPlayer(commandSenderSource.uniqueId).isPresent) {
                sendMessage(commandSenderSource, "&cAn error occurred while running this command. Please try again shortly.")
            }
        }

        pluginMain.coroutineScope.launch(exceptionHandler) {
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