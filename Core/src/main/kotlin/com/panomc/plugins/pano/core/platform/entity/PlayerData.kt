package com.panomc.plugins.pano.core.platform.entity

/**
 * One online player as Pano's server roster shows them.
 *
 * The uuid is the string form of the player's id on this server (the proxy's id on a proxy), so
 * Pano can line the roster up with the accounts it already knows without the plugin having to
 * resolve anything itself.
 *
 * [op], [whitelisted] and [gamemode] are what the panel's player menu needs to offer "de-op"
 * rather than "op", and so on. They are null wherever the concept does not exist - a proxy has no
 * ops, no vanilla whitelist and no game mode - and additive on the wire: an older Pano ignores
 * them. [gamemode] is the vanilla name in lower case (`survival`, `creative`, ...).
 */
data class PlayerData(
    val uuid: String,
    val username: String,
    val ping: Long,
    val op: Boolean? = null,
    val whitelisted: Boolean? = null,
    val gamemode: String? = null
)
