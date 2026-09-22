package com.panomc.plugins.pano.core.metrics

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.files.TestPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.entity.PlayerData
import com.panomc.plugins.pano.core.platform.request.ServerMetricsRequest
import io.vertx.core.Vertx
import java.io.File
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a `SERVER_METRICS` sample carries without a Minecraft server behind it: the two disk
 * figures of AGENT.md 2.4.18 A, and the promise that one platform accessor blowing up costs that
 * field alone.
 */
class MetricsReporterTest {
    private lateinit var vertx: Vertx
    private lateinit var root: File
    private lateinit var pluginMain: TestPluginMain

    private val sent = mutableListOf<PlatformRequest>()

    private val logger: Logger = Logger.getLogger("pano-test")

    @BeforeTest
    fun createServer() {
        vertx = Vertx.vertx()
        root = Files.createTempDirectory("pano-metrics").toFile()
        pluginMain = TestPluginMain(root)

        sent.clear()

        File(root, "world/level.dat").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2048))
        }
    }

    @AfterTest
    fun removeServer() {
        vertx.close()

        root.deleteRecursively()
    }

    @Test
    fun `every sample carries the size of the partition the server sits on`() {
        val sample = reporter().sample()

        // One syscall, not a walk: unlike diskUsed this is on the very first sample, which is
        // what lets the panel draw a disk gauge before the first directory walk has landed.
        assertEquals(root.totalSpace, sample.diskTotal)
        assertTrue(sample.diskTotal!! > 0L, "a real filesystem always has a size")
    }

    @Test
    fun `the directory size arrives once the background walk has finished`() {
        val reporter = reporter()

        val deadline = System.currentTimeMillis() + 5_000L
        var used: Long? = reporter.sample().diskUsed

        while (used == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)

            used = reporter.sample().diskUsed
        }

        assertEquals(2048L, used, "the walk should have added up the one file in the server")
    }

    @Test
    fun `a platform that throws costs its own field and nothing else`() {
        val sample = reporter(serverData = BrokenServerData()).sample()

        assertEquals(0, sample.playerCount, "the player list is the fallback for a broken count")
        assertEquals(0, sample.maxPlayerCount)
        assertNotNull(sample.diskTotal, "a broken server accessor must not cost the disk figures")
        assertTrue(sample.memMax > 0L)
    }

    @Test
    fun `nothing is sent while the socket is down`() {
        reporter(connected = false).report()

        assertTrue(sent.isEmpty(), "a tick with no connection is skipped rather than queued")
    }

    // --- cadence (AGENT.md 2.4.23) ---

    @Test
    fun `a requested cadence is clamped to half a second through one minute`() {
        assertEquals(500L, MetricsReporter.clampInterval(10L))
        assertEquals(500L, MetricsReporter.clampInterval(-5L))
        assertEquals(500L, MetricsReporter.clampInterval(500L))
        assertEquals(2_000L, MetricsReporter.clampInterval(2_000L))
        assertEquals(60_000L, MetricsReporter.clampInterval(999_999L))

        val reporter = reporter()

        reporter.setInterval(250L)
        assertEquals(500L, reporter.currentInterval())

        reporter.setInterval(null)
        assertEquals(MetricsReporter.INTERVAL_MILLIS, reporter.currentInterval(), "no value means back to normal")
    }

    @Test
    fun `a running reporter really reports at the new cadence`() {
        val reporter = reporter()

        try {
            reporter.start()
            reporter.setInterval(1_000L)

            val deadline = System.currentTimeMillis() + 4_500L

            while (synchronized(sent) { sent.size } < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }

            // At the old ten-second cadence not a single sample would be out yet.
            assertTrue(synchronized(sent) { sent.size } >= 3, "expected three samples in ~3 s, got ${sent.size}")
        } finally {
            reporter.stop()
        }
    }

    @Test
    fun `a cadence nobody renews lapses back to ten seconds after ninety`() {
        var now = 1_700_000_000_000L
        val reporter = reporter(connected = false).apply { clock = { now } }

        reporter.setInterval(1_000L)

        now += MetricsReporter.LEASE_MILLIS - 1
        reporter.tick()
        assertEquals(1_000L, reporter.currentInterval(), "still inside the lease")

        // A renewal pushes the lapse out again.
        reporter.setInterval(1_000L)
        now += MetricsReporter.LEASE_MILLIS - 1
        reporter.tick()
        assertEquals(1_000L, reporter.currentInterval(), "renewed, so still inside the lease")

        now += 1
        reporter.tick()
        assertEquals(MetricsReporter.INTERVAL_MILLIS, reporter.currentInterval())
    }

    @Test
    fun `a reconnect starts again at ten seconds`() {
        val reporter = reporter()

        reporter.setInterval(1_000L)
        reporter.resetInterval()

        assertEquals(MetricsReporter.INTERVAL_MILLIS, reporter.currentInterval())
    }

    @Test
    fun `a change to a player's op, whitelist or game mode is sent at once`() {
        val reporter = reporter()
        val player = PlayerData("11111111-1111-1111-1111-111111111111", "Steve", 20, op = false, whitelisted = false, gamemode = "survival")

        pluginMain.roster = listOf(player)

        // Before the first sample there is nothing to compare against, so nothing goes out.
        reporter.checkPlayerState()
        assertEquals(0, sent.size)

        reporter.report()
        reporter.checkPlayerState()
        assertEquals(1, sent.size, "an unchanged roster sends nothing extra")

        pluginMain.roster = listOf(player.copy(ping = 80))
        reporter.checkPlayerState()
        assertEquals(1, sent.size, "a ping change waits for the regular sample")

        pluginMain.roster = listOf(player.copy(op = true))
        reporter.checkPlayerState()
        assertEquals(2, sent.size)

        pluginMain.roster = listOf(player.copy(op = true, gamemode = "creative"))
        reporter.checkPlayerState()
        assertEquals(3, sent.size)
        assertEquals("creative", (sent.last() as ServerMetricsRequest).players.single().gamemode)
    }

    private fun reporter(
        serverData: ServerData = BrokenServerData(),
        connected: Boolean = true
    ) = MetricsReporter(vertx, logger, pluginMain, serverData, { synchronized(sent) { sent.add(it) } }) { connected }

    /** A server that answers nothing: every accessor here is one `safely {}` away from the sample. */
    private class BrokenServerData : ServerData {
        override fun serverName(): String = throw UnsupportedOperationException("No server in a unit test.")

        override fun motd(): String? = throw UnsupportedOperationException("No server in a unit test.")

        override fun port(): Int = throw UnsupportedOperationException("No server in a unit test.")

        override fun serverType(): ServerType = throw UnsupportedOperationException("No server in a unit test.")

        override fun serverVersion(): String = throw UnsupportedOperationException("No server in a unit test.")

        override fun playerCount(): Int = throw UnsupportedOperationException("No server in a unit test.")

        override fun maxPlayerCount(): Int = throw UnsupportedOperationException("No server in a unit test.")
    }
}
