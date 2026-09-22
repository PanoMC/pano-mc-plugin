package com.panomc.plugins.pano.core.files

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.platform.entity.InstalledPlugin
import com.panomc.plugins.pano.core.platform.entity.PlayerData
import java.io.File
import java.net.URLClassLoader
import java.util.logging.Logger

/**
 * A [PanoPluginMain] with a directory and a plugin list, and nothing else.
 *
 * The file manager, the backups and the schedules only ever ask their platform three things -
 * where the server lives, what it has loaded and how to reach its main thread - so a double that
 * answers those is enough to test all of them without a Minecraft server in the loop.
 */
class TestPluginMain(
    private val serverDirectory: File,
    private val plugins: List<InstalledPlugin> = emptyList()
) : PanoPluginMain {
    /** Commands this main was asked to run, in order, instead of a server to run them on. */
    val dispatched = mutableListOf<String>()

    /** Every `setWorldSaving` call, so a backup can be checked for pausing and resuming. */
    val worldSaving = mutableListOf<Boolean>()

    var shutdowns = 0
        private set

    var restarts = 0
        private set

    override fun getServerDirectory(): File = serverDirectory

    override fun getDataFolder(): File = File(serverDirectory, "plugins/Pano").apply { mkdirs() }

    override fun getPanoLogger(): Logger = Logger.getLogger("pano-test")

    override fun getPano(): Pano = throw UnsupportedOperationException("No Pano in a unit test.")

    override fun registerCommands(commands: List<Command>) = Unit

    override fun unregisterCommands(commands: List<Command>) = Unit

    override fun getServerData(): ServerData = throw UnsupportedOperationException("No server in a unit test.")

    override fun getPluginClassLoader(): URLClassLoader = URLClassLoader(emptyArray())

    override fun translateColor(text: String): String = text

    override fun registerEventListeners(listeners: Set<Listener>) = Unit

    override fun unregisterEventListeners(listeners: Set<Listener>) = Unit

    override fun getInstalledPlugins(): List<InstalledPlugin> = plugins

    override fun dispatchConsoleCommand(command: String) {
        dispatched.add(command)
    }

    override fun setWorldSaving(enabled: Boolean): Boolean {
        worldSaving.add(enabled)

        return true
    }

    override fun shutdown() {
        shutdowns++
    }

    override fun restart() {
        restarts++
    }

    /** Who this main was asked to kick, as `username to message`, instead of kicking anyone. */
    val kicked = mutableListOf<Pair<String, String>>()

    /** Chat lines this main was asked to show, as `username to message`. */
    val messaged = mutableListOf<Pair<String, String>>()

    /** The roster [getOnlinePlayers] answers with; a test changes it to simulate `/op` and co. */
    var roster: List<PlayerData> = emptyList()

    override fun getOnlinePlayers(): List<PlayerData> = roster

    override fun kickPlayer(player: String, message: String) {
        kicked.add(player to message)
    }

    override fun sendPlayerMessage(uuid: String, username: String, message: String): Boolean {
        if (roster.none { it.uuid == uuid || it.username == username }) {
            return false
        }

        messaged.add(username to message)

        return true
    }

    companion object {
        /** One loaded plugin, named after the jar it was loaded from. */
        fun plugin(file: String) = InstalledPlugin(
            name = file.removeSuffix(".jar"),
            version = "1.0.0",
            authors = emptyList(),
            description = null,
            enabled = true,
            file = file
        )
    }
}
