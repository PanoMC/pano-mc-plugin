package com.panomc.plugins.pano.core.task

import com.panomc.plugins.pano.core.util.FileHash
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The node daemon's `PluginInstallNameTest` vectors: the filename rule is the whole sandbox for a
 * plugin install, because the name Pano forwards is the only part of the message that becomes a
 * path on disk.
 */
class PluginInstallNameTest {
    private lateinit var root: File

    @BeforeTest
    fun createRoot() {
        root = Files.createTempDirectory("pano-install-name").toFile()
    }

    @AfterTest
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun `accepts an ordinary jar name`() {
        assertTrue(PluginInstallService.isJarName("EssentialsX-2.21.2.jar"))
        assertTrue(PluginInstallService.isJarName("worldedit.JAR"))
    }

    @Test
    fun `rejects traversal and separators`() {
        assertFalse(PluginInstallService.isJarName("../evil.jar"))
        assertFalse(PluginInstallService.isJarName("plugins/evil.jar"))
        assertFalse(PluginInstallService.isJarName("plugins\\evil.jar"))
        assertFalse(PluginInstallService.isJarName("..jar"))
    }

    @Test
    fun `rejects names that are not jars`() {
        assertFalse(PluginInstallService.isJarName("start.sh"))
        assertFalse(PluginInstallService.isJarName(".jar"))
        assertFalse(PluginInstallService.isJarName(""))
        assertFalse(PluginInstallService.isJarName(null))
    }

    @Test
    fun `rejects control characters and absurd lengths`() {
        assertFalse(PluginInstallService.isJarName("evil\u0000.jar"))
        assertFalse(PluginInstallService.isJarName("a".repeat(PluginInstallService.MAX_NAME_LENGTH) + ".jar"))
    }

    @Test
    fun `verifies a file against every published algorithm`() {
        val file = File(root, "sample.jar")

        file.writeText("pano")

        assertTrue(FileHash.matches(file, "SHA-1", FileHash.of(file, "SHA-1").uppercase()))
        assertTrue(FileHash.matches(file, "SHA-512", FileHash.of(file, "SHA-512")))
        assertFalse(FileHash.matches(file, "SHA-256", "00"))
    }
}
