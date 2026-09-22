package com.panomc.plugins.pano.core.files

import com.panomc.plugins.pano.core.platform.message.response.FileRequestMessage
import io.vertx.core.json.JsonObject
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
import kotlin.test.assertTrue

/**
 * The file manager as Pano drives it: one request object in, one `FILE_RESULT` payload out.
 *
 * Deliberately exercised through [FileService.handle] rather than through the private operations,
 * because the thing worth guarding is the *answer* - a panel only ever sees `ok`, `error` and the
 * payload, and the sandbox rules are only useful if they survive the dispatch.
 */
class FileServiceTest {
    private lateinit var root: File
    private lateinit var service: FileService

    private val logger: Logger = Logger.getLogger("pano-test")

    @BeforeTest
    fun createServer() {
        root = Files.createTempDirectory("pano-file-service").toFile()

        service = FileService(TestPluginMain(root, listOf(TestPluginMain.plugin("LuckPerms.jar"))), logger)
    }

    @AfterTest
    fun removeServer() {
        root.deleteRecursively()
    }

    private fun run(event: String, message: FileRequestMessage): JsonObject = service.handle(event, message)

    @Test
    fun `writes, lists and reads a file back`() {
        val written = run(FileService.FILE_WRITE, FileRequestMessage(path = "server.properties", content = "motd=Pano"))

        assertTrue(written.getBoolean("ok"))
        assertEquals(9L, written.getLong("size"))

        val listed = run(FileService.FILE_LIST, FileRequestMessage(path = ""))

        assertTrue(listed.getBoolean("ok"))
        assertEquals(false, listed.getBoolean("truncated"))

        val entry = listed.getJsonArray("entries").getJsonObject(0)

        assertEquals("server.properties", entry.getString("name"))
        assertEquals("file", entry.getString("type"))
        assertEquals(9L, entry.getLong("size"))

        val read = run(FileService.FILE_READ, FileRequestMessage(path = "server.properties"))

        assertTrue(read.getBoolean("ok"))
        assertEquals("motd=Pano", read.getString("content"))
        assertEquals(9L, read.getLong("size"))
        assertEquals(false, read.getBoolean("truncated"))
        assertEquals(false, read.getBoolean("binary"))
    }

    @Test
    fun `reports a truncated read rather than a short file`() {
        File(root, "big.txt").writeText("x".repeat(200))

        val read = run(FileService.FILE_READ, FileRequestMessage(path = "big.txt", maxBytes = 10))

        assertEquals(10, read.getString("content").length)
        assertEquals(200L, read.getLong("size"))
        assertEquals(true, read.getBoolean("truncated"))
    }

    @Test
    fun `flags a file that is not text instead of mangling it`() {
        File(root, "world.dat").writeBytes(byteArrayOf(0xC3.toByte(), 0x28, 0x00, 0x7F))

        val read = run(FileService.FILE_READ, FileRequestMessage(path = "world.dat"))

        assertEquals(true, read.getBoolean("binary"))
        assertEquals("", read.getString("content"))
    }

    @Test
    fun `makes directories, renames and deletes`() {
        assertTrue(run(FileService.FILE_MKDIR, FileRequestMessage(path = "plugins/LuckPerms")).getBoolean("ok"))
        assertTrue(File(root, "plugins/LuckPerms").isDirectory)

        File(root, "plugins/LuckPerms/config.yml").writeText("enabled: true")

        val renamed = run(
            FileService.FILE_RENAME,
            FileRequestMessage(from = "plugins/LuckPerms/config.yml", to = "plugins/LuckPerms/config.yml.bak")
        )

        assertTrue(renamed.getBoolean("ok"))
        assertTrue(File(root, "plugins/LuckPerms/config.yml.bak").isFile)

        val deleted = run(FileService.FILE_DELETE, FileRequestMessage(paths = listOf("plugins/LuckPerms/config.yml.bak")))

        assertTrue(deleted.getBoolean("ok"))
        assertEquals(1, deleted.getInteger("removed"))
    }

    @Test
    fun `hides this server's own credentials from every operation`() {
        File(root, "plugins/Pano").mkdirs()
        File(root, "plugins/Pano/config.conf").writeText("token = secret")

        val listed = run(FileService.FILE_LIST, FileRequestMessage(path = "plugins/Pano"))

        assertTrue(listed.getJsonArray("entries").isEmpty)

        val read = run(FileService.FILE_READ, FileRequestMessage(path = "plugins/Pano/config.conf"))

        assertEquals(false, read.getBoolean("ok"))
        assertEquals(FileService.ERROR_PATH_DENIED, read.getString("error"))

        val deleted = run(FileService.FILE_DELETE, FileRequestMessage(paths = listOf("plugins")))

        assertEquals(false, deleted.getBoolean("ok"))
        assertEquals(FileService.ERROR_PATH_DENIED, deleted.getString("error"))
        assertTrue(File(root, "plugins/Pano/config.conf").isFile)
    }

    @Test
    fun `refuses to touch a jar the running server has loaded`() {
        File(root, "plugins").mkdirs()
        File(root, "plugins/LuckPerms.jar").writeText("jar")
        File(root, "plugins/Spare.jar").writeText("jar")

        val written = run(FileService.FILE_WRITE, FileRequestMessage(path = "plugins/LuckPerms.jar", content = "x"))

        assertEquals(false, written.getBoolean("ok"))
        assertEquals(LoadedJars.ERROR_IN_USE, written.getString("error"))

        val deleted = run(FileService.FILE_DELETE, FileRequestMessage(paths = listOf("plugins/LuckPerms.jar")))

        assertEquals(false, deleted.getBoolean("ok"))
        assertEquals(LoadedJars.ERROR_IN_USE, deleted.getString("error"))
        assertTrue(File(root, "plugins/LuckPerms.jar").isFile)

        val renamed = run(
            FileService.FILE_RENAME,
            FileRequestMessage(from = "plugins/LuckPerms.jar", to = "plugins/LuckPerms.jar.disabled")
        )

        assertEquals(false, renamed.getBoolean("ok"))
        assertEquals(LoadedJars.ERROR_IN_USE, renamed.getString("error"))

        // A jar that is merely sitting there is not in use, and stays changeable.
        val spare = run(FileService.FILE_DELETE, FileRequestMessage(paths = listOf("plugins/Spare.jar")))

        assertTrue(spare.getBoolean("ok"))
    }

    @Test
    fun `a delete batch holding a loaded jar removes nothing at all`() {
        File(root, "plugins").mkdirs()
        File(root, "plugins/LuckPerms.jar").writeText("jar")
        File(root, "plugins/notes.txt").writeText("notes")

        val deleted = run(
            FileService.FILE_DELETE,
            FileRequestMessage(paths = listOf("plugins/notes.txt", "plugins/LuckPerms.jar"))
        )

        assertEquals(LoadedJars.ERROR_IN_USE, deleted.getString("error"))
        assertTrue(File(root, "plugins/notes.txt").isFile)
    }

    @Test
    fun `archives a selection and unpacks it again`() {
        File(root, "world").mkdirs()
        File(root, "world/level.dat").writeText("level")

        val archived = run(FileService.FILE_ARCHIVE, FileRequestMessage(paths = listOf("world"), target = "world.zip"))

        assertTrue(archived.getBoolean("ok"))
        assertTrue(File(root, "world.zip").isFile)

        val unarchived = run(FileService.FILE_UNARCHIVE, FileRequestMessage(path = "world.zip", target = "restored"))

        assertTrue(unarchived.getBoolean("ok"))
        assertEquals("level", File(root, "restored/world/level.dat").readText())
    }

    @Test
    fun `refuses an archive that is not one`() {
        File(root, "notes.txt").writeText("not a zip")

        val unarchived = run(FileService.FILE_UNARCHIVE, FileRequestMessage(path = "notes.txt", target = "out"))

        assertEquals(false, unarchived.getBoolean("ok"))
        assertEquals(FileService.ERROR_NOT_AN_ARCHIVE, unarchived.getString("error"))
    }

    @Test
    fun `an entry that climbs out of the extraction directory writes nothing`() {
        val archive = File(root, "evil.zip")

        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../../escaped.txt"))
            out.write("owned".toByteArray())
            out.closeEntry()
        }

        val escaped = File(root.parentFile, "escaped.txt")

        val unarchived = run(FileService.FILE_UNARCHIVE, FileRequestMessage(path = "evil.zip", target = "out"))

        assertEquals(false, unarchived.getBoolean("ok"))
        assertEquals(FileService.ERROR_PATH_DENIED, unarchived.getString("error"))
        assertFalse(escaped.exists())
    }

    @Test
    fun `hashes only the jars it was asked about`() {
        File(root, "plugins").mkdirs()
        File(root, "plugins/LuckPerms.jar").writeText("hello")
        File(root, "plugins/notes.txt").writeText("hello")

        val hashed = run(
            FileService.FILE_HASHES,
            FileRequestMessage(path = "plugins", names = listOf("LuckPerms.jar", "notes.txt", "missing.jar"))
        )

        assertTrue(hashed.getBoolean("ok"))

        val files = hashed.getJsonArray("files")

        assertEquals(1, files.size())

        val entry = files.getJsonObject(0)

        assertEquals("LuckPerms.jar", entry.getString("name"))
        assertEquals(5L, entry.getLong("size"))
        // The CurseForge fingerprint of "hello", which is the vector Murmur2Test pins.
        assertEquals("2788266382", entry.getString("murmur2"))
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", entry.getString("sha1"))
    }

    @Test
    fun `answers an operation it does not know rather than throwing`() {
        val answer = run("FILE_TELEPORT", FileRequestMessage(path = ""))

        assertEquals(false, answer.getBoolean("ok"))
        assertEquals(FileService.ERROR_UNKNOWN_OPERATION, answer.getString("error"))
    }

    @Test
    fun `refuses a write bigger than a config file has any business being`() {
        val written = run(
            FileService.FILE_WRITE,
            FileRequestMessage(path = "big.txt", content = "x".repeat(FileService.MAX_WRITE_BYTES + 1))
        )

        assertEquals(false, written.getBoolean("ok"))
        assertEquals(FileService.ERROR_TOO_LARGE, written.getString("error"))
    }
}
