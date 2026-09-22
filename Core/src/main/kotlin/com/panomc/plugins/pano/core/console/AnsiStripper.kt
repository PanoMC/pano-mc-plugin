package com.panomc.plugins.pano.core.console

import java.util.Locale

/**
 * Removes the terminal escape sequences a Minecraft console writes into its log stream.
 *
 * Server consoles are coloured: Paper/Velocity/Fabric render through a log4j2 terminal appender
 * and BungeeCord through jline, so the text an appender observes can still carry raw escapes.
 * Pano renders those lines as HTML, so they are stripped here - at the source - rather than
 * being shipped to the panel and cleaned there (AGENT.md 2.7: console output is untrusted text).
 *
 * Three families are covered:
 * - **CSI** - `ESC [ ... final`, which is every SGR colour code (`ESC[0m`), cursor move and
 *   erase sequence.
 * - **OSC** - `ESC ] ... BEL` or `ESC ] ... ESC \`, used for window titles and hyperlinks. An
 *   unterminated OSC is dropped to the end of the line rather than left half-rendered.
 * - **Short escapes** - `ESC`, optional intermediate bytes, one final byte: `ESC c` (reset),
 *   `ESC ( B` (charset), `ESC M` (reverse index) and friends.
 *
 * A lone `ESC` with nothing after it is dropped too, so no escape byte can ever survive into the
 * payload - Pano renders these lines as HTML and an escape byte there is noise at best.
 */
object AnsiStripper {
    private val ANSI_PATTERN = Regex(
        // CSI: ESC [ parameter-bytes intermediate-bytes final-byte
        "\u001B\\[[0-?]*[ -/]*[@-~]" +
                // OSC: ESC ] ... terminated by BEL or ST (ESC \), or by the end of the line
                "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\|$)" +
                // Any other short escape: ESC, optional intermediate bytes, one final byte
                "|\u001B[ -/]*[0-~]" +
                // A lone ESC that nothing recognisable follows
                "|\u001B"
    )

    /** [text] with every ANSI escape sequence removed. */
    fun strip(text: String): String {
        if (text.indexOf('\u001B') < 0) {
            return text
        }

        return ANSI_PATTERN.replace(text, "")
    }

    /** Most colour spans one line may carry (AGENT.md 2.4.21 A). */
    const val MAX_SPANS = 64

    /** Wire flag bits of a [ColorSpan]. */
    const val FLAG_BOLD = 1
    const val FLAG_ITALIC = 2
    const val FLAG_UNDERLINE = 4

    /**
     * The fixed 16-colour palette `30-37` then `90-97` map onto, tuned for the panel's dark
     * console rather than for a terminal. It is also xterm-256's 0-15. Shared verbatim with the
     * node's `ConsoleLineParser`, so the same line is the same colour whichever side captured it.
     */
    private val PALETTE = arrayOf(
        "#6e7681", "#ff7b72", "#7ee787", "#e3b341", "#79c0ff", "#d2a8ff", "#56d4dd", "#c9d1d9",
        "#8b949e", "#ffa198", "#aff5b4", "#f8e3a1", "#a5d6ff", "#e2c5ff", "#b3f0ff", "#f0f6fc"
    )

    /** The 6x6x6 cube's channel levels in xterm-256 (indices 16-231). */
    private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

    /** An SGR sequence this reads colours from: plain numeric parameters, final `m`. */
    private val SGR = Regex("^\u001B\\[([0-9;]*)m$")

    /**
     * [text] stripped exactly as [strip] strips it, together with the colour runs its SGR codes
     * described (AGENT.md 2.4.21 A).
     *
     * This walks the very same matches [strip] removes, so [Styled.text] is always byte for byte
     * what [strip] returns - the colours ride next to the plain text, they never change it. Only
     * foreground colour, bold, italic and underline are kept; backgrounds and every other
     * sequence are read and forgotten, because the panel paints its own background and a
     * server's idea of one would only fight it. Runs in the default style are not reported, and
     * two touching runs in the same style are one run.
     *
     * Pure and allocation-light on purpose: it runs on the logging thread for every line.
     */
    fun style(text: String): Styled {
        if (text.indexOf('\u001B') < 0) {
            return Styled(text, emptyList())
        }

        val out = StringBuilder(text.length)
        val spans = ArrayList<ColorSpan>()
        val state = SgrState()

        var position = 0

        for (match in ANSI_PATTERN.findAll(text)) {
            appendRun(out, spans, text, position, match.range.first, state)

            SGR.find(match.value)?.let { applySgr(state, it.groupValues[1]) }

            position = match.range.last + 1
        }

        appendRun(out, spans, text, position, text.length, state)

        return Styled(out.toString(), spans)
    }

    /** Current foreground and flags while walking a line's SGR codes. */
    private class SgrState {
        var color: String? = null
        var flags = 0
    }

    private fun appendRun(
        out: StringBuilder,
        spans: MutableList<ColorSpan>,
        text: String,
        from: Int,
        to: Int,
        state: SgrState
    ) {
        if (to <= from) {
            return
        }

        val start = out.length

        out.append(text, from, to)

        if (state.color == null && state.flags == 0) {
            return
        }

        val last = spans.lastOrNull()

        if (last != null && last.end == start && last.color == state.color && last.flags == state.flags) {
            spans[spans.size - 1] = last.copy(end = out.length)
        } else {
            spans.add(ColorSpan(start, out.length, state.color, state.flags))
        }
    }

    /**
     * Applies one SGR parameter list. An empty parameter is `0`, as a terminal reads it. An
     * extended colour (`38`/`48`) whose arguments are missing ends the list: there is no telling
     * which of the remaining numbers were meant as what.
     */
    private fun applySgr(state: SgrState, parameters: String) {
        val codes = parameters.split(';').map { it.toIntOrNull() ?: 0 }

        var index = 0

        while (index < codes.size) {
            when (val code = codes[index]) {
                0 -> {
                    state.color = null
                    state.flags = 0
                }

                1 -> state.flags = state.flags or FLAG_BOLD
                22 -> state.flags = state.flags and FLAG_BOLD.inv()
                3 -> state.flags = state.flags or FLAG_ITALIC
                23 -> state.flags = state.flags and FLAG_ITALIC.inv()
                4 -> state.flags = state.flags or FLAG_UNDERLINE
                24 -> state.flags = state.flags and FLAG_UNDERLINE.inv()
                in 30..37 -> state.color = PALETTE[code - 30]
                in 90..97 -> state.color = PALETTE[8 + code - 90]
                39 -> state.color = null

                38, 48 -> {
                    val mode = codes.getOrNull(index + 1) ?: return

                    val color = when (mode) {
                        5 -> {
                            val n = codes.getOrNull(index + 2) ?: return

                            index += 2

                            xterm256(n)
                        }

                        2 -> {
                            if (index + 4 >= codes.size) {
                                return
                            }

                            val r = codes[index + 2]
                            val g = codes[index + 3]
                            val b = codes[index + 4]

                            index += 4

                            if (r in 0..255 && g in 0..255 && b in 0..255) hex(r, g, b) else null
                        }

                        else -> return
                    }

                    // 48 is a background: its arguments are consumed above and then forgotten.
                    if (code == 38 && color != null) {
                        state.color = color
                    }
                }

                // Backgrounds (40-47, 49, 100-107) and everything else: read and ignored.
                else -> Unit
            }

            index++
        }
    }

    /** xterm-256 colour [n] as `#rrggbb`, or null outside 0-255. */
    private fun xterm256(n: Int): String? = when (n) {
        in 0..15 -> PALETTE[n]

        in 16..231 -> {
            val cube = n - 16

            hex(CUBE_LEVELS[cube / 36], CUBE_LEVELS[(cube / 6) % 6], CUBE_LEVELS[cube % 6])
        }

        in 232..255 -> {
            val grey = 8 + 10 * (n - 232)

            hex(grey, grey, grey)
        }

        else -> null
    }

    private fun hex(r: Int, g: Int, b: Int): String = String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b)

    /**
     * [spans] made fit for a line whose text was cut to [length]: anything starting past the
     * end is dropped, anything running past it is shortened, and at most [MAX_SPANS] are kept -
     * the first ones, since the start of a line is the part an operator reads. Null when nothing
     * is left, which is what keeps `c` off the wire for a plain line.
     */
    fun clip(spans: List<ColorSpan>, length: Int): List<ColorSpan>? {
        if (spans.isEmpty()) {
            return null
        }

        val kept = ArrayList<ColorSpan>(minOf(spans.size, MAX_SPANS))

        for (span in spans) {
            if (kept.size >= MAX_SPANS || span.start >= length) {
                break
            }

            kept.add(if (span.end > length) span.copy(end = length) else span)
        }

        return kept.ifEmpty { null }
    }
}

/**
 * One run of styled text: half-open character offsets into a line's final `m`, its foreground
 * as `#rrggbb` (null = the console's default) and its [AnsiStripper] flag bits.
 */
data class ColorSpan(val start: Int, val end: Int, val color: String?, val flags: Int) {
    /** The wire form, `[start, end, color, flags]`. */
    fun toWire(): List<Any?> = listOf(start, end, color, flags)
}

/** A line's plain text and the colour runs over it. */
data class Styled(val text: String, val spans: List<ColorSpan>)
