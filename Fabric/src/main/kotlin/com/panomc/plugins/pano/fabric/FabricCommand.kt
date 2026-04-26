package com.panomc.plugins.pano.fabric

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerPlayer

class FabricCommand(
    private val command: Command,
    private val pluginMain: PanoPluginMain
) : CommandHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as? CommandSourceStack)?.sendSystemMessage(
            FabricTextHelper.parseColoredText(message)
        )
    }

    override fun isPlayer(commandSender: Any): Boolean {
        val source = commandSender as? CommandSourceStack ?: return false
        return source.entity is ServerPlayer
    }

    override fun getUsername(commandSender: Any): String {
        val source = commandSender as? CommandSourceStack ?: return ""
        return try {
            (source.entity as? ServerPlayer)?.getGameProfile()?.name
                ?: source.getTextName()
        } catch (_: Exception) {
            source.getTextName()
        }
    }

    companion object {
        fun register(
            dispatcher: CommandDispatcher<CommandSourceStack>,
            command: Command,
            pluginMain: PanoPluginMain
        ) {
            val fabricCommand = FabricCommand(command, pluginMain)
            val commandName = command.name.lowercase()

            dispatcher.register(
                Commands.literal(commandName)
                    .executes { context ->
                        runBlocking {
                            command.handler(context.source, emptyArray(), fabricCommand)
                        }
                        1
                    }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .executes { context ->
                                val argsString = StringArgumentType.getString(context, "args")
                                val args = argsString.split(" ").toTypedArray()
                                runBlocking {
                                    command.handler(context.source, args, fabricCommand)
                                }
                                1
                            }
                    )
            )
        }
    }
}
