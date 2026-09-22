package com.panomc.plugins.pano.core.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleLinesTest {
    @Test
    fun `produces one line per record`() {
        val lines = ConsoleLines.toLines(1_000L, ConsoleLevel.WARN, "careful")

        assertEquals(1, lines.size)
        assertEquals(ConsoleLine(1_000L, ConsoleLevel.WARN, "careful"), lines[0])
    }

    @Test
    fun `splits embedded newlines into separate lines and drops empty ones`() {
        val lines = ConsoleLines.toLines(1L, ConsoleLevel.INFO, "first\n\nsecond\r\nthird")

        assertEquals(listOf("first", "second", "third"), lines.map { it.message })
        assertTrue(lines.all { it.timestamp == 1L && it.level == ConsoleLevel.INFO })
    }

    @Test
    fun `strips ansi from every produced line`() {
        val lines = ConsoleLines.toLines(1L, ConsoleLevel.INFO, "\u001B[31ma\u001B[m\n\u001B[32mb\u001B[m")

        assertEquals(listOf("a", "b"), lines.map { it.message })
    }

    @Test
    fun `truncates each line to the contract cap`() {
        val lines = ConsoleLines.toLines(1L, ConsoleLevel.INFO, "x".repeat(ConsoleLines.MAX_MESSAGE_LENGTH * 2))

        assertEquals(1, lines.size)
        assertEquals(ConsoleLines.MAX_MESSAGE_LENGTH, lines[0].message.length)
    }

    @Test
    fun `appends the stack trace of a thrown exception`() {
        val lines = ConsoleLines.toLines(1L, ConsoleLevel.ERROR, "boom", IllegalStateException("bang"))

        assertEquals("boom", lines[0].message)
        assertTrue(lines.size > 1, "expected the stack trace to be appended as extra lines")
        assertTrue(lines[1].message.contains("IllegalStateException"))
        assertTrue(lines.all { it.level == ConsoleLevel.ERROR })
    }

    @Test
    fun `returns nothing for an empty record`() {
        assertTrue(ConsoleLines.toLines(1L, ConsoleLevel.INFO, null).isEmpty())
        assertTrue(ConsoleLines.toLines(1L, ConsoleLevel.INFO, "   \n  ").isNotEmpty())
        assertTrue(ConsoleLines.toLines(1L, ConsoleLevel.INFO, "").isEmpty())
    }
}
