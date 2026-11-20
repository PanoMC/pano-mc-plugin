package com.panomc.plugins.pano.core.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlatformExtensionsTest {
    class OtherPluginMessage(val value: String? = null) : PlatformMessage

    class OtherPluginHandler : PlatformMessageHandler<OtherPluginMessage>() {
        override suspend fun handle(response: OtherPluginMessage) {}
    }

    class GetServerSettingsHandler : PlatformMessageHandler<OtherPluginMessage>() {
        override suspend fun handle(response: OtherPluginMessage) {}
    }

    private val seen = ArrayList<Boolean>()
    private val listener: (Boolean) -> Unit = { seen += it }

    @AfterTest
    fun reset() {
        PlatformExtensions.removeConnectionListener(listener)
        PlatformExtensions.connectionChanged(false)
        PlatformManager.BUILT_IN_HANDLER_NAMES = emptySet()
    }

    @Test
    fun `a handler of another plugin is found by its event name until it is removed`() {
        val first = OtherPluginHandler()
        val second = OtherPluginHandler()

        assertTrue(PlatformExtensions.registerMessageHandler(first))
        assertSame(first, PlatformExtensions.handler("OTHER_PLUGIN"))

        assertTrue(PlatformExtensions.registerMessageHandler(second), "the same name is replaced")
        assertFalse(PlatformExtensions.unregisterMessageHandler(first), "an old instance cannot remove the newer one")
        assertSame(second, PlatformExtensions.handler("OTHER_PLUGIN"))

        assertTrue(PlatformExtensions.unregisterMessageHandler(second))
        assertNull(PlatformExtensions.handler("OTHER_PLUGIN"))
    }

    @Test
    fun `the event of a built-in handler is never taken`() {
        PlatformManager.BUILT_IN_HANDLER_NAMES = setOf("GET_SERVER_SETTINGS")

        assertFalse(PlatformExtensions.registerMessageHandler(GetServerSettingsHandler()))
        assertNull(PlatformExtensions.handler("GET_SERVER_SETTINGS"))
    }

    @Test
    fun `a connection listener hears the current state at once and then only real changes`() {
        PlatformExtensions.connectionChanged(true)
        PlatformExtensions.addConnectionListener(listener)
        assertEquals(listOf(true), seen)

        PlatformExtensions.connectionChanged(true)
        PlatformExtensions.connectionChanged(false)
        PlatformExtensions.connectionChanged(false)
        PlatformExtensions.connectionChanged(true)
        assertEquals(listOf(true, false, true), seen)
        assertTrue(PlatformExtensions.isConnected())

        PlatformExtensions.removeConnectionListener(listener)
        PlatformExtensions.connectionChanged(false)
        assertEquals(listOf(true, false, true), seen, "a removed listener hears nothing")
    }

    @Test
    fun `a player's language is kept from the login until they leave`() {
        PlatformExtensions.rememberPlayer(" Steve ", "tr")
        assertEquals("tr", PlatformExtensions.playerLocale("steve"))

        PlatformExtensions.rememberPlayer("Steve", null)
        assertNull(PlatformExtensions.playerLocale("Steve"), "no account (or no language) forgets the old one")

        PlatformExtensions.rememberPlayer("Steve", "en-US")
        PlatformExtensions.forgetPlayer("STEVE")
        assertNull(PlatformExtensions.playerLocale("Steve"))
    }

    @Test
    fun `a failing listener does not stop the others`() {
        val other = ArrayList<Boolean>()
        val failing: (Boolean) -> Unit = { throw IllegalStateException("boom") }
        val good: (Boolean) -> Unit = { other += it }

        PlatformExtensions.addConnectionListener(failing)
        PlatformExtensions.addConnectionListener(good)
        PlatformExtensions.connectionChanged(true)

        assertEquals(listOf(false, true), other)

        PlatformExtensions.removeConnectionListener(failing)
        PlatformExtensions.removeConnectionListener(good)
    }
}
