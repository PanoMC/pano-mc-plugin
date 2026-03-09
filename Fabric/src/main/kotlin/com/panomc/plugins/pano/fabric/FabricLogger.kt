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
 */
class FabricLogger(name: String) : Logger(name, null) {
    private val slf4j = LoggerFactory.getLogger(name)

    override fun log(record: LogRecord) {
        val message = record.message ?: return
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
        when (level) {
            Level.SEVERE -> slf4j.error(msg)
            Level.WARNING -> slf4j.warn(msg)
            Level.INFO -> slf4j.info(msg)
            Level.CONFIG, Level.FINE -> slf4j.debug(msg)
            else -> slf4j.trace(msg)
        }
    }

    override fun log(level: Level, msg: String?, thrown: Throwable?) {
        if (msg == null) return
        when (level) {
            Level.SEVERE -> slf4j.error(msg, thrown)
            Level.WARNING -> slf4j.warn(msg, thrown)
            Level.INFO -> slf4j.info(msg, thrown)
            Level.CONFIG, Level.FINE -> slf4j.debug(msg, thrown)
            else -> slf4j.trace(msg, thrown)
        }
    }

    override fun log(level: Level, msgSupplier: Supplier<String>?) {
        val msg = msgSupplier?.get() ?: return
        log(level, msg)
    }

    override fun info(msg: String?) {
        if (msg != null) slf4j.info(msg)
    }

    override fun warning(msg: String?) {
        if (msg != null) slf4j.warn(msg)
    }

    override fun severe(msg: String?) {
        if (msg != null) slf4j.error(msg)
    }

    override fun fine(msg: String?) {
        if (msg != null) slf4j.debug(msg)
    }

    override fun isLoggable(level: Level): Boolean = true
}
