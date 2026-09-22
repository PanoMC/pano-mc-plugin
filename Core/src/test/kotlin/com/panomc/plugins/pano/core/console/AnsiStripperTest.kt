package com.panomc.plugins.pano.core.console

import kotlin.test.Test
import kotlin.test.assertEquals

class AnsiStripperTest {
    @Test
    fun `leaves plain text untouched`() {
        assertEquals("Done (3.421s)! For help, type \"help\"", AnsiStripper.strip("Done (3.421s)! For help, type \"help\""))
    }

    @Test
    fun `strips sgr colour codes`() {
        assertEquals(
            "[12:00:00 INFO]: Hello",
            AnsiStripper.strip("\u001B[0;32m[12:00:00 INFO]:\u001B[m \u001B[0;37mHello\u001B[m")
        )
    }

    @Test
    fun `strips true colour and cursor sequences`() {
        assertEquals("green", AnsiStripper.strip("\u001B[38;2;0;255;0mgreen\u001B[0m"))
        assertEquals("line", AnsiStripper.strip("\u001B[2K\u001B[1Gline"))
    }

    @Test
    fun `strips osc sequences terminated by bel and by st`() {
        assertEquals("after", AnsiStripper.strip("\u001B]0;window title\u0007after"))
        assertEquals("after", AnsiStripper.strip("\u001B]8;;https://panomc.com\u001B\\after"))
    }

    @Test
    fun `strips an unterminated osc sequence to the end of the line`() {
        assertEquals("before", AnsiStripper.strip("before\u001B]0;never terminated"))
    }

    @Test
    fun `strips short escapes and a lone escape byte`() {
        assertEquals("reset", AnsiStripper.strip("\u001Bcreset"))
        assertEquals("plain", AnsiStripper.strip("\u001B(Bplain"))
        assertEquals("trailing", AnsiStripper.strip("trailing\u001B"))
    }

    @Test
    fun `keeps section sign colour codes`() {
        // The contract is ANSI-only: legacy section-sign codes are the panel's business.
        assertEquals("§aGreen", AnsiStripper.strip("§aGreen"))
    }
}
