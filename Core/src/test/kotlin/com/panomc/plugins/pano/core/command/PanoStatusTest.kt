package com.panomc.plugins.pano.core.command

import com.panomc.plugins.pano.core.command.commands.PanoCommand
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.Protocol
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanoStatusTest {
    private val now = 1_800_000_000_000L

    private fun status(
        configured: Boolean = true,
        connected: Boolean = true,
        connecting: Boolean = false,
        lastRoundTrip: Long? = 35,
        settings: GetServerSettingsMessage? = GetServerSettingsMessage(
            authIntegration = false,
            authRequireVerified = false,
            authKickAfterRegister = false,
            banIntegration = true,
            permissionIntegration = true,
            translations = emptyMap(),
            locale = "en-US"
        )
    ) = PlatformManager.ConnectionStatus(
        configured = configured,
        connected = connected,
        connecting = connecting,
        host = "panel.example.com",
        port = 443,
        ssl = true,
        connectedAt = if (connected) now - (2 * 3_600_000L + 13 * 60_000L) else null,
        lastPongAt = if (connected) now - 8_000L else null,
        lastRoundTripMillis = if (connected) lastRoundTrip else null,
        heartbeatIntervalMillis = if (connected) 25_000L else null,
        heartbeatTimeoutMillis = if (connected) 75_000L else null,
        settings = settings
    )

    @Test
    fun `a healthy connection reads its address, uptime, latency, heartbeat and integrations`() {
        val lines = PanoCommand.statusLines(status(), latencyMillis = 42, now = now, pluginVersion = "1.0.0-alpha.64")

        assertEquals(
            listOf(
                "&6Pano status",
                "&7Platform: &fpanel.example.com:443 &7(https)",
                "&7Connection: &aConnected &7for 2h 13m",
                "&7Latency: &f42 ms",
                "&7Last heartbeat: &f8s ago &7(every 25s, timeout 1m 15s)",
                "&7Integrations: &fauth &7off&f, bans &aon&f, permissions &aon&f",
                "&7Plugin: &f1.0.0-alpha.64 &7(protocol ${Protocol.VERSION})"
            ),
            lines
        )
    }

    @Test
    fun `a ping that did not come back falls back to the last heartbeat, then says so`() {
        assertTrue("&7Latency: &f35 ms &7(last heartbeat; no answer to a new ping)" in PanoCommand.statusLines(status(), null, now, "x"))
        assertTrue("&7Latency: &cno answer to a ping" in PanoCommand.statusLines(status(lastRoundTrip = null), null, now, "x"))
    }

    @Test
    fun `retrying, disconnected and unconfigured say what they are and nothing they cannot know`() {
        val retrying = PanoCommand.statusLines(status(connected = false, connecting = true), null, now, "x")

        assertTrue("&7Connection: &eConnecting... &7(retrying until Pano answers)" in retrying)
        assertTrue(retrying.none { it.startsWith("&7Latency") || it.startsWith("&7Last heartbeat") })

        assertTrue("&7Connection: &cDisconnected" in PanoCommand.statusLines(status(connected = false), null, now, "x"))

        val unconfigured = PanoCommand.statusLines(status(configured = false, connected = false, settings = null), null, now, "x")

        assertEquals("&7Connection: &cNot connected to a Pano platform.", unconfigured[1])
        assertTrue(unconfigured.none { it.startsWith("&7Platform") })
    }

    @Test
    fun `durations keep the two largest units`() {
        assertEquals("0s", PanoCommand.formatDuration(0))
        assertEquals("1s", PanoCommand.formatDuration(1))
        assertEquals("45s", PanoCommand.formatDuration(45_000))
        assertEquals("1m 15s", PanoCommand.formatDuration(75_000))
        assertEquals("2h", PanoCommand.formatDuration(7_200_000))
        assertEquals("1d 3h", PanoCommand.formatDuration(97_200_000))
        assertEquals("0s", PanoCommand.formatDuration(-5))
    }
}
