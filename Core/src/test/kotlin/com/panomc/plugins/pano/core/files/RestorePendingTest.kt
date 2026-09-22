package com.panomc.plugins.pano.core.files

import java.io.File
import java.nio.file.Files
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The restore a plugin can actually do: the marker that survives a shutdown, and what happens to
 * the server directory the next time the platform loads this plugin.
 */
class RestorePendingTest {
    private lateinit var root: File

    private val logger: Logger = Logger.getLogger("pano-test")

    @BeforeTest
    fun createServer() {
        root = Files.createTempDirectory("pano-restore").toFile()

        RestorePending.forget()
    }

    @AfterTest
    fun removeServer() {
        RestorePending.forget()

        root.deleteRecursively()
    }

    private fun writeBackup(id: String, entries: Map<String, String>) {
        val archive = File(root, "backups/$id.zip")

        archive.parentFile.mkdirs()

        ZipOutputStream(archive.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun `does nothing at all when no restore was asked for`() {
        assertNull(RestorePending.apply(root, logger))
        assertNull(RestorePending.take())
    }

    @Test
    fun `unpacks the backup over the server and clears the marker`() {
        File(root, "server.properties").writeText("motd=now")
        File(root, "world").mkdirs()
        File(root, "world/level.dat").writeText("now")

        writeBackup("backup-1", mapOf("server.properties" to "motd=then", "world/level.dat" to "then"))

        RestorePending.arm(root, RestorePending.Marker(backupId = "backup-1", taskId = "task-1", at = 1L))

        val outcome = RestorePending.apply(root, logger)

        assertNotNull(outcome)
        assertTrue(outcome.ok)
        assertEquals("backup-1", outcome.backupId)
        assertEquals("task-1", outcome.taskId)

        assertEquals("motd=then", File(root, "server.properties").readText())
        assertEquals("then", File(root, "world/level.dat").readText())

        // Once, and only once: a marker left behind would restore the same archive on every boot.
        assertFalse(RestorePending.markerFile(root).exists())
        assertNull(RestorePending.apply(root, logger))
    }

    @Test
    fun `replaces a world in the archive wholesale and overlays the rest`() {
        File(root, "world/region").mkdirs()
        File(root, "world/level.dat").writeText("now")
        File(root, "world/region/r.7.7.mca").writeText("generated after the backup")
        File(root, "world_nether/region").mkdirs()
        File(root, "world_nether/region/r.0.0.mca").writeText("not in the backup, untouched")
        File(root, "plugins").mkdirs()
        File(root, "plugins/added.jar").writeText("installed after the backup")

        writeBackup(
            "backup-1",
            mapOf(
                "world/level.dat" to "then",
                "world/region/r.0.0.mca" to "then",
                "plugins/kept.jar" to "then"
            )
        )

        RestorePending.arm(root, RestorePending.Marker(backupId = "backup-1", at = 1L))

        assertTrue(RestorePending.apply(root, logger)!!.ok)

        assertEquals("then", File(root, "world/level.dat").readText())
        assertEquals("then", File(root, "world/region/r.0.0.mca").readText())
        assertFalse(File(root, "world/region/r.7.7.mca").exists())
        assertTrue(File(root, "world_nether/region/r.0.0.mca").isFile)
        assertTrue(File(root, "plugins/added.jar").isFile)
        assertEquals("then", File(root, "plugins/kept.jar").readText())
    }

    @Test
    fun `never restores this plugin's own credentials over the live ones`() {
        File(root, "plugins/Pano").mkdirs()
        File(root, "plugins/Pano/config.conf").writeText("token = current")

        writeBackup(
            "backup-1",
            mapOf(
                "plugins/Pano/config.conf" to "token = stale",
                "plugins/LuckPerms/config.yml" to "enabled: true"
            )
        )

        RestorePending.arm(root, RestorePending.Marker(backupId = "backup-1", at = 1L))

        assertTrue(RestorePending.apply(root, logger)!!.ok)

        assertEquals("token = current", File(root, "plugins/Pano/config.conf").readText())
        assertEquals("enabled: true", File(root, "plugins/LuckPerms/config.yml").readText())
    }

    @Test
    fun `an entry that climbs out of the server writes nothing and fails the restore`() {
        writeBackup("backup-1", mapOf("../escaped.txt" to "owned"))

        RestorePending.arm(root, RestorePending.Marker(backupId = "backup-1", at = 1L))

        val outcome = RestorePending.apply(root, logger)

        assertNotNull(outcome)
        assertFalse(outcome.ok)
        assertFalse(File(root.parentFile, "escaped.txt").exists())
        assertFalse(RestorePending.markerFile(root).exists())
    }

    @Test
    fun `a backup that is gone fails the restore instead of the boot`() {
        RestorePending.arm(root, RestorePending.Marker(backupId = "missing", taskId = "task-1", at = 1L))

        val outcome = RestorePending.apply(root, logger)

        assertNotNull(outcome)
        assertFalse(outcome.ok)
        assertNotNull(outcome.error)
        assertFalse(RestorePending.markerFile(root).exists())
    }

    @Test
    fun `the outcome is handed over once, for the one report Pano gets`() {
        writeBackup("backup-1", mapOf("server.properties" to "motd=then"))

        RestorePending.arm(root, RestorePending.Marker(backupId = "backup-1", at = 1L))
        RestorePending.apply(root, logger)

        assertNotNull(RestorePending.take())
        assertNull(RestorePending.take())
    }

    @Test
    fun `an unreadable marker is thrown away rather than retried forever`() {
        RestorePending.markerFile(root).apply {
            parentFile.mkdirs()
            writeText("{ this is not json")
        }

        assertNull(RestorePending.apply(root, logger))
        assertFalse(RestorePending.markerFile(root).exists())
    }
}
