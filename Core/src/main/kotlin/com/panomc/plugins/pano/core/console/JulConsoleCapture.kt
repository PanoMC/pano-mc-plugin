package com.panomc.plugins.pano.core.console

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/**
 * Attaches a `java.util.logging` handler to a logger, for the one platform whose console is not
 * log4j-core: BungeeCord, which logs through its own JUL logger plus jline.
 *
 * Plugin loggers on BungeeCord inherit from the proxy logger, so a handler installed on
 * `ProxyServer.getLogger()` sees the proxy's own lines and every plugin's.
 */
object JulConsoleCapture {
    /** Installs the handler on [logger] and returns a handle that removes it again. */
    fun install(logger: Logger, sink: (ConsoleLine) -> Unit): AutoCloseable {
        val handler = SinkHandler(sink)

        logger.addHandler(handler)

        return AutoCloseable {
            try {
                logger.removeHandler(handler)
            } catch (_: Throwable) {
                // Nothing useful to do while the plugin is being disabled.
            }
        }
    }

    private class SinkHandler(private val sink: (ConsoleLine) -> Unit) : Handler() {
        // Only used for formatMessage(): it resolves the record's resource bundle and its
        // {0}-style parameters, which record.message on its own does not.
        private val messageFormatter = SimpleFormatter()

        init {
            level = Level.ALL
        }

        override fun publish(record: LogRecord?) {
            if (record == null) {
                return
            }

            try {
                val message = try {
                    messageFormatter.formatMessage(record)
                } catch (_: Throwable) {
                    record.message
                }

                ConsoleLines.toLines(record.millis, mapLevel(record.level), message, record.thrown)
                    .forEach(sink)
            } catch (_: Throwable) {
                // A handler that throws would break the proxy's own logging; drop the line instead.
            }
        }

        override fun flush() {}

        override fun close() {}

        private fun mapLevel(level: Level?): ConsoleLevel {
            val value = level?.intValue() ?: Level.INFO.intValue()

            return when {
                value >= Level.SEVERE.intValue() -> ConsoleLevel.ERROR
                value >= Level.WARNING.intValue() -> ConsoleLevel.WARN
                value >= Level.CONFIG.intValue() -> ConsoleLevel.INFO
                value >= Level.FINE.intValue() -> ConsoleLevel.DEBUG
                else -> ConsoleLevel.TRACE
            }
        }
    }
}
