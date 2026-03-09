package com.panomc.plugins.pano.core.mcping

import java.awt.image.BufferedImage

/** Result of a Minecraft status ping. */
data class McStatus(
    val versionName: String? = null,
    val protocol: Int? = null,
    val playersOnline: Int? = null,
    val playersMax: Int? = null,
    /** Raw "description" (chat component) JSON or a simple string, as returned by the server. */
    val descriptionJson: String? = null,
    /** Full data URL, e.g. "data:image/png;base64,..." if present. */
    val faviconDataUrl: String? = null,
    /** Decoded favicon image (PNG) if present and decodable. */
    val faviconImage: BufferedImage? = null,
    /** Round-trip latency in milliseconds (if measured). */
    val latencyMs: Long? = null
)