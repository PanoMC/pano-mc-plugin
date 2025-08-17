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
            val textClass = Class.forName("net.minecraft.text.Text")
            val literal = textClass.getMethod("literal", String::class.java)

            val source = try {
                commandSender.javaClass.getMethod("getSource").invoke(commandSender)
            } catch (_: NoSuchMethodException) {
                commandSender
            }
            val feedback = source.javaClass.getMethod(
                "sendFeedback",
                java.util.function.Supplier::class.java,
                Boolean::class.javaPrimitiveType
            )

            val supplier = java.util.function.Supplier {
                literal.invoke(null, pluginMain.translateColor(message))
            }
            feedback.invoke(source, supplier, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
