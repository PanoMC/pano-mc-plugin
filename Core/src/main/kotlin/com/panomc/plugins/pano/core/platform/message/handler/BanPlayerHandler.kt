package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.BanPlayerMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BanPlayerHandler(
    private val platformManager: PlatformManager,
    private val pluginMain: PanoPluginMain
) : PlatformMessageHandler<BanPlayerMessage>() {
    private val i18nManager by lazy {
        platformManager.i18nManager
    }

    override suspend fun handle(response: BanPlayerMessage) {
        // serverSettings is lateinit and only assigned after the first GET_SERVER_SETTINGS
        // round trip; a BAN_PLAYER push arriving before that (first connect only) would
        // otherwise throw UninitializedPropertyAccessException here and silently drop the kick
        // (integrations-22). Mirrors PermissionIntegration.isPermissionIntegrationEnabled().
        val banIntegrationEnabled = try {
            platformManager.serverSettings.banIntegration
        } catch (_: UninitializedPropertyAccessException) {
            false
        }

        if (!banIntegrationEnabled) {
            return
        }

        val key = if (response.bannedUntil != null) {
            "auth.ban-kick-temporary"
        } else {
            "auth.ban-kick-permanent"
        }

        val formattedBannedUntil = response.bannedUntil?.let { timestamp ->
            val dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault()
            )
            dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        }

        // translate() never returns null (core-misc-5): a missing key must not abort this
        // handler before kickPlayer() runs, or a live ban push is silently dropped.
        val message = i18nManager.translate(response.locale, key, mapOf("reason" to response.banReason, "untilTime" to formattedBannedUntil))

        // Pass the raw '&'-coded string: kickPlayer() implementations do their own color
        // conversion, and pre-translating here double-converts on platforms whose conversion isn't
        // idempotent (Velocity's translateColor() emits ANSI escapes, which its kickPlayer() then
        // fails to parse as legacy-ampersand codes).
        pluginMain.kickPlayer(response.username, message)
    }
}