package com.panomc.plugins.pano.core.console

import io.vertx.core.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The colour spans a live console line carries (AGENT.md 2.4.21 A).
 *
 * The first five vectors are shared with the node's `ConsoleLineParser` test: the two parsers are
 * separate code serving the same panel, and a line has to come out the same colour whichever side
 * captured it.
 */
class AnsiStyleTest {
    private val esc = "\u001B"

    /** What one raw line becomes on the wire: its `m`, and its `c` as JSON (null when absent). */
    private fun wire(raw: String): Pair<String, String?> {
        val line = ConsoleLines.toLines(0L, ConsoleLevel.INFO, raw).single()

        return line.message to line.spans?.let { spans -> JsonArray(spans.map { it.toWire() }).encode() }
    }

    @Test
    fun `shared vector 1 - basic colour then reset`() {
        assertEquals("red plain" to """[[0,3,"#ff7b72",0]]""", wire("$esc[31mred$esc[0m plain"))
    }

    @Test
    fun `shared vector 2 - truecolour with bold switched off mid-run`() {
        assertEquals(
            "ABC" to """[[0,1,"#55ffff",1],[1,2,"#55ffff",0]]""",
            wire("$esc[1;38;2;85;255;255mA$esc[22mB$esc[39mC")
        )
    }

    @Test
    fun `shared vector 3 - xterm 256 colour`() {
        assertEquals("X" to """[[0,1,"#ff0000",0]]""", wire("$esc[38;5;196mX"))
    }

    @Test
    fun `shared vector 4 - a background alone carries no span`() {
        assertEquals("bg only" to null, wire("$esc[42mbg only"))
    }

    @Test
    fun `shared vector 5 - flags without a colour`() {
        assertEquals("u" to """[[0,1,null,6]]""", wire("$esc[4;3mu"))
    }

    @Test
    fun `the text is always exactly what strip gives`() {
        listOf(
            "plain",
            "$esc[0;32m[12:00:00 INFO]:$esc[m $esc[0;37mHello$esc[m",
            "$esc]0;title${"\u0007"}after$esc[2K$esc[1Gline",
            "$esc(Bcharset $esc[38;2;1;2mtruncated truecolour",
            "trailing lone escape $esc"
        ).forEach { raw ->
            assertEquals(AnsiStripper.strip(raw), AnsiStripper.style(raw).text, "for ${raw.replace(esc, "ESC")}")
        }
    }

    @Test
    fun `bright colours, the cube and the greys`() {
        assertEquals("""[[0,1,"#f0f6fc",0]]""", wire("$esc[97mW").second)
        assertEquals("""[[0,1,"#6e7681",0]]""", wire("$esc[38;5;0mK").second, "0-15 are the same palette")
        assertEquals("""[[0,1,"#5f87af",0]]""", wire("$esc[38;5;67mC").second)
        assertEquals("""[[0,1,"#080808",0]]""", wire("$esc[38;5;232mG").second)
        assertEquals("""[[0,1,"#eeeeee",0]]""", wire("$esc[38;5;255mG").second)
    }

    @Test
    fun `backgrounds are consumed without eating the colour after them`() {
        assertEquals("""[[0,1,"#7ee787",0]]""", wire("$esc[48;5;12;32mX").second)
        assertEquals("""[[0,1,"#7ee787",0]]""", wire("$esc[48;2;1;2;3;32mX").second)
        assertEquals(null, wire("$esc[41;101;49mX").second)
    }

    @Test
    fun `touching runs in the same style are one run and default runs are left out`() {
        assertEquals("""[[0,4,"#ff7b72",0]]""", wire("$esc[31mre$esc[31mds$esc[39m plain").second)
        assertEquals("plain blu" to """[[6,9,"#79c0ff",0]]""", wire("plain $esc[34mblu$esc[m"))
    }

    @Test
    fun `an empty parameter is a reset`() {
        assertEquals("""[[0,1,"#ff7b72",0]]""", wire("$esc[31mA$esc[mB").second)
        assertEquals("""[[0,1,"#ff7b72",0],[1,2,null,1]]""", wire("$esc[31mA$esc[;1mB").second)
    }

    @Test
    fun `spans are clipped to the capped message and to sixty four`() {
        val long = "$esc[31m" + "x".repeat(ConsoleLines.MAX_MESSAGE_LENGTH + 10)

        assertEquals(
            """[[0,${ConsoleLines.MAX_MESSAGE_LENGTH},"#ff7b72",0]]""",
            wire(long).second
        )

        val many = (0 until 100).joinToString("") { if (it % 2 == 0) "$esc[31mr" else "$esc[32mg" }
        val spans = ConsoleLines.toLines(0L, ConsoleLevel.INFO, many).single().spans!!

        assertEquals(AnsiStripper.MAX_SPANS, spans.size)
        assertEquals(ColorSpan(63, 64, "#7ee787", 0), spans.last())
    }

    @Test
    fun `a line that is only styled whitespace after the cut keeps no span past its end`() {
        val line = ConsoleLines.toLines(0L, ConsoleLevel.INFO, "ok$esc[31m\r").single()

        assertEquals("ok", line.message)
        assertNull(line.spans)
    }
}
