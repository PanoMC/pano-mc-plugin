package com.panomc.plugins.pano.core.console

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the deep console search behind `CONSOLE_SEARCH`. */
class ServerLogSearchTest {
    private lateinit var root: File

    private val zone: ZoneId = ZoneId.of("UTC")

    @BeforeTest
    fun createRoot() {
        root = Files.createTempDirectory("pano-log-search").toFile()
    }

    @AfterTest
    fun removeRoot() {
        root.deleteRecursively()
    }

    private fun logs(): File = File(root, "logs").apply { mkdirs() }

    private fun writeLatest(vararg lines: String): File =
        File(logs(), "latest.log").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun writeRotated(name: String, vararg lines: String): File {
        val file = File(logs(), name)

        GZIPOutputStream(file.outputStream()).use { it.write((lines.joinToString("\n") + "\n").toByteArray()) }

        return file
    }

    private fun search(
        query: String?,
        cursor: String? = null,
        limit: Int? = null,
        budgetMs: Long? = null,
        maxBytes: Int = Int.MAX_VALUE,
        clock: () -> Long = { 0L }
    ) = ServerLogSearch.search(
        File(root, "logs"), query, cursor, limit, budgetMs, zone,
        maxBytes = maxBytes,
        lineCost = { line, _ -> line.message.length },
        clock = clock
    )

    private fun ServerLogSearch.Result.texts() = matches.map { it.line.message.substringAfterLast(": ") }

    /** Pages through the whole search, returning every match in the order it was handed out. */
    private fun drain(query: String, limit: Int): List<String> {
        val all = ArrayList<String>()

        var cursor: String? = null
        var calls = 0

        do {
            val result = search(query, cursor, limit)

            assertTrue(result.ok)

            all += result.texts()
            cursor = result.cursor
            calls++
        } while (!result.done && calls < 1000)

        return all
    }

    private fun seed() {
        writeLatest("[10:00:00] [Server thread/INFO]: hit latest 1", "[10:00:01] [Server thread/INFO]: hit latest 2")
        writeRotated("2026-09-22-2.log.gz", "[09:00:00] [Server thread/INFO]: hit 22-2 a", "[09:00:01] [Server thread/WARN]: hit 22-2 b")
        writeRotated("2026-09-22-10.log.gz", "[11:00:00] [Server thread/INFO]: hit 22-10 a")
        writeRotated("2026-09-21-1.log.gz", "[08:00:00] [Server thread/INFO]: hit 21-1 a", "[08:00:01] [Server thread/INFO]: miss")
    }

    @Test
    fun `files are walked latest first, then rotations newest first by date and numeric index`() {
        seed()

        assertEquals(
            listOf("latest.log", "2026-09-22-10.log.gz", "2026-09-22-2.log.gz", "2026-09-21-1.log.gz"),
            ServerLogSearch.files(logs()).map { it.name }
        )

        val result = search("hit")

        assertEquals(
            listOf("hit latest 2", "hit latest 1", "hit 22-10 a", "hit 22-2 b", "hit 22-2 a", "hit 21-1 a"),
            result.texts()
        )
        assertEquals(
            listOf("latest.log", "latest.log", "2026-09-22-10.log.gz", "2026-09-22-2.log.gz", "2026-09-22-2.log.gz", "2026-09-21-1.log.gz"),
            result.matches.map { it.file }
        )
        assertTrue(result.done)
        assertNull(result.cursor)
        assertEquals(4, result.scannedFiles)
        assertEquals(4, result.totalFiles)
        assertTrue(result.scannedBytes > 0)
        assertFalse(result.capped)
    }

    @Test
    fun `lines keep the history shape - level and timestamp from the rotated file's name`() {
        seed()

        val line = search("22-2 b").matches.single().line

        assertEquals(ConsoleLevel.WARN, line.level)
        assertEquals(
            LocalDateTime.of(LocalDate.of(2026, 9, 22), java.time.LocalTime.of(9, 0, 1)).atZone(zone).toInstant().toEpochMilli(),
            line.timestamp
        )
        assertNull(line.spans)
    }

    @Test
    fun `matching is a case-insensitive substring of the message`() {
        seed()

        assertEquals(listOf("hit 22-2 b"), search("  [SERVER THREAD/warn]  ").texts())
    }

    @Test
    fun `a cursor resumes inside a file without repeating or losing a match`() {
        writeLatest(*(1..7).map { "[10:00:0$it] [Server thread/INFO]: hit $it" }.toTypedArray())
        writeRotated("2026-09-22-1.log.gz", *(1..5).map { "[09:00:0$it] [Server thread/INFO]: old $it hit" }.toTypedArray())

        val first = search("hit", limit = 3)

        assertEquals(listOf("hit 7", "hit 6", "hit 5"), first.texts())
        assertFalse(first.done)
        assertEquals(0, first.scannedFiles)

        val cursor = ServerLogSearch.decodeCursor(first.cursor!!)

        assertEquals(ServerLogSearch.Cursor("latest.log", 3), cursor)

        val second = search("hit", first.cursor, limit = 3)

        assertEquals(listOf("hit 4", "hit 3", "hit 2"), second.texts())

        val expected = (7 downTo 1).map { "hit $it" } + (5 downTo 1).map { "old $it hit" }.map { it.substringAfterLast(": ") }

        assertEquals(expected, drain("hit", 3))
        assertEquals(expected, drain("hit", 1))
        assertEquals(expected, drain("hit", 1000))
    }

    @Test
    fun `a limit reached exactly at the end of a file points the cursor at the next file`() {
        seed()

        val first = search("hit", limit = 2)

        assertEquals(listOf("hit latest 2", "hit latest 1"), first.texts())
        assertEquals(ServerLogSearch.Cursor("2026-09-22-10.log.gz", 0), ServerLogSearch.decodeCursor(first.cursor!!))
        assertEquals(1, first.scannedFiles)
        assertFalse(first.done)
    }

    @Test
    fun `a spent budget stops between files but never before the first`() {
        seed()

        var now = 0L

        // Every look at the clock costs a second, so the budget is gone as soon as it is checked.
        val result = search("hit", budgetMs = 200, clock = { now.also { now += 1000 } })

        assertEquals(listOf("hit latest 2", "hit latest 1"), result.texts())
        assertEquals(ServerLogSearch.Cursor("2026-09-22-10.log.gz", 0), ServerLogSearch.decodeCursor(result.cursor!!))

        // The next call starts on the next file, and again finishes it.
        val next = search("hit", result.cursor, budgetMs = 200, clock = { now.also { now += 1000 } })

        assertEquals(listOf("hit 22-10 a"), next.texts())
    }

    @Test
    fun `the reply size bound leaves the rest of the file to the cursor`() {
        writeLatest(*(1..5).map { "[10:00:0$it] [Server thread/INFO]: hit $it" }.toTypedArray())

        val one = "[10:00:01] [Server thread/INFO]: hit 1".length

        val result = search("hit", maxBytes = one * 2)

        assertEquals(listOf("hit 5", "hit 4"), result.texts())
        assertEquals(ServerLogSearch.Cursor("latest.log", 2), ServerLogSearch.decodeCursor(result.cursor!!))
    }

    @Test
    fun `a blank query is BAD_QUERY`() {
        seed()

        for (query in listOf(null, "", "   ")) {
            val result = search(query)

            assertFalse(result.ok)
            assertEquals(ServerLogSearch.BAD_QUERY, result.error)
        }
    }

    @Test
    fun `a cursor this reader did not write is BAD_CURSOR`() {
        seed()

        val encoder = Base64.getUrlEncoder().withoutPadding()

        val bad = listOf(
            "not base64 at all!",
            encoder.encodeToString("not json".toByteArray()),
            encoder.encodeToString("""{"v":2,"file":"latest.log","emitted":0}""".toByteArray()),
            encoder.encodeToString("""{"v":1,"file":"../latest.log","emitted":0}""".toByteArray()),
            encoder.encodeToString("""{"v":1,"file":"latest.log","emitted":-1}""".toByteArray()),
            encoder.encodeToString("""{"v":1,"file":"gone.log.gz","emitted":0}""".toByteArray())
        )

        for (cursor in bad) {
            val result = search("hit", cursor)

            assertFalse(result.ok, cursor)
            assertEquals(ServerLogSearch.BAD_CURSOR, result.error, cursor)
        }

        val good = encoder.encodeToString("""{"v":1,"file":"2026-09-22-2.log.gz","emitted":1}""".toByteArray())

        assertEquals(listOf("hit 22-2 a", "hit 21-1 a"), search("hit", good).texts())
    }

    @Test
    fun `a file over the size cap is read from its newest end and reported capped`() {
        val cap = ServerLogSearch.MAX_FILE_BYTES
        val filler = "[10:00:00] [Server thread/INFO]: " + "x".repeat(1000) + "\n"

        File(logs(), "latest.log").bufferedWriter().use { writer ->
            writer.write("[09:00:00] [Server thread/INFO]: hit lost at the start\n")

            var written = 0L

            while (written <= cap) {
                writer.write(filler)
                written += filler.length
            }

            writer.write("[11:00:00] [Server thread/INFO]: hit kept at the end\n")
        }

        val result = search("hit")

        assertTrue(result.capped)
        assertEquals(listOf("hit kept at the end"), result.texts())
        assertTrue(result.done)
    }

    @Test
    fun `no logs at all is an empty finished search`() {
        val result = search("hit")

        assertTrue(result.ok)
        assertTrue(result.done)
        assertEquals(0, result.totalFiles)
        assertNotNull(result.matches)
        assertTrue(result.matches.isEmpty())
    }
}
