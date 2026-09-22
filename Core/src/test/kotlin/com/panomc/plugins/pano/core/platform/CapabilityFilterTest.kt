package com.panomc.plugins.pano.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the rule that decides what a server announces it can do: `console.enabled = false` in
 * config.conf has to withdraw both console capabilities, or Pano keeps showing a console section
 * and dispatching `EXECUTE_COMMAND` at a server that will never answer.
 */
class CapabilityFilterTest {
    private val everything = setOf(
        Capability.CONSOLE,
        Capability.COMMANDS,
        Capability.METRICS,
        Capability.PLAYERS,
        Capability.POWER,
        Capability.PLUGINS
    )

    @Test
    fun `console enabled announces everything the platform implements`() {
        assertEquals(everything, CapabilityFilter.filter(everything, consoleEnabled = true))
    }

    @Test
    fun `console disabled withdraws console and commands together`() {
        val announced = CapabilityFilter.filter(everything, consoleEnabled = false)

        assertFalse(Capability.CONSOLE in announced)
        assertFalse(Capability.COMMANDS in announced)
        assertEquals(
            setOf(Capability.METRICS, Capability.PLAYERS, Capability.POWER, Capability.PLUGINS),
            announced
        )
    }

    @Test
    fun `console disabled leaves every non-console capability alone`() {
        val others = everything - CapabilityFilter.CONSOLE_CAPABILITIES

        assertEquals(others, CapabilityFilter.filter(others, consoleEnabled = false))
    }

    @Test
    fun `a platform that announces nothing stays empty either way`() {
        assertTrue(CapabilityFilter.filter(emptySet(), consoleEnabled = true).isEmpty())
        assertTrue(CapabilityFilter.filter(emptySet(), consoleEnabled = false).isEmpty())
    }

    @Test
    fun `filtering keeps the announced order so the wire ids stay stable`() {
        val announced = CapabilityFilter.filter(everything, consoleEnabled = false)

        assertEquals(listOf("metrics", "players", "power", "plugins"), announced.map { it.id })
    }

    @Test
    fun `the agent-lite capabilities are announced after the first six, in their agreed order`() {
        // The order and the ids are the wire contract (AGENT.md 2.4.17 C); Pano reads them as
        // strings and ignores any it does not know, so a rename here is a silently lost feature.
        val agentLite = setOf(
            Capability.FILES,
            Capability.BACKUPS,
            Capability.PLUGIN_INSTALL,
            Capability.SCHEDULES
        )

        assertEquals(listOf("files", "backups", "plugin-install", "schedules"), agentLite.map { it.id })

        assertEquals(
            listOf(
                "console", "commands", "power", "metrics", "players", "plugins", "files", "backups",
                "plugin-install", "schedules", "self-update"
            ),
            Capability.entries.map { it.id }
        )
    }

    @Test
    fun `a console switched off costs nothing but the console`() {
        // Files, backups, installs and schedules have nothing to do with reading the server log,
        // and an operator who turned the console off still expects their nightly backup.
        val announced = CapabilityFilter.filter(everything + Capability.FILES + Capability.BACKUPS, consoleEnabled = false)

        assertTrue(Capability.FILES in announced)
        assertTrue(Capability.BACKUPS in announced)
    }
}
