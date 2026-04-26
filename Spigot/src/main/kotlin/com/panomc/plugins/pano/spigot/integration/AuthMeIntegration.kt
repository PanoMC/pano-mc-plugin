package com.panomc.plugins.pano.spigot.integration

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.platform.message.response.*
import com.panomc.plugins.pano.core.platform.request.*
import com.panomc.plugins.pano.core.util.EmailUtil.maskEmail
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
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerCommandEvent
import java.io.File
import java.util.*

class AuthMeIntegration(override val panoPluginMain: SpigotMain) : Integration, Listener {

    private data class ConfigSetting(
        val path: String,
        val expectedValue: Any,
        val getValue: (FileConfiguration) -> Any?,
        val logMessage: String
    )

    private val requiredConfigSettings = listOf(
        ConfigSetting(
            path = "settings.security.passwordHash",
            expectedValue = "CUSTOM",
            getValue = { it.getString("settings.security.passwordHash") },
            logMessage = "Set AuthMe password hash to CUSTOM"
        ),
        ConfigSetting(
            path = "settings.registration.type",
            expectedValue = "PASSWORD",
            getValue = { it.getString("settings.registration.type") },
            logMessage = "Set AuthMe register type to PASSWORD"
        ),
        ConfigSetting(
            path = "settings.security.minPasswordLength",
            expectedValue = 6,
            getValue = { it.getInt("settings.security.minPasswordLength") },
            logMessage = "Set AuthMe min password length to 6"
        ),
        ConfigSetting(
            path = "settings.security.passwordMaxLength",
            expectedValue = 128,
            getValue = { it.getInt("settings.security.passwordMaxLength") },
            logMessage = "Set AuthMe password max length to 128"
        ),
        ConfigSetting(
            path = "settings.restrictions.allowedNicknameCharacters",
            expectedValue = "[a-zA-Z0-9_]*",
            getValue = { it.getString("settings.restrictions.allowedNicknameCharacters") },
            logMessage = "Set AuthMe allowed nickname characters to [a-zA-Z0-9_]*"
        )
    )
    private val logger by lazy {
        panoPluginMain.getPanoLogger()
    }

    private val authMePlugin by lazy {
        Bukkit.getPluginManager().getPlugin("AuthMe")!!
    }

    private lateinit var authMeApi: AuthMeApi

    private val pano by lazy {
        panoPluginMain.getPano()
    }

    private val platformManager by lazy {
        pano.platformManager
    }

    private val eventManager by lazy {
        pano.eventManager
    }

    private val i18nManager by lazy {
        pano.i18nManager
    }

    // when register command is called, saved here
    private val pendingRegisterPasswords = mutableMapOf<String, String>()

    private var initialized: Boolean = false

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

        authMeApi = AuthMeApi.getInstance()

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
        panoPluginMain.registerEventListeners(eventManager.eventListeners)
        // Pano’s custom password / event path is gone; AuthMe may still hold in-memory state that
        // matched that integration. Reloading makes it re-read config and re-initialize cleanly.
        // Only while Pano is still an enabled plugin: runTask on a disabled plugin throws.
        if (panoPluginMain.isEnabled) {
            reloadAuthMe()
        }

        logger.info("&eAuthMe integration is disabled.".colorize())

        initialized = false
    }

    override fun onDisable() {
        // Deterministic teardown while Spigot still allows task registration (see SpigotMain.onDisable order).
        stop()
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        if (platformManager.serverSettings.authIntegration) {
            start()
            return
        }

        stop()
    }

    override fun onDisconnect() {
        stop()
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        if (serverSettings.authIntegration) {
            start()
            return
        }

        stop()
    }

    override fun isInitialized(): Boolean = initialized

    private fun registerEvents() {
        panoPluginMain.unregisterEventListeners(eventManager.eventListeners)
        panoPluginMain.server.pluginManager.registerEvents(this, panoPluginMain)

        logger.info("&2Registered events for AuthMe.".colorize())
    }

    private fun forceConfig() {
        val config = authMePlugin.config
        val backupValues = mutableMapOf<String, Any?>()

        requiredConfigSettings.forEach { setting ->
            val currentValue = setting.getValue(config)
            if (currentValue != setting.expectedValue) {
                backupValues[setting.path] = currentValue
                config.set(setting.path, setting.expectedValue)
                logger.info(setting.logMessage.colorize())
            }
        }

        if (backupValues.isNotEmpty()) {
            // Save backup before changing config
            saveBackup(backupValues)
            
            authMePlugin.saveConfig()
            logger.info("AuthMe configuration has been updated for Pano integration".colorize())

            reloadAuthMe()
        }
    }

    private fun saveBackup(backupValues: Map<String, Any?>) {
        try {
            val dataFolder = panoPluginMain.dataFolder
            val backupFile = File(dataFolder, "authme-backup.yml")

            val backupConfig = if (backupFile.exists()) {
                YamlConfiguration.loadConfiguration(backupFile)
            } else {
                YamlConfiguration()
            }

            // Update backup values (overwrite existing ones)
            backupValues.forEach { (key, value) ->
                backupConfig.set(key, value)
            }

            backupFile.parentFile?.mkdirs()
            backupConfig.save(backupFile)

            logger.info("AuthMe backup saved to authme-backup.yml".colorize())
        } catch (e: Exception) {
            logger.warning("Failed to save AuthMe backup: ${e.message}".colorize())
        }
    }

    private fun reloadAuthMe() {
        if (!panoPluginMain.isEnabled) {
            return
        }
        try {
            Bukkit.getScheduler().runTask(panoPluginMain, Runnable {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "authme reload")
                logger.info("AuthMe configuration reloaded".colorize())
            })
        } catch (e: Exception) {
            logger.warning("Could not reload AuthMe automatically: ${e.message}".colorize())
            logger.warning("Please run '/authme reload' manually".colorize())
        }
    }

    private fun isConfigCompatible(): Boolean {
        val config = authMePlugin.config

        return requiredConfigSettings.all { setting ->
            setting.getValue(config) == setting.expectedValue
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerLogin(event: LoginEvent) {
        runBlocking {
            eventManager.eventListeners.filterIsInstance<OnPlayerJoin>().forEach { it.handle(panoPluginMain.eventHelper, event.player) }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerRegister(event: RegisterEvent) {
        val player = event.player
        val playerName = player.name

        val pendingPassword = pendingRegisterPasswords[playerName.lowercase()] ?: return

        pendingRegisterPasswords.remove(playerName.lowercase())

        runBlocking {
            val request = RegisterPlayerRequest(playerName, pendingPassword, getPlayerIp(playerName) ?: "unknown")

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
        runBlocking {
            eventManager.eventListeners.filterIsInstance<OnPlayerDisconnect>().forEach { it.handle(panoPluginMain.eventHelper, event.player) }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerDisconnect(event: PlayerQuitEvent) {
        if (authMeApi.isAuthenticated(event.player)) {
            runBlocking {
                eventManager.eventListeners.filterIsInstance<OnPlayerDisconnect>().forEach { it.handle(panoPluginMain.eventHelper, event.player) }
            }
        }

        if (pendingRegisterPasswords[event.player.name.lowercase()] != null) {
            pendingRegisterPasswords.remove(event.player.name.lowercase())
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
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
                pendingRegisterPasswords[playerName.lowercase()] = password

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