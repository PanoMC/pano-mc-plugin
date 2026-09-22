package com.panomc.plugins.pano.core.console

import io.vertx.core.json.JsonObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Deep console search: every log file this server has, newest first, a slice per call.
 *
 * [ServerLogTail]'s search only looks as far back as "load older" can page. This one walks the
 * whole `logs` directory - `latest.log`, then every rotation newest first - and hands back what
 * it found in one call together with an opaque cursor that says where the next call picks up, so
 * the panel can keep asking until [Result.done] without any file being scanned twice (except the
 * one a page ended inside of, which is re-read and skipped into).
 *
 * Within one file the matches come back newest line first, so the whole stream runs strictly
 * newest to oldest. The line shape and timestamps are exactly [ServerLogTail]'s: same stripping,
 * same level detection, same day derivation from the file's name or mtime.
 *
 * Like [ServerLogTail] nothing is kept between calls: the cursor is all the state there is, and
 * every read is bounded - a per-file byte cap, a per-line cap, a match limit, a time budget and a
 * reply size. The rules are shared with the node daemon's own search, so a managed and a linked
 * server answer the panel the same way for the same files.
 */
object ServerLogSearch {
    /** Matches per call when Pano sends no limit. */
    const val DEFAULT_LIMIT = 200

    /** Most matches one call may return. */
    const val MAX_LIMIT = 1000

    /** Time budget per call when Pano sends none. */
    const val DEFAULT_BUDGET_MS = 1500L

    const val MIN_BUDGET_MS = 200L

    const val MAX_BUDGET_MS = 5000L

    /** How much of one file may be read (decompressed, for a rotation) before the rest is given up on. */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** How often, in lines, a big file checks whether the time budget has run out. */
    const val CHECK_EVERY_LINES = 10_000

    /** Longest search text honoured; anything past it is cut off, not rejected. */
    const val MAX_QUERY_LENGTH = ServerLogTail.MAX_QUERY_LENGTH

    /** Longest line kept, as everywhere else in the console protocol. */
    const val MAX_LINE_LENGTH = ServerLogTail.MAX_LINE_LENGTH

    /** The only cursor version this reader writes or accepts. */
    private const val CURSOR_VERSION = 1

    const val BAD_QUERY = "BAD_QUERY"

    const val BAD_CURSOR = "BAD_CURSOR"

    private val LINE_TIME = Regex("^\\[(\\d{2}):(\\d{2}):(\\d{2})")

    /** `2026-09-22-3.log.gz`: the day, and the index of the rotation within that day. */
    private val ROTATED_NAME = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:-(\\d+))?")

    /** One match and the file it was found in (`latest.log`, `2026-09-22-3.log.gz`, ...). */
    data class Match(val line: ConsoleLine, val file: String)

    /**
     * One call's answer.
     *
     * [scannedFiles] counts the files finished so far across the whole search - the position the
     * cursor points at - so it reads as progress against [totalFiles]. [scannedBytes] is what this
     * call read. [capped] says a file this call read was cut short by [MAX_FILE_BYTES].
     */
    data class Result(
        val ok: Boolean,
        val error: String?,
        val matches: List<Match>,
        val cursor: String?,
        val done: Boolean,
        val scannedFiles: Int,
        val totalFiles: Int,
        val scannedBytes: Long,
        val capped: Boolean
    ) {
        companion object {
            fun error(code: String) = Result(false, code, emptyList(), null, true, 0, 0, 0L, false)
        }
    }

    /** Where a search resumes: [file], after skipping its [emitted] newest matches. */
    data class Cursor(val file: String, val emitted: Int)

    /**
     * Searches [logDirectory] for [query], starting where [cursor] says (or at the newest line
     * when it is null).
     *
     * [maxBytes] and [lineCost] bound the reply: once the matches taken would cost more than that,
     * the call stops and the cursor records how many of the current file's matches went out.
     * [clock] exists for tests.
     */
    fun search(
        logDirectory: File,
        query: String?,
        cursor: String?,
        limit: Int? = null,
        budgetMs: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        maxBytes: Int = Int.MAX_VALUE,
        lineCost: (ConsoleLine, String) -> Int = { _, _ -> 0 },
        clock: () -> Long = { System.currentTimeMillis() }
    ): Result {
        val needle = ServerLogTail.normaliseQuery(query) ?: return Result.error(BAD_QUERY)

        val take = (limit ?: DEFAULT_LIMIT).let { if (it <= 0) DEFAULT_LIMIT else it }.coerceAtMost(MAX_LIMIT)
        val budget = (budgetMs ?: DEFAULT_BUDGET_MS).coerceIn(MIN_BUDGET_MS, MAX_BUDGET_MS)

        val files = files(logDirectory)

        var index = 0
        var skip = 0

        if (!cursor.isNullOrBlank()) {
            val decoded = decodeCursor(cursor) ?: return Result.error(BAD_CURSOR)

            index = files.indexOfFirst { it.name == decoded.file }

            if (index < 0) {
                return Result.error(BAD_CURSOR)
            }

            skip = decoded.emitted
        }

        val started = clock()
        val found = ArrayList<Match>()

        var bytes = 0L
        var scanned = 0L
        var capped = false
        var first = true
        var next: Cursor? = null

        while (index < files.size) {
            val file = files[index]

            if (!first && (found.size >= take || clock() - started >= budget)) {
                next = Cursor(file.name, 0)

                break
            }

            // The first file of a call always runs to its end, however long that takes: cutting it
            // off would hand back a cursor pointing at the very place this call started from.
            val deadline: (() -> Boolean)? = if (first) null else ({ clock() - started >= budget })

            val scan = try {
                scanFile(file, needle, skip, take - found.size, zone, deadline)
            } catch (exception: Exception) {
                // A file that cannot be read is skipped, the way history skips it.
                null
            }

            first = false

            if (scan == null) {
                index++
                skip = 0

                continue
            }

            scanned += scan.bytes

            if (scan.aborted) {
                next = Cursor(file.name, skip)

                break
            }

            capped = capped || scan.capped

            var taken = 0

            for (line in scan.matches) {
                val cost = lineCost(line, file.name)

                if (bytes + cost > maxBytes && found.isNotEmpty()) {
                    break
                }

                bytes += cost
                found.add(Match(line, file.name))
                taken++
            }

            if (skip + taken < scan.total) {
                next = Cursor(file.name, skip + taken)

                break
            }

            index++
            skip = 0
        }

        return Result(
            ok = true,
            error = null,
            matches = found,
            cursor = next?.let { encodeCursor(it) },
            done = next == null,
            scannedFiles = if (next == null) files.size else index,
            totalFiles = files.size,
            scannedBytes = scanned,
            capped = capped
        )
    }

    /**
     * The files a search walks, in the order it walks them: `latest.log`, then every other `.log`
     * and `.log.gz`, newest first by the date and index in the name, undated ones by mtime.
     */
    internal fun files(logDirectory: File): List<File> {
        val all = logDirectory.listFiles() ?: return emptyList()

        val latest = all.filter { it.isFile && it.name == "latest.log" }

        val rotated = all
            .filter { it.isFile && it.name != "latest.log" && (it.name.endsWith(".log.gz") || it.name.endsWith(".log")) }
            .sortedWith(
                compareByDescending<File> { dateKey(it.name) ?: "" }
                    .thenByDescending { rotationIndex(it.name) }
                    .thenByDescending { it.lastModified() }
                    .thenByDescending { it.name }
            )

        return latest + rotated
    }

    /** `2026-09-22` out of a dated name, or null for one without a date. */
    private fun dateKey(name: String): String? =
        ROTATED_NAME.find(name)?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }

    /** The `N` of `YYYY-MM-DD-N`, compared as a number so `-10` sorts above `-9`. */
    private fun rotationIndex(name: String): Long =
        ROTATED_NAME.find(name)?.groupValues?.get(4)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: -1L

    internal fun encodeCursor(cursor: Cursor): String {
        val json = JsonObject()
            .put("v", CURSOR_VERSION)
            .put("file", cursor.file)
            .put("emitted", cursor.emitted)
            .encode()

        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** The cursor [raw] stands for, or null for anything this reader did not write. */
    internal fun decodeCursor(raw: String): Cursor? = try {
        val json = JsonObject(String(Base64.getUrlDecoder().decode(raw.trim().trimEnd('=')), Charsets.UTF_8))

        val version = json.getValue("v")
        val file = json.getValue("file")
        val emitted = json.getValue("emitted")

        if (version !is Number || version.toInt() != CURSOR_VERSION ||
            file !is String || file.isEmpty() || file.contains('/') || file.contains('\\') ||
            emitted !is Number || emitted.toLong() < 0 || emitted.toLong() > Int.MAX_VALUE
        ) {
            null
        } else {
            Cursor(file, emitted.toInt())
        }
    } catch (exception: Exception) {
        null
    }

    /**
     * What one file gave: up to the requested number of matches after the skipped ones, newest
     * first, and how many matches the whole file holds.
     */
    private class FileScan(
        val matches: List<ConsoleLine>,
        val total: Int,
        val bytes: Long,
        val capped: Boolean,
        val aborted: Boolean
    )

    /**
     * Matches of [needle] in [file], skipping its [skip] newest and taking the next [room].
     *
     * A fresh file is one pass holding at most [room] matches. A resumed one ([skip] above zero)
     * is two: the first only counts, so the second knows which matches are the wanted ones and
     * never has to hold the ones it skips - a file with a million matches must not cost a million
     * lines of memory just because the panel has paged deep into it.
     */
    private fun scanFile(
        file: File,
        needle: String,
        skip: Int,
        room: Int,
        zone: ZoneId,
        deadline: (() -> Boolean)?
    ): FileScan {
        if (skip == 0) {
            val walk = walk(file, needle, zone, deadline) { _, _ -> true }

            if (walk.aborted) {
                return FileScan(emptyList(), 0, walk.bytes, walk.capped, true)
            }

            val kept = walk.kept

            return FileScan(kept.takeLast(room).asReversed().toList(), walk.total, walk.bytes, walk.capped, false)
        }

        val count = walk(file, needle, zone, deadline) { _, _ -> false }

        if (count.aborted) {
            return FileScan(emptyList(), 0, count.bytes, count.capped, true)
        }

        val end = count.total - skip

        if (end <= 0) {
            return FileScan(emptyList(), count.total, count.bytes, count.capped, false)
        }

        val start = maxOf(0, end - room)
        val collect = walk(file, needle, zone, deadline) { index, _ -> index in start until end }

        if (collect.aborted) {
            return FileScan(emptyList(), 0, count.bytes + collect.bytes, collect.capped, true)
        }

        return FileScan(
            collect.kept.asReversed().toList(),
            count.total,
            count.bytes + collect.bytes,
            count.capped || collect.capped,
            false
        )
    }

    /** One match waiting for its day: the file's day count is only known once it is read to the end. */
    private class Pending(val dayOffset: Long, val time: LocalTime, val level: ConsoleLevel, val message: String)

    private class Walk(val kept: List<ConsoleLine>, val total: Int, val bytes: Long, val capped: Boolean, val aborted: Boolean)

    /**
     * Reads [file] forward line by line, dating every line the way [ServerLogTail.parse] does,
     * and keeps the matches [keep] accepts (by match index, oldest first). When [keep] accepts
     * everything only the newest [MAX_LIMIT] are held, which is all a caller can ever take.
     */
    private fun walk(
        file: File,
        needle: String,
        zone: ZoneId,
        deadline: (() -> Boolean)?,
        keep: (Int, Pending) -> Boolean
    ): Walk {
        val opened = open(file)

        val pending = ArrayDeque<Pending>()

        var total = 0
        var lines = 0
        var dayOffset = 0L
        var previousTime: LocalTime? = null
        var previousOffset = 0L
        var previousStamp = LocalTime.MIDNIGHT

        opened.stream.use { stream ->
            val reader = LineReader(
                InputStreamReader(
                    stream,
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                )
            )

            val builder = StringBuilder()

            var skipFirst = opened.dropFirst

            while (reader.next(builder)) {
                // The budget ran out inside this line: what was read of it is a fragment.
                if (!reader.lastTerminated && stream.isExhausted()) {
                    break
                }

                if (skipFirst) {
                    skipFirst = false

                    continue
                }

                lines++

                if (deadline != null && lines % CHECK_EVERY_LINES == 0 && deadline()) {
                    return Walk(emptyList(), 0, stream.bytesRead(), opened.capped || stream.isExhausted(), true)
                }

                val raw = builder.toString().trimEnd('\r')

                if (raw.isBlank()) {
                    continue
                }

                val stripped = AnsiStripper.strip(raw)
                val match = LINE_TIME.find(stripped)

                if (match != null) {
                    val time = LocalTime.of(
                        match.groupValues[1].toInt().coerceAtMost(23),
                        match.groupValues[2].toInt().coerceAtMost(59),
                        match.groupValues[3].toInt().coerceAtMost(59)
                    )

                    val previous = previousTime

                    if (previous != null && time < previous) {
                        dayOffset++
                    }

                    previousTime = time
                    previousOffset = dayOffset
                    previousStamp = time
                }

                val message = if (stripped.length > MAX_LINE_LENGTH) stripped.substring(0, MAX_LINE_LENGTH) else stripped

                if (!message.contains(needle, ignoreCase = true)) {
                    continue
                }

                val line = Pending(previousOffset, previousStamp, ServerLogTail.levelOf(stripped), message)

                if (keep(total, line)) {
                    pending.addLast(line)

                    if (pending.size > MAX_LIMIT) {
                        pending.removeFirst()
                    }
                }

                total++
            }

            val capped = opened.capped || stream.isExhausted()

            // A file dated by its mtime knows the day of its last line, not its first.
            val date = ServerLogTail.dateOf(file, zone)
            val base = if (ROTATED_NAME.containsMatchIn(file.name)) date else date.minusDays(dayOffset)

            val kept = pending.map {
                ConsoleLine(
                    LocalDateTime.of(base.plusDays(it.dayOffset), it.time).atZone(zone).toInstant().toEpochMilli(),
                    it.level,
                    it.message
                )
            }

            return Walk(kept, total, stream.bytesRead(), capped, false)
        }
    }

    private class Opened(val stream: CappedStream, val dropFirst: Boolean, val capped: Boolean)

    /**
     * A file opened for a forward read under [MAX_FILE_BYTES].
     *
     * A rotation is gzip and can only be read from its start, so a huge one gives up its tail. A
     * plain file bigger than the cap is read from [MAX_FILE_BYTES] before its end instead, since
     * its newest lines are the ones worth finding; the fragment that window opens inside of is
     * dropped.
     */
    private fun open(file: File): Opened {
        if (file.name.endsWith(".gz")) {
            return Opened(CappedStream(GZIPInputStream(file.inputStream().buffered()), MAX_FILE_BYTES), false, false)
        }

        val input = FileInputStream(file)

        try {
            val size = input.channel.size()
            val from = maxOf(0L, size - MAX_FILE_BYTES)

            if (from > 0L) {
                input.channel.position(from)
            }

            return Opened(CappedStream(input.buffered(), MAX_FILE_BYTES), from > 0L, from > 0L)
        } catch (exception: Exception) {
            input.close()

            throw exception
        }
    }

    /** An input stream that stops at a byte budget, counts what it read and remembers hitting the budget. */
    private class CappedStream(private val delegate: InputStream, private val budget: Long) : InputStream() {
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

    /**
     * Lines out of a reader, each capped at [MAX_LINE_LENGTH] as it is read, so a runaway line
     * never has to be held whole. [lastTerminated] says whether the last line ended in a newline.
     */
    private class LineReader(private val reader: Reader) {
        private val buffer = CharArray(8192)
        private var position = 0
        private var length = 0

        var lastTerminated = true
            private set

        fun next(builder: StringBuilder): Boolean {
            builder.setLength(0)

            var any = false

            while (true) {
                if (position >= length) {
                    length = reader.read(buffer)
                    position = 0

                    if (length <= 0) {
                        length = 0
                        lastTerminated = false

                        return any
                    }
                }

                any = true

                val start = position

                while (position < length && buffer[position] != '\n') {
                    position++
                }

                val room = MAX_LINE_LENGTH - builder.length

                if (room > 0) {
                    builder.append(buffer, start, minOf(position - start, room))
                }

                if (position < length) {
                    position++
                    lastTerminated = true

                    return true
                }
            }
        }
    }
}
