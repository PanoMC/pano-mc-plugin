package com.panomc.plugins.pano.core.platform

/**
 * Optional features a connected server can tell the platform it supports.
 *
 * The plugin announces the [id]s of the capabilities it implements on connect and the platform
 * only offers the matching panel features for those. Ids are part of the wire protocol, so they
 * must stay stable; unknown ids are ignored by the platform instead of failing the handshake.
 */
enum class Capability(val id: String) {
    /** Streams server log lines to Pano. */
    CONSOLE("console"),

    /** Executes console commands sent by Pano. */
    COMMANDS("commands"),

    /** Can stop or restart the server on request. */
    POWER("power"),

    /** Reports TPS, MSPT, memory usage and the player roster. */
    METRICS("metrics"),

    /** Exposes the online player roster with UUIDs and supports player actions. */
    PLAYERS("players"),

    /** Lists the plugins or mods installed on the server. */
    PLUGINS("plugins"),

    /** Answers the file manager from inside the server directory (AGENT.md 2.4.17 C). */
    FILES("files"),

    /** Takes backups of the server directory, and applies a restore on the next start. */
    BACKUPS("backups"),

    /** Downloads a plugin or mod jar Pano picked out into `plugins/` or `mods/`. */
    PLUGIN_INSTALL("plugin-install"),

    /** Runs this server's schedules itself, so they still happen while Pano is away. */
    SCHEDULES("schedules"),

    /**
     * Replaces its own jar with a newer build Pano sends (`PANO_PLUGIN_UPDATE`, AGENT.md B3).
     *
     * Not listed by the platform mains: `PlatformManager` adds it on connect whenever the plugin can
     * name the jar it runs from, because that - not the platform - is what decides whether there is
     * anything to replace.
     */
    SELF_UPDATE("self-update")
}
