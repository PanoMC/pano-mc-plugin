package com.panomc.plugins.pano.core.console

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Attaches a log4j2 appender to the running server's root logger without ever linking log4j-core
 * at compile time.
 *
 * Paper/Folia, Velocity and Fabric all render their console through log4j-core, but none of them
 * exposes it as a plugin/mod compile dependency, so the appender is built as a
 * [java.lang.reflect.Proxy] over the `org.apache.logging.log4j.core.Appender` interface loaded
 * from the classloader the running `LoggerContext` came from. That is the same
 * enter-through-the-API-then-resolve-the-implementation trick
 * [com.panomc.plugins.pano.core.util.LoggerUtil] uses, and for the same reason: resolving
 * `org.apache.logging.log4j.core.*` through this class's own loader would tie the whole feature
 * to servers that happen to expose log4j-core to plugins.
 *
 * Every call arrives on whatever thread logged the line, so the proxy does the absolute minimum:
 * copy the fields out of the (possibly reused, mutable) `LogEvent` and hand them to the sink,
 * which only enqueues. Every accessor is resolved once, on the public `LogEvent`/`Message`
 * interfaces rather than on the concrete event class, so no `setAccessible` call is needed and a
 * package-private event implementation cannot break the lookup.
 */
object Log4j2ConsoleCapture {
    private const val APPENDER_NAME = "PanoConsoleCapture"

    /**
     * Installs the appender and returns a handle that removes it again, or `null` when the server
     * is not running on log4j-core (or its internals moved) - in which case the caller simply has
     * no console capture rather than a broken one.
     */
    fun install(sink: (ConsoleLine) -> Unit): AutoCloseable? {
        return try {
            val apiLogManagerClass = Class.forName("org.apache.logging.log4j.LogManager")
            val context = apiLogManagerClass
                .getMethod("getContext", Boolean::class.javaPrimitiveType)
                .invoke(null, false) ?: return null

            // getConfiguration() only exists on log4j-core's LoggerContext, so this doubles as the
            // "is the implementation really log4j-core?" test (see LoggerUtil.trySetLog4j2).
            val configuration = context.javaClass.getMethod("getConfiguration").invoke(context) ?: return null

            val implLoader = context.javaClass.classLoader
            val appenderClass = Class.forName("org.apache.logging.log4j.core.Appender", false, implLoader)
            val filterClass = Class.forName("org.apache.logging.log4j.core.Filter", false, implLoader)
            val levelClass = Class.forName("org.apache.logging.log4j.Level", false, implLoader)
            val errorHandlerClass =
                Class.forName("org.apache.logging.log4j.core.ErrorHandler", false, implLoader)
            val logEventClass = Class.forName("org.apache.logging.log4j.core.LogEvent", false, implLoader)
            val messageClass = Class.forName("org.apache.logging.log4j.message.Message", false, implLoader)

            val accessors = LogEventAccessors(
                timeMillis = logEventClass.getMethod("getTimeMillis"),
                level = logEventClass.getMethod("getLevel"),
                message = logEventClass.getMethod("getMessage"),
                thrown = logEventClass.getMethod("getThrown"),
                formattedMessage = messageClass.getMethod("getFormattedMessage"),
                levelName = try {
                    levelClass.getMethod("name")
                } catch (_: Throwable) {
                    null
                }
            )

            val startedState = try {
                Class.forName("org.apache.logging.log4j.core.LifeCycle\$State", false, implLoader)
                    .getField("STARTED")
                    .get(null)
            } catch (_: Throwable) {
                null
            }

            val errorHandler = Proxy.newProxyInstance(
                implLoader,
                arrayOf(errorHandlerClass)
            ) { _, method, _ -> defaultReturnValue(method) }

            val appender = Proxy.newProxyInstance(
                implLoader,
                arrayOf(appenderClass),
                AppenderInvocationHandler(APPENDER_NAME, startedState, errorHandler, accessors, sink)
            )

            val rootLoggerConfig = configuration.javaClass.getMethod("getRootLogger").invoke(configuration)
                ?: return null

            rootLoggerConfig.javaClass
                .getMethod("addAppender", appenderClass, levelClass, filterClass)
                .invoke(rootLoggerConfig, appender, null, null)

            context.javaClass.getMethod("updateLoggers").invoke(context)

            AutoCloseable {
                try {
                    rootLoggerConfig.javaClass
                        .getMethod("removeAppender", String::class.java)
                        .invoke(rootLoggerConfig, APPENDER_NAME)

                    context.javaClass.getMethod("updateLoggers").invoke(context)
                } catch (_: Throwable) {
                    // Nothing useful to do on the way down: the plugin is being disabled and the
                    // sink behind this appender is already inert by then.
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private class LogEventAccessors(
        val timeMillis: Method,
        val level: Method,
        val message: Method,
        val thrown: Method,
        val formattedMessage: Method,
        val levelName: Method?
    )

    /**
     * Answers the whole `Appender`/`LifeCycle` surface by method name instead of by a compiled
     * interface, so a log4j-core release that adds or renames a method degrades to
     * [defaultReturnValue] rather than throwing out of a logging call.
     */
    private class AppenderInvocationHandler(
        private val name: String,
        private val startedState: Any?,
        private val errorHandler: Any,
        private val accessors: LogEventAccessors,
        private val sink: (ConsoleLine) -> Unit
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "append" -> {
                    args?.firstOrNull()?.let { append(it) }
                    null
                }

                "getName" -> name
                "getLayout" -> null
                "ignoreExceptions" -> true
                // Never null: log4j's AppenderControl dereferences getHandler() the moment an
                // append throws, so returning null there would turn a dropped line into an NPE
                // inside the caller's logging call.
                "getHandler" -> errorHandler
                "setHandler" -> null
                "getState" -> startedState
                "isStarted" -> true
                "isStopped" -> false
                "initialize", "start" -> null
                // stop() is void on LifeCycle but boolean on LifeCycle2; this proxy does not
                // declare LifeCycle2, yet answers both shapes in case a release folds them together.
                "stop" -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null

                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> name
                else -> defaultReturnValue(method)
            }
        }

        private fun append(event: Any) {
            try {
                val timestamp = (accessors.timeMillis.invoke(event) as? Number)?.toLong()
                    ?: System.currentTimeMillis()
                val level = mapLevel(accessors.level.invoke(event))
                val message = accessors.message.invoke(event)
                    ?.let { accessors.formattedMessage.invoke(it) as? String }
                val thrown = accessors.thrown.invoke(event) as? Throwable

                ConsoleLines.toLines(timestamp, level, message, thrown).forEach(sink)
            } catch (_: Throwable) {
                // An appender that throws poisons every log call on the server; a dropped console
                // line is the only acceptable failure mode here.
            }
        }

        private fun mapLevel(level: Any?): ConsoleLevel {
            if (level == null) {
                return ConsoleLevel.INFO
            }

            val name = try {
                (accessors.levelName?.invoke(level) as? String) ?: level.toString()
            } catch (_: Throwable) {
                return ConsoleLevel.INFO
            }

            return when (name.uppercase()) {
                "FATAL", "ERROR" -> ConsoleLevel.ERROR
                "WARN" -> ConsoleLevel.WARN
                "DEBUG" -> ConsoleLevel.DEBUG
                "TRACE", "ALL" -> ConsoleLevel.TRACE
                else -> ConsoleLevel.INFO
            }
        }
    }

    /** A type-correct "nothing" for a method this proxy does not model. */
    private fun defaultReturnValue(method: Method): Any? = when (method.returnType) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
