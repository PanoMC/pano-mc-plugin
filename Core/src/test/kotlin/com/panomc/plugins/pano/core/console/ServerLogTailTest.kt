package com.panomc.plugins.pano.core.console

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the log-file reader behind console history (AGENT.md 2.4.14).
 *
 * The vectors are deliberately the same ones the node daemon's own `ServerLogTailTest` uses: the
 * two readers are separate code in separate repositories serving the same panel, so the only
 * thing keeping them honest is that they are asked the same questions about the same files.
 */
class ServerLogTailTest {
    private lateinit var root: File

    private val zone: ZoneId = ZoneId.of("UTC")

    @BeforeTest
    fun createRoot() {
        root = Files.createTempDirectory("pano-log-tail").toFile()
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

    private fun stamp(date: LocalDate, hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute, second)
            .atZone(zone).toInstant().toEpochMilli()

    private fun read(limit: Int, skip: Int = 0) = ServerLogTail.read(File(root, "logs"), limit, skip, zone = zone)

    private fun messages(limit: Int, skip: Int = 0) = read(limit, skip).lines.map { it.message.substringAfterLast(": ") }

    private fun search(query: String?, limit: Int, skip: Int = 0) =
        ServerLogTail.read(File(root, "logs"), limit, skip, query, zone)

    private fun found(query: String?, limit: Int, skip: Int = 0) =
        search(query, limit, skip).lines.map { it.message.substringAfterLast(": ") }

    /** `line 1` .. `line count`, oldest first, with every tenth one mentioning a creeper. */
    private fun writeNumbered(count: Int) = writeLatest(*(1..count).map {
        if (it % 10 == 0) "[10:00:00] [Server thread/INFO]: line $it Creeper" else "[10:00:00] [Server thread/INFO]: line $it"
    }.toTypedArray())

    @Test
    fun `missing logs directory is not an error`() {
        assertEquals(ServerLogTail.Page.EMPTY, ServerLogTail.read(File(root, "logs"), 500, 0, zone = zone))
        assertEquals(ServerLogTail.Page.EMPTY, ServerLogTail.read(File(root, "nowhere"), 500, 0, zone = zone))
    }

    @Test
    fun `reads a file shorter than the limit whole`() {
        writeLatest(
            "[10:00:00] [Server thread/INFO]: Starting minecraft server",
            "[10:00:01] [Server thread/WARN]: Something odd",
            "[10:00:02] [Server thread/ERROR]: Broke"
        )

        val page = read(500)

        assertEquals(3, page.lines.size)
        assertEquals(
            listOf(ConsoleLevel.INFO, ConsoleLevel.WARN, ConsoleLevel.ERROR),
            page.lines.map { it.level }
        )
        assertTrue(page.lines.first().message.endsWith("Starting minecraft server"))
        assertTrue(page.lines.last().message.endsWith("Broke"))

        // The files ran out, so there is no older page to offer.
        assertFalse(page.hasMore)
    }

    @Test
    fun `reads the old level format too`() {
        writeLatest(
            "[10:00:00 INFO]: hello",
            "[10:00:01 SEVERE]: broke",
            "no prefix at all"
        )

        assertEquals(
            listOf(ConsoleLevel.INFO, ConsoleLevel.ERROR, ConsoleLevel.INFO),
            read(10).lines.map { it.level }
        )
    }

    @Test
    fun `strips the colours a console writes into its log`() {
        writeLatest("[10:00:00] [Server thread/INFO]: \u001B[32mgreen\u001B[0m text")

        assertEquals("[10:00:00] [Server thread/INFO]: green text", read(10).lines.single().message)
    }

    @Test
    fun `keeps only the newest lines when the file is longer than the limit`() {
        writeLatest(*(1..50).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        val page = read(10)

        assertEquals(10, page.lines.size)
        assertTrue(page.lines.first().message.endsWith("line 41"))
        assertTrue(page.lines.last().message.endsWith("line 50"))

        // Forty lines were left behind, so "load older" has something to fetch.
        assertTrue(page.hasMore)
    }

    @Test
    fun `ignores the blank line at the end of a file`() {
        writeLatest("[10:00:00] [Server thread/INFO]: only line")

        assertEquals(1, read(500).lines.size)
    }

    @Test
    fun `continues into a rotated gzip when latest is short`() {
        val yesterday = LocalDate.now(zone).minusDays(1)

        writeRotated(
            "$yesterday-1.log.gz",
            "[09:00:00] [Server thread/INFO]: old one",
            "[09:00:01] [Server thread/INFO]: old two"
        )

        writeLatest("[10:00:00] [Server thread/INFO]: new one")

        val page = read(500)

        assertEquals(listOf("old one", "old two", "new one"), page.lines.map { it.message.substringAfterLast(": ") })
        assertTrue(page.lines[0].timestamp < page.lines[2].timestamp)
        assertFalse(page.hasMore)
    }

    @Test
    fun `reads a plain rotated file as well as a gzipped one`() {
        File(logs(), "2025-08-16-1.log").writeText("[09:00:00] [Server thread/INFO]: plain rotation\n")

        writeLatest("[10:00:00] [Server thread/INFO]: current")

        assertEquals(listOf("plain rotation", "current"), messages(500))
    }

    @Test
    fun `stops at the file budget instead of reading every rotation`() {
        (1..6).forEach { index ->
            writeRotated("2025-08-1$index-1.log.gz", "[09:00:00] [Server thread/INFO]: file $index")
        }

        val page = read(500)

        assertEquals(ServerLogTail.MAX_ROTATED_FILES, page.lines.size)
        assertEquals(listOf("file 4", "file 5", "file 6"), page.lines.map { it.message.substringAfterLast(": ") })
    }

    @Test
    fun `skips the lines the panel already shows`() {
        writeLatest(*(1..50).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        val page = read(10, skip = 10)

        assertEquals((31..40).map { "line $it" }, page.lines.map { it.message.substringAfterLast(": ") })
        assertTrue(page.hasMore)
    }

    @Test
    fun `paging stops offering more once the history runs out`() {
        writeLatest(*(1..50).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        val page = read(10, skip = 45)

        // Only five lines are left before the skip, so the page is short and there is no older one.
        assertEquals((1..5).map { "line $it" }, page.lines.map { it.message.substringAfterLast(": ") })
        assertFalse(page.hasMore)
    }

    @Test
    fun `paging walks back across a rotation without a seam`() {
        writeRotated("2025-08-16-1.log.gz", *(1..5).map { "[09:00:0$it] [Server thread/INFO]: old $it" }.toTypedArray())
        writeLatest(*(1..5).map { "[10:00:0$it] [Server thread/INFO]: new $it" }.toTypedArray())

        assertEquals(listOf("new 3", "new 4", "new 5"), messages(3))
        assertEquals(listOf("old 5", "new 1", "new 2"), messages(3, skip = 3))
        assertEquals(listOf("old 2", "old 3", "old 4"), messages(3, skip = 6))
    }

    @Test
    fun `a skip past the whole history comes back empty`() {
        writeLatest(*(1..5).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        assertEquals(ServerLogTail.Page.EMPTY, read(10, skip = 20))
    }

    @Test
    fun `cuts the byte window at a line boundary`() {
        val file = File(logs(), "latest.log")
        val filler = "x".repeat(1024)

        // Comfortably more than the tail window, so the window starts mid-line every time.
        val count = (ServerLogTail.TAIL_WINDOW_BYTES / filler.length).toInt() + 200

        file.bufferedWriter().use { writer ->
            (1..count).forEach { writer.write("[10:00:00] [Server thread/INFO]: $it $filler\n") }
        }

        assertTrue(file.length() > ServerLogTail.TAIL_WINDOW_BYTES)

        val page = read(200)

        assertEquals(200, page.lines.size)

        // Every line is whole: no fragment of the line the window opened in the middle of.
        page.lines.forEach {
            assertTrue(it.message.startsWith("[10:00:00] [Server thread/INFO]: "), "cut line: ${it.message.take(40)}")
        }

        assertTrue(page.lines.last().message.startsWith("[10:00:00] [Server thread/INFO]: $count "))
        assertTrue(page.hasMore)
    }

    @Test
    fun `will not splice a rotation onto a page the window could not finish`() {
        writeRotated("2025-08-16-1.log.gz", "[09:00:00] [Server thread/INFO]: old line")

        val file = File(logs(), "latest.log")
        val filler = "z".repeat(4 * 1024)

        // Four kilobytes a line: 700 of them cannot fit in a two-megabyte window, so the reader
        // runs out of window before it runs out of file.
        file.bufferedWriter().use { writer ->
            (1..700).forEach { writer.write("[10:00:00] [Server thread/INFO]: $it $filler\n") }
        }

        val page = read(700)

        assertTrue(page.lines.isNotEmpty())
        assertTrue(page.lines.size < 700)

        // The rotation is older than what the window could reach, so pulling it in would report a
        // history with a hole in the middle of it as though it were continuous.
        assertTrue(page.lines.none { it.message.endsWith("old line") })

        // And the page is short, which is the one thing that always means "this is as far back as
        // it goes": offering an older page here would be a button that can only come back empty.
        assertFalse(page.hasMore)
    }

    @Test
    fun `truncates an absurdly long line instead of keeping it whole`() {
        writeLatest("[10:00:00] [Server thread/INFO]: " + "y".repeat(64 * 1024))

        val page = read(10)

        assertEquals(1, page.lines.size)
        assertEquals(ServerLogTail.MAX_LINE_LENGTH, page.lines.first().message.length)
    }

    @Test
    fun `dates lines from the rotated file name`() {
        writeRotated("2025-08-16-14.log.gz", "[02:29:00] [Server thread/INFO]: hello")

        assertEquals(stamp(LocalDate.of(2025, 8, 16), 2, 29, 0), read(10).lines.single().timestamp)
    }

    @Test
    fun `dates lines from the file mtime when the name has no date`() {
        val file = writeLatest("[02:29:00] [Server thread/INFO]: hello")
        val day = LocalDate.of(2025, 3, 4)

        file.setLastModified(stamp(day, 12, 0, 0))

        assertEquals(stamp(day, 2, 29, 0), read(10).lines.single().timestamp)
    }

    @Test
    fun `rolls the day forward when the clock goes backwards`() {
        val day = LocalDate.of(2025, 8, 16)

        writeRotated(
            "$day-1.log.gz",
            "[23:59:58] [Server thread/INFO]: before",
            "[23:59:59] [Server thread/INFO]: still before",
            "[00:00:01] [Server thread/INFO]: after",
            "[00:00:02] [Server thread/INFO]: later"
        )

        val lines = read(10).lines

        assertEquals(stamp(day, 23, 59, 58), lines[0].timestamp)
        assertEquals(stamp(day.plusDays(1), 0, 0, 1), lines[2].timestamp)
        assertEquals(stamp(day.plusDays(1), 0, 0, 2), lines[3].timestamp)
    }

    @Test
    fun `a line with no time inherits the one before it`() {
        val day = LocalDate.of(2025, 8, 16)

        writeRotated(
            "$day-1.log.gz",
            "java.lang.RuntimeException: no timestamp here",
            "[10:00:00] [Server thread/ERROR]: boom",
            "\tat com.example.Thing.run(Thing.java:1)"
        )

        val lines = read(10).lines

        // The first line has nothing to inherit, so it takes the file's day at midnight.
        assertEquals(stamp(day, 0, 0, 0), lines[0].timestamp)
        assertEquals(stamp(day, 10, 0, 0), lines[1].timestamp)
        assertEquals(lines[1].timestamp, lines[2].timestamp)
    }

    @Test
    fun `dates latest log backwards from its mtime when it spans midnight`() {
        val file = writeLatest(
            "[23:59:58] [Server thread/INFO]: before midnight",
            "[00:00:02] [Server thread/INFO]: after midnight"
        )

        val today = LocalDate.of(2025, 3, 4)

        file.setLastModified(stamp(today, 0, 30, 0))

        val lines = read(10).lines

        assertEquals(stamp(today.minusDays(1), 23, 59, 58), lines[0].timestamp)
        assertEquals(stamp(today, 0, 0, 2), lines[1].timestamp)
    }

    @Test
    fun `a request outside the protocol bounds is clamped, not obeyed`() {
        writeLatest(*(1..20).map { "[10:00:00] [Server thread/INFO]: line $it" }.toTypedArray())

        // Over the ceiling is read as the ceiling, and a nonsensical skip as no skip at all.
        assertEquals(20, ServerLogTail.read(File(root, "logs"), ServerLogTail.MAX_LIMIT * 5, -7, zone = zone).lines.size)
        assertEquals(ServerLogTail.Page.EMPTY, ServerLogTail.read(File(root, "logs"), 0, 0, zone = zone))
    }

    @Test
    fun `history from a log file never carries colour spans`() {
        writeLatest("\u001B[31m[10:00:00] [Server thread/INFO]: red in the file\u001B[0m")

        val line = read(10).lines.single()

        // AGENT.md 2.4.21 C: only live lines are coloured; a file is read back plain.
        assertEquals("[10:00:00] [Server thread/INFO]: red in the file", line.message)
        assertEquals(null, line.spans)
    }

    // --- search over the loadable window (AGENT.md 2.4.20) ---

    @Test
    fun `a blank query is the plain paging, byte for byte`() {
        writeRotated("2025-03-03-1.log.gz", *(1..40).map { "[09:00:00] [Server thread/INFO]: old $it" }.toTypedArray())
        writeNumbered(60)

        for ((limit, skip) in listOf(10 to 0, 25 to 30, 500 to 0, 5 to 95, 3 to 200)) {
            val plain = read(limit, skip)

            assertEquals(plain, search(null, limit, skip))
            assertEquals(plain, search("", limit, skip))
            assertEquals(plain, search("   \t ", limit, skip))
        }
    }

    @Test
    fun `skip and limit count matches, newest first, returned oldest first`() {
        writeNumbered(100)

        assertEquals(listOf("line 80 Creeper", "line 90 Creeper", "line 100 Creeper"), found("creeper", 3))
        assertEquals(listOf("line 50 Creeper", "line 60 Creeper", "line 70 Creeper"), found("creeper", 3, 3))
        assertEquals(listOf("line 10 Creeper"), found("creeper", 3, 9))
        assertEquals(emptyList(), found("creeper", 3, 10))
    }

    @Test
    fun `matching is a case-insensitive plain substring, never a regex`() {
        writeLatest(
            "[10:00:00] [Server thread/INFO]: a creeper exploded",
            "[10:00:01] [Server thread/INFO]: A CREEPER EXPLODED",
            "[10:00:02] [Server thread/INFO]: axb",
            "[10:00:03] [Server thread/INFO]: a.b",
            "[10:00:04] [Server thread/INFO]: nothing here"
        )

        assertEquals(listOf("a creeper exploded", "A CREEPER EXPLODED"), found("Creeper Exploded", 10))
        assertEquals(listOf("a.b"), found("a.b", 10), "the dot is a dot, not any character")
        assertEquals(emptyList(), found(".*", 10))
        assertEquals(listOf("A CREEPER EXPLODED"), found("  CREEPER EXPLODED  ", 1), "the query is trimmed")
    }

    @Test
    fun `the query is cut to two hundred characters`() {
        val head = "x".repeat(ServerLogTail.MAX_QUERY_LENGTH)

        writeLatest(
            "[10:00:00] [Server thread/INFO]: ${head}AAA",
            "[10:00:01] [Server thread/INFO]: unrelated"
        )

        assertEquals(head, ServerLogTail.normaliseQuery("  ${head}BBB  "))
        assertEquals(null, ServerLogTail.normaliseQuery("   "))

        // Only the first two hundred characters are matched, so a longer query whose tail is not
        // in the line still finds it.
        assertEquals(listOf("${head}AAA"), found("${head}BBB", 10))
    }

    @Test
    fun `a search never reaches further back than paging could`() {
        val window = ServerLogTail.SEARCH_WINDOW_LINES
        val total = window + 50

        // Lines 1..50 fall outside the window; every one of them is a match, line 51 is the
        // oldest line paging can still reach and it matches too.
        writeLatest(*(1..total).map {
            if (it <= 51) "[10:00:00] [Server thread/INFO]: needle $it" else "[10:00:00] [Server thread/INFO]: hay $it"
        }.toTypedArray())

        val deepest = read(ServerLogTail.MAX_LIMIT, ServerLogTail.MAX_SKIP).lines.first().message

        assertTrue(deepest.endsWith("needle 51"), "the deepest unfiltered page starts at line 51, was $deepest")

        val page = search("needle", ServerLogTail.MAX_LIMIT)

        assertEquals(listOf("needle 51"), page.lines.map { it.message.substringAfterLast(": ") })
        assertFalse(page.hasMore, "nothing older than the window may be offered")
    }

    @Test
    fun `has more only when the page filled and window is left unscanned`() {
        writeNumbered(100)

        assertTrue(search("creeper", 3).hasMore, "a full page with older lines left to look through")
        assertFalse(search("creeper", 20).hasMore, "a short page means the window ran out")

        // The page fills on line 10, and lines 1..9 are still unscanned: hasMore stays on even
        // though none of them matches - finding that out is the next page's job, not this one's.
        assertTrue(search("creeper", 5, 5).hasMore)

        // A page that fills on the very oldest line of the window has nothing left behind it.
        writeLatest(*(1..20).map { "[10:00:00] [Server thread/INFO]: creeper $it" }.toTypedArray())

        assertFalse(search("creeper", 5, 15).hasMore)
        assertTrue(search("creeper", 5, 14).hasMore)
    }
}
