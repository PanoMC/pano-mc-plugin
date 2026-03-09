package com.panomc.plugins.pano.fabric

import org.slf4j.LoggerFactory
import java.util.function.Supplier
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Bridges java.util.logging.Logger to SLF4J so that Pano core logs
 * appear with Fabric's native [HH:MM:SS] [Thread/LEVEL] format
 * instead of the default JUL format.
 *
 * Converts `§` color codes to ANSI escape sequences for console output.
 */
class FabricLogger(name: String) : Logger(name, null) {
    private val slf4j = LoggerFactory.getLogger(name)

    companion object {
        private val ANSI_COLORS = mapOf(
            '0' to "\u001B[30m",   // Black
            '1' to "\u001B[34m",   // Dark Blue
            '2' to "\u001B[32m",   // Dark Green
            '3' to "\u001B[36m",   // Dark Aqua
            '4' to "\u001B[31m",   // Dark Red
            '5' to "\u001B[35m",   // Dark Purple
            '6' to "\u001B[33m",   // Gold
            '7' to "\u001B[37m",   // Gray
            '8' to "\u001B[90m",   // Dark Gray
            '9' to "\u001B[94m",   // Blue
            'a' to "\u001B[92m",   // Green
            'b' to "\u001B[96m",   // Aqua
            'c' to "\u001B[91m",   // Red
            'd' to "\u001B[95m",   // Light Purple
            'e' to "\u001B[93m",   // Yellow
            'f' to "\u001B[97m",   // White
            'r' to "\u001B[0m"     // Reset
        )

        /**
         * Converts `§` color codes in a message to ANSI escape sequences
         * for colored console output, appending a reset at the end.
         */
        fun toAnsi(text: String): String {
            var result = text
            for ((code, ansi) in ANSI_COLORS) {
                result = result.replace("§$code", ansi)
            }
            return "$result\u001B[0m"
        }
    }

    override fun log(record: LogRecord) {
        val message = toAnsi(record.message ?: return)
        val thrown = record.thrown

        when (record.level) {
            Level.SEVERE -> if (thrown != null) slf4j.error(message, thrown) else slf4j.error(message)
            Level.WARNING -> if (thrown != null) slf4j.warn(message, thrown) else slf4j.warn(message)
            Level.INFO -> if (thrown != null) slf4j.info(message, thrown) else slf4j.info(message)
            Level.CONFIG, Level.FINE -> if (thrown != null) slf4j.debug(message, thrown) else slf4j.debug(message)
            else -> if (thrown != null) slf4j.trace(message, thrown) else slf4j.trace(message)
        }
    }

    override fun log(level: Level, msg: String?) {
        if (msg == null) return
        val message = toAnsi(msg)
        when (level) {
            Level.SEVERE -> slf4j.error(message)
            Level.WARNING -> slf4j.warn(message)
            Level.INFO -> slf4j.info(message)
            Level.CONFIG, Level.FINE -> slf4j.debug(message)
            else -> slf4j.trace(message)
        }
    }

    override fun log(level: Level, msg: String?, thrown: Throwable?) {
        if (msg == null) return
        val message = toAnsi(msg)
        when (level) {
            Level.SEVERE -> slf4j.error(message, thrown)
            Level.WARNING -> slf4j.warn(message, thrown)
            Level.INFO -> slf4j.info(message, thrown)
            Level.CONFIG, Level.FINE -> slf4j.debug(message, thrown)
            else -> slf4j.trace(message, thrown)
        }
    }

    override fun log(level: Level, msgSupplier: Supplier<String>?) {
        val msg = msgSupplier?.get() ?: return
        log(level, msg)
    }

    override fun info(msg: String?) {
        if (msg != null) slf4j.info(toAnsi(msg))
    }

    override fun warning(msg: String?) {
        if (msg != null) slf4j.warn(toAnsi(msg))
    }

    override fun severe(msg: String?) {
        if (msg != null) slf4j.error(toAnsi(msg))
    }

    override fun fine(msg: String?) {
        if (msg != null) slf4j.debug(toAnsi(msg))
    }

    override fun isLoggable(level: Level): Boolean = true
}
