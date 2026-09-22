package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `TRANSFER_PULL` - Pano wants this file streamed to it under [ticket] (AGENT.md 2.4.4).
 *
 * [path] is relative to the server directory, or `@backup/<id>` for one of this server's own
 * backups, which live outside the paths the file manager can reach.
 *
 * [paths], when present and not empty, turns the pull into a download of several things at once:
 * [path] is then the directory the selection was made in (`""` for the server directory) and
 * every entry of [paths] is a full server-relative path inside it, file or directory. What goes
 * up is a ZIP whose entry names are relative to [path]. Optional so a Pano that predates it keeps
 * talking to this plugin exactly as before.
 */
data class TransferPullMessage(
    val eventId: String? = null,
    val ticket: String? = null,
    val path: String? = null,
    val paths: List<String>? = null
) : PlatformMessage

/**
 * `TRANSFER_PUSH` - Pano has an uploaded file waiting under [ticket]; fetch it and write it to
 * [path].
 *
 * [size] is what the browser said it uploaded, checked before anything is fetched so an
 * oversized upload costs one refusal rather than a gigabyte of disk.
 */
data class TransferPushMessage(
    val eventId: String? = null,
    val ticket: String? = null,
    val path: String? = null,
    val size: Long? = null
) : PlatformMessage
