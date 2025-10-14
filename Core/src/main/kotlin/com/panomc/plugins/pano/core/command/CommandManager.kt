package com.panomc.plugins.pano.core.command

import com.panomc.plugins.pano.core.PlatformManager
import com.panomc.plugins.pano.core.command.commands.PanoCommand
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import java.util.logging.Logger

class CommandManager(
    private val logger: Logger,
    private val panoPluginMain: PanoPluginMain,
    platformManager: PlatformManager
) {
    private val commands = listOf<Command>(
        PanoCommand(platformManager)
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