package com.panomc.plugins.pano.velocity.integration

import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.platform.message.response.ChangePasswordMessage
import com.panomc.plugins.pano.core.platform.message.response.GetPlayerInfoMessage
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.platform.message.response.IsPlayerRegisteredMessage
import com.panomc.plugins.pano.core.platform.message.response.PlayerAuthenticateMessage
import com.panomc.plugins.pano.core.platform.message.response.RegisterPlayerMessage
import com.panomc.plugins.pano.core.platform.request.ChangePasswordRequest
import com.panomc.plugins.pano.core.platform.request.GetPlayerInfoRequest
import com.panomc.plugins.pano.core.platform.request.IsPlayerRegisteredRequest
import com.panomc.plugins.pano.core.platform.request.PlayerAuthenticateRequest
import com.panomc.plugins.pano.core.platform.request.RegisterPlayerRequest
import com.panomc.plugins.pano.velocity.VelocityMain
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.util.UuidUtils
import io.vertx.core.http.WebSocket
import kotlinx.coroutines.runBlocking
import net.elytrium.limboauth.LimboAuth
import net.elytrium.limboauth.api.ExternalPasswordProvider

class LimboAuthIntegration(override val panoPluginMain: VelocityMain) : Integration {
    private val pano by lazy {
        panoPluginMain.getPano()
    }

    private val platformManager by lazy {
        pano.platformManager
    }

    private val logger by lazy {
        panoPluginMain.getPanoLogger()
    }

    private val server by lazy {
        panoPluginMain.getServer()
    }

    // Takes ownership of the OnPlayerJoin listener while the integration is active so the integration
    // is the sole emitter of the "player joined" signal once LimboAuth (or the premium/online-mode
    // bypass) has let the player through the auth limbo. Account reconciliation no longer runs here:
    // it must happen before LimboAuth.authPlayer decides /login-vs-/register, so it moved to the
    // pre-login take-over below.
    private val joinListener by lazy {
        object : OnPlayerJoin(platformManager, panoPluginMain) {
            override suspend fun handle(eventHelper: EventHelper, vararg args: Any) {
                super.handle(eventHelper, *args)
            }
        }
    }

    // Runs on Velocity's PreLoginEvent (dispatched by VelocityEventListener.onPlayerPreLogin with
    // args = [event, username]) at the default NORMAL order, i.e. BEFORE LimboAuth's own
    // PostOrder.LATE pre-login handler and well before LimboAuth.authPlayer runs inside the limbo.
    // Reconciling here guarantees the placeholder AUTH row exists before authPlayer decides
    // /login-vs-/register, closing the first-join impersonation/kick window that PostLoginEvent left
    // open. Unlike the join listener, this one is ADDED alongside any existing OnPlayerPreLogin
    // listeners (e.g. BanIntegration's) rather than replacing them.
    private val preLoginListener by lazy {
        object : OnPlayerPreLogin() {
            override suspend fun handle(eventHelper: EventHelper, vararg args: Any) {
                reconcile(args[0] as PreLoginEvent, args[1] as String)
            }
        }
    }

    private var externalPasswordProvider: ExternalPasswordProvider? = null

    private var initialized: Boolean = false

    // Set once when a plain (non-fork) upstream LimboAuth is detected, so we don't retry and
    // re-log the warning on every server-settings change until the server is restarted.
    private var upstreamApiMissing: Boolean = false

    override fun isInitialized(): Boolean = initialized

    private fun start() {
        if (initialized || upstreamApiMissing) {
            return
        }

        if (!platformManager.serverSettings.authIntegration) {
            return
        }

        val limboAuth = getLimboAuth() ?: return

        // Guard against a plain upstream LimboAuth: the ExternalPasswordProvider API only exists in
        // the pano-limbo-auth fork. Touching it against upstream throws a linkage error, so we catch
        // it once here, warn, and disable the integration instead of crashing.
        try {
            val provider = createExternalPasswordProvider()

            limboAuth.setExternalPasswordProvider(provider)

            externalPasswordProvider = provider
        } catch (error: LinkageError) {
            upstreamApiMissing = true

            logger.warning(
                "&cpano-limbo-auth fork required for auth integration; plain upstream LimboAuth lacks the ExternalPasswordProvider API. Reason: ${error.message}".colorize()
            )

            return
        }

        initialized = true

        logger.info("&eLimboAuth is enabled, hooking into LimboAuth...".colorize())

        takeOverJoinListener()
        takeOverPreLoginListener()

        logger.info("&2LimboAuth integration is hooked!".colorize())
    }

    private fun stop() {
        if (!initialized) {
            return
        }

        val limboAuth = getLimboAuth()

        if (limboAuth != null && externalPasswordProvider != null) {
            try {
                limboAuth.setExternalPasswordProvider(null)
            } catch (error: LinkageError) {
                logger.warning("&cFailed to clear LimboAuth external password provider. Reason: ${error.message}".colorize())
            }
        }

        externalPasswordProvider = null

        restoreJoinListener()
        restorePreLoginListener()

        logger.info("&eLimboAuth integration is disabled.".colorize())

        initialized = false
    }

    override fun onDisable() {
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

    // Swap the default OnPlayerJoin listener out of the shared Velocity event listener set and swap
    // ours in, so join events are not emitted twice on PostLoginEvent.
    private fun takeOverJoinListener() {
        val listeners = panoPluginMain.velocityEventListener.listeners

        listeners.removeAll { it is OnPlayerJoin }
        listeners.add(joinListener)

        logger.info("&2Registered events for LimboAuth.".colorize())
    }

    private fun restoreJoinListener() {
        val listeners = panoPluginMain.velocityEventListener.listeners

        listeners.removeAll { it is OnPlayerJoin }
        listeners.addAll(pano.eventManager.eventListeners.filterIsInstance<OnPlayerJoin>())
    }

    // Add our reconcile pre-login listener into the shared Velocity event listener set. Unlike the
    // join take-over, we do NOT strip existing OnPlayerPreLogin listeners: BanIntegration registers
    // its own pre-login listener that must keep running, so we add ours alongside it.
    private fun takeOverPreLoginListener() {
        val listeners = panoPluginMain.velocityEventListener.listeners

        listeners.add(preLoginListener)
    }

    // Remove only our own reconcile listener, leaving any other OnPlayerPreLogin listeners intact.
    private fun restorePreLoginListener() {
        val listeners = panoPluginMain.velocityEventListener.listeners

        listeners.remove(preLoginListener)
    }

    private fun getLimboAuth(): LimboAuth? =
        server.pluginManager.getPlugin("limboauth")
            .flatMap { it.instance }
            .map { it as LimboAuth }
            .orElse(null)

    // Reconcile LimboAuth's registration state with Pano's at PRE-LOGIN (before authPlayer), mirroring
    // AuthMe's AsyncPlayerPreLoginEvent reconcile: if Pano knows the player but LimboAuth does not,
    // provision an external placeholder row (so authPlayer routes them to /login, not /register);
    // if LimboAuth knows the player but Pano does not, drop it. No Velocity Player exists yet at
    // pre-login, so we work from the PreLoginEvent + username: the IP comes from the inbound
    // connection and the UUID is the deterministic offline UUID (correct because we only ever
    // provision offline/non-premium players — see the premium guard below).
    private suspend fun reconcile(event: PreLoginEvent, playerName: String) {
        if (platformManager.getWebSocket() == null) {
            return
        }

        val limboAuth = getLimboAuth() ?: return

        val playerInfo = platformManager.sendMessageAwaitResponse<GetPlayerInfoMessage>(
            GetPlayerInfoRequest(playerName),
            GetPlayerInfoMessage::class.java
        )

        val registeredInLimboAuth = limboAuth.isRegistered(playerName)

        if (playerInfo.registered && !registeredInLimboAuth) {
            // Registered in Pano but not in LimboAuth.
            // Premium guard: LimboAuth marks premium/online-mode accounts with an EMPTY hash so they
            // auto-login and skip the auth limbo. A non-empty placeholder row would force them back
            // into the login limbo and break that auto-login, so never provision premium players.
            // We match LimboAuth's own pre-login premium decision (isPremium) since there is no
            // Velocity Player to call isOnlineMode() on yet at this stage.
            if (limboAuth.isPremium(playerName)) {
                return
            }

            val ip = event.connection.remoteAddress.address.hostAddress
            val uuid = UuidUtils.generateOfflinePlayerUuid(playerName)

            limboAuth.provisionExternalPlayer(playerName, uuid, ip)

            logger.info("&2Provisioned \"$playerName\" in LimboAuth from Pano.".colorize())
        } else if (!playerInfo.registered && registeredInLimboAuth) {
            // Registered in LimboAuth but not in Pano.
            limboAuth.unregisterPlayer(playerName)

            logger.info("&eUnregistered \"$playerName\" from LimboAuth (not registered in Pano).".colorize())
        }
    }

    // The central-authority delegation: LimboAuth asks us to verify every password. We answer with
    // Pano's result, or null for accounts Pano does not manage so LimboAuth's local BCrypt can still
    // serve purely-local logins. onRegister / onPasswordChanged sync game-side changes back to Pano.
    private fun createExternalPasswordProvider(): ExternalPasswordProvider =
        object : ExternalPasswordProvider {
            override fun verifyPassword(lowercaseNickname: String, password: String): Boolean? = runBlocking {
                if (platformManager.getWebSocket() == null) {
                    return@runBlocking null
                }

                val registeredResponse = platformManager.sendMessageAwaitResponse<IsPlayerRegisteredMessage>(
                    IsPlayerRegisteredRequest(lowercaseNickname),
                    IsPlayerRegisteredMessage::class.java
                )

                if (!registeredResponse.registered) {
                    // Not a Pano-managed account; let LimboAuth fall back to its local BCrypt.
                    return@runBlocking null
                }

                val authenticateResponse = platformManager.sendMessageAwaitResponse<PlayerAuthenticateMessage>(
                    PlayerAuthenticateRequest(lowercaseNickname, password),
                    PlayerAuthenticateMessage::class.java
                )

                authenticateResponse.success
            }

            override fun onRegister(lowercaseNickname: String, password: String, ip: String) {
                runBlocking {
                    if (platformManager.getWebSocket() == null) {
                        return@runBlocking
                    }

                    val response = platformManager.sendMessageAwaitResponse<RegisterPlayerMessage>(
                        RegisterPlayerRequest(lowercaseNickname, password, ip),
                        RegisterPlayerMessage::class.java
                    )

                    if (response.error != null) {
                        logger.severe("&cAn error occurred during the registration of \"$lowercaseNickname\": ${response.error}".colorize())

                        return@runBlocking
                    }

                    logger.info("&2Successfully registered player \"$lowercaseNickname\".".colorize())
                }
            }

            override fun onPasswordChanged(lowercaseNickname: String, newPassword: String) {
                runBlocking {
                    if (platformManager.getWebSocket() == null) {
                        return@runBlocking
                    }

                    val response = platformManager.sendMessageAwaitResponse<ChangePasswordMessage>(
                        ChangePasswordRequest(lowercaseNickname, newPassword),
                        ChangePasswordMessage::class.java
                    )

                    if (response.error != null) {
                        logger.severe("&cAn error occurred during the changing password of \"$lowercaseNickname\": ${response.error}".colorize())
                    }
                }
            }
        }
}
