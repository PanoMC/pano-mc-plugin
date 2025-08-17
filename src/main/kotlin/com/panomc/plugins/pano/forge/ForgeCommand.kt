package com.panomc.plugins.pano.forge

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking

/**
 * Simplified command wrapper for Forge. Actual registration should
 * be handled using Forge command APIs.
 */
class ForgeCommand(private val command: Command, private val pluginMain: PanoPluginMain) : CommandHelper {
    fun execute(commandSender: Any, args: Array<String>): Boolean = runBlocking {
        command.handler(commandSender, args, this@ForgeCommand)
    }

    override fun sendMessage(commandSender: Any, message: String) {
        // Message sending should be implemented with Forge APIs.
    }
}
