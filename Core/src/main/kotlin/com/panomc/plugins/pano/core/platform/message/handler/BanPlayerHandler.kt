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
        if (!platformManager.serverSettings.banIntegration) {
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

        val message = i18nManager.translate(response.locale, key, mapOf("reason" to response.banReason, "untilTime" to formattedBannedUntil))!!

        pluginMain.kickPlayer(response.username, pluginMain.translateColor(message))
    }
}