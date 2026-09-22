package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.files.FileAgent
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.InstallPluginMessage
import com.panomc.plugins.pano.core.task.PluginInstallService
import java.util.logging.Logger

/**
 * Handles `INSTALL_PLUGIN`: downloads a jar into `plugins/` (or `mods/`) and says so.
 *
 * No reply. Pano follows the install through `TASK_PROGRESS` exactly as it follows a node's, and
 * the refreshed plugin list that follows is what tells the panel the file is there - which is
 * also why it is sent after a short delay rather than immediately: the jar only becomes a *loaded*
 * plugin on the next restart, so the list is about the files, not about the game.
 */
class InstallPluginHandler(
    private val agent: FileAgent,
    private val platformManager: PlatformManager,
    private val pluginInstallService: PluginInstallService,
    private val logger: Logger
) : PlatformMessageHandler<InstallPluginMessage>() {
    override suspend fun handle(response: InstallPluginMessage) {
        try {
            // Through the file agent's lock: a download writing into `plugins/` while a file
            // manager extraction rewrites the same directory is the one overlap worth ruling out.
            agent.serialised { pluginInstallService.install(response) }
        } catch (exception: Throwable) {
            logger.warning("A plugin install failed: ${exception.javaClass.simpleName}: ${exception.message}")
        }

        platformManager.sendInstalledPlugins()
    }
}
