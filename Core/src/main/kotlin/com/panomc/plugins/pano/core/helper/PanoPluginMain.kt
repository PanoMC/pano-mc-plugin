package com.panomc.plugins.pano.core.helper

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import io.vertx.core.http.WebSocket
import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.logging.Logger

interface PanoPluginMain {
    fun getDataFolder(): File

    fun getPanoLogger(): Logger

    fun getPano(): Pano

    fun registerCommands(commands: List<Command>)

    fun unregisterCommands(commands: List<Command>)

    fun registerSchedule(task: () -> Unit)

    fun stopSchedule(task: () -> Unit)

    fun unregisterSchedules(tasks: List<() -> Unit>)

    fun getServerData(): ServerData

    fun getPluginClassLoader(): URLClassLoader

    fun translateColor(text: String): String

    fun registerEventListeners(listeners: Set<Listener>)

    fun unregisterEventListeners(listeners: Set<Listener>)

    fun onConnectionEstablished(webSocket: WebSocket?) {}

    fun onDisconnect() {}

    fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {}

    fun onPermissionsSnapshotUpdated(message: com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage) {}

    fun kickPlayer(player: String, message: String)

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