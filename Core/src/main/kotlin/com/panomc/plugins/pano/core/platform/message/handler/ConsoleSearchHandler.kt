package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.console.ConsoleStreamer
import com.panomc.plugins.pano.core.console.ServerLogSearch
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.ConsoleSearchMessage
import com.panomc.plugins.pano.core.platform.request.ConsoleSearchResultRequest
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.logging.Logger

/**
 * Handles `CONSOLE_SEARCH`: one slice of a search across every log file this server has, answered
 * with `CONSOLE_SEARCH_RESULT`.
 *
 * Built the way [ConsoleHistoryHandler] is and for the same reasons: the read runs on
 * [Dispatchers.IO], never on the event loop or the game's main thread; [searchLock] lets only one
 * search read at a time, however fast a panel pages; and the reply is kept under
 * [ConsoleHistoryHandler.MAX_RESULT_BYTES] of JSON so Pano's WebSocket never hangs up on it -
 * matches that do not fit are simply left for the next slice, which the cursor already points at.
 */
class ConsoleSearchHandler(
    private val platformManager: PlatformManager,
    private val consoleStreamer: ConsoleStreamer,
    private val pluginMain: PanoPluginMain,
    private val logger: Logger
) : PlatformMessageHandler<ConsoleSearchMessage>() {
    companion object {
        /** Rough JSON cost of one line's envelope: `{"t":...,"l":"INFO","m":"","f":""},`. */
        private const val LINE_OVERHEAD_BYTES = 48

        /** Rough JSON cost of the message itself: event name, event id, counters, cursor. */
        private const val ENVELOPE_BYTES = 512

        const val READ_FAILED = "READ_FAILED"

        const val DISABLED = "DISABLED"
    }

    private val searchLock = Mutex()

    override suspend fun handle(response: ConsoleSearchMessage) {
        val eventId = response.eventId?.let { raw ->
            try {
                UUID.fromString(raw.trim())
            } catch (exception: IllegalArgumentException) {
                null
            }
        }

        if (eventId == null) {
            logger.warning("Ignoring a CONSOLE_SEARCH request from Pano without a usable eventId.")

            return
        }

        // The operator's master switch, honoured exactly as history honours it.
        if (!consoleStreamer.isEnabled()) {
            send(ConsoleSearchResultRequest.failure(eventId, DISABLED, disabled = true))

            return
        }

        val result = try {
            searchLock.withLock {
                withContext(Dispatchers.IO) {
                    ServerLogSearch.search(
                        pluginMain.getLogDirectory(),
                        response.query,
                        response.cursor,
                        response.limit,
                        response.budgetMs,
                        maxBytes = ConsoleHistoryHandler.MAX_RESULT_BYTES - ENVELOPE_BYTES,
                        lineCost = { line, file -> sizeOf(line.message, file) }
                    )
                }
            }
        } catch (exception: Throwable) {
            logger.warning("Failed to search the console logs: ${exception.javaClass.simpleName}: ${exception.message}")

            null
        }

        if (result == null) {
            send(ConsoleSearchResultRequest.failure(eventId, READ_FAILED))

            return
        }

        if (!result.ok) {
            send(ConsoleSearchResultRequest.failure(eventId, result.error ?: READ_FAILED))

            return
        }

        send(
            ConsoleSearchResultRequest(
                eventId,
                ok = true,
                error = null,
                lines = result.matches.map {
                    ConsoleSearchResultRequest.Line(it.line.timestamp, it.line.level.name, it.line.message, it.file)
                },
                cursor = result.cursor,
                done = result.done,
                scannedFiles = result.scannedFiles,
                totalFiles = result.totalFiles,
                scannedBytes = result.scannedBytes,
                capped = result.capped
            )
        )
    }

    /** What one line costs in the encoded message, escaping included. */
    private fun sizeOf(message: String, file: String): Int =
        JsonObject().put("m", message).put("f", file).encode().toByteArray(Charsets.UTF_8).size + LINE_OVERHEAD_BYTES

    private fun send(request: ConsoleSearchResultRequest) {
        try {
            platformManager.sendMessage(request)
        } catch (exception: Throwable) {
            logger.warning("Failed to send console search results to Pano: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }
}
