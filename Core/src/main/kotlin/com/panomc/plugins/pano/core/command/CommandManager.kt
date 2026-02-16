package com.panomc.plugins.pano.core.command

import com.panomc.plugins.pano.core.command.commands.LinkCommand
import com.panomc.plugins.pano.core.command.commands.PanoCommand
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.i18n.I18nManager
import com.panomc.plugins.pano.core.platform.PlatformManager
import java.util.logging.Logger

class CommandManager(
    private val logger: Logger,
    private val panoPluginMain: PanoPluginMain,
    platformManager: PlatformManager,
    i18nManager: I18nManager
) {
    private val commands = listOf<Command>(
        PanoCommand(platformManager),
        LinkCommand(platformManager, i18nManager)
    )

    fun init() {
        logger.info("Registering commands")

        panoPluginMain.registerCommands(commands)
    }

    fun disable() {
        logger.info("Unregistering commands")

        panoPluginMain.unregisterCommands(commands)
    }
}