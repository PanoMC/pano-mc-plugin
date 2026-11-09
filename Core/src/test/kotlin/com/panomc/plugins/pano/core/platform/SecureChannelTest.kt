package com.panomc.plugins.pano.core.platform

import com.panomc.plugins.pano.core.util.Aes256GcmUtil
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SecureChannelTest {
    private val sharedKey = ByteArray(32) { it.toByte() }
    private val pluginNonce = ByteArray(32) { 1 }
    private val platformNonce = ByteArray(32) { 2 }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Pano's end of the same connection, built the way the platform builds it. */
    private fun platformEnd(): SecureChannel {
        val (pluginToPlatform, platformToPlugin) = SecureChannel.deriveKeys(sharedKey, pluginNonce, platformNonce)
        return SecureChannel(sendKey = platformToPlugin, receiveKey = pluginToPlatform)
    }

    private fun pluginEnd() = SecureChannel.forPlugin(sharedKey, pluginNonce, platformNonce)

    @Test
    fun `derives the keys of the spec's test vector`() {
        val (pluginToPlatform, platformToPlugin) = SecureChannel.deriveKeys(sharedKey, pluginNonce, platformNonce)

        assertEquals("1b89dc9581fd9a6c5c297f7fe8de651e772cb75e96faa7a5680e983315994f30", hex(pluginToPlatform.encoded))
        assertEquals("691864b6ecdb99d504ae5cc1d0f6f9991b5b02cd6da18ae870eaff2279faa149", hex(platformToPlugin.encoded))
    }

    @Test
    fun `messages go both ways in order`() {
        val plugin = pluginEnd()
        val platform = platformEnd()

        assertEquals("{\"a\":1}", platform.open(plugin.seal("{\"a\":1}")))
        assertEquals("{\"a\":2}", platform.open(plugin.seal("{\"a\":2}")))
        assertEquals("{\"b\":1}", plugin.open(platform.seal("{\"b\":1}")))
    }

    @Test
    fun `a replayed frame is refused`() {
        val plugin = pluginEnd()
        val platform = platformEnd()

        val frame = platform.seal("{\"event\":\"EXECUTE_COMMAND\",\"command\":\"op attacker\"}")

        plugin.open(frame)

        assertFailsWith<SecurityException> { plugin.open(frame) }
    }

    @Test
    fun `a frame recorded on another connection is refused`() {
        val recorded = platformEnd().seal("{\"event\":\"POWER\"}")

        val otherConnection = SecureChannel.forPlugin(sharedKey, pluginNonce, ByteArray(32) { 3 })

        assertFailsWith<SecurityException> { otherConnection.open(recorded) }
    }

    @Test
    fun `a frame reflected back to its sender is refused`() {
        val plugin = pluginEnd()

        assertFailsWith<SecurityException> { plugin.open(plugin.seal("{\"event\":\"CHANGE_PASSWORD\"}")) }
    }

    @Test
    fun `a skipped or reordered frame is refused`() {
        val plugin = pluginEnd()
        val platform = platformEnd()

        platform.seal("first")
        val second = platform.seal("second")

        assertFailsWith<SecurityException> { plugin.open(second) }
    }

    @Test
    fun `a v1 frame is refused on a v2 connection`() {
        val v1 = Aes256GcmUtil.encrypt("{\"event\":\"POWER\"}", Aes256GcmUtil.base64ToSecretKey(Base64.getEncoder().encodeToString(sharedKey)))

        assertFailsWith<SecurityException> { pluginEnd().open(v1) }
    }

    @Test
    fun `negotiation helpers read the headers as the spec writes them`() {
        assertEquals(setOf("channel-v2", "password-check"), SecureChannel.parseCapabilities(" Channel-V2 , password-check,,"))
        assertEquals(32, SecureChannel.decodeNonce(SecureChannel.encodeNonce(SecureChannel.newNonce()))?.size)
        assertNull(SecureChannel.decodeNonce(Base64.getEncoder().encodeToString(ByteArray(16))))
        assertNull(SecureChannel.decodeNonce("not base64!"))
        assertNull(SecureChannel.decodeNonce(null))
    }
}
