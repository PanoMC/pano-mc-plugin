package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.ServerType
import org.bukkit.Bukkit

object SpigotServerUtil {

    fun isFolia(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    fun detectServerType(): ServerType {
        return if (isFolia()) {
            ServerType.FOLIA
        } else {
            try {
                Class.forName("com.destroystokyo.paper.PaperConfig")
                ServerType.PAPER
            } catch (_: ClassNotFoundException) {
                val name = org.bukkit.Bukkit.getName().lowercase()
                val version = org.bukkit.Bukkit.getVersion().lowercase()
                when {
                    name.contains("paper", true) -> ServerType.PAPER
                    name.contains("spigot", true) || version.contains("spigot", true) -> ServerType.SPIGOT
                    name.contains("bukkit", true) -> ServerType.BUKKIT
                    else -> ServerType.SPIGOT
                }
            }
        }
    }

    fun getPlayerIp(playerName: String): String? {
        val player = Bukkit.getPlayer(playerName) ?: return null
        if (!player.isOnline) return null

        val address = player.address ?: return null
        return address.address?.hostAddress
    }
}