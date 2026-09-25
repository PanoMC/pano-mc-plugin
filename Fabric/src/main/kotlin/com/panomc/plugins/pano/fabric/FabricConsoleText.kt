package com.panomc.plugins.pano.fabric

/**
 * A Pano message with `&` or `§` codes, as a console line: colours and styles as ANSI escapes.
 *
 * Pure (no Minecraft types), so it can be tested on its own. Minecraft's own rules are kept: a
 * colour code clears the styles before it (so `&l&aX` is bold only until the next colour), `&r`
 * resets, and `&k` (obfuscated) has no terminal equivalent and is dropped.
 */
object FabricConsoleText {
    private const val ESC = "\u001B["
    private const val RESET = "${ESC}0m"

    private val COLORS = mapOf(
        '0' to "${ESC}30m",
        '1' to "${ESC}34m",
        '2' to "${ESC}32m",
        '3' to "${ESC}36m",
        '4' to "${ESC}31m",
        '5' to "${ESC}35m",
        '6' to "${ESC}33m",
        '7' to "${ESC}37m",
        '8' to "${ESC}90m",
        '9' to "${ESC}94m",
        'a' to "${ESC}92m",
        'b' to "${ESC}96m",
        'c' to "${ESC}91m",
        'd' to "${ESC}95m",
        'e' to "${ESC}93m",
        'f' to "${ESC}97m"
    )

    private val STYLES = mapOf(
        'l' to "${ESC}1m",
        'm' to "${ESC}9m",
        'n' to "${ESC}4m",
        'o' to "${ESC}3m"
    )

    fun toAnsi(message: String): String {
        val out = StringBuilder()
        var styled = false
        var index = 0

        while (index < message.length) {
            val char = message[index]

            if ((char == '&' || char == '§') && index + 1 < message.length) {
                val code = message[index + 1].lowercaseChar()

                val replacement = when {
                    code in COLORS -> RESET + COLORS.getValue(code)
                    code in STYLES -> STYLES.getValue(code)
                    code == 'r' -> RESET
                    code == 'k' -> ""
                    else -> null
                }

                if (replacement != null) {
                    out.append(replacement)
                    styled = styled || replacement.isNotEmpty()
                    index += 2

                    continue
                }
            }

            out.append(char)
            index++
        }

        if (styled) {
            out.append(RESET)
        }

        return out.toString()
    }
}
