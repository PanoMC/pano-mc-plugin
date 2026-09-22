package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.console.ConsoleStreamer
import com.panomc.plugins.pano.core.console.ServerLogTail
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.ConsoleHistoryMessage
import com.panomc.plugins.pano.core.platform.request.ConsoleHistoryResultRequest
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.logging.Logger

/**
 * Handles `CONSOLE_HISTORY`: reads a page of scrollback out of the server's log files and
 * answers with `CONSOLE_HISTORY_RESULT` (AGENT.md 2.4.14).
 *
 * Three things this must never do, and how each is avoided:
 *
 * - **Block the server.** The read is a file read of up to a couple of megabytes, so it runs on
 *   [Dispatchers.IO] rather than on the Vert.x event loop that delivered the message (let alone
 *   the game's main thread, which this path never touches at all).
 * - **Let a panel multiply the load.** Every click of "load older" is a fresh request, and a
 *   bored admin can click faster than a disk answers, so [readLock] serialises them: one read at
 *   a time per plugin instance, the rest wait their turn.
 * - **Send a frame Pano will hang up on.** Pano's WebSocket server takes the Vert.x default of
 *   256 KB per message, and this reply carries up to 1 000 lines of up to 4 KB each. The page is
 *   therefore trimmed to [MAX_RESULT_BYTES] of JSON *from the oldest end*, so what is sent stays
 *   one contiguous run ending where the panel is already reading, and `hasMore` is forced on so
 *   the panel knows to ask again for the rest.
 */
class ConsoleHistoryHandler(
    private val platformManager: PlatformManager,
    private val consoleStreamer: ConsoleStreamer,
    private val pluginMain: PanoPluginMain,
    private val logger: Logger
) : PlatformMessageHandler<ConsoleHistoryMessage>() {
    companion object {
        /** What "a screenful" means when Pano sends no limit. */
        const val DEFAULT_LIMIT = 500

        /**
         * Largest reply this will encode, in bytes of JSON before encryption.
         *
         * Pano reads with an unconfigured `HttpServerOptions`, so a WebSocket message over
         * `65536 * 4` bytes is rejected and the connection closed. The payload is Base64 of
         * (12-byte IV + ciphertext + 16-byte tag), i.e. about 4/3 of this, which leaves a
         * comfortable margin under that ceiling for JSON escaping.
         */
        const val MAX_RESULT_BYTES = 160 * 1024

        /** Rough JSON cost of one line's envelope: `{"t":...,"l":"INFO","m":""},`. */
        private const val LINE_OVERHEAD_BYTES = 40

        /** Rough JSON cost of the message itself: event name, event id, flags, brackets. */
        private const val ENVELOPE_BYTES = 256
    }

    // One read at a time per plugin instance. A Mutex rather than a lock: a waiting request
    // suspends its coroutine instead of pinning a thread of the pool the read itself needs.
    private val readLock = Mutex()

    override suspend fun handle(response: ConsoleHistoryMessage) {
        // Pano correlates the answer by this id; without a usable one there is nothing to answer
        // to, so the request is dropped rather than replied to with an id nobody is waiting on.
        val eventId = response.eventId?.let { raw ->
            try {
                UUID.fromString(raw.trim())
            } catch (exception: IllegalArgumentException) {
                null
            }
        }

        if (eventId == null) {
            logger.warning("Ignoring a CONSOLE_HISTORY request from Pano without a usable eventId.")

            return
        }

        // The operator's master switch. A server whose console is off does not read its log files
        // either, and saying so is kinder than an empty page the panel cannot explain.
        if (!consoleStreamer.isEnabled()) {
            send(ConsoleHistoryResultRequest(eventId, emptyList(), false, disabled = true))

            return
        }

        val limit = (response.limit ?: DEFAULT_LIMIT).coerceIn(1, ServerLogTail.MAX_LIMIT)
        val skip = (response.skip ?: 0).coerceIn(0, ServerLogTail.MAX_SKIP)

        val result = try {
            readLock.withLock {
                withContext(Dispatchers.IO) {
                    // A search reads the whole searchable window rather than one page of it, which
                    // is all the more reason for it to stay on the IO pool and behind readLock.
                    ServerLogTail.read(pluginMain.getLogDirectory(), limit, skip, response.query)
                }
            }
        } catch (exception: Throwable) {
            logger.warning("Failed to read console history: ${exception.javaClass.simpleName}: ${exception.message}")

            ServerLogTail.Page.EMPTY
        }

        val lines = result.lines.map { ConsoleHistoryResultRequest.Line(it.timestamp, it.level.name, it.message) }
        val fitted = fit(lines)

        send(ConsoleHistoryResultRequest(eventId, fitted, result.hasMore || fitted.size < lines.size))
    }

    /**
     * [lines] cut down to what fits in one message, dropping the oldest first.
     *
     * The newest end is kept because that is the end the panel prepends onto what it already
     * shows: dropping from there would leave a hole, while dropping from the old end just means
     * the next "load older" starts a little further forward.
     */
    private fun fit(lines: List<ConsoleHistoryResultRequest.Line>): List<ConsoleHistoryResultRequest.Line> {
        var total = ENVELOPE_BYTES
        var first = lines.size

        for (index in lines.indices.reversed()) {
            val cost = sizeOf(lines[index])

            if (total + cost > MAX_RESULT_BYTES && first < lines.size) {
                break
            }

            total += cost
            first = index
        }

        return if (first == 0) lines else lines.subList(first, lines.size)
    }

    /** What one line costs in the encoded message, escaping included. */
    private fun sizeOf(line: ConsoleHistoryResultRequest.Line): Int =
        JsonObject().put("m", line.m).encode().toByteArray(Charsets.UTF_8).size + LINE_OVERHEAD_BYTES

    private fun send(request: ConsoleHistoryResultRequest) {
        try {
            platformManager.sendMessage(request)
        } catch (exception: Throwable) {
            logger.warning("Failed to send console history to Pano: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }
}
