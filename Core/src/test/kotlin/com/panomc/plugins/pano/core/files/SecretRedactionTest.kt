package com.panomc.plugins.pano.core.files

import com.panomc.plugins.pano.core.platform.message.response.FileRequestMessage
import java.io.File
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactionTest {
    private lateinit var root: File
    private lateinit var service: FileService

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("pano-redaction").toFile()
        service = FileService(TestPluginMain(root), Logger.getLogger("pano-test"))
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val paperGlobal = """
        proxies:
          velocity:
            enabled: true
            online-mode: true
            secret: 'network-secret'
    """.trimIndent()

    @Test
    fun `the editor never sees the proxy secret or the rcon password`() {
        File(root, "config").mkdirs()
        File(root, "config/paper-global.yml").writeText(paperGlobal)
        File(root, "server.properties").writeText("motd=Hi\r\nrcon.password=hunter2\r\nenable-rcon=true\r\n")
        File(root, "config/FabricProxy-Lite.toml").writeText("hackOnlineMode = true\nsecret = \"network-secret\"\n")

        listOf("config/paper-global.yml", "server.properties", "config/FabricProxy-Lite.toml").forEach { path ->
            val content = service.handle(FileService.FILE_READ, FileRequestMessage(path = path)).getString("content")

            assertFalse(content.contains("network-secret") || content.contains("hunter2"), content)
            assertTrue(content.contains(SecretRedaction.PLACEHOLDER), content)
        }
    }

    @Test
    fun `saving a file with the secret still hidden keeps the real secret`() {
        File(root, "config").mkdirs()
        val file = File(root, "config/paper-global.yml").apply { writeText(paperGlobal) }

        val shown = service.handle(FileService.FILE_READ, FileRequestMessage(path = "config/paper-global.yml")).getString("content")
        val edited = shown.replace("online-mode: true", "online-mode: false")

        assertTrue(service.handle(FileService.FILE_WRITE, FileRequestMessage(path = "config/paper-global.yml", content = edited)).getBoolean("ok"))

        assertEquals(paperGlobal.replace("online-mode: true", "online-mode: false"), file.readText())
    }

    @Test
    fun `typing a new secret replaces it`() {
        val file = File(root, "server.properties").apply { writeText("rcon.password=old\n") }

        service.handle(FileService.FILE_WRITE, FileRequestMessage(path = "server.properties", content = "rcon.password=new\n"))

        assertEquals("rcon.password=new\n", file.readText())
    }

    @Test
    fun `other files are shown as they are`() {
        File(root, "config.yml").writeText("secret: visible")

        assertEquals("secret: visible", SecretRedaction.redact("config.yml", "secret: visible"))
        assertEquals("secret: visible", service.handle(FileService.FILE_READ, FileRequestMessage(path = "config.yml")).getString("content"))
    }
}
