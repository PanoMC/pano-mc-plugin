package com.panomc.plugins.pano.fabric

import kotlin.test.Test
import kotlin.test.assertEquals

class FabricConsoleTextTest {
    private val esc = "\u001B["

    @Test
    fun `colour codes become ANSI and the line ends reset`() {
        assertEquals(
            "${esc}0m${esc}33mPano status${esc}0m",
            FabricConsoleText.toAnsi("&6Pano status")
        )
    }

    @Test
    fun `a colour clears the styles before it, like Minecraft`() {
        assertEquals(
            "${esc}1mBold${esc}0m${esc}92m green${esc}0m",
            FabricConsoleText.toAnsi("&lBold&a green")
        )
    }

    @Test
    fun `section signs work too and obfuscated is dropped`() {
        assertEquals("${esc}0m${esc}97mA${esc}0mB${esc}0m", FabricConsoleText.toAnsi("§fA§k§rB"))
    }

    @Test
    fun `text without codes and ampersands that are not codes stay as they are`() {
        assertEquals("plain", FabricConsoleText.toAnsi("plain"))
        assertEquals("a & b&z", FabricConsoleText.toAnsi("a & b&z"))
    }
}
