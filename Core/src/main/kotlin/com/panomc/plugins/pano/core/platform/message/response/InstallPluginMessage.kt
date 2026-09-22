package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `INSTALL_PLUGIN` - put one plugin or mod jar into this server (AGENT.md 2.4.5).
 *
 * Pano resolved the download from Modrinth, Hangar or CurseForge and passes on whatever checksum
 * that source published; this side re-checks it, because Pano never sees the bytes.
 * [replaceFilename] is the jar an update supersedes, deleted only once the new one is safely in
 * place. [targetDir] is `plugins` or `mods`, and an absent one means whatever this platform loads
 * from.
 */
data class InstallPluginMessage(
    val eventId: String? = null,
    val taskId: String? = null,
    val downloadUrl: String? = null,
    val filename: String? = null,
    val targetDir: String? = null,
    val sha512: String? = null,
    val sha256: String? = null,
    val sha1: String? = null,
    val md5: String? = null,
    val replaceFilename: String? = null
) : PlatformMessage
