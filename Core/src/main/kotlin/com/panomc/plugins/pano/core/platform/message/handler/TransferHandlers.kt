package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.files.FileAgent
import com.panomc.plugins.pano.core.files.FileService
import com.panomc.plugins.pano.core.files.TransferService
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.TransferPullMessage
import com.panomc.plugins.pano.core.platform.message.response.TransferPushMessage
import java.util.logging.Logger

/**
 * `TRANSFER_PULL` - streams one file up to Pano for a browser that is waiting on it.
 *
 * No reply: the browser is blocked on the HTTP exchange the transfer itself is, so both the
 * success and the failure are reported there (see [TransferService]). Run through the file
 * agent's lock all the same, so a download of a world cannot overlap with an extraction rewriting
 * the same files.
 */
class TransferPullHandler(
    private val agent: FileAgent,
    private val transferService: TransferService,
    private val logger: Logger
) : PlatformMessageHandler<TransferPullMessage>() {
    override suspend fun handle(response: TransferPullMessage) {
        try {
            agent.serialised { transferService.pull(response) }
        } catch (exception: Throwable) {
            logger.warning("A transfer to Pano failed: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }
}

/** `TRANSFER_PUSH` - fetches an uploaded file from Pano and writes it into the server. */
class TransferPushHandler(
    private val agent: FileAgent,
    private val transferService: TransferService,
    private val logger: Logger
) : PlatformMessageHandler<TransferPushMessage>() {
    override suspend fun handle(response: TransferPushMessage) {
        val eventId = FileAgent.eventIdOf(response.eventId)

        if (eventId == null) {
            logger.warning("Ignoring a TRANSFER_PUSH from Pano without a usable eventId.")

            return
        }

        val result = try {
            agent.serialised { transferService.push(response) }
        } catch (exception: Throwable) {
            logger.warning("An upload from Pano failed: ${exception.javaClass.simpleName}: ${exception.message}")

            FileService.failure(exception.javaClass.simpleName)
        }

        agent.reply(eventId, result)
    }
}
