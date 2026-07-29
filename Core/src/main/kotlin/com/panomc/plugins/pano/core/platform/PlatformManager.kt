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

            // No explicit scheme was given: only try HTTPS. Do NOT silently fall back to plain
            // HTTP here — that would transmit the one-time platform code and the returned bearer
            // token in the clear. An operator who wants a plaintext connection (e.g. a local
            // self-hosted panel) opts in explicitly with an "http://" prefix, handled above
            // (platform-core-12 / cross-cutting-12).
            configsToTry.add(Triple(h, p, true))
        } else {
            configsToTry.add(Triple(platformAddress, 443, true))
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
            throw PanoError("&cCouldn't connect to Pano Platform. Tried: $triedConfigs")
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
            // Make the cleartext downgrade loud rather than automatic (platform-core-12 /
            // cross-cutting-12) - the operator explicitly opted into "http://" for this to happen.
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
        webSocket?.close()?.coAwait()
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

    companion object {
        // Caps the synchronous startup connect phase so a slow/unreachable platform can never
        // block the server's boot thread forever (platform-core-1); once spent, connectPlatformTask
        // hands off to the async background retry loop.
        private val BLOCKING_CONNECT_BUDGET_MILLIS = TimeUnit.SECONDS.toMillis(30)

        // Default bound for how long a caller can be parked awaiting a platform response
        // (platform-core-2). Callers with a tighter budget (e.g. pre-login paths) can still wrap
        // the call in their own, shorter withTimeout.
        private val DEFAULT_RESPONSE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15)
    }
}