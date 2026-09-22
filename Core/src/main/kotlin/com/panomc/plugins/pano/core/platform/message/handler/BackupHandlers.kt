package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.files.BackupService
import com.panomc.plugins.pano.core.files.FileAgent
import com.panomc.plugins.pano.core.files.FileService
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.BackupCreateMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupDeleteMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupListMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupRestoreMessage
import java.util.logging.Logger

/**
 * The four backup requests (AGENT.md 2.4.4 / 2.4.17 C).
 *
 * All of them go through the file agent's single-operation lock and its IO dispatcher: zipping a
 * server directory is the heaviest thing this plugin ever does, and the one thing it must never
 * do is two at once - or one while a file manager extraction is rewriting the same tree.
 */
class BackupCreateHandler(
    private val agent: FileAgent,
    private val backupService: BackupService,
    private val logger: Logger
) : PlatformMessageHandler<BackupCreateMessage>() {
    override suspend fun handle(response: BackupCreateMessage) {
        // No reply: a backup takes minutes, and Pano follows it through TASK_PROGRESS and the
        // BACKUP_CREATED that ends it, exactly as it follows a node's.
        try {
            agent.serialised { backupService.create(response) }
        } catch (exception: Throwable) {
            logger.warning("A backup failed: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }
}

class BackupListHandler(
    private val agent: FileAgent,
    private val backupService: BackupService,
    private val logger: Logger
) : PlatformMessageHandler<BackupListMessage>() {
    override suspend fun handle(response: BackupListMessage) {
        val eventId = FileAgent.eventIdOf(response.eventId) ?: return

        val result = try {
            agent.serialised { backupService.list() }
        } catch (exception: Throwable) {
            logger.warning("Could not list the backups: ${exception.javaClass.simpleName}: ${exception.message}")

            FileService.failure(exception.javaClass.simpleName)
        }

        agent.reply(eventId, result)
    }
}

class BackupDeleteHandler(
    private val agent: FileAgent,
    private val backupService: BackupService,
    private val logger: Logger
) : PlatformMessageHandler<BackupDeleteMessage>() {
    override suspend fun handle(response: BackupDeleteMessage) {
        val eventId = FileAgent.eventIdOf(response.eventId) ?: return

        val result = try {
            agent.serialised { backupService.delete(response) }
        } catch (exception: Throwable) {
            logger.warning("Could not delete a backup: ${exception.javaClass.simpleName}: ${exception.message}")

            FileService.failure(exception.javaClass.simpleName)
        }

        agent.reply(eventId, result)
    }
}

class BackupRestoreHandler(
    private val agent: FileAgent,
    private val backupService: BackupService,
    private val logger: Logger
) : PlatformMessageHandler<BackupRestoreMessage>() {
    override suspend fun handle(response: BackupRestoreMessage) {
        val eventId = FileAgent.eventIdOf(response.eventId) ?: return

        val result = try {
            agent.serialised { backupService.restore(response) }
        } catch (exception: Throwable) {
            logger.warning("Could not arm a restore: ${exception.javaClass.simpleName}: ${exception.message}")

            FileService.failure(exception.javaClass.simpleName)
        }

        agent.reply(eventId, result)
    }
}
