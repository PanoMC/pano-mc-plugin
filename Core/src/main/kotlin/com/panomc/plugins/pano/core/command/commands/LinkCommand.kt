package com.panomc.plugins.pano.core.command.commands

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.i18n.I18nManager
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.message.response.GenerateLinkCodeMessage
import com.panomc.plugins.pano.core.platform.request.GenerateLinkCodeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LinkCommand(
    private val platformManager: PlatformManager,
    private val i18nManager: I18nManager
) : Command {
    override val name: String = "link"
    override val permission: String? = null
    override val description: String = "Generates a link code to connect your account."
    override val permissionMessage: String = "You do not have permission!"
    override val usage: String = "/link"

    override suspend fun handler(commandSender: Any, args: Array<out String>, commandHelper: CommandHelper): Boolean {
        if (!commandHelper.isPlayer(commandSender)) {
            val message = i18nManager.translate(i18nManager.platformLocale, "auth.link.only-players")!!

            commandHelper.sendMessage(commandSender, message)
            return true
        }

        val username = commandHelper.getUsername(commandSender)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = platformManager.sendMessageAwaitResponse<GenerateLinkCodeMessage>(
                    GenerateLinkCodeRequest(username),
                    GenerateLinkCodeMessage::class.java
                )

                val message = i18nManager.translate(i18nManager.platformLocale, "auth.link.code-message", mapOf("code" to response.code))!!
                
                commandHelper.sendMessage(commandSender, message)
            } catch (e: Exception) {
                val message = i18nManager.translate(i18nManager.platformLocale, "auth.link.failed")!!

                commandHelper.sendMessage(commandSender, message)
                e.printStackTrace()
            }
        }
        return true
    }
}