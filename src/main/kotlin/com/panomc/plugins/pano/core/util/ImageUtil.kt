package com.panomc.plugins.pano.core.util

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

object ImageUtil {
    fun bufferedImageToDataUrl(image: BufferedImage, format: String = "png"): String {
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, format, outputStream)
        val imageBytes = outputStream.toByteArray()
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        return "data:image/$format;base64,$base64"
    }
}