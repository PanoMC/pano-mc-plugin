package com.panomc.plugins.pano.core.command.commands

import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.helper.CommandHelper
import com.panomc.plugins.pano.core.i18n.I18nManager
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.message.response.GenerateLinkCodeMessage
import com.panomc.plugins.pano.core.platform.message.response.GenerateLinkCodeStatus
import com.panomc.plugins.pano.core.platform.request.GenerateLinkCodeRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.ceil

class LinkCommand(
    private val platformManager: PlatformManager,
    private val i18nManager: I18nManager,
    // CommandManager constructs this directly (not through Spring), so keep this defaulted
    // rather than widening the constructor's required parameters.
    private val logger: Logger = Logger.getLogger(LinkCommand::class.java.name)
) : Command {
    override val name: String = "link"
    override val permission: String? = null
    override val description: String = "Generates a link code to connect your account."
    override val permissionMessage: String = "You do not have permission!"
    override val usage: String = "/link"

    // Per-player cooldown timestamps (epoch ms when the cooldown expires).
    // Mirrors the 30s validity of a link code on the server so spamming the
    // command never produces a different code or floods the websocket.
    private val cooldowns = ConcurrentHashMap<String, Long>()

    companion object {
        private const val COOLDOWN_MS = 30_000L
    }

    override suspend fun handler(commandSender: Any, args: Array<out String>, commandHelper: CommandHelper): Boolean {
        if (!commandHelper.isPlayer(commandSender)) {
            val message = i18nManager.translate(i18nManager.platformLocale, "auth.link.only-players")

            commandHelper.sendMessage(commandSender, message)
            return true
        }

        val username = commandHelper.getUsername(commandSender)
        val cooldownKey = username.lowercase()

        val now = System.currentTimeMillis()
        // Opportunistic purge of expired entries so cooldowns never grows unbounded across the
        // plugin's lifetime (core-misc-17) — every distinct player who has ever run /link
        // otherwise stays in the map permanently.
        cooldowns.values.removeIf { it <= now }
        val cooldownUntil = cooldowns[cooldownKey]
        if (cooldownUntil != null && cooldownUntil > now) {
            val remainingSeconds = ceil((cooldownUntil - now) / 1000.0).toLong().coerceAtLeast(1L)
            val message = i18nManager.translate(
                i18nManager.platformLocale,
                "auth.link.cooldown",
                mapOf("seconds" to remainingSeconds)
            )

            commandHelper.sendMessage(commandSender, message)
            return true
        }

        // Reserve the cooldown slot up-front so concurrent invocations cannot
        // bypass throttling while we are awaiting the platform response.
        cooldowns[cooldownKey] = now + COOLDOWN_MS

        // Runs directly in this suspend handler rather than spawning an inner coroutine
        // scope: every caller already dispatches command.handler onto its own long-lived,
        // cancel-on-disable scope, so an inner scope here only detached this work from that
        // scope's try/catch and CoroutineExceptionHandler, letting it outlive plugin disable.
        try {
            val response = platformManager.sendMessageAwaitResponse<GenerateLinkCodeMessage>(
                GenerateLinkCodeRequest(username),
                GenerateLinkCodeMessage::class.java
            )

            val message = when (response.status) {
                GenerateLinkCodeStatus.ALREADY_REGISTERED -> {
                    // No code was generated and no cooldown is needed: the player
                    // can keep playing without ever needing to /link again.
                    cooldowns.remove(cooldownKey)
                    i18nManager.translate(i18nManager.platformLocale, "auth.link.already-registered")
                }
                GenerateLinkCodeStatus.USER_NOT_FOUND -> {
                    cooldowns.remove(cooldownKey)
                    i18nManager.translate(i18nManager.platformLocale, "auth.link.user-not-found")
                }
                GenerateLinkCodeStatus.SUCCESS -> {
                    val code = response.code
                    if (code.isNullOrEmpty()) {
                        cooldowns.remove(cooldownKey)
                        i18nManager.translate(i18nManager.platformLocale, "auth.link.failed")
                    } else {
                        i18nManager.translate(
                            i18nManager.platformLocale,
                            "auth.link.code-message",
                            mapOf("code" to code)
                        )
                    }
                }
            }

            commandHelper.sendMessage(commandSender, message)
        } catch (e: Exception) {
            cooldowns.remove(cooldownKey)
            val message = i18nManager.translate(i18nManager.platformLocale, "auth.link.failed")

            commandHelper.sendMessage(commandSender, message)
            logger.log(Level.SEVERE, "Failed to generate link code", e)
        }
        return true
    }
}
