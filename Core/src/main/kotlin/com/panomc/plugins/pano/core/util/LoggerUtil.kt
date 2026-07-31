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
            // Enter through Log4j's *API* and then resolve the implementation classes from the
            // classloader of the LoggerContext that is actually running. Resolving
            // "org.apache.logging.log4j.core.*" through this class's own loader instead would tie
            // the whole helper to the server exposing log4j-core to plugins, which several
            // platforms deliberately do not do even though log4j-core is exactly what configures
            // the console they print to.
            val apiLogManagerClass = Class.forName("org.apache.logging.log4j.LogManager")
            val context = apiLogManagerClass
                .getMethod("getContext", Boolean::class.javaPrimitiveType)
                .invoke(null, false) ?: return false

            // getConfiguration() exists on log4j-core's LoggerContext but not on the API's, so
            // this doubles as the "is the implementation really log4j-core?" test - an API-only
            // context cannot be reconfigured at all and must fall through to the other backends.
            val config = context.javaClass.getMethod("getConfiguration").invoke(context) ?: return false

            val implLoader = context.javaClass.classLoader
            val loggerConfigClass =
                Class.forName("org.apache.logging.log4j.core.config.LoggerConfig", false, implLoader)
            val levelClass = Class.forName("org.apache.logging.log4j.Level", false, implLoader)
            val desiredLevel = levelClass.getField(level.uppercase()).get(null)

            val getLoggerConfig = config.javaClass.getMethod("getLoggerConfig", String::class.java)
            val loggerConfig = getLoggerConfig.invoke(config, loggerName) ?: return false
            val loggerConfigName =
                loggerConfig.javaClass.getMethod("getName").invoke(loggerConfig) as String

            if (loggerConfigName != loggerName) {
                val ctor = loggerConfigClass.getConstructor(
                    String::class.java,
                    levelClass,
                    Boolean::class.javaPrimitiveType
                )
                // additive = true. A LoggerConfig created here owns no appenders of its own, so a
                // non-additive one would not raise the threshold for this logger - it would route
                // everything it still accepts into nowhere, silently dropping the levels the
                // caller deliberately kept. Staying additive means the surviving records reach
                // the same appenders (the server console) they always did.
                val newLoggerConfig = ctor.newInstance(loggerName, desiredLevel, true)
                config.javaClass.getMethod("addLogger", String::class.java, loggerConfigClass)
                    .invoke(config, loggerName, newLoggerConfig)
            } else {
                loggerConfig.javaClass.getMethod("setLevel", levelClass)
                    .invoke(loggerConfig, desiredLevel)
            }

            context.javaClass.getMethod("updateLoggers").invoke(context)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // --- Logback / SLF4J ---
    private fun trySetLogback(loggerName: String, level: String): Boolean {
        return try {
            val loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory")
            val getLogger = loggerFactoryClass.getMethod("getLogger", String::class.java)
            val slf4jLogger = getLogger.invoke(null, loggerName) ?: return false

            // Same classloader reasoning as trySetLog4j2: resolve Logback from the binding that is
            // actually in use instead of requiring it to be visible from this class's own loader.
            // When the binding is something else (Log4j2 on most server platforms) this throws
            // ClassNotFoundException and the branch simply reports "not mine".
            val logbackLoggerClass =
                Class.forName("ch.qos.logback.classic.Logger", false, slf4jLogger.javaClass.classLoader)

            if (!logbackLoggerClass.isInstance(slf4jLogger)) {
                return false
            }

            val levelClass =
                Class.forName("ch.qos.logback.classic.Level", false, logbackLoggerClass.classLoader)
            val desiredLevel = levelClass.getField(level.uppercase()).get(null)

            logbackLoggerClass.getMethod("setLevel", levelClass).invoke(slf4jLogger, desiredLevel)

            true
        } catch (_: Throwable) {
            false
        }
    }

    // --- java.util.logging ---
    private fun trySetJavaUtilLogging(loggerName: String, level: String): Boolean {
        return try {
            val julLogger = java.util.logging.Logger.getLogger(loggerName)

            // On a server that routes JUL into another framework (log4j-jul, jul-to-slf4j), the
            // Logger handed back is a bridge that owns no level of its own. Writing to it does
            // nothing useful - the real level lives in the framework the other branches above
            // already cover - and log4j-jul's ApiLogger answers setLevel() by printing its own
            // "Ignoring call to `j.u.l.Logger.setLevel(...)`" warning, i.e. this helper would add
            // console noise while trying to remove some. Leave a bridged JUL alone.
            if (isBridgedJavaUtilLogging(julLogger)) {
                return false
            }

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

    /**
     * Whether java.util.logging is only a front for another framework, so setting a level through
     * it would be a no-op (and, with log4j-jul, a warning on the console).
     *
     * Checked on the concrete types rather than on the `java.util.logging.manager` system property:
     * a bridge can be installed programmatically after startup, and the property is not always the
     * one the running LogManager came from.
     */
    private fun isBridgedJavaUtilLogging(julLogger: java.util.logging.Logger): Boolean {
        val loggerClassName = julLogger.javaClass.name

        if (loggerClassName.startsWith("org.apache.logging.log4j.") || loggerClassName.startsWith("org.slf4j.")) {
            return true
        }

        return try {
            java.util.logging.LogManager.getLogManager().javaClass.name
                .startsWith("org.apache.logging.log4j.")
        } catch (_: Throwable) {
            false
        }
    }
}
