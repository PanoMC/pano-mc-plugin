package com.panomc.plugins.pano.core.platform

import io.vertx.core.Vertx
import io.vertx.core.http.WebSocketConnectOptions
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameGateTest {
    @Test
    fun `frames that arrive before the gate opens are delivered first, in order`() {
        val gate = FrameGate()
        val read = mutableListOf<String>()

        gate.receive("0")
        gate.receive("1")
        gate.open { read.add(it) }
        gate.receive("2")

        assertEquals(listOf("0", "1", "2"), read)
    }

    @Test
    fun `a refused frame stops delivery, held or live`() {
        val gate = FrameGate()
        val read = mutableListOf<String>()

        gate.receive("ok")
        gate.receive("bad")
        gate.receive("after")
        gate.open { read.add(it); it != "bad" }
        gate.receive("later")

        assertEquals(listOf("ok", "bad"), read)
    }

    @Test
    fun `a refused live frame stops the ones after it`() {
        val gate = FrameGate()
        val read = mutableListOf<String>()

        gate.open { read.add(it); it != "bad" }
        gate.receive("bad")
        gate.receive("after")

        assertEquals(listOf("bad"), read)
    }

    @Test
    fun `a closed gate drops what it held and delivers nothing`() {
        val gate = FrameGate()
        val read = mutableListOf<String>()

        gate.receive("0")
        gate.close()
        gate.open { read.add(it) }
        gate.receive("1")

        assertTrue(read.isEmpty())
    }

    /**
     * The bug itself, against a real Vert.x server: Pano writes the moment it accepts the socket,
     * and the plugin opens its channel a little later. Every frame must still arrive, in order.
     */
    @Test
    fun `nothing Pano writes on accept is lost while the client is still getting ready`() {
        val vertx = Vertx.vertx()

        try {
            val server = vertx.createHttpServer()
                .webSocketHandler { socket ->
                    // Right after the handshake, as Pano's ServerConnectAPI does.
                    vertx.setTimer(1) { (0 until 5).forEach { socket.writeTextMessage("frame-$it") } }
                }
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

            val gate = FrameGate()
            val read = Collections.synchronizedList(mutableListOf<String>())
            val all = CountDownLatch(5)

            val options = WebSocketConnectOptions().setHost("127.0.0.1").setPort(server.actualPort()).setURI("/")

            // The first handshake against a server this fresh is closed by Vert.x itself (seen on every run,
            // unrelated to the gate); one throwaway connection gets past it.
            runCatching {
                vertx.createWebSocketClient().connect(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
            }

            vertx.createWebSocketClient().webSocket()
                .textMessageHandler { gate.receive(it) }
                .connect(options)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

            // The plugin agreeing its channel and saving config.conf: the frames are already on their way.
            Thread.sleep(300)

            gate.open { read.add(it); all.countDown(); true }

            assertTrue(all.await(10, TimeUnit.SECONDS), "frames received: $read")
            assertEquals((0 until 5).map { "frame-$it" }, read.toList())
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }
}
