package com.panomc.plugins.pano.core.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which paths each scope picks, and how a world directory is found and emptied. */
class BackupScopeTest {
    private lateinit var root: File

    @BeforeTest
    fun createServer() {
        root = Files.createTempDirectory("pano-scope").toFile()
    }

    @AfterTest
    fun removeServer() {
        root.deleteRecursively()
    }

    private fun skip(path: String): Boolean = path == "backups" || path.startsWith("backups/")

    private fun directory(path: String) = File(root, path).apply { mkdirs() }

    @Test
    fun `finds the level-name world, its dimensions and every other world with a level dat`() {
        File(root, "server.properties").writeText("motd=Pano\nlevel-name=survival\n")
        directory("survival")
        directory("survival_nether")
        directory("survival_the_end")
        directory("creative")
        File(root, "creative/level.dat").writeText("level")
        // Not worlds: the default name is not what this server uses, a folder without level.dat,
        // a level.dat nested one level too deep, and the backups directory.
        directory("world")
        directory("plugins/Multiverse/worlds/deep")
        File(root, "plugins/Multiverse/worlds/deep/level.dat").writeText("level")
        directory("backups/old")
        File(root, "backups/old/level.dat").writeText("level")

        assertEquals(
            listOf("creative", "survival", "survival_nether", "survival_the_end"),
            WorldDirectories.detect(root, ::skip)
        )
    }

    @Test
    fun `defaults the level name to world`() {
        directory("world")
        directory("world_nether")

        assertEquals(listOf("world", "world_nether"), WorldDirectories.detect(root, ::skip))

        File(root, "server.properties").writeText("motd=no level name here")

        assertEquals(listOf("world", "world_nether"), WorldDirectories.detect(root, ::skip))
    }

    @Test
    fun `a server without a world has nothing for WORLDS`() {
        directory("plugins")

        val failure = assertFailsWith<BackupScopeException> { BackupScope.roots(root, BackupScope.SCOPE_WORLDS, null, ::skip) }

        assertEquals("NO_WORLDS", failure.message)
    }

    @Test
    fun `CUSTOM needs an include that names something`() {
        directory("plugins/LuckPerms")
        directory("world/region")
        File(root, "world_a").mkdirs()
        File(root, "world_b").mkdirs()

        assertFailsWith<BackupScopeException> { BackupScope.roots(root, BackupScope.SCOPE_CUSTOM, emptyList(), ::skip) }
        assertFailsWith<BackupScopeException> { BackupScope.roots(root, BackupScope.SCOPE_CUSTOM, listOf("missing"), ::skip) }

        assertEquals(
            listOf("plugins/LuckPerms", "world"),
            BackupScope.roots(root, BackupScope.SCOPE_CUSTOM, listOf("world/region", "world", "plugins/LuckPerms/", ".pano", "missing"), ::skip)
        )

        assertEquals(
            "INVALID_SCOPE",
            assertFailsWith<BackupScopeException> { BackupScope.roots(root, BackupScope.SCOPE_CUSTOM, listOf("world", "../x"), ::skip) }.message
        )

        assertEquals(
            listOf("world_a", "world_b"),
            BackupScope.roots(root, BackupScope.SCOPE_CUSTOM, listOf("world_*"), ::skip)
        )
    }

    @Test
    fun `the roots of ALL are the top-level entries a backup may see`() {
        directory("world")
        directory("logs")
        directory("backups/repo")
        directory(".pano")
        File(root, "server.properties").writeText("motd=Pano")

        assertEquals(listOf("logs", "server.properties", "world"), BackupScope.topLevel(root))
    }

    @Test
    fun `mode and scope are case-insensitive, absent means FULL and ALL`() {
        assertEquals("FULL", BackupScope.mode(null))
        assertEquals("SNAPSHOT", BackupScope.mode(" snapshot "))
        assertEquals("ALL", BackupScope.scope(""))
        assertEquals("WORLDS", BackupScope.scope("worlds"))
        assertFailsWith<BackupScopeException> { BackupScope.mode("INCREMENTAL") }
    }

    @Test
    fun `scans sorted, skipping excluded trees and denied files`() {
        directory("world/region")
        File(root, "world/level.dat").writeText("level")
        File(root, "world/region/r.0.0.mca").writeText("region")
        directory("logs")
        File(root, "logs/latest.log").writeText("noise")
        directory("plugins/Pano")
        File(root, "plugins/Pano/config.conf").writeText("token")
        File(root, "server.properties").writeText("motd=Pano")

        val exclude = BackupExcludeMatcher(null)

        val paths = BackupScope.scan(root, listOf("")) { skip(it) || exclude.matches(it) }.map { it.path }

        assertEquals(
            listOf("plugins", "plugins/Pano", "server.properties", "world", "world/level.dat", "world/region", "world/region/r.0.0.mca"),
            paths
        )
    }

    @Test
    fun `emptying a world keeps the directory and anything denied`() {
        directory("world/region")
        File(root, "world/region/r.0.0.mca").writeText("region")
        File(root, "world/level.dat").writeText("level")
        File(root, "world/session.pem").writeText("key material")

        WorldDirectories.clearContents(root, "world")

        assertTrue(File(root, "world").isDirectory)
        assertFalse(File(root, "world/region").exists())
        assertFalse(File(root, "world/level.dat").exists())
        assertTrue(File(root, "world/session.pem").isFile)
    }

    @Test
    fun `the worlds of a backup are its top-level directories with a level dat`() {
        assertEquals(
            setOf("world", "creative"),
            WorldDirectories.worldDirectoriesIn(
                listOf("world/level.dat", "world/region/r.0.0.mca", "creative/level.dat", "plugins/x/level.dat", "level.dat")
            )
        )
    }
}
