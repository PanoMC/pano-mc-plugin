package com.panomc.plugins.pano.core.platform

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.config.PanoConfig
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.i18n.I18nManager
import com.panomc.plugins.pano.core.mcping.McStatus
import com.panomc.plugins.pano.core.mcping.MinecraftStatusClient
import com.panomc.plugins.pano.core.model.PanoError
import com.panomc.plugins.pano.core.platform.PlatformMessage.Companion.responseName
import com.panomc.plugins.pano.core.platform.message.handler.BanPlayerHandler
import com.panomc.plugins.pano.core.platform.message.handler.GetServerSettingsHandler
import com.panomc.plugins.pano.core.platform.message.handler.PermissionsSnapshotUpdatedHandler
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.platform.request.GetServerSettingsRequest
import com.panomc.plugins.pano.core.platform.request.OnServerConnectRequest
import com.panomc.plugins.pano.core.util.Aes256GcmUtil
import com.panomc.plugins.pano.core.util.EncryptUtil
import com.panomc.plugins.pano.core.util.ImageUtil
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.*
import java.net.URI
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import javax.crypto.SecretKey

// A request awaiting its response: the deferred to complete and the class to decode the reply
// into. Kept as one entry so the two can never drift apart (platform-core-11 / platform-core-13).
private data class PendingRequest(
    val deferred: CompletableDeferred<PlatformMessage>,
    val responseType: Class<out PlatformMessageResponse>
)

// Outcome of a single connect attempt, used to drive the iterative retry loop in
// connectPlatformTask instead of recursing back into it (platform-core-1).
private enum class ConnectOutcome {
    CONNECTED, RETRY, ABORT
}

// Resolved (and already-validated) heartbeat cadence for the currently active connection, in
// milliseconds so it drops straight into vertx.setPeriodic and elapsed-time comparisons
// (platform-core-heartbeat).
private data class HeartbeatSettings(val intervalMillis: Long, val timeoutMillis: Long)

class PlatformManager(
    private val vertx: Vertx,
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val webClient: WebClient,
    private val webSocketClient: WebSocketClient,
    private val minecraftStatusClient: MinecraftStatusClient,
    private val serverData: ServerData,
    private val pluginMain: PanoPluginMain,
    val i18nManager: I18nManager
) {
    // Volatile: written on the Vert.x event loop (establishConnectionToPlatform /
    // onWebSocketClosed), read via getWebSocket() from Spigot's async pre-login thread, Bungee's
    // Dispatchers.IO pre-login coroutine and Fabric's plugin scope. Without this a pre-login check
    // can observe a stale value - a stale non-null socket makes BanIntegration attempt a send that
    // then throws (fail-closed, denies a legit player); a stale null skips the ban check entirely
    // (admits a banned player) (platform-core-XX).
    @Volatile
    private var webSocket: WebSocket? = null

    // Volatile: written from the /pano command thread (connectNewPlatform/disconnectPlatform no
    // longer run on the calling server thread) and read by the retry loop on the event loop
    // (platform-core-XX).
    @Volatile
    private var canConnect = true // to be able to cancel connection task

    // Guards connectPlatformTask's retry loop and establishConnectionToPlatform so at most one
    // of each is ever active at a time. Without this, a spurious close notification during the
    // deliberate teardown in establishConnectionToPlatform's catch block could spin up a brand
    // new reconnect loop on top of the one already retrying, doubling on every recurrence
    // (platform-core-XX).
    private val connecting = AtomicBoolean(false)

    // Vert.x timer id for the periodic ping heartbeat that keeps the socket non-idle behind a
    // reverse proxy (nginx's default 60s proxy_read_timeout otherwise kills an idle upgraded
    // connection) and lets us notice a dead peer without waiting on a TCP-level timeout. Null
    // whenever no heartbeat is currently running. @Volatile: started on the event loop at the
    // end of establishConnectionToPlatform, cancelled from the event loop on every path that
    // ends the connection (onWebSocketClosed, closeConnection, stop, a heartbeat-detected
    // death) and read back by the periodic callback itself (platform-core-heartbeat).
    @Volatile
    private var heartbeatTimerId: Long? = null

    // Vert.x timer id for the one-shot "is the peer still there" deadline, rescheduled every time
    // a pong comes in (see scheduleDeathWatchdog). Kept separate from heartbeatTimerId above so
    // the ping cadence (intervalMillis, needed to keep the socket non-idle behind nginx) and the
    // death-detection deadline (anchored to the actual last pong, needed to bound worst-case
    // detection latency) can no longer be coupled to the same tick
    // (platform-core-heartbeat-detect). @Volatile / null-when-idle / cancellation discipline all
    // mirror heartbeatTimerId exactly.
    @Volatile
    private var deathWatchdogTimerId: Long? = null

    // Epoch millis of the last pong received for the CURRENTLY active socket. Seeded to "now" by
    // startHeartbeat itself (before the pongHandler is registered or the death watchdog's first
    // timer can run) so the first interval can never look like an instant timeout before a
    // single ping has had a chance to round-trip (platform-core-heartbeat). @Volatile: written by
    // startHeartbeat's own seed assignment and, from then on, by the pongHandler; read from the
    // death watchdog's timer callback (for the elapsed-time log line - the watchdog's own fire
    // instant, not this field, is what actually decides the peer is dead, see
    // scheduleDeathWatchdog) - call sites that are NOT guaranteed to share a Vert.x context. On
    // the default await-pano-connection: true path, connectPlatformTask's synchronous phase
    // drives establishConnectionToPlatform through runBlocking, so it resumes on the Minecraft
    // server's own main thread rather than a Vert.x thread; startHeartbeat's seed write and its
    // vertx.setPeriodic/setTimer calls then run from there too, which makes Vert.x spin up an
    // ad-hoc context for them - while the pongHandler always fires on the socket's own I/O
    // context. Safe anyway: the seed write happens-before the pongHandler is even registered and
    // before the watchdog's first timer is scheduled - both are set up later in that same
    // startHeartbeat call, on the same thread - so it can never race either of them; after that,
    // only the pongHandler ever writes and only the watchdog ever reads, never a read-modify-write
    // shared between the two, so @Volatile's plain write-then-read visibility guarantee is all
    // correctness needs here - no shared context required.
    @Volatile
    private var lastPongReceivedAt: Long = 0

    // ConcurrentHashMap because entries are written from whatever thread calls
    // sendMessageAwaitResponse (game/auth threads, Dispatchers.IO) and read/removed from the
    // Vert.x event loop (platform-core-11).
    private val pendingResponses = ConcurrentHashMap<UUID, PendingRequest>()
    // Volatile: set on the event loop inside onConnectionEstablished() (itself reachable from the
    // /pano command thread's synchronous connect phase too), read from the same pre-login threads
    // as webSocket above (platform-core-XX).
    @Volatile
    lateinit var serverSettings: GetServerSettingsMessage
        internal set

    // encryptionKey/encryptionKeySource are read/written together by validateEncryptionKey() from
    // arbitrary threads (game/auth threads, Dispatchers.IO, the event loop). @Volatile alone only
    // makes each field's own writes visible - it can't stop one thread from observing the new key
    // paired with the old source (or vice versa) mid-update. Guard the pair with a lock so
    // validateEncryptionKey's read-compare-and-maybe-rebuild is atomic and the two can never be
    // observed out of sync (platform-core-XX).
    private val encryptionKeyLock = Any()

    @Volatile
    private var encryptionKey: SecretKey? = null

    // The encryption-key string encryptionKey was last built from, so a rotated key (a new
    // savePlatform() write, or config.conf hand-edited on disk) is picked up instead of the
    // stale cached SecretKey being reused forever (platform-core-14).
    @Volatile
    private var encryptionKeySource: String? = null
    private val decoder by lazy {
        Base64.getDecoder()
    }

    internal val messageHandlerDefinitions = mutableSetOf<PlatformMessageHandler<*>>(
        GetServerSettingsHandler(this, pluginMain),
        BanPlayerHandler(this, pluginMain),
        PermissionsSnapshotUpdatedHandler(pluginMain)
    )

    val connectPlatformTask: (delay: Boolean, async: Boolean) -> Unit by lazy {
        { delay, async ->
            // The retry loop lives entirely inside this single suspend function so a failed
            // attempt can just loop back around, instead of establishConnectionToPlatform
            // re-invoking connectPlatformTask (which used to nest a brand new runBlocking inside
            // the one already running on the server main thread on every retry) (platform-core-1).
            suspend fun run() {
                if (delay) {
                    delay(TimeUnit.SECONDS.toMillis(3))
                }

                if (!isPlatformConfigured() || !canConnect) {
                    return
                }

                // Only one retry loop (and therefore one in-flight connect attempt) may run at a
                // time. A caller that loses the race is a no-op: a loop is already driving
                // reconnection and will reach the same end state (platform-core-XX).
                // Tracked locally (not just via the shared flag) so the `finally` below only ever
                // releases the flag this particular run() actually acquired - the synchronous
                // handoff path further down flips this back to false itself once it hands the
                // flag off to a newly launched loop, so `finally` can't then release a flag that
                // newer loop has since re-acquired for itself (platform-core-XX).
                var acquired = connecting.compareAndSet(false, true)

                if (!acquired) {
                    return
                }

                try {
                    if (async) {
                        // Background reconnect: keep retrying until connected, aborted (e.g. an
                        // invalid token) or the plugin is disabled/reconfigured.
                        while (canConnect && isPlatformConfigured()) {
                            when (establishConnectionToPlatform()) {
                                ConnectOutcome.CONNECTED, ConnectOutcome.ABORT -> return
                                ConnectOutcome.RETRY -> delay(TimeUnit.SECONDS.toMillis(3))
                            }
                        }
                    } else {
                        // Synchronous startup phase (await-pano-connection: true): retry for a
                        // bounded budget so a slow/unreachable platform can never hang the server's
                        // boot thread forever. Once the budget is spent, hand off to the async
                        // background loop above and return so the caller's runBlocking unblocks.
                        val deadline = System.currentTimeMillis() + BLOCKING_CONNECT_BUDGET_MILLIS
                        var outcome = ConnectOutcome.RETRY

                        while (canConnect && isPlatformConfigured()) {
                            outcome = establishConnectionToPlatform()

                            if (outcome != ConnectOutcome.RETRY || System.currentTimeMillis() >= deadline) {
                                break
                            }

                            delay(TimeUnit.SECONDS.toMillis(3))
                        }

                        if (outcome == ConnectOutcome.RETRY && canConnect && isPlatformConfigured()) {
                            logger.warning(
                                pluginMain.translateColor(
                                    "&eCouldn't connect to Pano Platform within the startup window. The server will finish booting and keep retrying in the background."
                                )
                            )

                            // Release before handing off - the async loop below re-acquires the
                            // flag itself, and would otherwise be silently dropped by the CAS
                            // since this synchronous phase is still holding it here. Clear
                            // `acquired` too so the `finally` below - which may still run after the
                            // handed-off loop has already re-acquired the flag - is a no-op instead
                            // of releasing a flag it no longer owns (platform-core-XX).
                            connecting.set(false)
                            acquired = false

                            connectPlatformTask.invoke(true, true)
                        }
                    }
                } finally {
                    if (acquired) {
                        connecting.set(false)
                    }
                }
            }

            if (async) {
                CoroutineScope(vertx.dispatcher()).launch {
                    run()
                }
            } else {
                runBlocking {
                    run()
                }
            }
        }
    }

    fun isPlatformConfigured(): Boolean {
        val platformConfig = configManager.config.platform ?: return false

        return platformConfig.token.isNotBlank() && platformConfig.encryptionKey.isNotBlank()
    }

    fun start() {
        logger.info(pluginMain.translateColor("Checking is platform connection configured"))

        if (!isPlatformConfigured()) {
            logger.severe(pluginMain.translateColor("&eThis server has not been connected to any Pano Platform!"))
            logger.severe(pluginMain.translateColor("""&6Type: "/pano connect <platform-address> <platform-code>" to connect Pano Platform."""))
            logger.severe(pluginMain.translateColor("""&6For more information please visit: http://panomc.com/platform-connect"""))

            return
        }

        // Validate eagerly rather than letting a malformed key escape from deep inside
        // onConnectionEstablished() later and half-initialize the plugin (platform-core-7).
        val encryptionKeyIsValid = try {
            Aes256GcmUtil.base64ToSecretKey(configManager.config.platform!!.encryptionKey)
            true
        } catch (_: Exception) {
            false
        }

        if (!encryptionKeyIsValid) {
            logger.severe(pluginMain.translateColor("&cError: The platform.encryption-key in config.conf is not a valid 32-byte Base64 key."))
            logger.severe(pluginMain.translateColor("""&6Reconnect with "/pano disconnect" then "/pano connect <platform-address> <platform-code>"."""))

            return
        }

        logger.info(pluginMain.translateColor("Connecting to platform..."))

        canConnect = true

        val config = configManager.config
        val awaitPanoConnection = config.awaitPanoConnection

        if (awaitPanoConnection) {
            logger.info(pluginMain.translateColor("&ePano MC plugin will wait for connection to Pano before the server starts. This is recommended to synchronize the settings."))
            logger.info(pluginMain.translateColor("&eYou can disable this in config by &6await-pano-connection: false&e. &cBut this may lead in player logins even before Pano can handle!"))
        }

        connectPlatformTask.invoke(false, !awaitPanoConnection)
    }

    suspend fun stop() {
        canConnect = false

        // Belt-and-braces: closeConnection() below also cancels the heartbeat, but stop() can
        // return before ever reaching it (isPlatformConfigured() == false) - listed as its own
        // path to cancel on regardless of what closeConnection ends up doing
        // (platform-core-heartbeat). Cancelling an already-stopped/never-started timer is a
        // harmless no-op.
        cancelHeartbeat()

        // Fail everything still parked in sendMessageAwaitResponse instead of leaving those
        // callers awaiting forever (platform-core-2).
        failAllPendingResponses(PanoError("&cPano plugin is shutting down."))

        if (!isPlatformConfigured()) {
            return
        }

        val isWebsocketNull = webSocket == null

        closeConnection()

        if (!isWebsocketNull) {
            logger.info(pluginMain.translateColor("&6Disconnected from platform."))
        }
    }

    fun getWebSocket() = webSocket

    private suspend fun queryServerStatus(): McStatus {
        return try {
            minecraftStatusClient.query(host = serverData.connectableHostAddress(), port = serverData.port())
        } catch (e: Exception) {
            McStatus(null, null, null, null, null, null, null, null)
        }
    }

    suspend fun connectNewPlatform(platformAddress: String, platformCode: String) {
        val pingData = queryServerStatus()

        val configsToTry = mutableListOf<Triple<String, Int, Boolean>>()

        if (platformAddress.startsWith("http://") || platformAddress.startsWith("https://")) {
            val uri = URI(platformAddress)
            val h = uri.host ?: platformAddress
            val s = uri.scheme == "https"
            val p = if (uri.port != -1) uri.port else (if (s) 443 else 80)

            if (p !in 0..65535) {
                throw PanoError("&cError: Invalid port number '$p'. Port must be between 0 and 65535.")
            }

            configsToTry.add(Triple(h, p, s))
        } else if (platformAddress.contains(":")) {
            val splitHost = platformAddress.split(":")
            val h = splitHost[0]
            val p = splitHost[1].toIntOrNull()

            if (p == null) {
                throw PanoError("&cError: Invalid port '${splitHost[1]}'. Port must be a number between 0 and 65535.")
            }

            if (p !in 0..65535) {
                throw PanoError("&cError: Invalid port number '$p'. Port must be between 0 and 65535.")
            }

            // No explicit scheme was given: always try HTTPS first so an encrypted platform
            // wins whenever one is listening. Plain HTTP is attempted only as a fallback, and
            // only when the host is unambiguously loopback/private — there the one-time platform
            // code and the returned bearer token never leave a trusted network. For a public
            // host we still refuse to downgrade silently: that would leak both in the clear and
            // let anyone able to block the HTTPS attempt force the downgrade. Such an operator
            // opts in explicitly with an "http://" prefix, handled above (platform-core-12 /
            // cross-cutting-12).
            configsToTry.add(Triple(h, p, true))

            if (isLocalOrPrivateHost(h)) {
                configsToTry.add(Triple(h, p, false))
            }
        } else {
            configsToTry.add(Triple(platformAddress, 443, true))

            if (isLocalOrPrivateHost(platformAddress)) {
                configsToTry.add(Triple(platformAddress, 80, false))
            }
        }

        val requestBody = JsonObject()

        requestBody
            .put("platformCode", platformCode)
            .put("serverName", serverData.serverName())
            .put("playerCount", serverData.playerCount())
            .put("maxPlayerCount", serverData.maxPlayerCount())
            .put("serverType", serverData.serverType())
            .put("serverVersion", serverData.serverVersion())
            .put("host", serverData.hostAddress())
            .put("port", serverData.port())
            .put("startTime", Pano.serverStartTime)
            .put("publicKey", configManager.config.publicKey)

        val faviconImage = pingData.faviconImage ?: serverData.favicon()

        if (faviconImage != null) {
            val faviconDataUrl = ImageUtil.bufferedImageToDataUrl(faviconImage)

            // Only send favicon if it's a safe image format (no SVG or other dangerous types)
            if (ImageUtil.isAllowedImageDataUrl(faviconDataUrl)) {
                requestBody.put("favicon", faviconDataUrl)
            }
        }

        val motd = serverData.motd()

        if (motd != null) {
            requestBody
                .put("motd", motd)
        }

        var finalResponse: HttpResponse<*>? = null
        var finalHost = ""
        var finalPort = 0
        var finalSsl = false
        var lastException: Exception? = null

        for ((h, p, s) in configsToTry) {
            val scheme = if (s) "https" else "http"
            try {
                val response = webClient
                    .post(p, h, "/api/server/connect")
                    .ssl(s)
                    .timeout(5000)
                    .sendJsonObject(requestBody)
                    .coAwait()

                val body = try {
                    response.bodyAsJsonObject()
                } catch (_: Exception) {
                    null
                }

                if (body != null && (body.containsKey("result") || body.containsKey("token"))) {
                    finalResponse = response
                    finalHost = h
                    finalPort = p
                    finalSsl = s
                    break
                }

                logger.info("Tried $scheme://$h:$p - got status ${response.statusCode()} but no valid Pano response.")
            } catch (exception: Exception) {
                lastException = exception
                logger.info("Tried $scheme://$h:$p - failed: ${exception.message}")
            }
        }

        if (finalResponse == null) {
            val triedConfigs = configsToTry.joinToString(", ") { (h, p, s) ->
                "${if (s) "https" else "http"}://$h:$p"
            }
            logger.warning("Couldn't connect to Pano Platform. Tried: $triedConfigs. Last error: ${lastException?.message ?: "Unknown"}")

            // Only HTTPS was attempted, so the likeliest cause is a platform served over plain
            // HTTP on a public host - which is never downgraded to automatically. Point at the
            // explicit opt-in instead of leaving the operator with a bare "couldn't connect".
            val hint = if (configsToTry.none { !it.third }) {
                " &eIf your platform is served over plain HTTP, retry with an explicit \"http://\" prefix."
            } else {
                ""
            }

            throw PanoError("&cCouldn't connect to Pano Platform. Tried: $triedConfigs$hint")
        }

        val body = finalResponse.bodyAsJsonObject()

        if (body != null && body.getString("result") == "error") {
            val error = body.getString("error")

            throw PanoError(getErrorMessageByErrorCode(error))
        }

        val keySpec = PKCS8EncodedKeySpec(decoder.decode(configManager.config.privateKey))
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec)

        val token = body.getString("token")
        val decodedEncryptionKey = Base64.getDecoder().decode(body.getString("encryptionKey"))
        val encryptionKey = EncryptUtil.decryptData(decodedEncryptionKey, privateKey)

        if (!savePlatform(finalHost, finalPort, finalSsl, token, encryptionKey)) {
            throw PanoError("&cError: Connected to Pano Platform but failed to save the connection to config.conf. Check disk space/permissions and try again.")
        }

        if (finalSsl) {
            logger.info(pluginMain.translateColor("&2Connected to Pano Platform at https://$finalHost:$finalPort"))
        } else {
            // Make the cleartext connection loud (platform-core-12 / cross-cutting-12) - this is
            // only reachable when the operator wrote an explicit "http://" prefix, or when the
            // host is loopback/private and HTTPS wasn't answered.
            logger.warning(pluginMain.translateColor("&eConnected to Pano Platform at http://$finalHost:$finalPort - this connection is NOT encrypted."))
        }

        canConnect = true
    }

    suspend fun disconnectPlatform() {
        canConnect = false

        failAllPendingResponses(PanoError("&cDisconnecting from Pano Platform."))
        closeConnection()

        val platformConfig = configManager.config.platform!!
        val host = platformConfig.host
        val port = platformConfig.port
        val token = platformConfig.token

        val request = webClient
            .post(port, host, "/api/server/disconnect")
            .ssl(platformConfig.ssl)
            .putHeader("Authorization", "Bearer $token")
            .send()

        try {
            request.coAwait()
        } catch (exception: Exception) {
            // The socket is already closed at this point - do NOT leave canConnect == false with
            // the platform config still intact, or nothing can ever reconnect again
            // (platform-core-8). Clear the local link regardless and just warn that the platform
            // itself wasn't notified.
            logger.warning(pluginMain.translateColor("&eCould not notify Pano Platform about the disconnect. Reason: ${exception.message}. Clearing the local link anyway."))
        }

        val removed = removePlatform()

        // Notify integrations to unregister their events. Deliberately AFTER removePlatform() -
        // it used to run before, which left isPlatformConfigured() still reading true at the
        // moment integrations observed it, making a deliberate /pano disconnect indistinguishable
        // from a transient WebSocket drop (onWebSocketClosed() below also calls onDisconnect(),
        // but with the platform config still intact). Running this after removePlatform() gives
        // integrations an unambiguous signal: isPlatformConfigured() == false here means a real,
        // deliberate unlink (see AuthMeIntegration.onDisconnect()).
        pluginMain.onDisconnect()

        if (!removed) {
            throw PanoError("&cError: Disconnected from Pano Platform but failed to save the change to config.conf. Check disk space/permissions.")
        }
    }

    // Returns an outcome instead of recursing back into connectPlatformTask itself, so the retry
    // loop in connectPlatformTask stays iterative (platform-core-1).
    private suspend fun establishConnectionToPlatform(): ConnectOutcome {
        // Defense in depth alongside the `connecting` CAS in connectPlatformTask: never open a
        // second live socket on top of one that's already connected (platform-core-XX).
        if (webSocket != null) {
            return ConnectOutcome.CONNECTED
        }

        val platformConfig = configManager.config.platform!!
        val host = platformConfig.host
        val port = platformConfig.port
        val token = platformConfig.token

        val webSocketConnectOptions = WebSocketConnectOptions()

        webSocketConnectOptions.host = host
        webSocketConnectOptions.port = port
        webSocketConnectOptions.isSsl = platformConfig.ssl
        webSocketConnectOptions.uri = "/api/server/connection"
        webSocketConnectOptions.method = HttpMethod.GET

        webSocketConnectOptions.putHeader("Authorization", "Bearer $token")

        val webSocket: WebSocket

        try {
            webSocket = webSocketClient.connect(webSocketConnectOptions).coAwait()
        } catch (exception: Exception) {
            if (exception is UpgradeRejectedException) {
                // The rejecting party (a reverse proxy returning a 502 HTML page, for example)
                // does not have to answer with JSON. Guard the decode so that case falls through
                // to the generic retry below instead of throwing out of this catch block and
                // killing the whole reconnect loop (platform-core-3).
                val body = try {
                    exception.body?.toJsonObject()
                } catch (_: Exception) {
                    null
                }

                if (body != null && body.getString("result") == "error") {
                    val error = body.getString("error")

                    logger.severe(pluginMain.translateColor(getErrorMessageByErrorCode(error)))

                    if (error == PlatformErrorCodes.INVALID_TOKEN.toString()) {
                        if (!removePlatform()) {
                            // Not fatal to the abort itself - the invalid token is already
                            // unusable against the platform regardless - but the admin needs to
                            // know config.conf may still hold it on disk.
                            logger.severe(pluginMain.translateColor("&cError: Failed to save the platform disconnect to config.conf. The invalid token may still be present on disk."))
                        }

                        return ConnectOutcome.ABORT
                    }

                    return ConnectOutcome.RETRY
                }
            }

            logger.severe(pluginMain.translateColor("&cError: Failed to connect Pano Platform. Reason: ${exception.message}"))

            return ConnectOutcome.RETRY
        }

        logger.info(pluginMain.translateColor("&2Connected successfully to the platform!"))

        this.webSocket = webSocket

        webSocket.textMessageHandler { msg ->
            CoroutineScope(vertx.dispatcher()).launch {
                onWebsocketTextMessage(msg)
            }
        }

        webSocket.closeHandler {
            // Guard against a late close notification for an OLD socket nulling out (or failing
            // pending responses belonging to) a socket that has since been replaced by a newer,
            // already-established connection (platform-core-XX).
            if (this.webSocket === webSocket) {
                onWebSocketClosed()
            }
        }

        try {
            onConnectionEstablished()
        } catch (exception: Exception) {
            // A failure here (e.g. a malformed platform.encryption-key) used to escape all the
            // way out of connectPlatformTask with the socket left open and half the plugin never
            // wired up (platform-core-7). Tear the half-open connection down and retry instead.
            logger.severe(pluginMain.translateColor("&cError: Failed to finish connecting to Pano Platform. Reason: ${exception.message}"))

            // The heartbeat only ever starts AFTER onConnectionEstablished() returns
            // successfully (below), so this is a no-op today - kept anyway since this is exactly
            // the "deliberate teardown" path the heartbeat's timer discipline must cover
            // regardless of where in the method it failed (platform-core-heartbeat).
            cancelHeartbeat()

            // Detach the handlers before this deliberate close - otherwise close() synchronously
            // drives the closeHandler into onWebSocketClosed(), which (seeing canConnect == true)
            // would spin up a second reconnect loop on top of the one already retrying here
            // (platform-core-XX).
            webSocket.closeHandler(null)
            webSocket.textMessageHandler(null)

            this.webSocket = null

            try {
                webSocket.close().coAwait()
            } catch (_: Exception) {
            }

            return ConnectOutcome.RETRY
        }

        // Only after the handshake (OnServerConnectRequest + GetServerSettings) has actually
        // completed - starting any earlier would let the periodic ping/timeout race the
        // handshake itself (platform-core-heartbeat).
        startHeartbeat(webSocket)

        return ConnectOutcome.CONNECTED
    }

    private suspend fun onConnectionEstablished() {
        val pingData = queryServerStatus()

        val eventRequest = OnServerConnectRequest(
            serverData.serverName(),
            serverData.playerCount(),
            serverData.maxPlayerCount(),
            serverData.serverType(),
            serverData.serverVersion(),
            serverData.hostAddress(),
            serverData.port(),
            Pano.serverStartTime,
            ImageUtil.sanitizeFaviconDataUrl(
                if (pingData.faviconImage != null || serverData.favicon() != null)
                    ImageUtil.bufferedImageToDataUrl(pingData.faviconImage ?: serverData.favicon()!!)
                else null
            ),
            serverData.motd()
        )

        sendMessage(eventRequest)

        logger.info(pluginMain.translateColor("Sent server info update to the platform."))

        serverSettings = sendMessageAwaitResponse(GetServerSettingsRequest(), GetServerSettingsMessage::class.java)

        // Update I18nManager cache when server settings are received
        i18nManager.updateCache(serverSettings)

        logger.info(pluginMain.translateColor("Received server settings."))

        pluginMain.onConnectionEstablished(webSocket)
    }

    private suspend fun onWebsocketTextMessage(encryptedMessage: String) {
        // Every step below can throw (bad key, AEAD failure, malformed JSON, an unguarded
        // handler...). This coroutine is fire-and-forget from webSocket.textMessageHandler, so
        // without a catch here any of those aborts the frame silently and, if it was a response
        // to an in-flight request, strands that caller forever (platform-core-4). Only the
        // exception type/message is ever logged - never the key material or the raw ciphertext.
        var eventId: UUID? = null
        var pending: PendingRequest? = null

        try {
            validateEncryptionKey()
            val msg = Aes256GcmUtil.decrypt(encryptedMessage, encryptionKey!!)
            val json = JsonObject(msg)
            val event = json.getString("event")
            json.remove("event")

            eventId = if (json.getString("eventId") == null) null else UUID.fromString(json.getString("eventId"))

            json.remove("eventId")

            pending = eventId?.let { pendingResponses.remove(it) }

            if (pending != null) {
                if (pending.responseType.responseName() == event) {
                    pending.deferred.complete(Pano.gson.fromJson(json.encode(), pending.responseType))
                } else {
                    // Used to just remove the entry and drop it, stranding the awaiting caller
                    // with no timeout to rescue it (platform-core-2).
                    pending.deferred.completeExceptionally(
                        IllegalStateException("Received response type \"$event\" did not match the expected \"${pending.responseType.responseName()}\".")
                    )
                }

                return
            }

            messageHandlerDefinitions.find { it.getHandlerName() == event }?.let {
                val messageObj = Pano.gson.fromJson(json.encode(), it.messageClass)

                @Suppress("UNCHECKED_CAST")
                val typedListener = it as PlatformMessageHandler<PlatformMessage>

                typedListener.handle(messageObj)
            }
        } catch (exception: Exception) {
            logger.severe("Failed to process platform message (${exception.javaClass.simpleName}): ${exception.message}")

            // If we already claimed this eventId's pending entry (or the failure happened after
            // decoding it, e.g. Gson choking on the payload), fail it instead of letting the
            // caller hang.
            (pending ?: eventId?.let { pendingResponses.remove(it) })?.deferred?.completeExceptionally(exception)
        }
    }

    private fun onWebSocketClosed() {
        // Covers both a remote/network-initiated drop and a heartbeat-detected death (which
        // closes the socket itself to get here) - either way, the timer belongs to a socket that
        // is gone now (platform-core-heartbeat).
        cancelHeartbeat()

        webSocket = null

        // Fail everything still parked in sendMessageAwaitResponse instead of leaving those
        // callers awaiting forever (platform-core-2).
        failAllPendingResponses(PanoError("&cLost connection to Pano Platform."))

        // Notify integrations to unregister their events
        pluginMain.onDisconnect()

        if (canConnect) {
            logger.info(pluginMain.translateColor("&6Lost connection to platform."))

            logger.info(pluginMain.translateColor("&eRetrying to connect..."))

            connectPlatformTask.invoke(true, true)
        }
    }

    // Completes and removes every entry still parked in pendingResponses, so a caller blocked in
    // sendMessageAwaitResponse fails fast instead of awaiting a deferred that will never be
    // completed again (platform-core-2).
    private fun failAllPendingResponses(cause: Throwable) {
        pendingResponses.keys.toList().forEach { eventId ->
            pendingResponses.remove(eventId)?.deferred?.completeExceptionally(cause)
        }
    }

    private suspend fun closeConnection() {
        // Cancel before the close even completes - closeHandler/onWebSocketClosed will cancel
        // again once it fires (harmless no-op), but this rules out the periodic timer sneaking
        // in one more tick against a socket that's already on its way out
        // (platform-core-heartbeat).
        cancelHeartbeat()

        webSocket?.close()?.coAwait()
    }

    // Reads platform.config's heartbeat-interval/heartbeat-timeout and validates them, instead
    // of trusting a hand-edited config.conf outright: an interval <= 0 would spin
    // vertx.setPeriodic uselessly (or throw), and a timeout that isn't comfortably larger than
    // the interval would start declaring a perfectly healthy connection dead after little more
    // than a single missed pong. Falls back to PanoConfig's shipped defaults with a warning
    // rather than heartbeating a good connection to death (platform-core-heartbeat).
    private fun resolveHeartbeatSettings(): HeartbeatSettings {
        val config = configManager.config
        val configuredInterval = config.heartbeatInterval
        val configuredTimeout = config.heartbeatTimeout

        val configuredIntervalMillis = TimeUnit.SECONDS.toMillis(configuredInterval.toLong())
        val configuredTimeoutMillis = TimeUnit.SECONDS.toMillis(configuredTimeout.toLong())

        // The third condition is the one that is easy to miss: scheduleDeathWatchdog subtracts a
        // fixed close-handshake budget from the timeout, so the deadline an operator actually gets
        // is (timeout - budget), not timeout. Requiring at least two intervals of room AFTER that
        // subtraction guarantees a healthy connection always has a full interval of slack to land
        // its next pong and reschedule the watchdog. Without it, timeout >= 2 * interval alone
        // still admits e.g. 5s/10s, where the watchdog would fire at exactly the instant the next
        // pong is due - a coin flip that declares a perfectly healthy connection dead and puts the
        // plugin into a self-inflicted reconnect loop.
        if (configuredInterval <= 0 ||
            configuredTimeout < configuredInterval * 2 ||
            configuredTimeoutMillis - CLOSE_HANDSHAKE_BUDGET_MILLIS < configuredIntervalMillis * 2
        ) {
            logger.warning(
                pluginMain.translateColor(
                    "&eInvalid heartbeat-interval/heartbeat-timeout in config.conf (interval must be greater than 0, and timeout must be at least twice the interval plus ${CLOSE_HANDSHAKE_BUDGET_MILLIS / 1000}s of close-handshake budget). Falling back to the defaults: ${PanoConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS}s / ${PanoConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS}s."
                )
            )

            return HeartbeatSettings(
                TimeUnit.SECONDS.toMillis(PanoConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS.toLong()),
                TimeUnit.SECONDS.toMillis(PanoConfig.DEFAULT_HEARTBEAT_TIMEOUT_SECONDS.toLong())
            )
        }

        return HeartbeatSettings(
            TimeUnit.SECONDS.toMillis(configuredInterval.toLong()),
            TimeUnit.SECONDS.toMillis(configuredTimeout.toLong())
        )
    }

    // Starts the ping/pong heartbeat for `socket`, called once establishConnectionToPlatform's
    // handshake (OnServerConnectRequest + GetServerSettings, done in onConnectionEstablished) has
    // fully completed. Keeps the connection non-idle behind a reverse proxy (nginx's default 60s
    // proxy_read_timeout otherwise kills an idle upgraded connection) and lets us notice a dead
    // peer without waiting on a TCP-level timeout. Purely protocol-level (ping/pong frames) -
    // does not touch the AES-256-GCM message encryption path (platform-core-heartbeat).
    private fun startHeartbeat(socket: WebSocket) {
        val settings = resolveHeartbeatSettings()

        // Seed before the pongHandler, the ping timer or the death watchdog can possibly run, so
        // the very first interval can never look like an instant timeout before a single ping has
        // had a chance to round-trip (platform-core-heartbeat).
        lastPongReceivedAt = System.currentTimeMillis()

        socket.pongHandler {
            // Same socket-identity guard the closeHandler in establishConnectionToPlatform uses -
            // a pong for a socket that has since been replaced by a newer connection must not
            // refresh the new connection's liveness clock (platform-core-heartbeat).
            if (this.webSocket === socket) {
                lastPongReceivedAt = System.currentTimeMillis()

                // Every fresh pong pushes the death deadline back out - this is what lets a
                // healthy connection run indefinitely without ever tripping the watchdog
                // (platform-core-heartbeat-detect).
                scheduleDeathWatchdog(socket, settings)
            }
        }

        // Ping cadence only from here on - liveness is decided solely by the death watchdog
        // below, on its own schedule anchored to the actual last pong. Quantising death-detection
        // to a multiple of intervalMillis (as a single combined tick used to do) is exactly the
        // bug this split fixes: it let a peer that died right after a pong hide for up to one
        // extra full interval past the configured timeout (platform-core-heartbeat-detect).
        heartbeatTimerId = vertx.setPeriodic(settings.intervalMillis) { timerId ->
            // Inert once the socket this timer was started for is gone - closed, replaced by a
            // newer connection, or a stale timer that outlived a cancel race. Mirrors the
            // closeHandler's identity check in establishConnectionToPlatform (platform-core-heartbeat).
            if (this.webSocket !== socket) {
                vertx.cancelTimer(timerId)

                return@setPeriodic
            }

            // writePing (Vert.x 5) never throws synchronously here - a closed/broken socket, or
            // a ping payload over 125 bytes, both resolve as a FAILED Future instead (verified
            // against WebSocketImplBase.writeFrame/writePing: the closed check returns
            // context.failedFuture(...), never a throw). A synchronous try/catch around this call
            // is therefore dead code on every path, including the oversized-payload contract -
            // moot anyway since Buffer.buffer() here is always empty. Mirror the backend's
            // ServerManager and attach onFailure instead (platform-core-heartbeat).
            socket.writePing(Buffer.buffer()).onFailure { cause ->
                // The Future can complete after this socket has already been superseded (closed,
                // replaced by a newer connection) - re-check identity the same way the
                // pongHandler/death watchdog do, so a failure for a stale socket can't tear down
                // a connection that has since replaced it (platform-core-heartbeat).
                if (this.webSocket === socket) {
                    logger.warning(pluginMain.translateColor("&eFailed to send heartbeat ping to Pano Platform. Reason: ${cause.message}"))

                    killHeartbeatConnection(socket)
                }
            }
        }

        scheduleDeathWatchdog(socket, settings)
    }

    // (Re)schedules the single one-shot timer that actually decides the peer is dead, replacing
    // whatever watchdog timer was already pending for this heartbeat. Called once from
    // startHeartbeat (seeding the very first deadline) and then again on every pongHandler
    // invocation, so the fire instant is always settings.timeoutMillis minus
    // CLOSE_HANDSHAKE_BUDGET_MILLIS after the most recent pong - never quantised to a multiple of
    // the ping interval the way a shared ping/check tick was. This is what bounds worst-case
    // detection to the configured heartbeat-timeout instead of up to timeout + intervalMillis
    // (platform-core-heartbeat-detect).
    //
    // It deliberately fires before the full configured timeout has elapsed, to leave room for
    // killHeartbeatConnection's socket.close() below: against a peer that never answers with its
    // own close frame, close() waits out the WebSocketClient's closingTimeout before the
    // closeHandler (and therefore the reconnect) fires. PlatformManager doesn't construct that
    // WebSocketClient - SpringConfig.provideWebsocketClient() calls vertx.createWebSocketClient()
    // with no options - so it runs at the library default,
    // WebSocketClientOptions.DEFAULT_CLOSING_TIMEOUT (10s), and nothing in this file can shorten
    // it. Carving that budget out of the watchdog delay here instead keeps
    // detection-plus-close-handshake bounded by settings.timeoutMillis overall
    // (platform-core-heartbeat-detect).
    private fun scheduleDeathWatchdog(socket: WebSocket, settings: HeartbeatSettings) {
        deathWatchdogTimerId?.let { vertx.cancelTimer(it) }

        // Unreachable belt-and-braces: resolveHeartbeatSettings already rejects any config whose
        // timeout does not leave at least two full intervals after CLOSE_HANDSHAKE_BUDGET_MILLIS
        // is carved out, so this subtraction cannot land below intervalMillis for settings that
        // came from it. The floor stays because vertx.setTimer rejects anything under 1ms, and a
        // future edit to the validator should fail safe (a late watchdog) rather than turn every
        // pong into an almost-immediate spurious "dead" verdict.
        val delayMillis = (settings.timeoutMillis - CLOSE_HANDSHAKE_BUDGET_MILLIS)
            .coerceAtLeast(settings.intervalMillis)

        deathWatchdogTimerId = vertx.setTimer(delayMillis) {
            // Mirrors the ping timer's identity guard - inert for a socket that is already gone
            // or has been superseded (platform-core-heartbeat-detect).
            if (this.webSocket !== socket) {
                return@setTimer
            }

            val elapsedMillis = System.currentTimeMillis() - lastPongReceivedAt

            logger.warning(pluginMain.translateColor("&eNo heartbeat response from Pano Platform in ${elapsedMillis}ms, treating connection as dead."))

            killHeartbeatConnection(socket)
        }
    }

    // Cancels this heartbeat's timer, then closes `socket` so the EXISTING closeHandler /
    // onWebSocketClosed / connectPlatformTask reconnect path takes over from here - the heartbeat
    // does not reimplement any of that (platform-core-heartbeat).
    private fun killHeartbeatConnection(socket: WebSocket) {
        cancelHeartbeat()

        try {
            socket.close()
        } catch (_: Exception) {
            // Already closing/closed - closeHandler (if it hasn't already fired) still drives
            // onWebSocketClosed() and the reconnect loop from here regardless.
        }
    }

    // Cancelling an already-cancelled (or never-started) timer is harmless - vertx.cancelTimer
    // simply returns false for an unknown id - so every path that ends the connection
    // (onWebSocketClosed, closeConnection, stop, the deliberate-teardown branch in
    // establishConnectionToPlatform's catch, a heartbeat-detected death) can call this
    // unconditionally without needing to know whether a heartbeat is even running
    // (platform-core-heartbeat). Cancels both the ping-cadence timer and the death watchdog - all
    // existing call sites stay untouched and now tear down both by calling this one function
    // (platform-core-heartbeat-detect).
    private fun cancelHeartbeat() {
        heartbeatTimerId?.let { vertx.cancelTimer(it) }
        heartbeatTimerId = null

        deathWatchdogTimerId?.let { vertx.cancelTimer(it) }
        deathWatchdogTimerId = null
    }

    private fun savePlatform(host: String, port: Int, ssl: Boolean, token: String, encryptionKey: String): Boolean {
        if (configManager.config.platform == null) {
            configManager.config.platform = PanoConfig.Companion.PlatformConfig()
        }

        val platformConfig = configManager.config.platform!!

        platformConfig.host = host
        platformConfig.port = port
        platformConfig.ssl = ssl
        platformConfig.token = token
        platformConfig.encryptionKey = encryptionKey

        return configManager.saveConfig()
    }

    private fun removePlatform(): Boolean {
        if (configManager.config.platform == null) {
            configManager.config.platform = PanoConfig.Companion.PlatformConfig()
        }

        val platformConfig = configManager.config.platform!!

        platformConfig.host = ""
        platformConfig.port = 80
        platformConfig.ssl = false
        platformConfig.token = ""
        platformConfig.encryptionKey = ""

        synchronized(encryptionKeyLock) {
            encryptionKey = null
            encryptionKeySource = null
        }

        return configManager.saveConfig()
    }

    private fun getErrorMessageByErrorCode(error: String): String {
        if (error == PlatformErrorCodes.NEED_PERMISSION.toString()) {
            return "&cError: Need permission. &ePlease allow this server in panel of Pano Platform."
        }

        if (error == PlatformErrorCodes.INSTALLATION_REQUIRED.toString()) {
            return "&cError: Platform is not installed. Please first install your Pano platform."
        }

        if (error == PlatformErrorCodes.INVALID_TOKEN.toString()) {
            return "&cError: Token is invalid. Please reconnect Pano platform."
        }

        if (error == PlatformErrorCodes.INVALID_PLATFORM_CODE.toString()) {
            return "&cError: Platform code is invalid, check your information."
        }

        return "&cError: Failed to connect Pano Platform. Reason: $error"
    }

    private fun validateEncryptionKey() {
        val encodedKey = configManager.config.platform!!.encryptionKey

        // Rebuild whenever there is no cached key yet, or the config no longer matches what the
        // cache was built from - a new savePlatform() write or a hand-edited config.conf must
        // not keep encrypting/decrypting under a stale key (platform-core-14). Synchronized so the
        // read-compare-and-maybe-rebuild is atomic and no other thread can ever observe the key
        // and its source out of sync (platform-core-XX).
        synchronized(encryptionKeyLock) {
            if (encryptionKey == null || encryptionKeySource != encodedKey) {
                encryptionKey = Aes256GcmUtil.base64ToSecretKey(encodedKey)
                encryptionKeySource = encodedKey
            }
        }
    }

    fun sendMessage(platformRequest: PlatformRequest) {
        validateEncryptionKey()

        val message = platformRequest.encode()
        val encryptedMessage = Aes256GcmUtil.encrypt(message, encryptionKey!!)

        webSocket?.writeTextMessage(encryptedMessage)
    }

    suspend fun <T : PlatformMessageResponse> sendMessageAwaitResponse(
        platformRequest: PlatformRequest,
        responseType: Class<out PlatformMessageResponse>,
        timeoutMillis: Long = DEFAULT_RESPONSE_TIMEOUT_MILLIS
    ): T {
        // Fail fast instead of silently dropping the write below while the caller still awaits
        // it forever (platform-core-2).
        val socket = webSocket ?: throw PanoError("&cError: Not connected to Pano Platform.")

        validateEncryptionKey()

        val deferred = CompletableDeferred<T>()

        @Suppress("UNCHECKED_CAST")
        val pendingRequest = PendingRequest(deferred as CompletableDeferred<PlatformMessage>, responseType)

        pendingResponses[platformRequest.eventId] = pendingRequest

        try {
            val message = platformRequest.encode()
            val encryptedMessage = Aes256GcmUtil.encrypt(message, encryptionKey!!)

            try {
                socket.writeTextMessage(encryptedMessage).coAwait()
            } catch (exception: Exception) {
                deferred.completeExceptionally(exception)
                throw exception
            }

            // Bounded wait so a platform that never answers (or dies mid-request) can never park
            // the calling thread for the life of the JVM (platform-core-2).
            return withTimeout(timeoutMillis) {
                deferred.await()
            }
        } finally {
            // Guarantee cleanup on every exit path - success, write failure, timeout or
            // cancellation - so pendingResponses can never accumulate a stale entry
            // (platform-core-2 / platform-core-13).
            pendingResponses.remove(platformRequest.eventId)
        }
    }

    /**
     * Whether [host] is unambiguously loopback or on a private/link-local network, i.e. a host
     * where falling back to plain HTTP keeps the platform code and bearer token inside a trusted
     * network.
     *
     * Deliberately string-based on the literal the operator typed: this runs on the /pano command's
     * coroutine and must not block on a DNS lookup, and a public name that merely *resolves* to a
     * private address today is not proof the traffic stays on a trusted network tomorrow.
     */
    private fun isLocalOrPrivateHost(host: String): Boolean {
        // Strip IPv6 brackets and any zone id ("[fe80::1%eth0]" -> "fe80::1").
        val normalizedHost = host.trim().removeSurrounding("[", "]").substringBefore('%').lowercase()

        if (normalizedHost.isEmpty()) {
            return false
        }

        if (normalizedHost == "localhost" ||
            normalizedHost.endsWith(".localhost") ||
            normalizedHost.endsWith(".local") ||
            normalizedHost.endsWith(".internal") ||
            normalizedHost.endsWith(".home.arpa")
        ) {
            return true
        }

        if (normalizedHost.contains(":")) {
            // ::1 (loopback), fc00::/7 (unique local), fe80::/10 (link-local).
            return normalizedHost == "::1" ||
                    normalizedHost.startsWith("fc") ||
                    normalizedHost.startsWith("fd") ||
                    normalizedHost.startsWith("fe8") ||
                    normalizedHost.startsWith("fe9") ||
                    normalizedHost.startsWith("fea") ||
                    normalizedHost.startsWith("feb")
        }

        val octets = normalizedHost.split(".")

        if (octets.size != 4) {
            return false
        }

        val numbers = octets.map { octet -> octet.toIntOrNull() ?: return false }

        if (numbers.any { number -> number !in 0..255 }) {
            return false
        }

        // 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16.
        return when {
            numbers[0] == 127 -> true
            numbers[0] == 10 -> true
            numbers[0] == 172 && numbers[1] in 16..31 -> true
            numbers[0] == 192 && numbers[1] == 168 -> true
            numbers[0] == 169 && numbers[1] == 254 -> true
            else -> false
        }
    }

    companion object {
        // Caps the synchronous startup connect phase so a slow/unreachable platform can never
        // block the server's boot thread forever (platform-core-1); once spent, connectPlatformTask
        // hands off to the async background retry loop.
        private val BLOCKING_CONNECT_BUDGET_MILLIS = TimeUnit.SECONDS.toMillis(30)

        // Default bound for how long a caller can be parked awaiting a platform response
        // (platform-core-2). Callers with a tighter budget (e.g. pre-login paths) can still wrap
        // the call in their own, shorter withTimeout.
        private val DEFAULT_RESPONSE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15)

        // How long killHeartbeatConnection's socket.close() can be stuck waiting for a dead
        // peer's own close frame before the WebSocketClient gives up and forces the TCP
        // connection shut - i.e. WebSocketClientOptions.DEFAULT_CLOSING_TIMEOUT, since
        // SpringConfig.provideWebsocketClient() builds that client with no options and this file
        // has no way to override it per-connection (WebSocketConnectOptions has no equivalent
        // setting). Subtracted from the death watchdog's delay in scheduleDeathWatchdog so
        // detection-plus-close-handshake together still land within settings.timeoutMillis
        // (platform-core-heartbeat-detect).
        private val CLOSE_HANDSHAKE_BUDGET_MILLIS =
            TimeUnit.SECONDS.toMillis(WebSocketClientOptions.DEFAULT_CLOSING_TIMEOUT.toLong())
    }
}