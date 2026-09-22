package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.files.TestPluginMain
import com.panomc.plugins.pano.core.platform.entity.PlayerData
import com.panomc.plugins.pano.core.platform.message.response.PlayerActionMessage
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

/** `PLAYER_ACTION` reaches the player: the kick and the chat line the panel's menu sends. */
class PlayerActionHandlerTest {
    private val uuid = "11111111-1111-1111-1111-111111111111"
    private val pluginMain = TestPluginMain(File(".")).apply {
        roster = listOf(PlayerData(uuid, "Steve", 20))
    }
    private val handler = PlayerActionHandler(pluginMain, Logger.getLogger("pano-test"))

    @Test
    fun `a message is shown to the player with the sender in front`() = runBlocking {
        handler.handle(PlayerActionMessage("MESSAGE", uuid, "Steve", "  hello  ", "Admin"))

        assertEquals(listOf("Steve" to "&8[&bPano&8] &7Admin&8: &fhello"), pluginMain.messaged)
    }

    @Test
    fun `an empty message sends nothing`() = runBlocking {
        handler.handle(PlayerActionMessage("MESSAGE", uuid, "Steve", "   ", "Admin"))

        assertEquals(emptyList(), pluginMain.messaged)
    }

    @Test
    fun `a kick carries its reason, or vanilla's wording without one`() = runBlocking {
        handler.handle(PlayerActionMessage("KICK", uuid, "Steve", "bye", "Admin"))
        handler.handle(PlayerActionMessage("kick", uuid, "Steve", null, "Admin"))

        assertEquals(
            listOf("Steve" to "bye", "Steve" to PlayerActionHandler.DEFAULT_KICK_REASON),
            pluginMain.kicked
        )
    }
}
