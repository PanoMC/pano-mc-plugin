package com.panomc.plugins.pano.core.command.commands

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.annotation.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.model.PanoError
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.Protocol
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

        if (args[0].equals("status", true)) {
            return statusCommand(commandSender, commandHelper)
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

    /**
     * `/pano status`: whether this server is linked, to what, how fast the link answers and what
     * Pano turned on for it. The latency is a ping sent now (a few seconds at most, on the command
     * coroutine, never the server thread), falling back to the last heartbeat's round trip.
     */
    private suspend fun statusCommand(commandSender: Any, commandHelper: CommandHelper): Boolean {
        val status = platformManager.connectionStatus()
        val latency = if (status.connected) platformManager.measureLatency() else null

        statusLines(status, latency, System.currentTimeMillis()).forEach {
            commandHelper.sendMessage(commandSender, it)
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
        commandHelper.sendMessage(
            commandSender,
            "&e/pano status - Show the connection to Pano platform."
        )
    }

    companion object {
        /**
         * The lines `/pano status` prints, `&`-coded. Pure, so what an admin reads in every state
         * can be asserted without a socket.
         *
         * @param latencyMillis a round trip measured just now, or null when there was none (not
         *   connected, or no answer in time) -- the last heartbeat's is shown then.
         * @param now the current time, for "connected for" and "last heartbeat ... ago".
         */
        fun statusLines(
            status: PlatformManager.ConnectionStatus,
            latencyMillis: Long?,
            now: Long,
            pluginVersion: String = Pano.VERSION
        ): List<String> {
            val lines = mutableListOf("&6Pano status")

            if (!status.configured) {
                lines += "&7Connection: &cNot connected to a Pano platform."
                lines += "&7Connect with: &e/pano connect <platform-address> <platform-code>"
                lines += "&7Plugin: &f$pluginVersion &7(protocol ${Protocol.VERSION})"

                return lines
            }

            val scheme = if (status.ssl) "https" else "http"
            val address = listOfNotNull(status.host, status.port?.toString()).joinToString(":")

            lines += "&7Platform: &f${address.ifEmpty { "-" }} &7($scheme)"

            lines += when {
                status.connected && status.connectedAt != null ->
                    "&7Connection: &aConnected &7for ${formatDuration(now - status.connectedAt)}"

                status.connected -> "&7Connection: &aConnected"
                status.connecting -> "&7Connection: &eConnecting... &7(retrying until Pano answers)"
                else -> "&7Connection: &cDisconnected"
            }

            if (status.connected) {
                val measured = latencyMillis ?: status.lastRoundTripMillis

                lines += when {
                    latencyMillis != null -> "&7Latency: &f$latencyMillis ms"
                    measured != null -> "&7Latency: &f$measured ms &7(last heartbeat; no answer to a new ping)"
                    else -> "&7Latency: &cno answer to a ping"
                }

                status.lastPongAt?.let { lastPong ->
                    val cadence = if (status.heartbeatIntervalMillis != null && status.heartbeatTimeoutMillis != null) {
                        " &7(every ${formatDuration(status.heartbeatIntervalMillis)}, timeout ${formatDuration(status.heartbeatTimeoutMillis)})"
                    } else {
                        ""
                    }

                    lines += "&7Last heartbeat: &f${formatDuration(now - lastPong)} ago$cadence"
                }
            }

            status.settings?.let { settings ->
                lines += "&7Integrations: &f" + listOf(
                    "auth" to settings.authIntegration,
                    "bans" to settings.banIntegration,
                    "permissions" to settings.permissionIntegration
                ).joinToString(", ") { (name, on) -> "$name ${if (on) "&aon&f" else "&7off&f"}" }
            }

            lines += "&7Plugin: &f$pluginVersion &7(protocol ${Protocol.VERSION})"

            return lines
        }

        /** `2h 13m`, `45s`, `1d 3h`: the two largest units, never "0s" for a positive span. */
        fun formatDuration(millis: Long): String {
            val totalSeconds = (millis.coerceAtLeast(0) + 999) / 1000

            val days = totalSeconds / 86_400
            val hours = totalSeconds % 86_400 / 3_600
            val minutes = totalSeconds % 3_600 / 60
            val seconds = totalSeconds % 60

            return when {
                days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
                hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
                minutes > 0 -> if (seconds > 0) "${minutes}m ${seconds}s" else "${minutes}m"
                else -> "${seconds}s"
            }
        }
    }
}