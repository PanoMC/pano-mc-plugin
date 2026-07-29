package com.panomc.plugins.pano.core.command.commands

import com.panomc.plugins.pano.core.annotation.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.model.PanoError
import com.panomc.plugins.pano.core.platform.PlatformManager
import java.io.Console
import java.util.logging.Level
import java.util.logging.Logger

@Command
class PanoCommand(
    private val platformManager: PlatformManager,
    // CommandManager constructs this directly (not through Spring), so keep this defaulted
    // rather than widening the constructor's required parameters.
    private val logger: Logger = Logger.getLogger(PanoCommand::class.java.name)
) : com.panomc.plugins.pano.core.command.Command {
    override val name: String = "Pano"
    override val permission: String = "pano.admin"
    override val description: String = "Runs Pano commands."
    override val permissionMessage: String = "You do not have permission!"
    override val usage: String = "pano"

    override suspend fun handler(commandSender: Any, args: Array<out String>, commandHelper: CommandHelper): Boolean {
        if (args.isEmpty()) {
            showHelp(commandSender, commandHelper)

            return true
        }

        if (args[0].equals("connect", true)) {
            return connectCommand(commandSender, args, commandHelper)
        }

        if (args[0].equals("disconnect", true)) {
            return disconnectCommand(commandSender, commandHelper)
        }

        showHelp(commandSender, commandHelper)

        return true
    }

    private suspend fun connectCommand(
        commandSender: Any,
        args: Array<out String>,
        commandHelper: CommandHelper
    ): Boolean {
        if (args.size <= 2) {
            showConnectArgumentUsage(commandSender, commandHelper)

            return true
        }

        val platformAddress = args[1]
        val platformCode = args[2]

        if (platformManager.isPlatformConfigured()) {
            commandHelper.sendMessage(
                commandSender,
                "&cAlready connected to a platform. &eYou can disconnect by: /pano disconnect"
            )

            return true
        }

        commandHelper.sendMessage(commandSender, "Connecting...")

        try {
            platformManager.connectNewPlatform(platformAddress, platformCode)
        } catch (panoError: PanoError) {
            commandHelper.sendMessage(commandSender, panoError.message!!)

            return true
        } catch (exception: Exception) {
            // connectNewPlatform can throw plain exceptions after the HTTP call already
            // succeeded (e.g. a stale private-key/public-key pair failing to decrypt the
            // response). Previously this branch sent nothing, leaving the sender stuck at
            // "Connecting..." with only a console stack trace to go on (core-misc-14).
            logger.log(Level.SEVERE, "Failed to connect to platform", exception)

            commandHelper.sendMessage(
                commandSender,
                "&cConnection failed: ${exception.message ?: exception::class.java.simpleName}. See console for details."
            )

            return true
        }

        commandHelper.sendMessage(
            commandSender,
            "&eToken saved, please allow this server on panel of Pano platform."
        )

        platformManager.connectPlatformTask.invoke(true, true)

        return true
    }

    private suspend fun disconnectCommand(
        commandSender: Any,
        commandHelper: CommandHelper
    ): Boolean {
        if (!platformManager.isPlatformConfigured()) {
            commandHelper.sendMessage(
                commandSender,
                "&cThis server is already not connected to a Pano platform."
            )

            return true
        }

        commandHelper.sendMessage(commandSender, "Disconnecting...")

        try {
            platformManager.disconnectPlatform()
        } catch (exception: Exception) {
            // disconnectPlatform can throw non-PanoError exceptions (removePlatform / config
            // I/O / coAwait failures) whose message is null; mirror connectCommand's handling
            // so the real reason isn't lost behind a KotlinNullPointerException (core-misc-14).
            logger.log(Level.SEVERE, "Failed to disconnect from platform", exception)

            commandHelper.sendMessage(
                commandSender,
                exception.message ?: exception::class.java.simpleName
            )

            return true
        }

        if (commandSender !is Console) {
            commandHelper.sendMessage(commandSender, "&2Disconnected from platform!")
        }

        return true
    }

    private fun showConnectArgumentUsage(
        commandSender: Any,
        commandHelper: CommandHelper
    ) {
        commandHelper.sendMessage(commandSender, "&cUsage: /pano connect <platform-address> <platform-code>")
    }

    private fun showHelp(commandSender: Any, commandHelper: CommandHelper) {
        commandHelper.sendMessage(commandSender, "&6Pano MC Plugin Commands:")
        commandHelper.sendMessage(
            commandSender,
            "&e/pano connect <platform-address> <platform-code> - Connect to Pano platform."
        )
        commandHelper.sendMessage(
            commandSender,
            "&e/pano disconnect - Disconnect from Pano platform."
        )
    }
}