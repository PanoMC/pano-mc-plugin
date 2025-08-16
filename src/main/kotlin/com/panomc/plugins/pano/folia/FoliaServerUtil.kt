package com.panomc.plugins.pano.folia

import com.panomc.plugins.pano.core.ServerType

/**
 * Utility methods for determining the server implementation when running on a
 * Bukkit based server. Folia is checked first before falling back to the
 * Paper/Spigot/Bukkit detection used for the Spigot implementation.
 */
object FoliaServerUtil {

    fun detectServerType(): ServerType {
        return try {
            // Class only present on Folia servers
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            ServerType.FOLIA
        } catch (_: ClassNotFoundException) {
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
}

