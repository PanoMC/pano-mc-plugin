package com.panomc.plugins.pano.core.files

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipToolTest {
    private lateinit var root: File
    private lateinit var outside: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("pano-ziptool").toFile()
        outside = Files.createTempDirectory("pano-ziptool-outside").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    private fun entries(target: File): List<String> = ZipFile(target).use { zip -> zip.entries().toList().map { it.name } }

    @Test
    fun `archives skip symbolic links instead of following them out of the server`() {
        File(outside, "secret.txt").writeText("host file")
        File(root, "plugins/dynmap").mkdirs()
        File(root, "plugins/dynmap/config.txt").writeText("ok")

        try {
            Files.createSymbolicLink(File(root, "plugins/dynmap/web").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "plugins/dynmap/link.txt").toPath(), File(outside, "secret.txt").toPath())
        } catch (_: Exception) {
            // No symlink support here (e.g. Windows without the privilege): nothing to check.
            return
        }

        val target = File(root, "out/archive.zip")
        val files = mutableListOf<String>()

        ZipTool.archive(root, listOf("plugins"), target, onFile = { files.add(it) })

        val names = entries(target)

        assertTrue(names.none { it.contains("dynmap/web") || it.contains("link.txt") }, names.toString())
        assertEquals(listOf("plugins/dynmap/config.txt"), files)
    }
}
