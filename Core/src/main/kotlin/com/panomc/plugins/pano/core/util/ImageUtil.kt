package com.panomc.plugins.pano.core.util

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

object ImageUtil {
    /**
     * Whitelist of allowed image MIME types.
     * SVG and other non-raster formats are intentionally excluded for security (XSS prevention).
     */
    private val ALLOWED_IMAGE_MIME_TYPES = setOf(
        "image/png",
        "image/jpeg",
        "image/webp",
        "image/gif"
    )

    /**
     * Whitelist of allowed ImageIO format names (used in bufferedImageToDataUrl).
     */
    private val ALLOWED_IMAGE_FORMATS = setOf("png", "jpeg", "jpg", "webp", "gif")

    fun bufferedImageToDataUrl(image: BufferedImage, format: String = "png"): String {
        val safeFormat = format.lowercase()
        require(safeFormat in ALLOWED_IMAGE_FORMATS) {
            "Unsupported image format: $format. Allowed formats: $ALLOWED_IMAGE_FORMATS"
        }

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, safeFormat, outputStream)
        val imageBytes = outputStream.toByteArray()
        val base64 = Base64.getEncoder().encodeToString(imageBytes)

        val mimeType = when (safeFormat) {
            "jpg" -> "jpeg"
            else -> safeFormat
        }
        return "data:image/$mimeType;base64,$base64"
    }

    /**
     * Check whether a data URL uses an allowed image MIME type.
     * Returns true only for data:image/(png|jpeg|webp|gif);base64,... URLs.
     */
    fun isAllowedImageDataUrl(dataUrl: String): Boolean {
        if (!dataUrl.startsWith("data:")) return false

        val mimeEnd = dataUrl.indexOf(';')
        if (mimeEnd == -1) return false

        val mimeType = dataUrl.substring(5, mimeEnd).lowercase()
        return mimeType in ALLOWED_IMAGE_MIME_TYPES
    }

    /**
     * Validates and returns the favicon data URL if it uses an allowed format.
     * Returns null if the data URL is not a safe image format.
     */
    fun sanitizeFaviconDataUrl(dataUrl: String?): String? {
        if (dataUrl == null) return null
        return if (isAllowedImageDataUrl(dataUrl)) dataUrl else null
    }
}