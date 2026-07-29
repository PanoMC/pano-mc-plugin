package com.panomc.plugins.pano.core.integration

import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.message.response.GetPlayerInfoMessage
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.platform.request.GetPlayerInfoRequest
import io.vertx.core.http.WebSocket
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BanIntegration(override val panoPluginMain: PanoPluginMain) : Integration {
    private val pano by lazy {
        panoPluginMain.getPano()
    }

    private val platformManager by lazy {
        pano.platformManager
    }

    private val logger by lazy {
        panoPluginMain.getPanoLogger()
    }

    private val i18nManager by lazy {
        pano.i18nManager
    }

    private val eventListeners by lazy {
        setOf(
            object : OnPlayerPreLogin() {
                override suspend fun handle(eventHelper: EventHelper, vararg args: Any) {
                    if (platformManager.getWebSocket() == null) {
                        return
                    }

                    val event = args[0]
                    val username = args[1] as String

                    val playerInfo = platformManager.sendMessageAwaitResponse<GetPlayerInfoMessage>(
                        GetPlayerInfoRequest(username),
                        GetPlayerInfoMessage::class.java
                    )

                    if (!playerInfo.banned) {
                        return
                    }

                    val key = if (playerInfo.bannedUntil != null) {
                        "auth.ban-kick-temporary"
                    } else {
                        "auth.ban-kick-permanent"
                    }

                    val formattedBannedUntil = playerInfo.bannedUntil?.let { timestamp ->
                        val dateTime = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(timestamp),
                            ZoneId.systemDefault()
                        )
                        dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    }

                    // translate() never returns null (core-misc-5): a missing key must not abort
                    // this handler before disallow() runs, or the ban check fails open.
                    val message = i18nManager.translate(playerInfo, key, mapOf("reason" to playerInfo.banReason, "untilTime" to formattedBannedUntil))

                    eventHelper.disallow(event, message)
                }
            }
        )
    }

    private var initialized: Boolean = false

    override fun isInitialized(): Boolean = initialized

    private fun registerEvents() {
        panoPluginMain.registerEventListeners(eventListeners) // register ban event listeners

        logger.info("&2Registered events for ban integration.".colorize())
    }

    private fun start() {
        if (initialized) {
            return
        }

        if (!platformManager.serverSettings.banIntegration) {
            return
        }

        initialized = true

        logger.info("Ban integration is enabled, hooking...")

        registerEvents()

        logger.info("&2Ban integration is hooked!".colorize())
    }

    private fun stop() {
        if (!initialized) {
            return
        }

        panoPluginMain.unregisterEventListeners(this.eventListeners) // unregister ban event listeners

        logger.info("&eBan integration is disabled.".colorize())

        initialized = false
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        if (platformManager.serverSettings.banIntegration) {
            start()
            return
        }

        stop()
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        if (serverSettings.banIntegration) {
            start()
            return
        }

        stop()
    }
}