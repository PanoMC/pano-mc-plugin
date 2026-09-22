package com.panomc.plugins.pano.core.files

import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.message.response.BackupCreateMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupDeleteMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupRestoreMessage
import com.panomc.plugins.pano.core.platform.request.TaskProgressRequest
import com.panomc.plugins.pano.core.task.TaskReporter
import com.panomc.plugins.pano.core.util.Sha256
import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.util.logging.Logger
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Backups end to end, minus the server: what ends up in the archive, what Pano is told about it,
 * and what a restore actually does to a plugin that cannot restore anything today.
 */
class BackupServiceTest {
    private lateinit var root: File
    private lateinit var pluginMain: TestPluginMain
    private lateinit var service: BackupService

    private val sent = mutableListOf<PlatformRequest>()

    private val logger: Logger = Logger.getLogger("pano-test")

    @BeforeTest
    fun createServer() {
        root = Files.createTempDirectory("pano-backups").toFile()
        pluginMain = TestPluginMain(root)

        sent.clear()

        service = BackupService(pluginMain, TaskReporter({ sent.add(it) }, logger), { sent.add(it) }, logger)

        File(root, "server.properties").writeText("motd=Pano")
        File(root, "world").mkdirs()
        File(root, "world/level.dat").writeText("level")
        File(root, "logs").mkdirs()
        File(root, "logs/latest.log").writeText("noise")
        File(root, "plugins/Pano").mkdirs()
        File(root, "plugins/Pano/config.conf").writeText("token = secret")
    }

    @AfterTest
    fun removeServer() {
        RestorePending.forget()

        root.deleteRecursively()
    }

    private fun entriesOf(archive: File): Set<String> = ZipFile(archive).use { zip ->
        zip.entries().toList().map { it.name }.toSet()
    }

    private fun backupCreated(): JsonObject? = sent
        .filterIsInstance<com.panomc.plugins.pano.core.platform.request.BackupCreatedRequest>()
        .firstOrNull()
        ?.let { JsonObject(it.encode()) }

    private fun progress(): List<TaskProgressRequest> = sent.filterIsInstance<TaskProgressRequest>()

    @Test
    fun `zips the server, leaving out the noise and this server's credentials`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1", name = "Before the update"))

        val archive = File(root, "backups/backup-1.zip")

        assertTrue(archive.isFile)

        val entries = entriesOf(archive)

        assertTrue("server.properties" in entries)
        assertTrue("world/level.dat" in entries)
        // Excluded by default, and by the rule that keeps an archive out of itself.
        assertFalse(entries.any { it.startsWith("logs") })
        assertFalse(entries.any { it.startsWith("backups") })
        // Denied everywhere, backups included: a copy of the credentials is still the credentials.
        assertFalse(entries.any { it.endsWith("config.conf") })
    }

    @Test
    fun `honours the exclusions Pano asked for`() {
        service.create(
            BackupCreateMessage(taskId = "task-1", backupId = "backup-1", exclude = listOf("world/"))
        )

        val entries = entriesOf(File(root, "backups/backup-1.zip"))

        assertFalse(entries.any { it.startsWith("world") })
        assertTrue("server.properties" in entries)
        // An explicit list replaces the defaults, so the logs come along this time.
        assertTrue(entries.any { it.startsWith("logs") })
    }

    @Test
    fun `announces the backup with the checksum of the file on disk`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1", name = "Nightly"))

        val announced = backupCreated()

        assertNotNull(announced)
        assertEquals("BACKUP_CREATED", announced.getString("event"))

        val backup = announced.getJsonObject("backup")
        val archive = File(root, "backups/backup-1.zip")

        assertEquals("backup-1", backup.getString("id"))
        assertEquals("Nightly", backup.getString("name"))
        assertEquals(archive.length(), backup.getLong("sizeBytes"))
        assertEquals(Sha256.of(archive), backup.getString("sha256"))
        assertTrue(backup.getLong("createdAt") > 0)
    }

    @Test
    fun `pauses world saving while it reads, and resumes it afterwards`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1"))

        assertEquals(listOf(false, true), pluginMain.worldSaving)
    }

    @Test
    fun `reports the task as running and then done, once`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1"))

        val frames = progress()

        assertEquals(TaskReporter.KIND_BACKUP, frames.first().kind)
        assertEquals(TaskReporter.STATUS_RUNNING, frames.first().status)
        assertEquals(1, frames.count { it.status == TaskReporter.STATUS_DONE })
        assertEquals(100, frames.last { it.status == TaskReporter.STATUS_DONE }.percent)
    }

    @Test
    fun `refuses a backup id that is not a usable name`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "../escape"))

        assertEquals(1, progress().count { it.status == TaskReporter.STATUS_FAILED })
        assertFalse(File(root, "backups").exists())
    }

    @Test
    fun `lists what it took, newest first, and deletes it again`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1", name = "One"))
        service.create(BackupCreateMessage(taskId = "task-2", backupId = "backup-2", name = "Two"))

        val listed = service.list()

        assertTrue(listed.getBoolean("ok"))

        val backups = listed.getJsonArray("backups")

        assertEquals(2, backups.size())
        assertEquals(setOf("One", "Two"), backups.map { (it as JsonObject).getString("name") }.toSet())

        assertTrue(service.delete(BackupDeleteMessage(backupId = "backup-1")).getBoolean("ok"))
        assertFalse(File(root, "backups/backup-1.zip").exists())
        assertEquals(1, service.list().getJsonArray("backups").size())
    }

    @Test
    fun `a restore is armed for the next start rather than done now`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1"))

        val answer = service.restore(
            BackupRestoreMessage(backupId = "backup-1", taskId = "task-2", requestedBy = "kahverengi")
        )

        assertTrue(answer.getBoolean("ok"))
        assertEquals(BackupService.MODE_NEXT_START, answer.getString("mode"))

        val marker = RestorePending.read(root)

        assertNotNull(marker)
        assertEquals("backup-1", marker.backupId)
        assertEquals("task-2", marker.taskId)
        assertEquals("kahverengi", marker.requestedBy)

        // Nothing was touched: the files are still the ones the running server has.
        assertEquals("motd=Pano", File(root, "server.properties").readText())
    }

    @Test
    fun `refuses to arm a restore of a backup that is not here`() {
        val answer = service.restore(BackupRestoreMessage(backupId = "missing", taskId = "task-2"))

        assertFalse(answer.getBoolean("ok"))
        assertNull(RestorePending.read(root))
    }

    @Test
    fun `resolves a backup download path and nothing else`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1"))

        assertNotNull(service.resolveVirtual("@backup/backup-1"))
        assertNull(service.resolveVirtual("@backup/../../etc/passwd"))
        assertNull(service.resolveVirtual("server.properties"))
    }

    private fun failure(): String? = progress().singleOrNull { it.status == TaskReporter.STATUS_FAILED }?.error

    @Test
    fun `a full backup of the worlds holds only the worlds`() {
        File(root, "world_nether/DIM-1").mkdirs()
        File(root, "world_nether/DIM-1/r.0.0.mca").writeText("nether")

        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1", scope = "WORLDS"))

        val entries = entriesOf(File(root, "backups/backup-1.zip"))

        assertTrue("world/level.dat" in entries)
        assertTrue("world_nether/DIM-1/r.0.0.mca" in entries)
        assertFalse("server.properties" in entries)

        val backup = backupCreated()!!.getJsonObject("backup")

        assertEquals("FULL", backup.getString("mode"))
        assertEquals("WORLDS", backup.getString("scope"))
        assertEquals(2L, backup.getLong("fileCount"))
        assertEquals(File(root, "backups/backup-1.zip").length(), backup.getLong("storedBytes"))
    }

    @Test
    fun `a custom backup holds what it includes, minus what it excludes`() {
        File(root, "plugins/LuckPerms").mkdirs()
        File(root, "plugins/LuckPerms/config.yml").writeText("enabled: true")
        File(root, "plugins/LuckPerms/cache.tmp").writeText("noise")

        service.create(
            BackupCreateMessage(
                taskId = "task-1",
                backupId = "backup-1",
                scope = "CUSTOM",
                include = listOf("plugins/LuckPerms", "server.properties", "does-not-exist"),
                exclude = listOf("*.tmp")
            )
        )

        val entries = entriesOf(File(root, "backups/backup-1.zip"))

        assertEquals(setOf("plugins/LuckPerms/", "plugins/LuckPerms/config.yml", "server.properties"), entries)
    }

    @Test
    fun `a custom backup with nothing to include is INVALID_SCOPE`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1", scope = "CUSTOM"))

        assertEquals("INVALID_SCOPE", failure())
        assertNull(backupCreated())
    }

    @Test
    fun `an unknown mode or scope is INVALID_SCOPE`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1", mode = "DIFFERENTIAL"))
        service.create(BackupCreateMessage(taskId = "task-2", backupId = "backup-2", scope = "EVERYTHING"))

        assertEquals(2, progress().count { it.status == TaskReporter.STATUS_FAILED && it.error == "INVALID_SCOPE" })
    }

    @Test
    fun `a worlds backup of a server without a world is NO_WORLDS`() {
        File(root, "world").deleteRecursively()

        service.create(BackupCreateMessage(taskId = "task-1", backupId = "backup-1", mode = "SNAPSHOT", scope = "WORLDS"))

        assertEquals("NO_WORLDS", failure())
        // Refused before the server noticed anything.
        assertEquals(emptyList(), pluginMain.worldSaving)
    }

    @Test
    fun `a snapshot goes into the repository and is announced with its costs`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "snap-1", name = "First", mode = "SNAPSHOT"))

        assertFalse(File(root, "backups/snap-1.zip").exists())
        assertTrue(File(root, "backups/repo/snapshots/snap-1.json.gz").isFile)
        assertEquals(listOf(false, true), pluginMain.worldSaving)

        val backup = backupCreated()!!.getJsonObject("backup")

        assertEquals("SNAPSHOT", backup.getString("mode"))
        assertEquals("ALL", backup.getString("scope"))
        assertTrue(backup.containsKey("sha256"))
        assertNull(backup.getValue("sha256"))
        // server.properties and world/level.dat; logs are excluded, the credentials denied.
        assertEquals(2L, backup.getLong("fileCount"))
        assertEquals("motd=Pano".length + "level".length.toLong(), backup.getLong("sizeBytes"))
        assertTrue(backup.getLong("storedBytes") > 0)

        val meta = JsonObject(File(root, "backups/snap-1.json").readText())

        assertEquals("SNAPSHOT", meta.getString("mode"))
        assertTrue(meta.containsKey("sha256"))

        val frames = progress()

        assertTrue(frames.any { it.message == "Scanning files" })
        assertEquals("Snapshot: 2 new of 2 (2 files)", frames.single { it.status == TaskReporter.STATUS_DONE }.message)
    }

    @Test
    fun `a second snapshot of an unchanged server stores nothing new`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "snap-1", mode = "SNAPSHOT"))

        sent.clear()

        service.create(BackupCreateMessage(taskId = "task-2", backupId = "snap-2", mode = "SNAPSHOT"))

        assertEquals(0L, backupCreated()!!.getJsonObject("backup").getLong("storedBytes"))
    }

    @Test
    fun `lists full backups and snapshots together, and deleting a snapshot collects its chunks`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "full-1", name = "Full"))
        service.create(BackupCreateMessage(taskId = "task-2", backupId = "snap-1", name = "Snap", mode = "SNAPSHOT"))

        val listed = service.list().getJsonArray("backups").map { it as JsonObject }

        assertEquals(setOf("FULL", "SNAPSHOT"), listed.map { it.getString("mode") }.toSet())

        val chunks = File(root, "backups/repo/data")

        assertTrue(chunks.walkTopDown().any { it.isFile })
        assertTrue(service.delete(BackupDeleteMessage(backupId = "snap-1")).getBoolean("ok"))

        assertFalse(File(root, "backups/snap-1.json").exists())
        assertFalse(File(root, "backups/repo/snapshots/snap-1.json.gz").exists())
        assertFalse(chunks.walkTopDown().any { it.isFile })
        assertEquals(listOf("full-1"), service.list().getJsonArray("backups").map { (it as JsonObject).getString("id") })
    }

    @Test
    fun `a snapshot is restored on the next start`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "snap-1", mode = "SNAPSHOT"))

        File(root, "server.properties").writeText("motd=Later")
        File(root, "world/r.1.1.mca").writeText("generated after the backup")

        assertTrue(service.restore(BackupRestoreMessage(backupId = "snap-1", taskId = "task-2")).getBoolean("ok"))

        val outcome = RestorePending.apply(root, logger)

        assertNotNull(outcome)
        assertTrue(outcome.ok, outcome.error)
        assertEquals("motd=Pano", File(root, "server.properties").readText())
        assertFalse(File(root, "world/r.1.1.mca").exists())
        assertEquals("token = secret", File(root, "plugins/Pano/config.conf").readText())
    }

    @Test
    fun `refuses to arm a restore of a snapshot with a missing chunk`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "snap-1", mode = "SNAPSHOT"))

        val chunk = File(root, "backups/repo/data").walkTopDown().first { it.isFile }

        chunk.delete()

        val answer = service.restore(BackupRestoreMessage(backupId = "snap-1", taskId = "task-2"))

        assertFalse(answer.getBoolean("ok"))
        assertEquals("CORRUPT_CHUNK ${chunk.name}", answer.getString("error"))
        assertNull(RestorePending.read(root))
    }

    @Test
    fun `downloads a snapshot as a zip built on the fly`() {
        service.create(BackupCreateMessage(taskId = "task-1", backupId = "snap-1", mode = "SNAPSHOT"))

        val source = service.resolveVirtual("@backup/snap-1")

        assertTrue(source is VirtualSource.Streamed)
        assertEquals("snap-1.zip", source.name)

        val output = java.io.ByteArrayOutputStream()

        source.write(output)

        val names = java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toSet()
        }

        // Every directory is recorded, plugins/Pano included; only the credentials inside it are not.
        assertEquals(setOf("plugins/", "plugins/Pano/", "server.properties", "world/", "world/level.dat"), names)

        service.create(BackupCreateMessage(taskId = "task-2", backupId = "full-1"))

        assertTrue(service.resolveVirtual("@backup/full-1") is VirtualSource.OfFile)
    }
}
