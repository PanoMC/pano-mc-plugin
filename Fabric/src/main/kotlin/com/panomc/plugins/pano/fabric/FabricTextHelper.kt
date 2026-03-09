package com.panomc.plugins.pano.fabric

import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Converts legacy `&` color-coded strings into proper Minecraft Text components
 * with Formatting styles. This ensures colors work both in-game and in console.
 *
 * Supports: &0-9, &a-f, &r (reset), &l (bold), &o (italic), &n (underline), &m (strikethrough), &k (obfuscated)
 */
object FabricTextHelper {

    private val COLOR_MAP = mapOf(
        '0' to Formatting.BLACK,
        '1' to Formatting.DARK_BLUE,
        '2' to Formatting.DARK_GREEN,
        '3' to Formatting.DARK_AQUA,
        '4' to Formatting.DARK_RED,
        '5' to Formatting.DARK_PURPLE,
        '6' to Formatting.GOLD,
        '7' to Formatting.GRAY,
        '8' to Formatting.DARK_GRAY,
        '9' to Formatting.BLUE,
        'a' to Formatting.GREEN,
        'b' to Formatting.AQUA,
        'c' to Formatting.RED,
        'd' to Formatting.LIGHT_PURPLE,
        'e' to Formatting.YELLOW,
        'f' to Formatting.WHITE,
        'k' to Formatting.OBFUSCATED,
        'l' to Formatting.BOLD,
        'm' to Formatting.STRIKETHROUGH,
        'n' to Formatting.UNDERLINE,
        'o' to Formatting.ITALIC,
        'r' to Formatting.RESET
    )

    /**
     * Parses a string with `&` color codes into a styled Text component.
     * Example: "&6Pano &eMC Plugin" → Gold "Pano " + Yellow "MC Plugin"
     */
    fun parseColoredText(message: String): Text {
        val result: MutableText = Text.empty()
        var currentFormatting = mutableListOf<Formatting>()
        var currentText = StringBuilder()
        var i = 0

        while (i < message.length) {
            if ((message[i] == '&' || message[i] == '§') && i + 1 < message.length) {
                val code = message[i + 1].lowercaseChar()
                val formatting = COLOR_MAP[code]

                if (formatting != null) {
                    // Flush current text with current formatting
                    if (currentText.isNotEmpty()) {
                        val part = Text.literal(currentText.toString())
                        if (currentFormatting.isNotEmpty()) {
                            part.formatted(*currentFormatting.toTypedArray())
                        }
                        result.append(part)
                        currentText = StringBuilder()
                    }

                    if (formatting == Formatting.RESET) {
                        currentFormatting = mutableListOf()
                    } else if (formatting.isColor) {
                        // Color codes reset all previous formatting
                        currentFormatting = mutableListOf(formatting)
                    } else {
                        // Style codes (bold, italic, etc.) stack
                        currentFormatting.add(formatting)
                    }

                    i += 2
                    continue
                }
            }

            currentText.append(message[i])
            i++
        }

        // Flush remaining text
        if (currentText.isNotEmpty()) {
            val part = Text.literal(currentText.toString())
            if (currentFormatting.isNotEmpty()) {
                part.formatted(*currentFormatting.toTypedArray())
            }
            result.append(part)
        }

        return result
    }
}
