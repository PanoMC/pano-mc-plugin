package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.console.ConsoleLevel
import com.panomc.plugins.pano.core.console.ConsoleStreamer
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.ExecuteCommandMessage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Handles `EXECUTE_COMMAND`: runs a console command a panel user sent.
 *
 * The command is echoed into the console stream before it is dispatched, prefixed with the user
 * who sent it, so everyone watching the console sees who ran what and in which order - the
 * command itself produces no acknowledgement message, only its own log output.
 *
 * The leading slash a user may have typed is stripped here, once, for every platform: none of
 * the four dispatchers wants it.
 *
 * `console.enabled = false` in config.conf switches the whole console feature off, commands
 * included, so a command that arrives anyway is refused instead of dispatched. Pano is told as
 * much on connect (the `commands` capability is not announced), so this only catches a panel that
 * was already open when the flag was flipped, or one ignoring the announcement.
 */
class ExecuteCommandHandler(
    private val consoleStreamer: ConsoleStreamer,
    private val pluginMain: PanoPluginMain,
    private val logger: Logger
) : PlatformMessageHandler<ExecuteCommandMessage>() {
    // One warning for the lifetime of this handler: a stale panel can keep sending, and a line
    // per refused command would spam the log of the very operator who switched the feature off.
    private val refusalLogged = AtomicBoolean(false)

    override suspend fun handle(response: ExecuteCommandMessage) {
        if (!consoleStreamer.isEnabled()) {
            if (refusalLogged.compareAndSet(false, true)) {
                logger.warning("Ignoring console commands sent by Pano: console capture is disabled in config.conf (console.enabled = false).")
            }

            return
        }

        val command = response.command.trim().removePrefix("/").trim()

        if (command.isEmpty()) {
            return
        }

        val issuedBy = response.issuedBy?.takeIf { it.isNotBlank() } ?: "unknown"

        consoleStreamer.emit(ConsoleLevel.INFO, "[Pano:$issuedBy] > $command")

        // FINE, not INFO: the echo above is the operator-facing record of this, and an INFO line
        // here would be captured by the console appender and shown in the panel twice.
        logger.fine("Executing console command from Pano (issued by $issuedBy): $command")

        try {
            pluginMain.dispatchConsoleCommand(command)
        } catch (exception: Throwable) {
            logger.warning("Failed to dispatch console command from Pano: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }
}
