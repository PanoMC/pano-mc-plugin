package com.panomc.plugins.pano.core.console

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.platform.request.ConsoleLinesRequest
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the batching, ring-buffer and back-pressure rules of the console contract
 * (AGENT.md 2.4.1). The Vert.x flush timer is deliberately bypassed by calling [ConsoleStreamer.flush]
 * directly so the assertions are deterministic rather than time-dependent.
 */
class ConsoleStreamerTest {
    private lateinit var vertx: Vertx
    private lateinit var dataFolder: File
    private lateinit var configManager: ConfigManager
    private lateinit var streamer: ConsoleStreamer

    private val sent = mutableListOf<ConsoleLinesRequest>()
    private var connected = true

    @BeforeTest
    fun setUp() {
        vertx = Vertx.vertx()
        dataFolder = Files.createTempDirectory("pano-console-test").toFile()
        configManager = ConfigManager(vertx, Logger.getLogger("PanoConsoleTest"), dataFolder)

        runBlocking { configManager.init() }

        streamer = ConsoleStreamer(
            vertx,
            Logger.getLogger("PanoConsoleTest"),
            configManager,
            FakePluginMain(dataFolder),
            { request -> sent.add(request as ConsoleLinesRequest) },
            { connected }
        )

        // Drive flush() by hand instead of racing the 250 ms Vert.x timer.
        streamer.scheduleFlushes = false
        streamer.start()
    }

    @AfterTest
    fun tearDown() {
        streamer.stop()
        configManager.close()
        vertx.close()
        dataFolder.deleteRecursively()
    }

    @Test
    fun `sends nothing until pano asks for the stream`() {
        streamer.accept(line("before"))
        streamer.flush()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `replays the ring buffer oldest first when streaming is enabled`() {
        repeat(3) { streamer.accept(line("line $it")) }

        streamer.setStreaming(true)
        streamer.flush()

        assertEquals(listOf("line 0", "line 1", "line 2"), sent.flatMap { it.lines }.map { it.m })
    }

    @Test
    fun `keeps only the last 500 lines in the ring buffer`() {
        repeat(ConsoleStreamer.RING_BUFFER_SIZE + 120) { streamer.accept(line("line $it")) }

        streamer.setStreaming(true)
        streamer.flush()

        val replayed = sent.flatMap { it.lines }.map { it.m }

        assertEquals(ConsoleStreamer.RING_BUFFER_SIZE, replayed.size)
        assertEquals("line 120", replayed.first())
        assertEquals("line ${ConsoleStreamer.RING_BUFFER_SIZE + 119}", replayed.last())
    }

    @Test
    fun `never puts more than a batch of lines in one message`() {
        repeat(250) { streamer.accept(line("line $it")) }

        streamer.setStreaming(true)
        streamer.flush()

        assertTrue(sent.all { it.lines.size <= ConsoleStreamer.BATCH_SIZE })
        assertEquals(250, sent.sumOf { it.lines.size })
    }

    @Test
    fun `caps the stream at 500 lines per second and reports the overflow as dropped`() {
        streamer.setStreaming(true)

        // One window's worth plus a full extra queue: the cap lets 500 through, the queue holds
        // PENDING_LIMIT and everything beyond that is dropped oldest-first.
        val total = ConsoleStreamer.MAX_LINES_PER_SECOND + ConsoleStreamer.PENDING_LIMIT + 300

        repeat(total) { streamer.accept(line("line $it")) }

        streamer.flush()

        assertEquals(ConsoleStreamer.MAX_LINES_PER_SECOND, sent.sumOf { it.lines.size })

        // Everything the queue could not hold is accounted for, and the first line that survived
        // is not "line 0" - the oldest ones are the ones that went.
        assertEquals(total - ConsoleStreamer.PENDING_LIMIT, sent.sumOf { it.dropped }.toInt())
        assertTrue(sent.first().lines.first().m != "line 0")
    }

    @Test
    fun `stops streaming but keeps buffering while the connection is down`() {
        streamer.setStreaming(true)
        streamer.accept(line("before"))
        streamer.flush()
        sent.clear()

        streamer.onDisconnect()
        streamer.accept(line("during outage"))
        streamer.flush()

        assertTrue(sent.isEmpty(), "nothing may be sent while the stream is off")

        // ... and the buffered line is replayed once Pano asks again.
        streamer.setStreaming(true)
        streamer.flush()

        assertEquals(listOf("before", "during outage"), sent.flatMap { it.lines }.map { it.m })
    }

    @Test
    fun `queues instead of sending while the socket is down`() {
        streamer.setStreaming(true)
        connected = false

        streamer.accept(line("offline"))
        streamer.flush()

        assertTrue(sent.isEmpty())

        connected = true
        streamer.flush()

        assertEquals(listOf("offline"), sent.flatMap { it.lines }.map { it.m })
    }

    @Test
    fun `emits pano's own lines into the same stream`() {
        streamer.setStreaming(true)
        streamer.emit(ConsoleLevel.INFO, "[Pano:admin] > say hi")
        streamer.flush()

        assertEquals(listOf("[Pano:admin] > say hi"), sent.flatMap { it.lines }.map { it.m })
        assertEquals("INFO", sent.first().lines.first().l)
    }

    private fun line(message: String) = ConsoleLine(1_000L, ConsoleLevel.INFO, message)

    private class FakePluginMain(private val dataFolder: File) : PanoPluginMain {
        override fun getDataFolder(): File = dataFolder

        override fun getPanoLogger(): Logger = Logger.getLogger("PanoConsoleTest")

        override fun getPano(): Pano = throw UnsupportedOperationException("not needed for this test")

        override fun registerCommands(commands: List<Command>) {}

        override fun unregisterCommands(commands: List<Command>) {}

        override fun getServerData(): ServerData = object : ServerData {
            override fun serverName(): String = "test"
            override fun hostAddress(): String = "127.0.0.1"
            override fun motd(): String? = null
            override fun port(): Int = 25565
            override fun serverType(): ServerType = ServerType.SPIGOT
            override fun serverVersion(): String = "test"
            override fun playerCount(): Int = 0
            override fun maxPlayerCount(): Int = 0
        }

        override fun getPluginClassLoader(): URLClassLoader = URLClassLoader(emptyArray())

        override fun translateColor(text: String): String = text

        override fun registerEventListeners(listeners: Set<Listener>) {}

        override fun unregisterEventListeners(listeners: Set<Listener>) {}

        override fun kickPlayer(player: String, message: String) {}

        // No capture: these tests drive accept() themselves.
        override fun installConsoleCapture(sink: (ConsoleLine) -> Unit): AutoCloseable? = AutoCloseable {}
    }
}
