package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.ServerType

object SpigotServerUtil {

    fun detectServerType(): ServerType {
        return try {
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