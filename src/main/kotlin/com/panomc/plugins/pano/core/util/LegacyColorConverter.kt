package com.panomc.plugins.pano.core.util

object LegacyColorConverter {
    private val COLORS = mapOf(
        '0' to "\u001B[30m", // Black
        '1' to "\u001B[34m", // Dark Blue
        '2' to "\u001B[32m", // Dark Green
        '3' to "\u001B[36m", // Dark Aqua
        '4' to "\u001B[31m", // Dark Red
        '5' to "\u001B[35m", // Dark Purple
        '6' to "\u001B[33m", // Gold
        '7' to "\u001B[37m", // Gray
        '8' to "\u001B[90m", // Dark Gray
        '9' to "\u001B[94m", // Blue
        'a' to "\u001B[92m", // Green
        'b' to "\u001B[96m", // Aqua
        'c' to "\u001B[91m", // Red
        'd' to "\u001B[95m", // Light Purple
        'e' to "\u001B[93m", // Yellow
        'f' to "\u001B[97m", // White
        'r' to "\u001B[0m"   // Reset
    )

    fun translate(text: String): String {
        var result = text.replace('&', '§')
        for ((code, ansi) in COLORS) {
            result = result.replace("§$code", ansi)
        }
        return "$result\u001B[0m" // reset at end
    }
}
