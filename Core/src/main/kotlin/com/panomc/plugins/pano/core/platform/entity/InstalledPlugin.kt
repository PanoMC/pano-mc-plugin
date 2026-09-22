package com.panomc.plugins.pano.core.platform.entity

/**
 * One plugin or mod installed on this server, as Pano's plugin list shows it.
 *
 * [enabled] is only ever false on the Bukkit family - Velocity, BungeeCord and Fabric have no
 * concept of a loaded-but-disabled plugin, so their lists are read-only and always enabled.
 * [file] is the file name only, never a path: Pano has no business knowing the server's directory
 * layout, and this is used to match a plugin against a Pano resource, not to reach the file.
 */
data class InstalledPlugin(
    val name: String,
    val version: String,
    val authors: List<String>,
    val description: String?,
    val enabled: Boolean,
    val file: String?
)
