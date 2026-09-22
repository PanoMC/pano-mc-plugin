package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.SyncSchedulesMessage
import com.panomc.plugins.pano.core.schedule.ScheduleRunner
import java.util.logging.Logger

/**
 * Handles `SYNC_SCHEDULES`: replaces this server's schedule set with the one Pano just sent.
 *
 * No reply and no merge. Pano sends the whole set on every change and again on every connect, so
 * the only correct thing to do with what arrives is to believe it (AGENT.md 2.4.6).
 */
class SyncSchedulesHandler(
    private val scheduleRunner: ScheduleRunner,
    private val logger: Logger
) : PlatformMessageHandler<SyncSchedulesMessage>() {
    override suspend fun handle(response: SyncSchedulesMessage) {
        try {
            scheduleRunner.sync(response)
        } catch (exception: Throwable) {
            logger.warning("Could not take Pano's schedules: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }
}
