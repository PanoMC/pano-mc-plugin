package com.panomc.plugins.pano.core.platform

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

/**
 * The public, event based surface for OTHER plugins on the same server (e.g. Pano Market's in-game component), independent of
 * any one [PlatformManager]: Pano builds a new manager whenever it re-initialises itself, and what is registered here stays.
 *
 * - [registerMessageHandler]: receive the messages the platform pushes under the handler's event name. Names of the built-in
 *   handlers are never taken over.
 * - [addConnectionListener]: told `true` once the connection to the platform is up and its handshake is done (requests may
 *   be sent from then on), `false` when it is gone. A new listener is told the current state at once.
 * - [playerLocale]: the language a player chose on the website, learned when they logged in and kept until they leave.
 *
 * Listeners and handlers run on the connection's thread: they must hand real work to their own thread at once.
 */
object PlatformExtensions {
    /** Raised when the features of this object change in a way a plugin must check before using them. */
    const val API_VERSION = 1

    private val handlers = ConcurrentHashMap<String, PlatformMessageHandler<*>>()
    private val playerLocales = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val logger = Logger.getLogger("Pano")

    @Volatile
    private var connected = false

    /** Whether the platform connection is up (handshake done) right now. */
    fun isConnected(): Boolean = connected

    /**
     * Receives the pushes whose event is [PlatformMessageHandler.getHandlerName]; a handler of the same name is replaced. `false`
     * for the name of a built-in handler.
     */
    fun registerMessageHandler(handler: PlatformMessageHandler<*>): Boolean {
        val name = handler.getHandlerName()

        if (name in PlatformManager.BUILT_IN_HANDLER_NAMES) return false

        handlers[name] = handler

        return true
    }

    /** Removes [handler] (only that instance: a newer one of the same name stays). */
    fun unregisterMessageHandler(handler: PlatformMessageHandler<*>): Boolean = handlers.remove(handler.getHandlerName(), handler)

    fun addConnectionListener(listener: (connected: Boolean) -> Unit) {
        listeners += listener

        notify(listener, connected)
    }

    fun removeConnectionListener(listener: (connected: Boolean) -> Unit) {
        listeners -= listener
    }

    /**
     * The language of [username]'s Pano account (`tr`, `en-US`), from the platform's answer to the player lookup this plugin makes
     * at every login; kept until the player leaves. `null` for a player without an account, one who logged in while the platform was
     * not reachable, or after they left.
     */
    fun playerLocale(username: String): String? = playerLocales[username.trim().lowercase()]

    /** What the platform said about [username] at the login; a blank or missing language forgets the old one. */
    fun rememberPlayer(username: String, locale: String?) {
        val key = username.trim().lowercase()

        if (key.isEmpty()) return

        val clean = locale?.trim()?.takeIf { it.isNotEmpty() && it.length <= 16 }

        if (clean == null) playerLocales.remove(key) else playerLocales[key] = clean
    }

    /** The player left this server: nothing about them is kept. */
    fun forgetPlayer(username: String) {
        playerLocales.remove(username.trim().lowercase())
    }

    internal fun handler(event: String): PlatformMessageHandler<*>? = handlers[event]

    /** Called by the manager; only a real change is passed on. */
    @Synchronized
    internal fun connectionChanged(isConnected: Boolean) {
        if (connected == isConnected) return

        connected = isConnected
        listeners.forEach { notify(it, isConnected) }
    }

    private fun notify(listener: (Boolean) -> Unit, value: Boolean) {
        try {
            listener(value)
        } catch (e: Exception) {
            logger.warning("A connection listener of another plugin failed: ${e.message}")
        }
    }
}
