package com.panomc.plugins.pano.spigot.integration

import com.panomc.plugins.pano.core.platform.message.response.PlayerAuthenticateMessage
import com.panomc.plugins.pano.core.platform.request.PlayerAuthenticateRequest
import com.panomc.plugins.pano.spigot.Integration
import com.panomc.plugins.pano.spigot.SpigotMain
import fr.xephi.authme.events.PasswordEncryptionEvent
import fr.xephi.authme.security.crypts.EncryptionMethod
import fr.xephi.authme.security.crypts.HashedPassword
import io.vertx.core.http.WebSocket
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority

class AuthMeIntegration(private val spigotMain: SpigotMain) : Integration {
    private val logger by lazy {
        spigotMain.getPanoLogger()
    }

    private val authMePlugin by lazy {
        Bukkit.getPluginManager().getPlugin("AuthMe")!!
    }

    override fun onEnable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("AuthMe")) {
            return
        }

        logger.info("&eAuthMe is enabled, hooking into AuthMe...".colorize())

        registerEvents()

        if (!isConfigCompatible()) {
            logger.warning("&6AuthMe config is not compatible with Pano. Pano will force and reload AuthMe.".colorize())

            forceConfig()
        } else {
            logger.info("&2AuthMe config is compatible with Pano, reloading AuthMe for fixing issues.".colorize())
            reloadAuthMe()
        }


    }

    private fun registerEvents() {
        spigotMain.server.pluginManager.registerEvents(this, spigotMain)

        logger.info("&2Registered events for AuthMe.".colorize())
    }

    private fun forceConfig() {
        val config = authMePlugin.config

        var configChanged = false

        if (config.getString("settings.security.passwordHash") != "CUSTOM") {
            configChanged = true
            config.set("settings.security.passwordHash", "CUSTOM")
            logger.info("Set AuthMe password hash to CUSTOM".colorize())
        }

        if (configChanged) {
            authMePlugin.saveConfig()
            logger.info("AuthMe configuration has been updated for Pano integration".colorize())

            reloadAuthMe()
        }
    }

    private fun reloadAuthMe() {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "authme reload")
            logger.info("AuthMe configuration reloaded".colorize())
        } catch (e: Exception) {
            logger.warning("Could not reload AuthMe automatically: ${e.message}".colorize())
            logger.warning("Please run '/authme reload' manually".colorize())
        }
    }

    private fun String.colorize() = spigotMain.translateColor(this)

    private fun isConfigCompatible(): Boolean {
        val config = authMePlugin.config

        var compatible = true

        if (config.getString("settings.security.passwordHash") != "CUSTOM") {
            compatible = false
        }

        return compatible
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPreLogin(event: PasswordEncryptionEvent) {
        event.method = object : EncryptionMethod {
            override fun computeHash(
                password: String?,
                name: String?
            ): HashedPassword {
                return HashedPassword(password)
            }

            override fun computeHash(
                password: String?,
                salt: String?,
                name: String?
            ): String {
                return ""
            }

            override fun comparePassword(
                password: String,
                hashedPassword: HashedPassword?,
                name: String
            ): Boolean {
                val success: Boolean

                runBlocking {
                    val response = spigotMain.pano.platformManager.sendMessageAwaitResponse<PlayerAuthenticateMessage>(
                        PlayerAuthenticateRequest(
                            name,
                            password
                        )
                    )

                    success = response.success
                }

                return success
            }

            override fun generateSalt(): String = ""

            override fun hasSeparateSalt(): Boolean = false

        }
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
    }
}