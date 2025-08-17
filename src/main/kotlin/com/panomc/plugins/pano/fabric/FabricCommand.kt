package com.panomc.plugins.pano.fabric

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import com.mojang.brigadier.context.CommandContext
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import kotlinx.coroutines.runBlocking

class FabricCommand(private val command: Command, private val pluginMain: PanoPluginMain) : CommandHelper {
    fun register(dispatcher: CommandDispatcher<Any>) {
        dispatcher.register(
            literal<Any>(command.name)
                .executes { ctx -> execute(ctx) }
                .then(argument<Any, String>("args", StringArgumentType.greedyString()).executes { ctx -> execute(ctx) })
        )
    }

    private fun execute(context: CommandContext<Any>): Int {
        val args = context.input.split(" ").drop(1).toTypedArray()
        runBlocking {
            command.handler(context.source, args, this@FabricCommand)
        }
        return 1
    }

    override fun sendMessage(commandSender: Any, message: String) {
        try {
            val source = try {
                commandSender.javaClass.getMethod("getSource").invoke(commandSender)
            } catch (_: NoSuchMethodException) {
                commandSender
            }

            val feedback = source.javaClass.methods.firstOrNull {
                it.parameterCount == 2 &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    java.util.function.Supplier::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: throw NoSuchMethodException("sendFeedback")

            val colored = pluginMain.translateColor(message)
            val textObj = FabricTextUtil.toText(colored)
            val supplier = java.util.function.Supplier { textObj }
            feedback.isAccessible = true
            feedback.invoke(source, supplier, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
