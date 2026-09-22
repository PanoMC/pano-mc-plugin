package com.panomc.plugins.pano.core.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The multi-path download, without the HTTP around it.
 *
 * What a browser ends up with is the archive [TransferService.writeZip] writes and the refusal
 * [TransferService.validateSelection] gives, so those two are what is pinned here: entry names
 * relative to the folder the selection was made in, empty folders kept, credentials left out of a
 * directory walk and refused outright when asked for by name.
 */
class TransferZipTest {
    private lateinit var root: File

    @BeforeTest
    fun createServer() {
        root = Files.createTempDirectory("pano-transfer-zip").toFile()

        File(root, "plugins/LuckPerms/data").mkdirs()
        File(root, "plugins/LuckPerms/config.yml").writeText("storage: h2")
        File(root, "plugins/LuckPerms/data/users.json").writeText("{}")
        File(root, "plugins/LuckPerms/empty").mkdirs()
        File(root, "plugins/Pano").mkdirs()
        File(root, "plugins/Pano/config.conf").writeText("token = secret")
        File(root, "plugins/Pano/lang.yml").writeText("hello: hi")
        File(root, "plugins/keys/server.pem").apply { parentFile.mkdirs() }.writeText("key")
        File(root, "plugins/Essentials.jar").writeText("jar")
    }

    @AfterTest
    fun removeServer() {
        root.deleteRecursively()
    }

    private fun zip(base: String, paths: List<String>, maxBytes: Long = TransferService.MAX_TRANSFER_BYTES): Map<String, String> {
        val output = ByteArrayOutputStream()

        TransferService.writeZip(root, base, paths, output, maxBytes)

        val entries = linkedMapOf<String, String>()

        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break

                assertTrue(entry.lastModifiedTime != null, "Every entry carries its modification time.")

                entries[entry.name] = input.readBytes().decodeToString()
            }
        }

        return entries
    }

    @Test
    fun `names entries relative to the base and walks directories`() {
        val entries = zip("plugins", listOf("plugins/LuckPerms", "plugins/Pano", "plugins/Essentials.jar", "plugins/keys"))

        assertEquals(
            setOf(
                "LuckPerms/config.yml",
                "LuckPerms/data/users.json",
                "LuckPerms/empty/",
                "Pano/lang.yml",
                "Essentials.jar",
                // Its only file is key material, so the folder goes in as the empty one it now is.
                "keys/"
            ),
            entries.keys
        )

        assertEquals("storage: h2", entries["LuckPerms/config.yml"])
    }

    @Test
    fun `a selection at the top of the server keeps full paths`() {
        val entries = zip("", listOf("plugins/LuckPerms/config.yml"))

        assertEquals(setOf("plugins/LuckPerms/config.yml"), entries.keys)
    }

    @Test
    fun `refuses a denied, missing or outside path before anything is sent`() {
        assertNull(TransferService.validateSelection(root, "plugins", listOf("plugins/LuckPerms", "plugins/Essentials.jar")))

        assertEquals(
            FileService.ERROR_PATH_DENIED,
            TransferService.validateSelection(root, "plugins", listOf("plugins/LuckPerms", "plugins/Pano/config.conf"))
        )
        assertEquals(
            FileService.ERROR_NOT_FOUND,
            TransferService.validateSelection(root, "plugins", listOf("plugins/Missing"))
        )
        assertEquals(
            FileService.ERROR_PATH_DENIED,
            TransferService.validateSelection(root, "plugins/LuckPerms", listOf("plugins/Essentials.jar"))
        )
        assertEquals(
            FileService.ERROR_PATH_DENIED,
            TransferService.validateSelection(root, "plugins", listOf("plugins/../../etc/passwd"))
        )
        assertEquals(
            FileService.ERROR_NOT_FOUND,
            TransferService.validateSelection(root, "nowhere", listOf("nowhere/file"))
        )
    }

    @Test
    fun `skips symbolic links instead of following them`() {
        val outside = Files.createTempDirectory("pano-transfer-outside").toFile()

        try {
            File(outside, "secret.txt").writeText("host file")

            Files.createSymbolicLink(File(root, "plugins/LuckPerms/escape").toPath(), outside.toPath())

            val entries = zip("plugins", listOf("plugins/LuckPerms"))

            assertTrue(entries.keys.none { it.startsWith("LuckPerms/escape") })
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `breaks the stream once the size ceiling is passed`() {
        assertFailsWith<IllegalStateException> {
            zip("plugins", listOf("plugins/LuckPerms"), maxBytes = 5)
        }
    }
}
