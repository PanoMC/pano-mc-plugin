package com.panomc.plugins.pano.core.console

import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.zip.GZIPInputStream

/**
 * The scrollback this server wrote before anyone was listening.
 *
 * [ConsoleStreamer]'s ring buffer only holds the last 500 lines and only for as long as the
 * plugin has been running, so a console opened an hour after a boot shows nothing of it - even
 * though the server has been writing every one of those lines to `logs/latest.log` the whole
 * time. This reads those files back on demand (AGENT.md 2.4.14), so a console that opens cold
 * opens on history and "load older" can keep walking backwards through the rotations.
 *
 * **Nothing is kept.** There is no tailing thread, no cache and no state on this object: a call
 * allocates the byte window it reads, the lines it parses out of it, and releases both when it
 * returns. A log is also an untrusted file that a misbehaving plugin can grow to gigabytes, so
 * every read is bounded - a byte window at the end of `latest.log`, a decompression budget per
 * rotated file, a ceiling on how many rotations are opened, and a cap on a single line.
 *
 * The rules are shared with the node daemon's own reader
 * (`com.panomc.node.console.ServerLogTail` in pano-web-platform), so a managed and a linked
 * server hand the panel the same history for the same files.
 */
object ServerLogTail {
    /** Hard ceiling on how much of the end of `latest.log` may be read. */
    const val TAIL_WINDOW_BYTES = 2L * 1024 * 1024

    /** Window budget per requested line: a Minecraft log line is ~120 bytes with its prefix. */
    const val BYTES_PER_LINE = 120L

    /** Floor for the window, so a small request still reads a useful chunk in one go. */
    const val MIN_TAIL_WINDOW_BYTES = 64L * 1024

    /** How much of one rotated file may be decompressed before the rest of it is given up on. */
    const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** How much may be decompressed across all rotated files in one call. */
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

    /** How many rotated files may be opened before the answer is simply "that is all there is". */
    const val MAX_ROTATED_FILES = 3

    /** Longest line kept. Matches the protocol's per-line cap, so nothing is cut twice. */
    const val MAX_LINE_LENGTH = ConsoleLines.MAX_MESSAGE_LENGTH

    /** Most lines one request may ask for (AGENT.md 2.4.14). */
    const val MAX_LIMIT = 1000

    /** Furthest back a request may page, counted in lines from the end of the log. */
    const val MAX_SKIP = 20_000

    /**
     * How many raw lines a search looks through (AGENT.md 2.4.20): exactly the lines unfiltered
     * paging can reach, the deepest page being [MAX_LIMIT] lines [MAX_SKIP] back. Find must never
     * turn up a line that "load older" could not have shown.
     */
    const val SEARCH_WINDOW_LINES = MAX_SKIP + MAX_LIMIT

    /** Longest search text honoured; anything past it is cut off, not rejected. */
    const val MAX_QUERY_LENGTH = 200

    /** Leading `[12:34:56]`, the only clock a Minecraft log line carries. */
    private val LINE_TIME = Regex("^\\[(\\d{2}):(\\d{2}):(\\d{2})")

    /** Modern format, where the level is the tail of a thread tag: `[12:34:56] [Server thread/WARN]:`. */
    private val THREAD_TAGGED = Regex("^\\[\\d{2}:\\d{2}:\\d{2}]\\s+\\[[^\\]]*?/([A-Za-z]+)]:")

    /** Older format, where the level sits next to the timestamp: `[12:34:56 WARN]:`. */
    private val TIME_TAGGED = Regex("^\\[\\d{2}:\\d{2}:\\d{2}\\s+([A-Za-z]+)]:")

    /** `2025-08-16-14.log.gz` and friends: the day a rotated file belongs to is in its name. */
    private val ROTATED_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

    /**
     * One page of history, oldest line first, and whether anything older is still on disk.
     *
     * [hasMore] means the reader stopped because the page was full, not because the files ran
     * out: it is the answer to "is there an older page", and it is only ever true when something
     * older was actually left behind. That is what turns the panel's "load older" button off.
     */
    data class Page(val lines: List<ConsoleLine>, val hasMore: Boolean) {
        companion object {
            val EMPTY = Page(emptyList(), false)
        }
    }

    /** The lines read off the end of a file, and whether that file still holds older ones. */
    data class Tail(val lines: List<String>, val hasOlder: Boolean)

    /**
     * The [limit] console lines that sit [skip] lines back from the end of this server's log,
     * oldest first.
     *
     * [skip] is how many of the newest lines to step over before the page starts: they are the
     * ones the panel already shows, and "give me the 500 before those" is the whole of paging.
     * Nothing is remembered between calls - the page is found by reading backwards from the end
     * every time, which is why [skip] is bounded and why a page costs the lines it steps over.
     *
     * [logDirectory] is the server's own `logs` directory (see `PanoPluginMain.getLogDirectory`).
     * A missing or unreadable one is not a failure: a server that has never written a log has no
     * history, and the console should open empty rather than error.
     *
     * A non-blank [query] turns the page into a search (AGENT.md 2.4.20), see [search]. A blank
     * or missing one is the plain paging above, unchanged.
     */
    fun read(
        logDirectory: File,
        limit: Int,
        skip: Int = 0,
        query: String? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): Page {
        if (limit <= 0 || !logDirectory.isDirectory) {
            return Page.EMPTY
        }

        val take = limit.coerceAtMost(MAX_LIMIT)
        val step = skip.coerceIn(0, MAX_SKIP)
        val needle = normaliseQuery(query)

        if (needle != null) {
            return search(collect(logDirectory, SEARCH_WINDOW_LINES, zone).lines, needle, take, step)
        }

        val want = take + step
        val window = collect(logDirectory, want, zone)

        // An older page is only worth offering when this one came back full: a short page means
        // the reader reached the beginning of the history, wherever that beginning was.
        return Page(window.lines.dropLast(step).takeLast(take), window.older && window.lines.size >= want)
    }

    /**
     * The search text as it is matched: trimmed, then cut to [MAX_QUERY_LENGTH], or null when
     * nothing is left - which is what makes a blank query the plain paging, byte for byte.
     */
    internal fun normaliseQuery(query: String?): String? =
        query?.trim()?.take(MAX_QUERY_LENGTH)?.takeIf { it.isNotEmpty() }

    /**
     * One page of the lines in [window] (oldest first, as [collect] returns it) whose text holds
     * [needle], ignoring case.
     *
     * A plain substring and never a regex: the text comes straight from a panel's search box,
     * and a pattern would hand whoever types there a way to make this thread backtrack forever.
     * Lines are walked newest to oldest and [step] and [take] count *matches*, so "load older"
     * in search mode pages with the number of matches it already shows. The walk stops as soon
     * as the page is full, and [Page.hasMore] is true only then *and* while some of the window is
     * still unscanned - whether that remainder holds another match is left for the next page to
     * find out, which keeps a search as cheap as the page it returns.
     */
    internal fun search(window: List<ConsoleLine>, needle: String, take: Int, step: Int): Page {
        val found = ArrayList<ConsoleLine>(minOf(take, 64))

        var skipped = 0
        var index = window.size - 1

        while (index >= 0 && found.size < take) {
            val line = window[index]

            if (line.message.contains(needle, ignoreCase = true)) {
                if (skipped < step) {
                    skipped++
                } else {
                    found.add(line)
                }
            }

            index--
        }

        return Page(found.asReversed().toList(), found.size >= take && index >= 0)
    }

    /** The newest lines one read reached, oldest first, and whether anything older is on disk. */
    private class Collected(val lines: List<ConsoleLine>, val older: Boolean)

    /**
     * Reads the newest [want] lines of this server's history across `latest.log` and the
     * rotations, under every byte budget above. Shared by paging and search, so both see the
     * same lines for the same [want].
     */
    private fun collect(logDirectory: File, want: Int, zone: ZoneId): Collected {
        val collected = ArrayDeque<ConsoleLine>()

        // Whether anything older than the oldest collected line is still on disk. Only ever set
        // by a reader that had to leave something behind, so a false here means the files really
        // did run out rather than that nobody looked.
        var older = false

        // The byte window ran out before the page was full. What is older than it is still in the
        // file but out of reach, and carrying on into a rotated file would report a history with
        // a hole in the middle of it as though it were continuous.
        var walled = false

        val latest = File(logDirectory, "latest.log")

        if (latest.isFile) {
            val tail = try {
                tailLines(latest, want)
            } catch (exception: Exception) {
                Tail(emptyList(), false)
            }

            val left = prepend(collected, parse(tail.lines, dateOf(latest, zone), zone, anchorEnd(latest)), want)

            older = left || tail.hasOlder
            walled = tail.hasOlder && collected.size < want
        }

        if (!walled) {
            var budget = MAX_TOTAL_BYTES
            var opened = 0

            for (file in rotated(logDirectory)) {
                if (collected.size >= want || opened >= MAX_ROTATED_FILES || budget <= 0) {
                    // Stopped with files still unread: whatever is in them is older than this page.
                    older = true

                    break
                }

                opened++

                val raw = try {
                    gzipLines(file, want, minOf(MAX_FILE_BYTES, budget))
                } catch (exception: Exception) {
                    continue
                }

                budget -= raw.bytesRead

                if (raw.hasOlder) {
                    older = true
                }

                if (prepend(collected, parse(raw.lines, dateOf(file, zone), zone, anchorEnd(file)), want)) {
                    older = true
                }
            }
        }

        return Collected(collected.toList(), older)
    }

    /**
     * Puts an older file's lines in front of what is already collected, keeping the newest N.
     *
     * Reports whether anything had to be left behind, which is this file saying it still holds
     * lines older than the ones that fit.
     */
    private fun prepend(target: ArrayDeque<ConsoleLine>, older: List<ConsoleLine>, limit: Int): Boolean {
        val room = limit - target.size

        if (room <= 0) {
            return older.isNotEmpty()
        }

        older.takeLast(room).asReversed().forEach { target.addFirst(it) }

        return older.size > room
    }

    /**
     * The rotated logs, newest first.
     *
     * Dated names sort themselves, and anything without a date falls back to its mtime, which is
     * what a fork that rotates to `server.log.1` leaves behind.
     */
    private fun rotated(logs: File): List<File> = (logs.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name != "latest.log" && (it.name.endsWith(".log.gz") || it.name.endsWith(".log")) }
        .sortedWith(compareByDescending<File> { it.name.takeIf { name -> ROTATED_DATE.containsMatchIn(name) } ?: "" }
            .thenByDescending { it.lastModified() }
            .thenByDescending { it.name })

    /**
     * The last [want] lines of a plain file, read through a byte window.
     *
     * The window is budgeted per line rather than fixed, so an ordinary request reads a few tens
     * of kilobytes instead of two megabytes, and it doubles - up to [TAIL_WINDOW_BYTES] - when a
     * file of unusually long lines does not fill the request. Either way the answer is the same
     * lines a flat two-megabyte window would have given, which is what keeps this in step with
     * the node's reader.
     *
     * The window never starts at a line boundary, so the first fragment it catches is thrown away
     * rather than reported as a line that was never written. [Tail.hasOlder] says the file still
     * holds lines before the ones returned - either behind the window or beyond [want].
     */
    internal fun tailLines(file: File, want: Int): Tail {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val size = channel.size()

            if (size <= 0L) {
                return Tail(emptyList(), false)
            }

            var window = (BYTES_PER_LINE * want).coerceIn(MIN_TAIL_WINDOW_BYTES, TAIL_WINDOW_BYTES)

            while (true) {
                val from = maxOf(0L, size - window)
                val bytes = ByteArray((size - from).toInt())

                channel.position(from)

                var read = 0

                while (read < bytes.size) {
                    val count = channel.read(ByteBuffer.wrap(bytes, read, bytes.size - read))

                    if (count <= 0) {
                        break
                    }

                    read += count
                }

                val pieces = split(decode(bytes, 0, read))

                // The fragment the window opened inside of is not a line anyone wrote, and a log
                // file ends with a newline, so its trailing empty piece is not one either. Blank
                // lines go the same way: they carry nothing and would spend the limit.
                val lines = (if (from > 0L && pieces.isNotEmpty()) pieces.drop(1) else pieces)
                    .filter { it.isNotBlank() }

                if (lines.size >= want || from == 0L || window >= TAIL_WINDOW_BYTES) {
                    return Tail(lines.takeLast(want).map { cap(it) }, from > 0L || lines.size > want)
                }

                window = minOf(window * 2, TAIL_WINDOW_BYTES)
            }
        }
    }

    /**
     * One rotated file's last lines, with a hard ceiling on how much of it is decompressed.
     *
     * A gzip stream can only be read forwards, so a file bigger than its budget gives up the rest
     * of itself rather than being decompressed whole to reach the last screenful of it.
     */
    private fun gzipLines(file: File, want: Int, byteBudget: Long): RawLines {
        val raw: InputStream = if (file.name.endsWith(".gz")) {
            GZIPInputStream(file.inputStream().buffered())
        } else {
            file.inputStream().buffered()
        }

        val budgeted = BudgetedStream(raw, byteBudget)

        budgeted.use { input ->
            val reader = InputStreamReader(
                input,
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
            ).buffered()

            val kept = ArrayDeque<String>()

            var dropped = false

            while (true) {
                val line = readLine(reader) ?: break

                if (line.isNotBlank() && push(kept, line, want)) {
                    dropped = true
                }
            }

            // The budget ran out somewhere inside a line; whatever was kept of it is a fragment,
            // and a fragment is not something to show an operator as a line the server printed.
            if (budgeted.isExhausted() && kept.isNotEmpty()) {
                kept.removeLast()
            }

            return RawLines(kept.toList(), budgeted.bytesRead(), dropped)
        }
    }

    /**
     * One line, capped as it is read.
     *
     * Reading it with [java.io.BufferedReader.readLine] would mean holding a whole line in memory
     * before deciding it is too long, and "too long" here can be the entire budget.
     */
    private fun readLine(reader: Reader): String? {
        val builder = StringBuilder()

        var any = false

        while (true) {
            val value = reader.read()

            if (value < 0) {
                return if (any) builder.toString().trimEnd('\r') else null
            }

            any = true

            val char = value.toChar()

            if (char == '\n') {
                return builder.toString().trimEnd('\r')
            }

            if (builder.length < MAX_LINE_LENGTH) {
                builder.append(char)
            }
        }
    }

    /** An input stream that stops at a byte budget and remembers that it did. */
    private class BudgetedStream(private val delegate: InputStream, private val budget: Long) : InputStream() {
        private var read = 0L
        private var exhausted = false

        fun bytesRead(): Long = read

        fun isExhausted(): Boolean = exhausted

        override fun read(): Int {
            if (read >= budget) {
                exhausted = true

                return -1
            }

            val value = delegate.read()

            if (value >= 0) {
                read++
            }

            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (read >= budget) {
                exhausted = true

                return -1
            }

            val count = delegate.read(buffer, offset, minOf(length.toLong(), budget - read).toInt())

            if (count > 0) {
                read += count
            }

            return count
        }

        override fun close() = delegate.close()
    }

    /** Keeps the newest [limit] lines, reporting whether an older one had to be evicted. */
    private fun push(kept: ArrayDeque<String>, line: String, limit: Int): Boolean {
        kept.addLast(cap(line))

        var dropped = false

        while (kept.size > limit) {
            kept.removeFirst()

            dropped = true
        }

        return dropped
    }

    private class RawLines(val lines: List<String>, val bytesRead: Long, val hasOlder: Boolean)

    /** UTF-8 with malformed bytes replaced: a window cut mid-character must not throw. */
    private fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
    }

    private fun split(text: String): List<String> = text.split('\n').map { it.trimEnd('\r') }

    private fun cap(line: String): String =
        if (line.length > MAX_LINE_LENGTH) line.substring(0, MAX_LINE_LENGTH) else line

    /**
     * Turns one file's raw lines into console lines.
     *
     * A log line carries a time of day and nothing else, so the day comes from the file: its name
     * when that is dated, its mtime otherwise. Times that go backwards inside a file mean midnight
     * passed, so the day rolls forward. A line with no readable time inherits the timestamp before
     * it, and the very first such line is dated to that day at 00:00.
     *
     * [anchorEnd] is for a file dated by its mtime, where the known day is the day of the *last*
     * line rather than the first: `latest.log` read at one in the morning starts the evening
     * before, and dating its first line today would put the whole boot in the future.
     */
    internal fun parse(
        raw: List<String>,
        date: LocalDate,
        zone: ZoneId,
        anchorEnd: Boolean = false
    ): List<ConsoleLine> {
        val first = parseFrom(raw, date, zone)

        if (!anchorEnd || first.rollovers == 0L) {
            return first.lines
        }

        return parseFrom(raw, date.minusDays(first.rollovers), zone).lines
    }

    private fun parseFrom(raw: List<String>, date: LocalDate, zone: ZoneId): Parsed {
        var day = date
        var previousTime: LocalTime? = null
        var previousStamp = LocalDateTime.of(date, LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()

        val lines = ArrayList<ConsoleLine>(raw.size)

        raw.forEach { line ->
            if (line.isEmpty()) {
                return@forEach
            }

            val stripped = AnsiStripper.strip(line)
            val match = LINE_TIME.find(stripped)

            val stamp = if (match == null) {
                previousStamp
            } else {
                val time = LocalTime.of(
                    match.groupValues[1].toInt().coerceAtMost(23),
                    match.groupValues[2].toInt().coerceAtMost(59),
                    match.groupValues[3].toInt().coerceAtMost(59)
                )

                val previous = previousTime

                if (previous != null && time < previous) {
                    day = day.plusDays(1)
                }

                previousTime = time

                LocalDateTime.of(day, time).atZone(zone).toInstant().toEpochMilli()
            }

            previousStamp = stamp

            lines.add(ConsoleLine(stamp, levelOf(stripped), cap(stripped)))
        }

        return Parsed(lines, ChronoUnit.DAYS.between(date, day))
    }

    private class Parsed(val lines: List<ConsoleLine>, val rollovers: Long)

    /**
     * The level a log line announces, or INFO when it announces none.
     *
     * A file is all the plugin has here - there is no `LogEvent` to ask - so the level is read
     * back out of the text the server printed, exactly as the node does it.
     */
    internal fun levelOf(stripped: String): ConsoleLevel {
        val match = THREAD_TAGGED.find(stripped) ?: TIME_TAGGED.find(stripped) ?: return ConsoleLevel.INFO

        return when (match.groupValues[1].uppercase()) {
            "TRACE", "FINEST", "FINER" -> ConsoleLevel.TRACE
            "DEBUG", "FINE", "CONFIG" -> ConsoleLevel.DEBUG
            "INFO" -> ConsoleLevel.INFO
            "WARN", "WARNING" -> ConsoleLevel.WARN
            "ERROR", "SEVERE", "FATAL" -> ConsoleLevel.ERROR
            else -> ConsoleLevel.INFO
        }
    }

    /** Whether a file's day has to be read off its last line rather than its first. */
    private fun anchorEnd(file: File): Boolean = !ROTATED_DATE.containsMatchIn(file.name)

    /** The day a log file belongs to: the date in its name, or the day it was last written. */
    internal fun dateOf(file: File, zone: ZoneId): LocalDate {
        val match = ROTATED_DATE.find(file.name)

        if (match != null) {
            try {
                return LocalDate.of(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt()
                )
            } catch (exception: Exception) {
                // A name that looks dated but is not; the mtime is still true.
            }
        }

        return Instant.ofEpochMilli(file.lastModified()).atZone(zone).toLocalDate()
    }
}
