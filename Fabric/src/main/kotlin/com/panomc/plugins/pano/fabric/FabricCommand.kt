package com.panomc.plugins.pano.fabric

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        // This version's CommandSourceStack has no int-level hasPermission(); permission
        // levels are PermissionCheck.check(PermissionSet) instead (platform-modules-1).
        private fun hasCommandPermission(command: Command, source: CommandSourceStack): Boolean =
            command.permission == null || Commands.LEVEL_ADMINS.check(source.permissions())

        // register() is only ever called with a FabricMain (see FabricMain's two call sites), but
        // takes the interface type; fall back to a throwaway scope in the (unreachable in practice)
        // case that ever changes, rather than silently dropping the command.
        private fun resolveScope(pluginMain: PanoPluginMain): CoroutineScope =
            (pluginMain as? FabricMain)?.getPluginScopeOrNull() ?: CoroutineScope(Dispatchers.IO)

        fun register(
            dispatcher: CommandDispatcher<CommandSourceStack>,
            command: Command,
            pluginMain: PanoPluginMain
        ) {
            val fabricCommand = FabricCommand(command, pluginMain)
            val commandName = command.name.lowercase()

            dispatcher.register(
                Commands.literal(commandName)
                    // Guard the literal root: Brigadier's default requirement is `true`, and a
                    // predicate on the "args" child alone would leave bare "/pano" world-executable
                    // (platform-modules-1 / core-misc-1).
                    .requires { source -> hasCommandPermission(command, source) }
                    .executes { context ->
                        if (!hasCommandPermission(command, context.source)) {
                            fabricCommand.sendMessage(context.source, command.permissionMessage)
                            return@executes 1
                        }

                        // Don't block the dispatching thread (the server main thread here) on the
                        // handler's network I/O; reply asynchronously instead (platform-modules-7).
                        // Resolved per invocation, not captured at registration time, so a future
                        // ordering change can't pin a stale/throwaway scope for the command's lifetime.
                        resolveScope(pluginMain).launch {
                            command.handler(context.source, emptyArray(), fabricCommand)
                        }
                        1
                    }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .executes { context ->
                                if (!hasCommandPermission(command, context.source)) {
                                    fabricCommand.sendMessage(context.source, command.permissionMessage)
                                    return@executes 1
                                }

                                val argsString = StringArgumentType.getString(context, "args")
                                val args = argsString.split(" ").toTypedArray()
                                resolveScope(pluginMain).launch {
                                    command.handler(context.source, args, fabricCommand)
                                }
                                1
                            }
                    )
            )
        }
    }
}
