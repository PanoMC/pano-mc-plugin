package com.panomc.plugins.pano.core.platform

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.config.PanoConfig
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.mcping.MinecraftStatusClient
import com.panomc.plugins.pano.core.model.PanoError
import com.panomc.plugins.pano.core.platform.PlatformMessage.Companion.responseName
import com.panomc.plugins.pano.core.platform.message.response.PongMessage
import com.panomc.plugins.pano.core.platform.request.OnServerConnectRequest
import com.panomc.plugins.pano.core.util.ImageUtil
import io.vertx.core.Vertx
import io.vertx.core.http.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class PlatformManager(
    private val vertx: Vertx,
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val webClient: WebClient,
    private val webSocketClient: WebSocketClient,
    private val minecraftStatusClient: MinecraftStatusClient,
    private val serverData: ServerData,
    private val pluginMain: PanoPluginMain
) {
    private var webSocket: WebSocket? = null
    private var canConnect = true // to be able to cancel connection task
    private val pendingResponses = mutableMapOf<UUID, CompletableDeferred<PlatformMessage>>()

    internal val messageResponseDefinitions = mutableSetOf<Class<out PlatformMessageResponse>>(
        PongMessage::class.java
    )

    internal val messageHandlerDefinitions = mutableSetOf<PlatformMessageHandler<*>>()

    val connectPlatformTask: (delay: Boolean) -> Unit by lazy {
        {
            CoroutineScope(vertx.dispatcher()).launch {
                if (it) {
                    delay(TimeUnit.SECONDS.toMillis(3))
                }

                if (!isPlatformConfigured()) {
                    return@launch
                }

                if (canConnect) {
                    establishConnectionToPlatform()
                }
            }
        }
    }

    fun isPlatformConfigured(): Boolean {
        val platformConfig = configManager.config.platform ?: return false

        return !platformConfig.token.isNullOrEmpty()
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
        connectPlatformTask.invoke(false)
    }

    suspend fun stop() {
        canConnect = false

        if (!isPlatformConfigured()) {
            return
        }

        val isWebsocketNull = webSocket == null

        closeConnection()

        if (!isWebsocketNull) {
            printLostConnectionToPlatform()
        }
    }

    fun getWebSocket() = webSocket

    suspend fun connectNewPlatform(platformAddress: String, platformCode: String) {
        val pingData = minecraftStatusClient.query(host = serverData.hostAddress(), port = serverData.port())

        var port = 8088
        var host = platformAddress

        if (host.contains(":")) {
            val splitHost = host.split(":")

            host = splitHost[0]

            port = splitHost[1].toInt()
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

        if (pingData.faviconImage != null) {
            requestBody
                .put(
                    "favicon",
                    ImageUtil.bufferedImageToDataUrl(pingData.faviconImage)
                )
        }

        if (pingData.descriptionJson != null) {
            requestBody
                .put("motd", pingData.descriptionJson)
        }

        val request = webClient
            .post(port, host, "/api/server/connect")
            .sendJsonObject(requestBody)

        val response: HttpResponse<*>

        try {
            response = request.coAwait()
        } catch (exception: Exception) {
            exception.printStackTrace()

            throw PanoError("&cCouldn't connect to Pano Platform. Check your information. Checkout console for more detail.")
        }

        val body = response.bodyAsJsonObject()

        if (body != null && body.getString("result") == "error") {
            val error = body.getString("error")

            throw PanoError(getErrorMessageByErrorCode(error))
        }

        val token = body.getString("token")

        savePlatform(host, port, token)
        canConnect = true
    }

    suspend fun disconnectPlatform() {
        canConnect = false
        closeConnection()

        val platformConfig = configManager.config.platform!!
        val host = platformConfig.host
        val port = platformConfig.port!!
        val token = platformConfig.token

        val request = webClient
            .post(port, host, "/api/server/disconnect")
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

    private suspend fun establishConnectionToPlatform() {
        val platformConfig = configManager.config.platform!!
        val host = platformConfig.host
        val port = platformConfig.port
        val token = platformConfig.token

        val webSocketConnectOptions = WebSocketConnectOptions()

        webSocketConnectOptions.host = host
        webSocketConnectOptions.port = port
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

                    connectPlatformTask.invoke(true)

                    return
                }
            }

            logger.severe(pluginMain.translateColor("&cError: Failed to connect Pano Platform. Reason: ${exception.message}"))

            connectPlatformTask.invoke(true)

            return
        }

        logger.info(pluginMain.translateColor("&2Connected successfully to the platform!"))

        this.webSocket = webSocket

        onConnectionEstablished()

        webSocket.textMessageHandler { msg ->
            CoroutineScope(vertx.dispatcher()).launch {
                onWebsocketTextMessage(msg)
            }
        }

        webSocket.closeHandler {
            onWebSocketClosed()
        }
    }

    private suspend fun onConnectionEstablished() {
        val pingData = minecraftStatusClient.query(host = serverData.hostAddress(), port = serverData.port())

        val eventRequest = OnServerConnectRequest(
            serverData.serverName(),
            serverData.playerCount(),
            serverData.maxPlayerCount(),
            serverData.serverType(),
            serverData.serverVersion(),
            serverData.hostAddress(),
            serverData.port(),
            Pano.serverStartTime,
            if (pingData.faviconImage != null) ImageUtil.bufferedImageToDataUrl(pingData.faviconImage) else null,
            pingData.descriptionJson
        )

        webSocket?.writeTextMessage(eventRequest.encode())

        logger.info(pluginMain.translateColor("Sent server info update to the platform."))

        pluginMain.onConnectionEstablished(webSocket)
    }

    private suspend fun onWebsocketTextMessage(msg: String) {
        val json = JsonObject(msg)
        val event = json.getString("event")
        json.remove("event")

        val eventId = if (json.getString("eventId") == null) null else UUID.fromString(json.getString("eventId"))

        if (eventId == null) {
            messageHandlerDefinitions.find { it.getHandlerName() == event }?.let {
                val messageObj = Pano.gson.fromJson(json.encode(), it.messageClass)

                @Suppress("UNCHECKED_CAST")
                val typedListener = it as PlatformMessageHandler<PlatformMessage>

                typedListener.handle(messageObj)
            }

            return
        }

        json.remove("eventId")

        if (pendingResponses.containsKey(eventId)) {
            messageResponseDefinitions.find { it.responseName() == event }?.let {
                pendingResponses[eventId]?.complete(Pano.gson.fromJson(json.encode(), it))
            }
            pendingResponses.remove(eventId)
        }
    }

    private fun printLostConnectionToPlatform() {
        logger.info(pluginMain.translateColor("&6Lost connection to platform."))
    }

    private fun onWebSocketClosed() {
        webSocket = null

        if (canConnect) {
            printLostConnectionToPlatform()

            logger.info(pluginMain.translateColor("&eRetrying to connect..."))

            connectPlatformTask.invoke(true)
        }
    }

    private suspend fun closeConnection() {
        webSocket?.close()?.coAwait()
    }

    private fun savePlatform(host: String, port: Int, token: String) {
        if (configManager.config.platform == null) {
            configManager.config.platform = PanoConfig.Companion.PlatformConfig()
        }

        val platformConfig = configManager.config.platform!!

        platformConfig.host = host
        platformConfig.port = port
        platformConfig.token = token

        configManager.saveConfig()
    }

    private fun removePlatform() {
        if (configManager.config.platform == null) {
            configManager.config.platform = PanoConfig.Companion.PlatformConfig()
        }

        val platformConfig = configManager.config.platform!!

        platformConfig.host = ""
        platformConfig.port = 8080
        platformConfig.token = ""

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

    fun sendRequest(platformRequest: PlatformRequest) {
        webSocket?.writeTextMessage(platformRequest.encode())
    }

    suspend fun <T : PlatformMessage> sendRequestAwaitResponse(
        platformRequest: PlatformRequest
    ): T {
        val deferred = CompletableDeferred<T>()
        pendingResponses[platformRequest.eventId] = deferred as CompletableDeferred<PlatformMessage>
        webSocket?.writeTextMessage(platformRequest.encode())
        return deferred.await()
    }
}