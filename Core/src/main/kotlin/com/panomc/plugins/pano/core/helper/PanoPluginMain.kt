package com.panomc.plugins.pano.core.helper

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.console.ConsoleLine
import com.panomc.plugins.pano.core.platform.Capability
import com.panomc.plugins.pano.core.platform.entity.InstalledPlugin
import com.panomc.plugins.pano.core.platform.entity.PlayerData
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import io.vertx.core.http.WebSocket
import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.logging.Logger

interface PanoPluginMain {
    fun getDataFolder(): File

    /**
     * This server's own directory: the one holding `server.properties`, `plugins/` and the worlds.
     *
     * The root of everything the file manager, the backups and the schedules work with (AGENT.md
     * 2.4.17 C), and the anchor [getLogDirectory] hangs off.
     *
     * The default is the working directory, which is where a Minecraft server, a Velocity or
     * BungeeCord proxy and a Fabric run directory all live when they are started the ordinary way.
     * Each platform overrides it with the directory it actually knows about, because a server
     * started by a panel or a wrapper script often runs from somewhere else entirely.
     */
    fun getServerDirectory(): File = File(".")

    /**
     * The directory this server writes its log files to, i.e. the one holding `latest.log`.
     *
     * Read on demand by [com.panomc.plugins.pano.core.console.ServerLogTail] when Pano asks for
     * console history (AGENT.md 2.4.14); nothing else ever touches it, and nothing is kept
     * between requests.
     *
     * `logs` under [getServerDirectory] on every platform, which is where all four put it - so a
     * platform only has to say where it lives, once.
     */
    fun getLogDirectory(): File = File(getServerDirectory(), "logs")

    /**
     * Where this server loads plugins or mods from, relative to [getServerDirectory].
     *
     * `plugins` everywhere except the mod loaders, and the default a plugin install falls back to
     * when Pano names no target directory of its own.
     */
    fun getPluginDirectoryName(): String = "plugins"

    /**
     * The jar this plugin was loaded from, which a self-update replaces (AGENT.md B3).
     *
     * The default asks the main class's code source, which every platform answers the same way for
     * a plugin loaded from a jar. A platform overrides it where it knows better - the Bukkit family,
     * where Paper may be running a remapped copy rather than the file in `plugins/`, and Fabric,
     * whose loader can name a mod's origin directly. Null means "cannot tell", and a plugin that
     * cannot tell does not announce `self-update` at all.
     */
    fun getOwnJarFile(): File? = com.panomc.plugins.pano.core.update.PanoSelfUpdate.jarOf(javaClass)

    /**
     * Bukkit's `plugins/update/` directory, or null on a platform without that mechanism.
     *
     * A jar there named like a loaded plugin's jar replaces it at the next boot, before anything has
     * loaded it - the safest possible moment for a swap, so a self-update prefers it wherever it
     * exists and only falls back to swapping its own jar on shutdown where it does not.
     */
    fun getUpdateFolder(): File? = null

    /**
     * Stops or resumes world autosaving, on the server's main thread, flushing on the way out.
     *
     * Called around a backup so the copy is of a world that was consistent at some point, rather
     * than of one caught halfway through a chunk write (AGENT.md 2.4.17 C). Returns whether this
     * platform did anything: both proxies have no worlds at all, so the default is "nothing to
     * do" and a backup of them is simply a copy of what is on disk.
     *
     * Asynchronous like every other main-thread hop here: the caller gives the server a moment to
     * finish writing rather than waiting on an acknowledgement the server never sends.
     */
    fun setWorldSaving(enabled: Boolean): Boolean = false

    fun getPanoLogger(): Logger

    fun getPano(): Pano

    fun registerCommands(commands: List<Command>)

    fun unregisterCommands(commands: List<Command>)

    fun getServerData(): ServerData

    fun getPluginClassLoader(): URLClassLoader

    fun translateColor(text: String): String

    fun registerEventListeners(listeners: Set<Listener>)

    fun unregisterEventListeners(listeners: Set<Listener>)

    fun onConnectionEstablished(webSocket: WebSocket?) {}

    fun onDisconnect() {}

    /**
     * Optional protocol features this platform implementation supports.
     *
     * The ids are announced to the platform on connect. The default is "nothing extra", so a
     * platform main that has not implemented any of them compiles and behaves unchanged.
     */
    fun getCapabilities(): Set<Capability> = emptySet()

    fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {}

    fun onPermissionsSnapshotUpdated(message: com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage) {}

    /**
     * Starts feeding every line this server logs into [sink], returning a handle that removes the
     * capture again, or `null` when this platform cannot capture its console.
     *
     * [sink] is called on whatever thread produced the line - the server main thread included -
     * so an implementation must only hand the line over and never block, log or do I/O.
     *
     * The returned handle is closed when the plugin is disabled; leaving an appender or handler
     * attached to a logger that outlives the plugin would keep the old classloader alive.
     */
    fun installConsoleCapture(sink: (ConsoleLine) -> Unit): AutoCloseable? = null

    /**
     * Runs [command] as the server console, on the server's main thread.
     *
     * The command arrives without a leading slash and is passed to the platform's own dispatcher
     * as data - never through a shell (AGENT.md 2.7).
     */
    fun dispatchConsoleCommand(command: String) {}

    /**
     * Ticks per second averaged over the last 1, 5 and 15 minutes, or `null` on a platform where
     * the concept does not exist (both proxies) or the figure cannot be obtained.
     */
    fun getTps(): DoubleArray? = null

    /**
     * Mean milliseconds spent per tick, or `null` when the platform does not measure it.
     *
     * Deliberately separate from [getTps]: a server can be at a healthy 20 TPS and still be one
     * bad plugin away from lagging, which only the tick time shows.
     */
    fun getMspt(): Double? = null

    /** The players online right now, with their ping. Empty on a platform that has no roster. */
    fun getOnlinePlayers(): List<PlayerData> = emptyList()

    /**
     * Stops the server, on its main thread.
     *
     * Default is a no-op so a platform that has not implemented power control simply ignores the
     * request; it never announces the `power` capability either, so Pano does not offer the
     * buttons in the first place.
     */
    fun shutdown() {}

    /**
     * Restarts the server, on its main thread.
     *
     * Only the Bukkit family can really do this (Spigot's `restart-script`); everywhere else a
     * restart is a stop plus whatever wrapper loop the host has, so the default falls back to
     * [shutdown] and each implementation logs that it did.
     */
    fun restart() {
        shutdown()
    }

    /** Every plugin or mod this server has loaded. Empty on a platform that cannot enumerate them. */
    fun getInstalledPlugins(): List<InstalledPlugin> = emptyList()

    /**
     * Enables or disables the plugin called [name], returning whether the request was accepted.
     *
     * "Accepted" rather than "applied": the toggle itself runs on the server's main thread, so
     * all this can report is that the plugin exists and that toggling it is allowed. Pano learns
     * the outcome from the `INSTALLED_PLUGINS` list that follows.
     *
     * Only the Bukkit family implements this; everywhere else it stays false.
     */
    fun setPluginEnabled(name: String, enabled: Boolean): Boolean = false

    fun kickPlayer(player: String, message: String)

    /**
     * Shows [message] - raw '&'-coded, like [kickPlayer]'s - to one online player in chat,
     * returning whether that player was found. [uuid] is tried first and [username] after it, so
     * a proxy whose ids differ from the backend's still finds the right person.
     */
    fun sendPlayerMessage(uuid: String, username: String, message: String): Boolean = false

    /**
     * UUID used to store a player in LuckPerms *before* they are seen by the server (no LP / Mojang row yet).
     * This must match the underlying server’s "offline" or never-joined profile id: same as
     * `Bukkit.getOfflinePlayer(name).getUniqueId()` on Spigot. Default uses the standard
     * [UUID v3] over `"OfflinePlayer:<name>"` UTF-8 bytes (vanilla offline-mode algorithm).
     *
     * [Online mode]: If a premium player later connects, their *real* Mojang UUID may differ;
     * in that case LuckPerms should be updated when they first join. Until then, this id lets
     * Pano’s panel permissions apply a concrete LP user entry.
     */
    fun getNeverJoinedPlayerUniqueId(minecraftName: String): UUID {
        return UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + minecraftName).toByteArray(StandardCharsets.UTF_8)
        )
    }
}