package com.panomc.plugins.pano.core.backup.snapshot

import com.panomc.plugins.pano.core.files.BackupScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The repository on its own: what a snapshot writes, what the next one reuses, what a delete
 * frees, and what a restore refuses to do with a repository that has been tampered with.
 */
class SnapshotRepositoryTest {
    private lateinit var root: File
    private lateinit var repository: SnapshotRepository

    @BeforeTest
    fun createServer() {
        root = Files.createTempDirectory("pano-snapshots").toFile()
        repository = SnapshotRepository(File(root, "backups/repo"))

        File(root, "server.properties").writeText("motd=Pano")
        File(root, "world/region").mkdirs()
        File(root, "world/level.dat").writeText("level")
        File(root, "world/region/r.0.0.mca").writeBytes(random(3 * 1024 * 1024 + 17, 1))
        File(root, "world/region/r.0.1.mca").writeBytes(ByteArray(600_000) { (it % 7).toByte() })
        File(root, "world/empty").mkdirs()
        File(root, "plugins").mkdirs()
        File(root, "plugins/empty.txt").writeBytes(ByteArray(0))
    }

    @AfterTest
    fun removeServer() {
        root.deleteRecursively()
    }

    private fun random(size: Int, seed: Long): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun skip(path: String): Boolean = path == "backups" || path.startsWith("backups/")

    private fun snapshot(id: String): SnapshotResult {
        val scanned = BackupScope.scan(root, listOf(""), ::skip)

        return repository.locked {
            repository.create(
                root,
                SnapshotManifest(
                    id = id,
                    name = "Snapshot $id",
                    createdAt = System.currentTimeMillis(),
                    scope = BackupScope.SCOPE_ALL,
                    include = emptyList(),
                    exclude = listOf("logs/"),
                    roots = BackupScope.topLevel(root),
                    entries = emptyList()
                ),
                scanned
            )
        }
    }

    private fun chunkFiles(): List<File> =
        File(repository.directory, "data").walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `writes the agreed layout`() {
        val result = snapshot("one")

        assertEquals(
            "{\"version\":1,\"chunker\":\"fastcdc\",\"min\":262144,\"avg\":1048576,\"max\":4194304}",
            File(repository.directory, "config.json").readText()
        )
        assertTrue(File(repository.directory, "snapshots/one.json.gz").isFile)
        assertFalse(File(repository.directory, "lock").exists())

        chunkFiles().forEach { file ->
            assertEquals(file.name.substring(0, 2), file.parentFile.name)
            assertTrue(SnapshotRepository.isChunkId(file.name))
        }

        val manifest = repository.readManifest("one")

        assertEquals(listOf("plugins", "server.properties", "world"), manifest.roots)
        assertEquals(manifest.entries.map { it.path }.sorted(), manifest.entries.map { it.path })
        assertTrue(manifest.entries.none { it.path.startsWith("backups") })
        assertTrue(manifest.entries.single { it.path == "world/empty" }.directory)
        assertEquals(emptyList(), manifest.entries.single { it.path == "plugins/empty.txt" }.chunks)

        assertEquals(5L, result.fileCount)
        assertEquals(3L * 1024 * 1024 + 17 + 600_000 + 5 + 9, result.logicalBytes)
        assertEquals(result.newChunks, chunkFiles().size)
        assertEquals(chunkFiles().sumOf { it.length() }, result.storedBytes)
    }

    @Test
    fun `stores compressible chunks deflated and random ones raw`() {
        snapshot("one")

        val manifest = repository.readManifest("one")

        val repetitive = manifest.entries.single { it.path == "world/region/r.0.1.mca" }.chunks.single()
        val noise = manifest.entries.single { it.path == "world/region/r.0.0.mca" }.chunks.first()

        assertEquals(SnapshotRepository.HEADER_DEFLATE, repository.chunkFile(repetitive).readBytes()[0])
        assertEquals(SnapshotRepository.HEADER_RAW, repository.chunkFile(noise).readBytes()[0])
    }

    @Test
    fun `restores every file byte for byte with its modification time`() {
        val region = File(root, "world/region/r.0.0.mca")
        val original = region.readBytes()

        region.setLastModified(1_600_000_000_000)

        snapshot("one")

        region.writeBytes(ByteArray(10))
        File(root, "world/level.dat").delete()
        File(root, "world/empty").delete()

        repository.locked { repository.restore(root, "one") }

        assertContentEquals(original, region.readBytes())
        assertEquals(1_600_000_000_000, region.lastModified())
        assertEquals("level", File(root, "world/level.dat").readText())
        assertTrue(File(root, "world/empty").isDirectory)
        assertEquals(0L, File(root, "plugins/empty.txt").length())
    }

    @Test
    fun `reuses the parent's chunks for files that did not change`() {
        val first = snapshot("one")

        // Same size and time but different content: a snapshot that reads it would store a new
        // chunk, one that trusts the parent does not - which is the point, and the proof.
        val level = File(root, "world/level.dat")
        val time = level.lastModified()

        level.writeText("LEVEL")
        level.setLastModified(time)

        File(root, "server.properties").writeText("motd=Changed")

        val second = snapshot("two")

        assertTrue(first.newChunks > 0)
        assertEquals(1, second.newChunks)
        assertEquals(
            repository.readManifest("one").entries.single { it.path == "world/level.dat" }.chunks,
            repository.readManifest("two").entries.single { it.path == "world/level.dat" }.chunks
        )
    }

    @Test
    fun `re-reads a file whose reused chunks have gone missing`() {
        snapshot("one")

        val chunk = repository.readManifest("one").entries.single { it.path == "world/level.dat" }.chunks.single()

        repository.chunkFile(chunk).delete()

        val second = snapshot("two")

        assertEquals(1, second.newChunks)
        assertTrue(repository.chunkFile(chunk).isFile)
    }

    @Test
    fun `deleting a snapshot collects only the chunks nothing else uses`() {
        snapshot("one")

        File(root, "world/region/r.0.0.mca").writeBytes(random(2 * 1024 * 1024, 99))

        snapshot("two")

        val shared = repository.readManifest("two").chunkIds()
        val onlyFirst = repository.readManifest("one").chunkIds() - shared

        assertTrue(onlyFirst.isNotEmpty())

        // An orphan from a backup that died halfway, and a temporary file it left.
        val orphan = repository.storeChunk("orphan".toByteArray(), 6).first
        val stale = File(repository.chunkFile(orphan).parentFile, "leftover.tmp").apply { writeText("x") }

        val collected = repository.locked { repository.delete("one") }!!

        assertEquals(onlyFirst.size + 2, collected.removedChunks)
        assertFalse(repository.hasSnapshot("one"))
        assertFalse(repository.hasChunk(orphan))
        assertFalse(stale.exists())
        onlyFirst.forEach { assertFalse(repository.hasChunk(it)) }
        shared.forEach { assertTrue(repository.hasChunk(it)) }

        repository.locked { repository.restore(root, "two") }
    }

    @Test
    fun `a corrupt chunk fails the restore before anything is touched`() {
        snapshot("one")

        val chunk = repository.readManifest("one").entries.single { it.path == "world/region/r.0.0.mca" }.chunks.last()
        val file = repository.chunkFile(chunk)
        val bytes = file.readBytes()

        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        File(root, "server.properties").writeText("motd=Now")
        File(root, "world/region/r.9.9.mca").writeText("newer")

        val failure = assertFailsWith<CorruptChunkException> { repository.locked { repository.restore(root, "one") } }

        assertEquals("CORRUPT_CHUNK $chunk", failure.message)
        assertEquals("motd=Now", File(root, "server.properties").readText())
        assertTrue(File(root, "world/region/r.9.9.mca").isFile)
    }

    @Test
    fun `a missing chunk fails the restore the same way`() {
        snapshot("one")

        val chunk = repository.readManifest("one").entries.single { it.path == "server.properties" }.chunks.single()

        repository.chunkFile(chunk).delete()

        assertEquals(chunk, repository.firstMissingChunk(repository.readManifest("one")))

        val failure = assertFailsWith<CorruptChunkException> { repository.locked { repository.restore(root, "one") } }

        assertEquals("CORRUPT_CHUNK $chunk", failure.message)
    }

    @Test
    fun `replaces a world wholesale and overlays everything else`() {
        snapshot("one")

        File(root, "world/region/r.5.5.mca").writeText("generated after the backup")
        File(root, "plugins/added.jar").writeText("installed after the backup")

        repository.locked { repository.restore(root, "one") }

        assertFalse(File(root, "world/region/r.5.5.mca").exists())
        assertTrue(File(root, "plugins/added.jar").isFile)
    }

    @Test
    fun `a second operation while one holds the lock is REPO_BUSY`() {
        repository.locked {
            val failure = assertFailsWith<RepoBusyException> { repository.locked { } }

            assertEquals("REPO_BUSY", failure.message)
        }

        repository.locked { }
    }

    @Test
    fun `streams a snapshot as a zip`() {
        snapshot("one")

        val output = ByteArrayOutputStream()

        repository.writeZip("one", output)

        val entries = mutableMapOf<String, ByteArray>()

        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break

                entries[entry.name] = zip.readBytes()
            }
        }

        assertContentEquals(File(root, "world/region/r.0.0.mca").readBytes(), entries["world/region/r.0.0.mca"])
        assertEquals("motd=Pano", String(entries["server.properties"]!!))
        assertTrue("world/empty/" in entries)
    }

    @Test
    fun `refuses a repository written with other parameters`() {
        repository.initialise()

        File(repository.directory, "config.json").writeText("{\"version\":1,\"chunker\":\"fastcdc\",\"min\":1,\"avg\":2,\"max\":4}")

        assertFailsWith<IllegalStateException> { repository.initialise() }
    }
}
