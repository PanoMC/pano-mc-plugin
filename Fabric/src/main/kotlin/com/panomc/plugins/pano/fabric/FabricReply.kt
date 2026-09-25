package com.panomc.plugins.pano.fabric

import net.minecraft.commands.CommandSourceStack
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

/**
 * Where a Pano command's answer goes, by who asked.
 *
 * The server console gets a log line, the way every other Pano line there is written: colours
 * as ANSI, no prefix. A system message would reach it too, but Minecraft prints one sent to the
 * console as a plain `System chat: ...` line with every colour code stripped, which is what
 * `/pano status` looked like there. Everybody else -- a player, RCON, a command block -- still gets
 * the message itself, handed to the server thread, because the command handlers answer from a
 * coroutine and Minecraft's messaging is not meant to be driven from outside that thread.
 */
object FabricReply {
    private val consoleLogger = LoggerFactory.getLogger("Pano")

    /**
     * `CommandSourceStack.source`, the thing behind the stack: the server itself for the console,
     * the RCON thread for RCON. Private, so read reflectively; a Minecraft that renames it falls
     * back to the console's own name in [isServerConsole].
     */
    private val sourceField: Field? = try {
        CommandSourceStack::class.java.getDeclaredField("source").apply { isAccessible = true }
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    fun send(source: CommandSourceStack, message: String) {
        if (isServerConsole(source)) {
            consoleLogger.info(FabricConsoleText.toAnsi(message))

            return
        }

        val text = FabricTextHelper.parseColoredText(message)
        val server = try {
            source.server
        } catch (_: Exception) {
            null
        }

        if (server == null) {
            runCatching { source.sendSystemMessage(text) }

            return
        }

        try {
            server.execute { runCatching { source.sendSystemMessage(text) } }
        } catch (_: Exception) {
            // The server is shutting down and takes no more tasks; there is nobody to tell.
        }
    }

    /** Whether [source] is the server console itself, rather than RCON, a block or an entity. */
    fun isServerConsole(source: CommandSourceStack): Boolean {
        if (source.entity != null) {
            return false
        }

        val inner = try {
            sourceField?.get(source)
        } catch (_: Exception) {
            null
        }

        if (inner != null) {
            return inner === source.server
        }

        return source.textName == CONSOLE_NAME
    }

    /** The name `MinecraftServer.createCommandSourceStack` gives the console. */
    private const val CONSOLE_NAME = "Server"
}
