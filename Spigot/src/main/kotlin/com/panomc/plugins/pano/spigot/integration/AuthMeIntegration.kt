package com.panomc.plugins.pano.spigot.integration

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.platform.message.response.*
import com.panomc.plugins.pano.core.platform.request.*
import com.panomc.plugins.pano.core.util.EmailUtil.maskEmail
import com.panomc.plugins.pano.spigot.Integration
import com.panomc.plugins.pano.spigot.SpigotMain
import com.panomc.plugins.pano.spigot.SpigotServerUtil.getPlayerIp
import fr.xephi.authme.api.v3.AuthMeApi
import fr.xephi.authme.events.LoginEvent
import fr.xephi.authme.events.LogoutEvent
import fr.xephi.authme.events.PasswordEncryptionEvent
import fr.xephi.authme.events.RegisterEvent
import fr.xephi.authme.security.crypts.EncryptionMethod
import fr.xephi.authme.security.crypts.HashedPassword
import io.vertx.core.http.WebSocket
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerCommandEvent
import java.util.*

class AuthMeIntegration(private val spigotMain: SpigotMain) : Integration {
    private val logger by lazy {
        spigotMain.getPanoLogger()
    }

    private val authMePlugin by lazy {
        Bukkit.getPluginManager().getPlugin("AuthMe")!!
    }

    private val authMeApi by lazy {
        AuthMeApi.getInstance()
    }

    private val platformManager by lazy {
        spigotMain.pano.platformManager
    }

    private val eventManager by lazy {
        spigotMain.pano.eventManager
    }

    private val i18nManager by lazy {
        spigotMain.pano.i18nManager
    }

    // when register command is called, saved here
    private val pendingRegisterPasswords = mutableMapOf<String, String>()

    internal var initialized: Boolean = false

    private fun start() {
        if (initialized) {
            return
        }

        if (!platformManager.serverSettings.authIntegration) {
            return
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("AuthMe")) {
            return
        }

        initialized = true

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

    private fun stop() {
        if (!initialized) {
            return
        }

        HandlerList.unregisterAll(this)
        spigotMain.registerEventListeners(eventManager.eventListeners)
        reloadAuthMe()

        logger.info("&eAuthMe integration is disabled.".colorize())

        initialized = false
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        if (platformManager.serverSettings.authIntegration) {
            start()
            return
        }

        stop()
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        if (serverSettings.authIntegration) {
            start()
            return
        }

        stop()
    }

    private fun registerEvents() {
        spigotMain.unregisterEventListeners(listOf())
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

        if (config.getString("settings.registration.type") != "PASSWORD") {
            configChanged = true
            config.set("settings.registration.type", "PASSWORD")
            logger.info("Set AuthMe register type to PASSWORD".colorize())
        }

        if (configChanged) {
            authMePlugin.saveConfig()
            logger.info("AuthMe configuration has been updated for Pano integration".colorize())

            reloadAuthMe()
        }
    }

    private fun reloadAuthMe() {
        try {
            Bukkit.getScheduler().runTask(spigotMain, Runnable {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "authme reload")
                logger.info("AuthMe configuration reloaded".colorize())
            })
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

        if (config.getString("settings.registration.type") != "PASSWORD") {
            compatible = false
        }

        return compatible
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerLogin(event: LoginEvent) {
        eventManager.eventListeners.find { it is OnPlayerJoin }?.handle(spigotMain.eventHelper, event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerRegister(event: RegisterEvent) {
        val player = event.player
        val playerName = player.name

        val pendingPassword = pendingRegisterPasswords[playerName] ?: return

        pendingRegisterPasswords.remove(playerName)

        val request = RegisterPlayerRequest(playerName, pendingPassword, getPlayerIp(playerName) ?: "unknown")

        runBlocking {
            val registerResponse = platformManager.sendMessageAwaitResponse<RegisterPlayerMessage>(
                request,
                RegisterPlayerMessage::class.java
            )

            if (registerResponse.error != null) {
                player.kickPlayer("")
                logger.severe("&cAn error occurred during the registration of \"$playerName\": ${registerResponse.error}".colorize())
                return@runBlocking
            }

            logger.info("&2Successfully registered player \"$playerName\".".colorize())

            if (platformManager.serverSettings.authKickAfterRegister) {
                val message = i18nManager.translate(i18nManager.platformLocale, "auth.register-kick")!!

                player.kickPlayer(message.colorize())
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerLogout(event: LogoutEvent) {
        eventManager.eventListeners.find { it is OnPlayerDisconnect }?.handle(spigotMain.eventHelper, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerDisconnect(event: PlayerQuitEvent) {
        if (authMeApi.isAuthenticated(event.player)) {
            eventManager.eventListeners.find { it is OnPlayerDisconnect }?.handle(spigotMain.eventHelper, event.player)
        }

        if (pendingRegisterPasswords[event.player.name] != null) {
            pendingRegisterPasswords.remove(event.player.name)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerJoin(event: AsyncPlayerPreLoginEvent) {
        runBlocking {
            val playerName = event.name
            val playerInfo =
                platformManager.sendMessageAwaitResponse<GetPlayerInfoMessage>(
                    GetPlayerInfoRequest(playerName),
                    GetPlayerInfoMessage::class.java
                )

            if (platformManager.serverSettings.authRequireVerified && playerInfo.registered && !playerInfo.verified) { // registered but not verified, kick
                val key = if (playerInfo.email.isNullOrBlank()) "auth.register-kick" else "auth.not-verified"
                val message = i18nManager.translate(playerInfo, key, mapOf("email" to maskEmail(playerInfo.email)))!!

                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message.colorize())
            }

            val registeredInAuthMe = authMeApi.isRegistered(playerName)

            if (!registeredInAuthMe && playerInfo.registered) {
                // registered in Pano but not in AuthMe
                authMeApi.registerPlayer(playerName, UUID.randomUUID().toString())
            } else if (registeredInAuthMe && !playerInfo.registered) {
                // registered in AuthMe but not in Pano
                authMeApi.forceUnregister(playerName)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        if (event.isCancelled) {
            return
        }

        val msg = event.message.lowercase()

        val listOfUnsupported = listOf(
            "/unregister",
            "/authme unregister",
            "/authme unreg"
            )

        if (listOfUnsupported.any { msg.startsWith(it) }) {
            // Check permission based on command (only for admin commands)
            val hasPermission = when {
                msg.startsWith("/authme unregister") || msg.startsWith("/authme unreg") ->
                    event.player.hasPermission("authme.admin.unregister")
                msg.startsWith("/authme changepass") || msg.startsWith("/authme changepassword") ->
                    event.player.hasPermission("authme.admin.changepassword")
                else -> false
            }

            // If player has permission, let AuthMe handle it
            if (hasPermission) {
                return
            }

            event.isCancelled = true
            event.player.sendMessage("&cThis command is unsupported by Pano!".colorize())
            event.player.sendMessage("&cCheckout docs: https://panomc.com/docs".colorize())
            return
        }

        if (msg.startsWith("/authme reg") || msg.startsWith("/authme register")) {
            // If player doesn't have permission, let AuthMe handle the permission check
            if (!event.player.hasPermission("authme.admin.register")) {
                return
            }

            val args = event.message.split("\\s+".toRegex()) // split spaces

            if (args.size != 4) {
                return
            }

            val playerName = args[2]
            val password = args[3]

            runBlocking {
                val response =
                    platformManager.sendMessageAwaitResponse<IsPlayerRegisteredMessage>(
                        IsPlayerRegisteredRequest(
                            playerName
                        ),
                        IsPlayerRegisteredMessage::class.java
                    )

                if (response.registered) {
                    event.player.sendMessage("&c${playerName} is already registered.".colorize())
                    event.isCancelled = true
                    return@runBlocking
                }

                val request = RegisterPlayerRequest(playerName, password, getPlayerIp(playerName) ?: "unknown")
                val registerResponse = platformManager.sendMessageAwaitResponse<RegisterPlayerMessage>(
                    request,
                    RegisterPlayerMessage::class.java
                )

                if (registerResponse.error != null) {
                    event.isCancelled = true
                    event.player.sendMessage("&cAn error occurred during the registration of \"$playerName\": ${registerResponse.error}".colorize())
                    return@runBlocking
                }

                event.player.sendMessage("&2Successfully registered player \"$playerName\".".colorize())
                logger.info("&2Successfully registered player \"$playerName\".".colorize())

                val player = Bukkit.getPlayer(playerName)

                if ((player?.isOnline ?: false) && platformManager.serverSettings.authKickAfterRegister) {
                    val message = i18nManager.translate(i18nManager.platformLocale, "auth.register-kick")!!

                    player.kickPlayer(message.colorize())
                }
            }
        }

        if (msg.startsWith("/authme reload")) {
            // If player doesn't have permission, let AuthMe handle the permission check
            if (!event.player.hasPermission("authme.admin.reload")) {
                return
            }

            authMePlugin.reloadConfig()

            if (!isConfigCompatible()) {
                event.player.sendMessage("&6AuthMe config is not compatible with Pano. Pano will force and reload AuthMe.".colorize())

                forceConfig()

                event.player.sendMessage("&2AuthMe reloaded successfully!".colorize())
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onServerCommandProcess(event: ServerCommandEvent) {
        val msg = event.command.lowercase()

        val listOfUnsupported = listOf(
            "authme unregister",
            "authme unreg"
        )

        if (listOfUnsupported.any { msg.startsWith(it) }) {
            event.isCancelled = true
            event.sender.sendMessage("&cThis command is unsupported by Pano!".colorize())
            event.sender.sendMessage("&cCheckout docs: https://panomc.com/docs".colorize())
            return
        }

        if (msg.startsWith("authme reg") || msg.startsWith("authme register")) {
            val args = event.command.split("\\s+".toRegex()) // split spaces

            if (args.size != 4) {
                return
            }

            val playerName = args[2]
            val password = args[3]

            runBlocking {
                val response =
                    platformManager.sendMessageAwaitResponse<IsPlayerRegisteredMessage>(
                        IsPlayerRegisteredRequest(
                            playerName
                        ),
                        IsPlayerRegisteredMessage::class.java
                    )

                if (response.registered) {
                    logger.severe("&c${playerName} is already registered.".colorize())
                    event.isCancelled = true
                    return@runBlocking
                }

                val request = RegisterPlayerRequest(playerName, password, getPlayerIp(playerName) ?: "unknown")
                val registerResponse = platformManager.sendMessageAwaitResponse<RegisterPlayerMessage>(
                    request,
                    RegisterPlayerMessage::class.java
                )

                if (registerResponse.error != null) {
                    event.isCancelled = true
                    logger.warning("&cAn error occurred: ${registerResponse.error}".colorize())
                    return@runBlocking
                }

                logger.info("&2Successfully registered player \"$playerName\".".colorize())

                val player = Bukkit.getPlayer(playerName)

                if ((player?.isOnline ?: false) && platformManager.serverSettings.authKickAfterRegister) {
                    val message = i18nManager.translate(i18nManager.platformLocale, "auth.register-kick")!!

                    player.kickPlayer(message.colorize())
                }
            }
        }

        if (msg.startsWith("authme reload")) {
            authMePlugin.reloadConfig()

            if (!isConfigCompatible()) {
                logger.severe("&6AuthMe config is not compatible with Pano. Pano will force and reload AuthMe.".colorize())

                forceConfig()
                event.isCancelled = true
            }
        }
    }

    private fun handleComputeHash(playerName: String, password: String) {
        runBlocking {
            val response =
                platformManager.sendMessageAwaitResponse<IsPlayerRegisteredMessage>(
                    IsPlayerRegisteredRequest(
                        playerName
                    ),
                    IsPlayerRegisteredMessage::class.java
                )

            if (!response.registered) {
                pendingRegisterPasswords[playerName] = password

                return@runBlocking
            }

            val request = ChangePasswordRequest(playerName, password)

            val changePasswordResponse = platformManager.sendMessageAwaitResponse<ChangePasswordMessage>(
                request,
                ChangePasswordMessage::class.java
            )

            if (changePasswordResponse.error == null) {
                return@runBlocking
            }

            logger.severe("&cAn error occurred during the changing password of \"$playerName\": ${changePasswordResponse.error}".colorize())
            return@runBlocking
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPasswordEncryptionEvent(event: PasswordEncryptionEvent) {
        event.method = object : EncryptionMethod {
            override fun computeHash(
                password: String,
                name: String
            ): HashedPassword {
                handleComputeHash(name, password)

                return HashedPassword(password)
            }

            override fun computeHash(
                password: String,
                salt: String?,
                name: String
            ): String {
                handleComputeHash(name, password)

                return password
            }

            override fun comparePassword(
                password: String,
                hashedPassword: HashedPassword?,
                name: String
            ): Boolean {
                val success: Boolean

                runBlocking {
                    val response = platformManager.sendMessageAwaitResponse<PlayerAuthenticateMessage>(
                        PlayerAuthenticateRequest(
                            name,
                            password
                        ),
                        PlayerAuthenticateMessage::class.java
                    )

                    success = response.success
                }

                return success
            }

            override fun generateSalt(): String? = null

            override fun hasSeparateSalt(): Boolean = false

        }
    }
}