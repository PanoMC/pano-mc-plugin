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
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.crypto.SecretKey

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
    private var webSocket: WebSocket? = null
    private var canConnect = true // to be able to cancel connection task
    private val pendingResponses = mutableMapOf<UUID, CompletableDeferred<PlatformMessage>>()
    private val pendingResponseTypes = mutableMapOf<UUID, Class<out PlatformMessageResponse>>()
    lateinit var serverSettings: GetServerSettingsMessage
        internal set

    private var encryptionKey: SecretKey? = null
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
            suspend fun run() {
                if (delay) {
                    delay(TimeUnit.SECONDS.toMillis(3))
                }

                if (!isPlatformConfigured()) {
                    return
                }

                if (canConnect) {
                    establishConnectionToPlatform(async)
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
            configsToTry.add(Triple(h, p, s))
        } else if (platformAddress.contains(":")) {
            val splitHost = platformAddress.split(":")
            val h = splitHost[0]
            val p = splitHost[1].toIntOrNull() ?: 8088
            configsToTry.add(Triple(h, p, true))
            configsToTry.add(Triple(h, p, false))
        } else {
            configsToTry.add(Triple(platformAddress, 443, true))
            configsToTry.add(Triple(platformAddress, 80, false))
            configsToTry.add(Triple(platformAddress, 8088, false))
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
            try {
                val response = webClient
                    .post(p, h, "/api/server/connect")
                    .ssl(s)
                    .timeout(5000)
                    .sendJsonObject(requestBody)
                    .coAwait()

                if (response.statusCode() == 200) {
                    val body = response.bodyAsJsonObject()
                    if (body != null && (body.containsKey("result") || body.containsKey("token"))) {
                        finalResponse = response
                        finalHost = h
                        finalPort = p
                        finalSsl = s
                        break
                    }
                }
            } catch (exception: Exception) {
                lastException = exception
            }
        }

        if (finalResponse == null) {
            lastException?.printStackTrace()
            throw PanoError("&cCouldn't connect to Pano Platform. Checked all possible configurations. Checkout console for more detail.")
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

        savePlatform(finalHost, finalPort, finalSsl, token, encryptionKey)
        canConnect = true
    }

    suspend fun disconnectPlatform() {
        canConnect = false
        closeConnection()

        // Notify integrations to unregister their events
        pluginMain.onDisconnect()

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
            logger.severe(pluginMain.translateColor("&cError: Failed to connect Pano Platform. Reason: ${exception.message}"))

            throw Exception("&cCouldn't connect to Pano Platform. Be sure platform is up and accessible.")
        }

        removePlatform()
    }

    private suspend fun establishConnectionToPlatform(async: Boolean) {
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
                val body = exception.body.toJsonObject()

                if (body != null && body.getString("result") == "error") {
                    val error = body.getString("error")

                    logger.severe(pluginMain.translateColor(getErrorMessageByErrorCode(error)))

                    if (error == PlatformErrorCodes.INVALID_TOKEN.toString()) {
                        removePlatform()

                        return
                    }

                    connectPlatformTask.invoke(true, async)

                    return
                }
            }

            logger.severe(pluginMain.translateColor("&cError: Failed to connect Pano Platform. Reason: ${exception.message}"))

            connectPlatformTask.invoke(true, async)

            return
        }

        logger.info(pluginMain.translateColor("&2Connected successfully to the platform!"))

        this.webSocket = webSocket

        webSocket.textMessageHandler { msg ->
            CoroutineScope(vertx.dispatcher()).launch {
                onWebsocketTextMessage(msg)
            }
        }

        webSocket.closeHandler {
            onWebSocketClosed()
        }

        onConnectionEstablished()
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
        validateEncryptionKey()
        val msg = Aes256GcmUtil.decrypt(encryptedMessage, encryptionKey!!)
        val json = JsonObject(msg)
        val event = json.getString("event")
        json.remove("event")

        val eventId = if (json.getString("eventId") == null) null else UUID.fromString(json.getString("eventId"))

        json.remove("eventId")

        if (pendingResponses.containsKey(eventId)) {
            val responseType = pendingResponseTypes[eventId]!!
            if (responseType.responseName() == event) {
                pendingResponses[eventId]?.complete(Pano.gson.fromJson(json.encode(), responseType))
            }
            pendingResponses.remove(eventId)
            return
        }

        messageHandlerDefinitions.find { it.getHandlerName() == event }?.let {
            val messageObj = Pano.gson.fromJson(json.encode(), it.messageClass)

            @Suppress("UNCHECKED_CAST")
            val typedListener = it as PlatformMessageHandler<PlatformMessage>

            typedListener.handle(messageObj)
        }
    }

    private fun onWebSocketClosed() {
        webSocket = null

        // Notify integrations to unregister their events
        pluginMain.onDisconnect()

        if (canConnect) {
            logger.info(pluginMain.translateColor("&6Lost connection to platform."))

            logger.info(pluginMain.translateColor("&eRetrying to connect..."))

            connectPlatformTask.invoke(true, true)
        }
    }

    private suspend fun closeConnection() {
        webSocket?.close()?.coAwait()
    }

    private fun savePlatform(host: String, port: Int, ssl: Boolean, token: String, encryptionKey: String) {
        if (configManager.config.platform == null) {
            configManager.config.platform = PanoConfig.Companion.PlatformConfig()
        }

        val platformConfig = configManager.config.platform!!

        platformConfig.host = host
        platformConfig.port = port
        platformConfig.ssl = ssl
        platformConfig.token = token
        platformConfig.encryptionKey = encryptionKey

        configManager.saveConfig()
    }

    private fun removePlatform() {
        if (configManager.config.platform == null) {
            configManager.config.platform = PanoConfig.Companion.PlatformConfig()
        }

        val platformConfig = configManager.config.platform!!

        platformConfig.host = ""
        platformConfig.port = 80
        platformConfig.ssl = false
        platformConfig.token = ""
        platformConfig.encryptionKey = ""

        encryptionKey = null

        configManager.saveConfig()
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
        if (encryptionKey == null) {
            val encodedKey = configManager.config.platform!!.encryptionKey
            encryptionKey = Aes256GcmUtil.base64ToSecretKey(encodedKey)
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
        responseType: Class<out PlatformMessageResponse>
    ): T {
        validateEncryptionKey()

        val deferred = CompletableDeferred<T>()
        pendingResponses[platformRequest.eventId] = deferred as CompletableDeferred<PlatformMessage>
        pendingResponseTypes[platformRequest.eventId] = responseType

        val message = platformRequest.encode()
        val encryptedMessage = Aes256GcmUtil.encrypt(message, encryptionKey!!)

        webSocket?.writeTextMessage(encryptedMessage)
        return deferred.await()
    }
}