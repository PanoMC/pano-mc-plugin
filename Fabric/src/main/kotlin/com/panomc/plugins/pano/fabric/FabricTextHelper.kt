package com.panomc.plugins.pano.fabric

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Converts legacy `&` color-coded strings into proper Minecraft text components
 * with ChatFormatting styles. This ensures colors work both in-game and in console.
 *
 * Supports: &0-9, &a-f, &r (reset), &l (bold), &o (italic), &n (underline), &m (strikethrough), &k (obfuscated)
 *
 * Only the `ChatFormatting` constants and `withStyle(ChatFormatting...)` are used from Minecraft:
 * Minecraft 26.3 removed `ChatFormatting.isColor()` (26.1 still had it), and calling it threw a
 * `NoSuchMethodError` on every coloured message -- which, being an `Error`, got past every
 * `catch (Exception)` on the way and crashed the server on the ban kick. Whether a code is a
 * colour is decided from the code itself instead, and a text that cannot be styled at all is
 * still delivered as plain text rather than taking the server down.
 */
object FabricTextHelper {
    /** The codes that set a colour (and so replace the formatting so far), as opposed to a style. */
    private const val COLOR_CODES = "0123456789abcdef"

    /** Any `&x` / `§x` code, for the plain-text fallback. */
    private val CODE_PATTERN = Regex("[&§][0-9a-fk-orA-FK-OR]")

    private val COLOR_MAP = mapOf(
        '0' to ChatFormatting.BLACK,
        '1' to ChatFormatting.DARK_BLUE,
        '2' to ChatFormatting.DARK_GREEN,
        '3' to ChatFormatting.DARK_AQUA,
        '4' to ChatFormatting.DARK_RED,
        '5' to ChatFormatting.DARK_PURPLE,
        '6' to ChatFormatting.GOLD,
        '7' to ChatFormatting.GRAY,
        '8' to ChatFormatting.DARK_GRAY,
        '9' to ChatFormatting.BLUE,
        'a' to ChatFormatting.GREEN,
        'b' to ChatFormatting.AQUA,
        'c' to ChatFormatting.RED,
        'd' to ChatFormatting.LIGHT_PURPLE,
        'e' to ChatFormatting.YELLOW,
        'f' to ChatFormatting.WHITE,
        'k' to ChatFormatting.OBFUSCATED,
        'l' to ChatFormatting.BOLD,
        'm' to ChatFormatting.STRIKETHROUGH,
        'n' to ChatFormatting.UNDERLINE,
        'o' to ChatFormatting.ITALIC,
        'r' to ChatFormatting.RESET
    )

    /**
     * Parses a string with `&` color codes into a styled Component.
     * Example: "&6Pano &eMC Plugin" → Gold "Pano " + Yellow "MC Plugin"
     */
    fun parseColoredText(message: String): Component = try {
        styled(message)
    } catch (_: LinkageError) {
        // A Minecraft that moved or dropped one of the few calls below: say it without colour
        // instead of letting a kick or a command reply crash the server.
        Component.literal(CODE_PATTERN.replace(message, ""))
    }

    private fun styled(message: String): Component {
        val result: MutableComponent = Component.empty()
        var currentFormatting = mutableListOf<ChatFormatting>()
        var currentText = StringBuilder()
        var i = 0

        while (i < message.length) {
            if ((message[i] == '&' || message[i] == '§') && i + 1 < message.length) {
                val code = message[i + 1].lowercaseChar()
                val formatting = COLOR_MAP[code]

                if (formatting != null) {
                    if (currentText.isNotEmpty()) {
                        val part: MutableComponent = Component.literal(currentText.toString())
                        if (currentFormatting.isNotEmpty()) {
                            part.withStyle(*currentFormatting.toTypedArray())
                        }
                        result.append(part)
                        currentText = StringBuilder()
                    }

                    if (formatting == ChatFormatting.RESET) {
                        currentFormatting = mutableListOf()
                    } else if (code in COLOR_CODES) {
                        currentFormatting = mutableListOf(formatting)
                    } else {
                        currentFormatting.add(formatting)
                    }

                    i += 2
                    continue
                }
            }

            currentText.append(message[i])
            i++
        }

        if (currentText.isNotEmpty()) {
            val part = Component.literal(currentText.toString())
            if (currentFormatting.isNotEmpty()) {
                part.withStyle(*currentFormatting.toTypedArray())
            }
            result.append(part)
        }

        return result
    }
}
