package com.panomc.plugins.pano.core.files

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.message.response.FileRequestMessage
import com.panomc.plugins.pano.core.platform.request.FileResultRequest
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.logging.Logger

/**
 * Runs one file operation at a time, off the server's threads, and answers Pano with the result.
 *
 * Three things this must never do, and how each is avoided:
 *
 * - **Block the server.** Every operation is disk work, so it runs on [Dispatchers.IO] rather
 *   than on the Vert.x event loop that delivered the message (let alone the game's main thread,
 *   which this path never touches at all).
 * - **Let a panel multiply the load.** A file manager can issue requests as fast as a person can
 *   click, so [lock] serialises them: one operation at a time per plugin instance, the rest wait
 *   their turn. It also means a listing can never observe a half-finished extraction.
 * - **Leave a panel hanging.** Pano parks the person's HTTP request on the `eventId` until this
 *   answers, so every path out of here sends a `FILE_RESULT` - including the ones where the
 *   operation itself blew up, which come back as `{ ok: false, error }`.
 */
class FileAgent(
    private val pluginMain: PanoPluginMain,
    private val send: (PlatformRequest) -> Unit,
    private val logger: Logger
) {
    private val lock = Mutex()

    private val service by lazy { FileService(pluginMain, logger) }

    /** Answers one `FILE_*` request. [event] is the operation name, i.e. the handler's own name. */
    suspend fun handle(event: String, message: FileRequestMessage) {
        val eventId = eventIdOf(message.eventId)

        if (eventId == null) {
            logger.warning("Ignoring a $event request from Pano without a usable eventId.")

            return
        }

        val result = try {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    service.handle(event, message)
                }
            }
        } catch (exception: Throwable) {
            logger.warning("$event failed: ${exception.javaClass.simpleName}: ${exception.message}")

            FileService.failure(exception.javaClass.simpleName)
        }

        reply(eventId, result)
    }

    /** Sends one `FILE_RESULT`, swallowing a dead socket rather than failing the caller. */
    fun reply(eventId: UUID, result: JsonObject) {
        try {
            send(FileResultRequest(eventId, result))
        } catch (exception: Throwable) {
            logger.warning("Failed to answer Pano: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    /** Runs [block] under the same one-at-a-time lock, for the operations that are not `FILE_*`. */
    suspend fun <T> serialised(block: () -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            block()
        }
    }

    companion object {
        /** Pano's correlation id, or null when it sent one nothing can be answered on. */
        fun eventIdOf(raw: String?): UUID? = try {
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { UUID.fromString(it) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
