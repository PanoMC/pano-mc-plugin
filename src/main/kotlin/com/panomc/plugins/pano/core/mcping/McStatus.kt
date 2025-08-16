package com.panomc.plugins.pano.core.mcping

import java.awt.image.BufferedImage

/** Result of a Minecraft status ping. */
data class McStatus(
    val versionName: String?,
    val protocol: Int?,
    val playersOnline: Int?,
    val playersMax: Int?,
    /** Raw "description" (chat component) JSON or a simple string, as returned by the server. */
    val descriptionJson: String?,
    /** Full data URL, e.g. "data:image/png;base64,..." if present. */
    val faviconDataUrl: String?,
    /** Decoded favicon image (PNG) if present and decodable. */
    val faviconImage: BufferedImage?,
    /** Round-trip latency in milliseconds (if measured). */
    val latencyMs: Long?
)