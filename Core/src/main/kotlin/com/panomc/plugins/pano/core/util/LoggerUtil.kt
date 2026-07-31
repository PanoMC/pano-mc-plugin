@file:Suppress("UNCHECKED_CAST")

package com.panomc.plugins.pano.core.util


import java.util.logging.Level as JULLevel

/**
 * Utility object to safely adjust logger levels across various logging frameworks.
 *
 * Supports:
 * - Log4j2 (Paper / Folia)
 * - Logback (Velocity / BungeeCord)
 * - java.util.logging (Spigot)
 *
 * It will automatically detect which logging system is available and apply
 * the desired level without throwing exceptions if others are missing.
 */
object LoggerUtil {

    /**
     * Sets the log level for a specific logger name across known logging frameworks.
     *
     * Every backend that is actually present is updated, rather than stopping at the first one
     * that accepts the change: a caller cannot know which backend the log line it wants to quiet
     * is really routed through (Vert.x's own LoggerFactory prefers SLF4J whenever that is on the
     * classpath, and the SLF4J binding is then Log4j2 on Paper/Velocity/Fabric but Logback
     * elsewhere), and setting a level for a logger name in an unused framework is a harmless
     * no-op.
     *
     * @param loggerName The full logger name (e.g. "io.vertx.core.http.impl.HttpClientConnectionInternal")
     * @param level The log level as string (e.g. "ERROR", "WARN", "INFO", "OFF")
     * @return true if at least one logging backend accepted the change.
     */
    fun setLoggerLevel(loggerName: String, level: String): Boolean {
        // Deliberately silent (no println): this runs during boot, and a utility whose whole job
        // is to remove log noise must not add its own.
        var applied = false

        if (trySetLog4j2(loggerName, level)) applied = true
        if (trySetLogback(loggerName, level)) applied = true
        if (trySetJavaUtilLogging(loggerName, level)) applied = true

        return applied
    }

    // --- Log4j2 ---
    private fun trySetLog4j2(loggerName: String, level: String): Boolean {
        return try {
            val log4jContextClass = Class.forName("org.apache.logging.log4j.core.LoggerContext")
            val context = log4jContextClass.getMethod("getContext", Boolean::class.javaPrimitiveType)
                .invoke(null, false)
            val config = context.javaClass.getMethod("getConfiguration").invoke(context)
            val loggerConfigClass = Class.forName("org.apache.logging.log4j.core.config.LoggerConfig")
            val levelClass = Class.forName("org.apache.logging.log4j.Level")
            val desiredLevel = levelClass.getField(level.uppercase()).get(null)

            val getLoggerConfig = config.javaClass.getMethod("getLoggerConfig", String::class.java)
            var loggerConfig = getLoggerConfig.invoke(config, loggerName)
            val loggerConfigName =
                loggerConfig.javaClass.getMethod("getName").invoke(loggerConfig) as String

            if (loggerConfigName != loggerName) {
                val ctor = loggerConfigClass.getConstructor(
                    String::class.java,
                    levelClass,
                    Boolean::class.javaPrimitiveType
                )
                val newLoggerConfig = ctor.newInstance(loggerName, desiredLevel, false)
                config.javaClass.getMethod("addLogger", String::class.java, loggerConfigClass)
                    .invoke(config, loggerName, newLoggerConfig)
            } else {
                loggerConfig.javaClass.getMethod("setLevel", levelClass)
                    .invoke(loggerConfig, desiredLevel)
            }

            context.javaClass.getMethod("updateLoggers").invoke(context)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
    }

    // --- Logback / SLF4J ---
    private fun trySetLogback(loggerName: String, level: String): Boolean {
        return try {
            val loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory")
            val getLogger = loggerFactoryClass.getMethod("getLogger", String::class.java)
            val slf4jLogger = getLogger.invoke(null, loggerName)

            val logbackLoggerClass = Class.forName("ch.qos.logback.classic.Logger")
            if (logbackLoggerClass.isInstance(slf4jLogger)) {
                val logbackLogger = logbackLoggerClass.cast(slf4jLogger)
                val levelClass = Class.forName("ch.qos.logback.classic.Level")
                val desiredLevel = levelClass.getField(level.uppercase()).get(null)
                logbackLoggerClass.getMethod("setLevel", levelClass)
                    .invoke(logbackLogger, desiredLevel)
                true
            } else false
        } catch (_: ClassNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
    }

    // --- java.util.logging ---
    private fun trySetJavaUtilLogging(loggerName: String, level: String): Boolean {
        return try {
            val julLogger = java.util.logging.Logger.getLogger(loggerName)
            val julLevel = when (level.uppercase()) {
                "OFF" -> JULLevel.OFF
                "ERROR", "SEVERE" -> JULLevel.SEVERE
                "WARN", "WARNING" -> JULLevel.WARNING
                "INFO" -> JULLevel.INFO
                "DEBUG", "FINE" -> JULLevel.FINE
                "TRACE", "FINER", "FINEST" -> JULLevel.FINEST
                else -> JULLevel.INFO
            }
            julLogger.level = julLevel
            true
        } catch (t: Throwable) {
            false
        }
    }
}
