package com.panomc.plugins.pano.fabric

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity

class FabricCommand(
    private val command: Command,
    private val pluginMain: PanoPluginMain
) : CommandHelper {

    override fun sendMessage(commandSender: Any, message: String) {
        (commandSender as? ServerCommandSource)?.sendMessage(
            FabricTextHelper.parseColoredText(message)
        )
    }

    override fun isPlayer(commandSender: Any): Boolean {
        val source = commandSender as? ServerCommandSource ?: return false
        return source.entity is ServerPlayerEntity
    }

    override fun getUsername(commandSender: Any): String {
        val source = commandSender as? ServerCommandSource ?: return ""
        return try {
            (source.entity as? ServerPlayerEntity)?.name?.string ?: source.name
        } catch (_: Exception) {
            source.name
        }
    }

    companion object {
        fun register(
            dispatcher: CommandDispatcher<ServerCommandSource>,
            command: Command,
            pluginMain: PanoPluginMain
        ) {
            val fabricCommand = FabricCommand(command, pluginMain)

            // Register with lowercase name (Brigadier is case-sensitive)
            val commandName = command.name.lowercase()

            dispatcher.register(
                CommandManager.literal(commandName)
                    .executes { context ->
                        // No args
                        runBlocking {
                            command.handler(context.source, emptyArray(), fabricCommand)
                        }
                        1
                    }
                    .then(
                        CommandManager.argument("args", StringArgumentType.greedyString())
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
