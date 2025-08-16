package com.panomc.plugins.pano.fabric

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource

class FabricCommand(private val command: Command, private val pluginMain: PanoPluginMain) : CommandHelper {
    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            CommandManager.literal(command.name)
                .executes { ctx -> execute(ctx) }
                .then(
                    CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes { ctx -> execute(ctx) }
                )
        )
    }

    private fun execute(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val input = context.input.split(" ").drop(1).toTypedArray()

        runBlocking {
            command.handler(source, input, this@FabricCommand)
        }

        return 1
    }

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as ServerCommandSource).sendFeedback({
            net.minecraft.text.Text.literal(pluginMain.translateColor(message))
        }, false)
    }
}
