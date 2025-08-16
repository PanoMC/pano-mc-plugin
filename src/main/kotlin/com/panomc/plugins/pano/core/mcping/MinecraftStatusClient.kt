package com.panomc.plugins.pano.core.mcping

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.*
import javax.imageio.ImageIO
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


/**
 * MinecraftStatusClient (Vert.x 5)
 *
 * Usage:
 *   val vertx = Vertx.vertx()
 *   val client = MinecraftStatusClient(vertx)
 *   val status = client.query(host = "127.0.0.1", port = 25565)
 *   println(status.versionName)
 *   client.close()
 */
class MinecraftStatusClient(
    vertx: Vertx,
    connectTimeoutMs: Int = 5000
) {
    private val net: NetClient = vertx.createNetClient(
        NetClientOptions()
            .setConnectTimeout(connectTimeoutMs)
            .setIdleTimeout((connectTimeoutMs / 1000).coerceAtLeast(1))
            .setTcpNoDelay(true)
    )

    /**
     * Query server status (Server List Ping, 1.7+).
     *
     * @param host Target hostname/IP.
     * @param port Target port (default 25565).
     * @param protocolVersion Protocol sent in handshake (most servers accept a wide range).
     * @param measureLatency If true, sends Ping (0x01) and reads Pong to measure RTT.
     */
    suspend fun query(
        host: String,
        port: Int = 25565,
        protocolVersion: Int = 760, // ~1.19.4; usually fine for status on modern servers
        measureLatency: Boolean = true
    ): McStatus {
        val socket = connectAwait(port, host)

        try {
            // ---- Handshake -> STATUS state ----
            val handshakePayload = Buffer.buffer()
                .putVarInt(protocolVersion)
                .putStringMC(host)
                .appendUnsignedShort(port)
                .putVarInt(1) // next state = STATUS (1)

            writePacket(socket, 0x00, handshakePayload)

            // ---- Status Request ----
            writePacket(socket, 0x00, Buffer.buffer())

            // ---- Status Response (JSON) ----
            val responsePacket = readPacket(socket)

            // Decode packetId AND how many bytes it consumed (no readerIndex in Vert.x Buffer)
            val (packetId, idBytes) = tryDecodeVarInt(responsePacket)
                ?: throw IllegalStateException("Could not decode status packet id")
            require(packetId == 0x00) { "Unexpected status packet id: $packetId" }

            // JSON string starts immediately after the packetId bytes
            val jsonStr = responsePacket.readStringMC(idBytes)

            // Minimal parsing with regex (replace with a JSON parser if preferred)
            val versionName = Regex("\"version\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"")
                .find(jsonStr)?.groupValues?.getOrNull(1)
            val protocol = Regex("\"version\"\\s*:\\s*\\{[^}]*\"protocol\"\\s*:\\s*(\\d+)")
                .find(jsonStr)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val playersOnline = Regex("\"players\"\\s*:\\s*\\{[^}]*\"online\"\\s*:\\s*(\\d+)")
                .find(jsonStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val playersMax = Regex("\"players\"\\s*:\\s*\\{[^}]*\"max\"\\s*:\\s*(\\d+)")
                .find(jsonStr)?.groupValues?.getOrNull(1)?.toIntOrNull()

            // Keep the whole "description" (component JSON or string)
            val descriptionJson = Regex("\"description\"\\s*:\\s*(\\{.*?\\}|\".*?\")")
                .find(jsonStr)?.groupValues?.getOrNull(1)

            // Favicon as data URL
            val faviconDataUrl = Regex("\"favicon\"\\s*:\\s*\"(data:image/png;base64,[^\"]+)\"")
                .find(jsonStr)?.groupValues?.getOrNull(1)

            // Decode favicon to image (if present)
            val faviconImage: BufferedImage? = faviconDataUrl?.let { dataUrl ->
                val base64Part = dataUrl.substringAfter("base64,", missingDelimiterValue = "")
                if (base64Part.isNotEmpty()) {
                    val bytes = Base64.getDecoder().decode(base64Part)
                    ImageIO.read(ByteArrayInputStream(bytes))
                } else null
            }

            // ---- Optional latency: Ping 0x01 / Pong 0x01 ----
            var latency: Long? = null
            if (measureLatency) {
                val now = System.nanoTime()
                val pingPayload = Buffer.buffer().appendLong(now)
                writePacket(socket, 0x01, pingPayload)

                val pong = readPacket(socket)
                val (pongId, pongIdBytes) = tryDecodeVarInt(pong)
                    ?: throw IllegalStateException("Could not decode pong packet id")
                if (pongId == 0x01) {
                    // The payload is the echoed long immediately after the packetId bytes
                    val echoed = pong.getLong(pongIdBytes)
                    latency = (System.nanoTime() - echoed) / 1_000_000
                }
            }

            return McStatus(
                versionName = versionName,
                protocol = protocol,
                playersOnline = playersOnline,
                playersMax = playersMax,
                descriptionJson = descriptionJson,
                faviconDataUrl = faviconDataUrl,
                faviconImage = faviconImage,
                latencyMs = latency
            )
        } finally {
            // Always try to close the socket
            try {
                closeAwait(socket)
            } catch (_: Throwable) { /* ignore */
            }
        }
    }

    /** Close the underlying NetClient (optional). */
    fun close() {
        try {
            net.close()
        } catch (_: Throwable) { /* ignore */
        }
    }

    // -----------------------------------------------------------------------
    // Private suspend wrappers for Vert.x 5 Futures
    // -----------------------------------------------------------------------
// Vert.x 5: connect returns Future<NetSocket> (no handler arg)
    private suspend fun connectAwait(port: Int, host: String): NetSocket =
        suspendCancellableCoroutine { cont ->
            val fut = net.connect(port, host) // returns Future<NetSocket>
            fut.onComplete { ar ->
                if (ar.succeeded()) cont.resume(ar.result())
                else cont.resumeWithException(ar.cause())
            }
        }

    // Vert.x 5: write returns Future<Void>
    private suspend fun writePacket(socket: NetSocket, packetId: Int, payload: Buffer): Unit =
        suspendCancellableCoroutine { cont ->
            val inner = Buffer.buffer().putVarInt(packetId).appendBuffer(payload)
            val framed = Buffer.buffer().putVarInt(inner.length()).appendBuffer(inner)
            val fut = socket.write(framed) // returns Future<Void>
            fut.onComplete { ar ->
                if (ar.succeeded()) cont.resume(Unit)
                else cont.resumeWithException(ar.cause())
            }
        }
    /** Read exactly one framed Minecraft packet: [VarInt length][VarInt id][payload…]. */
    private suspend fun readPacket(socket: NetSocket): Buffer =
        suspendCancellableCoroutine { cont ->
            var acc = Buffer.buffer()
            var readIndex = 0
            var expectedLen: Int? = null
            val MAX_PACKET_LEN = 2 * 1024 * 1024 // 2 MiB koruma

            fun compactIfNeeded() {
                // Çok ilerlediysek, tüketilmeyen kısmı yeni bir büyüyebilir buffer'a kopyala
                if (readIndex > 65536 || readIndex == acc.length()) {
                    val remaining = acc.length() - readIndex
                    val fresh = Buffer.buffer(remaining.coerceAtLeast(0))
                    if (remaining > 0) fresh.appendBuffer(acc.getBuffer(readIndex, acc.length()))
                    acc = fresh
                    readIndex = 0
                }
            }

            val handler = io.vertx.core.Handler<Buffer> { chunk ->
                acc.appendBuffer(chunk)

                while (true) {
                    if (expectedLen == null) {
                        // Paket uzunluğu varInt, acc[readIndex..] başlangıcından oku
                        val lenDecoded = tryDecodeVarIntAt(acc, readIndex) ?: break
                        val (len, lenBytes) = lenDecoded
                        require(len in 0..MAX_PACKET_LEN) { "Packet length $len exceeds limit" }
                        // Uzunluk alanını aş
                        readIndex += lenBytes
                        expectedLen = len
                    }

                    val need = expectedLen!!
                    if (acc.length() - readIndex < need) break

                    // Tam bir paket var: [VarInt id][payload…] uzunluğu = need
                    val packet = acc.getBuffer(readIndex, readIndex + need)
                    readIndex += need
                    expectedLen = null

                    socket.handler(null)
                    cont.resume(packet)
                    return@Handler
                }

                compactIfNeeded()
            }

            socket.handler(handler)
            socket.exceptionHandler { err ->
                socket.handler(null)
                if (!cont.isCompleted) cont.resumeWithException(err)
            }
            socket.closeHandler {
                if (!cont.isCompleted) cont.resumeWithException(IllegalStateException("Socket closed"))
            }
        }

    /** Decode VarInt at absolute [index]; returns (value, bytesConsumed) or null if incomplete. */
    private fun tryDecodeVarIntAt(buf: Buffer, index: Int): Pair<Int, Int>? {
        var numRead = 0
        var result = 0
        while (true) {
            if (buf.length() <= index + numRead) return null
            val read = buf.getByte(index + numRead).toInt() and 0xFF
            val value = read and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++
            if (numRead > 5) throw IllegalArgumentException("VarInt too big")
            if ((read and 0x80) == 0) break
        }
        return result to numRead
    }

    private suspend fun closeAwait(socket: NetSocket): Unit =
        suspendCancellableCoroutine { cont ->
            val fut = socket.close() // Future<Void>
            fut.onComplete { ar ->
                if (ar.succeeded()) cont.resume(Unit)
                else cont.resumeWithException(ar.cause())
            }
        }

    // -----------------------------------------------------------------------
    // Private binary helpers (MC VarInt, MC String, etc.)
    // -----------------------------------------------------------------------

    /** Append a VarInt (7-bit continuation) to this buffer. */
    private fun Buffer.putVarInt(value: Int): Buffer {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                this.appendByte(v.toByte())
                return this
            }
            this.appendByte(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
    }

    /**
     * Try to decode a VarInt from the **start** of [buf];
     * returns (value, bytesConsumed) or null if incomplete.
     */
    private fun tryDecodeVarInt(buf: Buffer): Pair<Int, Int>? {
        var numRead = 0
        var result = 0
        while (true) {
            if (buf.length() <= numRead) return null
            val read = buf.getByte(numRead).toInt() and 0xFF
            val value = read and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++
            if (numRead > 5) throw IllegalArgumentException("VarInt too big")
            if ((read and 0x80) == 0) break
        }
        return result to numRead
    }

    /** Read a VarInt at an absolute [index] (does not mutate any pointer). */
    private fun Buffer.readVarIntAt(index: Int): Int {
        var numRead = 0
        var result = 0
        while (true) {
            val read = this.getByte(index + numRead).toInt() and 0xFF
            val value = read and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++
            if (numRead > 5) throw IllegalArgumentException("VarInt too big")
            if ((read and 0x80) == 0) break
        }
        return result
    }

    /** Write a Minecraft UTF-8 string: [VarInt length][bytes…]. */
    private fun Buffer.putStringMC(s: String): Buffer {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        this.putVarInt(bytes.size)
        this.appendBytes(bytes)
        return this
    }

    /**
     * Read a Minecraft UTF-8 string that starts at absolute [index].
     * Format: [VarInt length][bytes…].
     */
    private fun Buffer.readStringMC(index: Int): String {
        var i = index
        // Decode VarInt length
        var numRead = 0
        var len = 0
        while (true) {
            val read = this.getByte(i + numRead).toInt() and 0xFF
            val value = read and 0x7F
            len = len or (value shl (7 * numRead))
            numRead++
            if ((read and 0x80) == 0) break
            if (numRead > 5) throw IllegalArgumentException("VarInt too big")
        }
        i += numRead
        val bytes = this.getBytes(i, i + len)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** Append an unsigned 16-bit integer (big-endian). */
    private fun Buffer.appendUnsignedShort(v: Int): Buffer {
        this.appendByte(((v ushr 8) and 0xFF).toByte())
        this.appendByte((v and 0xFF).toByte())
        return this
    }
}
