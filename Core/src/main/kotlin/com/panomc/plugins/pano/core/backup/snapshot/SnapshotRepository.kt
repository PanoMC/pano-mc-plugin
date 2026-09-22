package com.panomc.plugins.pano.core.backup.snapshot

import com.panomc.plugins.pano.core.files.PathSafety
import com.panomc.plugins.pano.core.files.ServerFileDenylist
import com.panomc.plugins.pano.core.files.WorldDirectories
import io.vertx.core.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Another create, delete or restore holds the repository; the wire error is `REPO_BUSY`. */
class RepoBusyException : IllegalStateException(SnapshotRepository.ERROR_REPO_BUSY)

/**
 * A chunk a snapshot needs is missing or does not hash to its name; the wire error is
 * `CORRUPT_CHUNK <sha>`.
 */
class CorruptChunkException(val chunkId: String) : IllegalStateException("${SnapshotRepository.ERROR_CORRUPT_CHUNK} $chunkId")

/** One path found while scanning, before it has been read. */
data class ScannedPath(
    val path: String,
    val directory: Boolean,
    val size: Long,
    val mtime: Long
)

/** What a snapshot cost, for `BACKUP_CREATED` and the task's last line. */
data class SnapshotResult(
    val fileCount: Long,
    /** Logical bytes of every file in the snapshot. */
    val logicalBytes: Long,
    /** Chunk files this snapshot had to write. */
    val newChunks: Int,
    /** Distinct chunks the snapshot references. */
    val totalChunks: Int,
    /** Bytes those new chunk files take on disk, header byte included. */
    val storedBytes: Long
)

/** What a garbage collection removed. */
data class GcResult(val removedChunks: Int, val removedBytes: Long)

/**
 * An incremental, deduplicating backup repository in plain files, readable and writable by both
 * the node daemon and this plugin.
 *
 * A snapshot is a manifest - every path it covers and the chunks each file is made of - and the
 * chunks themselves are stored once, by the SHA-256 of their content, however many snapshots use
 * them. The nightly backup of a world where a few hundred region files changed therefore writes
 * those few hundred files' changed chunks and a manifest, and is still a complete, independently
 * restorable copy of the server.
 *
 * The layout, which the node daemon writes byte for byte the same (see [FastCdc] for how files are
 * cut):
 *
 * - `config.json` - `{"version":1,"chunker":"fastcdc","min":262144,"avg":1048576,"max":4194304}`.
 * - `data/<hh>/<sha256>` - one chunk per file, named by the lowercase hex SHA-256 of its
 *   *uncompressed* bytes, `<hh>` its first two characters. The file is one header byte and the
 *   payload: `0x00` for the raw bytes, `0x01` for a zlib stream (java.util.zip.Deflater, level 6,
 *   not nowrap). Deflate is only kept when it saves at least 5 %, i.e. when
 *   `compressed * 100 <= raw * 95`; region files are compressed already and would otherwise be
 *   paid for twice on every read.
 * - `snapshots/<backupId>.json.gz` - the manifest ([SnapshotManifest]).
 * - `lock` - held while anything writes or deletes.
 *
 * Crash safety comes from ordering alone: every file is written as `.tmp` and moved into place,
 * chunks before the manifest that names them. A backup that dies halfway leaves chunks nothing
 * references, which the next [gc] removes, and never a manifest pointing at chunks that are not
 * there.
 */
class SnapshotRepository(val directory: File) {
    private val dataDirectory = File(directory, DATA_DIRECTORY)
    private val snapshotsDirectory = File(directory, SNAPSHOTS_DIRECTORY)

    /** Whether a repository has been initialised here at all. */
    fun exists(): Boolean = File(directory, CONFIG_FILE).isFile

    /**
     * Creates the repository if there is none, and refuses one written with different parameters.
     *
     * A repository chunked with another size or chunker would still restore, but a snapshot taken
     * into it by this code would share nothing with the ones before it - so a mismatch is an error
     * rather than something to quietly paper over.
     */
    fun initialise() {
        val config = File(directory, CONFIG_FILE)

        if (config.isFile) {
            val existing = try {
                JsonObject(config.readText())
            } catch (_: Exception) {
                null
            }

            check(
                existing != null &&
                    existing.getValue("version")?.toString() == "1" &&
                    existing.getString("chunker") == "fastcdc" &&
                    (existing.getValue("min") as? Number)?.toLong() == FastCdc.MIN_SIZE.toLong() &&
                    (existing.getValue("avg") as? Number)?.toLong() == FastCdc.AVG_SIZE.toLong() &&
                    (existing.getValue("max") as? Number)?.toLong() == FastCdc.MAX_SIZE.toLong()
            ) { "The snapshot repository at ${directory.path} has an unsupported config.json." }

            return
        }

        dataDirectory.mkdirs()
        snapshotsDirectory.mkdirs()

        writeAtomically(config, CONFIG_JSON.toByteArray(Charsets.UTF_8))
    }

    /**
     * Runs [block] holding the repository's lock, or throws [RepoBusyException] at once.
     *
     * An OS file lock on `lock` rather than a file that exists-means-locked: the operating system
     * drops it the moment the process dies, so a server that crashed mid-backup does not leave a
     * repository nobody can back up into for hours. The file itself is removed on release; one
     * left behind by a crash is simply locked again by whoever comes next. An in-process guard
     * sits in front of it, because a FileChannel lock does not reliably exclude another thread of
     * the same JVM that opened the file through a second channel.
     */
    fun <T> locked(block: () -> T): T {
        directory.mkdirs()

        val key = directory.absoluteFile.normalize().path

        if (!HELD.add(key)) {
            throw RepoBusyException()
        }

        try {
            return lockedOnDisk(block)
        } finally {
            HELD.remove(key)
        }
    }

    private fun <T> lockedOnDisk(block: () -> T): T {
        val lockFile = File(directory, LOCK_FILE)
        val channel = RandomAccessFile(lockFile, "rw").channel

        val lock: FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            // Held by this same JVM, which for a lock is exactly the same as held by another one.
            null
        } catch (exception: IOException) {
            channel.close()

            throw exception
        }

        if (lock == null) {
            channel.close()

            throw RepoBusyException()
        }

        try {
            return block()
        } finally {
            lockFile.delete()

            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    /** Where the manifest of [backupId] lives. */
    fun manifestFile(backupId: String): File =
        PathSafety.resolveUnder(snapshotsDirectory, "$backupId$MANIFEST_SUFFIX")

    fun hasSnapshot(backupId: String): Boolean = manifestFile(backupId).isFile

    /** Where the chunk [chunkId] lives. */
    fun chunkFile(chunkId: String): File {
        require(isChunkId(chunkId)) { "\"$chunkId\" is not a chunk id." }

        return File(File(dataDirectory, chunkId.substring(0, 2)), chunkId)
    }

    fun hasChunk(chunkId: String): Boolean = isChunkId(chunkId) && chunkFile(chunkId).isFile

    /**
     * Stores the first [length] bytes of [bytes] as a chunk, unless it is already there.
     *
     * Returns the chunk's id and how many bytes this call wrote to disk - 0 when the chunk
     * existed, which is the whole point of the repository.
     */
    fun storeChunk(bytes: ByteArray, length: Int): Pair<String, Long> {
        val chunkId = sha256(bytes, length)
        val file = chunkFile(chunkId)

        if (file.isFile) {
            return chunkId to 0L
        }

        val encoded = encodeChunk(bytes, length)

        file.parentFile.mkdirs()

        writeAtomically(file, encoded)

        return chunkId to encoded.size.toLong()
    }

    /**
     * The uncompressed bytes of [chunkId], checked against its name.
     *
     * Throws [CorruptChunkException] for a chunk that is missing, unreadable, or not what its name
     * says it is - the three ways a restore can go wrong that are worth refusing before a single
     * file has been touched.
     */
    fun readChunk(chunkId: String): ByteArray {
        val file = if (isChunkId(chunkId)) chunkFile(chunkId) else throw CorruptChunkException(chunkId)

        val stored = try {
            Files.readAllBytes(file.toPath())
        } catch (_: IOException) {
            throw CorruptChunkException(chunkId)
        }

        if (stored.isEmpty()) {
            throw CorruptChunkException(chunkId)
        }

        val content = when (stored[0]) {
            HEADER_RAW -> stored.copyOfRange(1, stored.size)
            HEADER_DEFLATE -> inflate(stored, chunkId)
            else -> throw CorruptChunkException(chunkId)
        }

        if (sha256(content, content.size) != chunkId) {
            throw CorruptChunkException(chunkId)
        }

        return content
    }

    /** Every snapshot id in the repository, in no particular order. */
    fun snapshotIds(): List<String> = snapshotsDirectory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(MANIFEST_SUFFIX) }
        ?.map { it.name.removeSuffix(MANIFEST_SUFFIX) }
        .orEmpty()

    fun readManifest(backupId: String): SnapshotManifest = SnapshotManifest.read(manifestFile(backupId))

    /**
     * The most recently written snapshot, which a new one reuses unchanged files from.
     *
     * Chosen by the manifest file's modification time rather than by parsing every manifest for
     * its `createdAt`: a manifest is written once and never touched, so the two agree, and a
     * repository with hundreds of snapshots should not have to decompress all of them to start a
     * backup. One that cannot be read is passed over for the next.
     */
    fun newestManifest(): SnapshotManifest? = snapshotsDirectory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(MANIFEST_SUFFIX) }
        ?.sortedByDescending { it.lastModified() }
        ?.asSequence()
        ?.mapNotNull { file ->
            try {
                SnapshotManifest.read(file)
            } catch (_: Exception) {
                null
            }
        }
        ?.firstOrNull()

    /**
     * Takes a snapshot of [paths] (relative to [root], sorted, as a scan produced them).
     *
     * Call it holding [locked]. A file whose path, size and modification time match the newest
     * existing snapshot's entry is not read at all - its chunk list is reused, as long as every
     * one of those chunks is still in the repository - which is what makes the second backup of
     * an idle server take seconds. Everything else is read, cut with [FastCdc] and stored chunk by
     * chunk. A file that disappears between the scan and the read is left out; any other read
     * error fails the snapshot, which then leaves nothing but orphan chunks behind.
     *
     * [progress] is told the bytes processed so far (reused files count at their size) and the
     * number of files done; [beforeManifest] runs once every chunk is stored and just before the
     * manifest is written.
     */
    fun create(
        root: File,
        template: SnapshotManifest,
        paths: List<ScannedPath>,
        progress: (processedBytes: Long, totalBytes: Long, filesDone: Int, filesTotal: Int) -> Unit = { _, _, _, _ -> },
        beforeManifest: () -> Unit = {}
    ): SnapshotResult {
        initialise()

        require(!hasSnapshot(template.id)) { "A snapshot called ${template.id} exists already." }

        val parent = newestManifest()
            ?.entries
            ?.filter { !it.directory }
            ?.associateBy { it.path }
            .orEmpty()

        val files = paths.filter { !it.directory }
        val totalBytes = files.sumOf { it.size }

        val splitter = FastCdc.Splitter()
        val present = HashSet<String>()
        val referenced = HashSet<String>()
        val entries = ArrayList<ManifestEntry>(paths.size)

        var processed = 0L
        var filesDone = 0
        var newChunks = 0
        var storedBytes = 0L
        var logicalBytes = 0L
        var fileCount = 0L

        fun chunkPresent(chunkId: String): Boolean {
            if (chunkId in present) {
                return true
            }

            return hasChunk(chunkId).also { if (it) present.add(chunkId) }
        }

        paths.forEach { scanned ->
            if (scanned.directory) {
                entries.add(ManifestEntry(scanned.path, true, 0, scanned.mtime))

                return@forEach
            }

            val previous = parent[scanned.path]

            val entry = if (
                previous != null &&
                previous.size == scanned.size &&
                previous.mtime == scanned.mtime &&
                previous.chunks.all { chunkPresent(it) }
            ) {
                ManifestEntry(scanned.path, false, previous.size, previous.mtime, previous.chunks)
            } else {
                readFile(File(root, scanned.path), splitter) { chunkId, written ->
                    if (written > 0) {
                        newChunks++
                        storedBytes += written
                    }

                    present.add(chunkId)
                }?.let { (chunks, size) -> ManifestEntry(scanned.path, false, size, scanned.mtime, chunks) }
            }

            processed += scanned.size
            filesDone++

            if (entry != null) {
                entries.add(entry)
                referenced.addAll(entry.chunks)

                logicalBytes += entry.size
                fileCount++
            }

            progress(processed, totalBytes, filesDone, files.size)
        }

        beforeManifest()

        val manifest = template.copy(entries = entries)

        val target = manifestFile(template.id)
        val temporary = File(target.parentFile, "${target.name}$TEMPORARY_SUFFIX")

        target.parentFile.mkdirs()

        SnapshotManifest.write(manifest, temporary)
        move(temporary, target)

        return SnapshotResult(fileCount, logicalBytes, newChunks, referenced.size, storedBytes)
    }

    /**
     * Cuts [file] into chunks and stores them, returning the chunk list and the bytes read, or
     * null when the file vanished before it could be opened - a log rotated or a temporary file
     * deleted between the scan and the read is not a reason to fail a backup.
     */
    private fun readFile(
        file: File,
        splitter: FastCdc.Splitter,
        onStored: (chunkId: String, written: Long) -> Unit
    ): Pair<List<String>, Long>? {
        val chunks = ArrayList<String>()
        var size = 0L

        val input = try {
            Files.newInputStream(file.toPath())
        } catch (_: NoSuchFileException) {
            return null
        }

        input.use { stream ->
            splitter.split(stream) { bytes, length ->
                val (chunkId, written) = storeChunk(bytes, length)

                onStored(chunkId, written)

                chunks.add(chunkId)
                size += length
            }
        }

        return chunks to size
    }

    /**
     * Removes the snapshot [backupId] and collects the chunks only it used. Call it holding
     * [locked]. Returns null when there was no such snapshot.
     */
    fun delete(backupId: String): GcResult? {
        val manifest = manifestFile(backupId)

        if (!manifest.isFile) {
            return null
        }

        Files.deleteIfExists(manifest.toPath())

        return gc()
    }

    /**
     * Deletes every chunk no remaining snapshot references, and every leftover `.tmp`.
     *
     * Call it holding [locked]. Refuses to delete anything at all when a manifest cannot be read:
     * a chunk only an unreadable manifest refers to is indistinguishable from an orphan, and
     * deleting it would turn a manifest that might be repairable into one that certainly is not.
     */
    fun gc(): GcResult {
        val marked = HashSet<String>()

        snapshotsDirectory.listFiles()?.forEach { file ->
            if (file.name.endsWith(TEMPORARY_SUFFIX)) {
                file.delete()

                return@forEach
            }

            if (!file.isFile || !file.name.endsWith(MANIFEST_SUFFIX)) {
                return@forEach
            }

            val manifest = try {
                SnapshotManifest.read(file)
            } catch (exception: Exception) {
                throw IllegalStateException("The snapshot ${file.name} could not be read, so nothing was collected.", exception)
            }

            manifest.entries.forEach { marked.addAll(it.chunks) }
        }

        var removed = 0
        var removedBytes = 0L

        dataDirectory.listFiles()?.filter { it.isDirectory }?.forEach { bucket ->
            bucket.listFiles()?.forEach { file ->
                if (file.name !in marked || file.name.endsWith(TEMPORARY_SUFFIX)) {
                    val length = file.length()

                    if (file.delete()) {
                        removed++
                        removedBytes += length
                    }
                }
            }

            // Only succeeds when the bucket is now empty, which is exactly when it should go.
            bucket.delete()
        }

        return GcResult(removed, removedBytes)
    }

    /**
     * Reads and checks every chunk [manifest] references, throwing [CorruptChunkException] for
     * the first that is missing or wrong.
     */
    fun verify(manifest: SnapshotManifest) {
        manifest.chunkIds().forEach { readChunk(it) }
    }

    /** Whether every chunk [manifest] references is at least present, without reading any. */
    fun firstMissingChunk(manifest: SnapshotManifest): String? = manifest.chunkIds().firstOrNull { !hasChunk(it) }

    /**
     * Puts the snapshot [backupId] back into [root].
     *
     * Call it holding [locked], with nothing running out of [root]. In this order, so that a
     * restore that cannot finish never starts:
     *
     * 1. Every chunk the snapshot needs is read and checked; a missing or corrupt one fails the
     *    restore with `CORRUPT_CHUNK <sha>` before anything is touched.
     * 2. Every world directory in the snapshot (a top-level directory with a `level.dat` in it) is
     *    emptied, so region files the world grew after the backup do not survive into a mix of
     *    two points in time. Denied paths and whatever [keep] names are left alone.
     * 3. Every directory and file is written, other paths overlaying what is there, and the
     *    recorded modification times are put back - directories last, deepest first, because
     *    writing a file into one changes its time.
     */
    fun restore(root: File, backupId: String, keep: (String) -> Boolean = { false }) {
        val manifestFile = manifestFile(backupId)

        require(manifestFile.isFile) { "That backup is not on this server any more." }

        val manifest = SnapshotManifest.read(manifestFile)

        verify(manifest)

        val restorable = manifest.entries.filter { entry ->
            val normalised = ServerFileDenylist.normalise(entry.path)

            normalised.isNotEmpty() && !ServerFileDenylist.isDenied(normalised) && !keep(normalised)
        }

        WorldDirectories.worldDirectoriesIn(manifest.entries.filter { !it.directory }.map { it.path }).forEach { world ->
            WorldDirectories.clearContents(root, world) { relative -> keep(relative) }
        }

        val directories = ArrayList<Pair<File, Long>>()

        restorable.forEach { entry ->
            val target = PathSafety.resolveRelative(root, entry.path)

            if (entry.directory) {
                if (target.isFile) {
                    target.delete()
                }

                target.mkdirs()

                directories.add(target to entry.mtime)

                return@forEach
            }

            if (Files.isSymbolicLink(target.toPath())) {
                Files.delete(target.toPath())
            } else if (target.isDirectory) {
                WorldDirectories.clearContents(root, ServerFileDenylist.normalise(entry.path)) { relative -> keep(relative) }

                target.delete()
            }

            target.parentFile?.mkdirs()

            target.outputStream().buffered().use { output ->
                entry.chunks.forEach { chunkId -> output.write(readChunk(chunkId)) }
            }

            setModified(target, entry.mtime)
        }

        directories.sortedByDescending { it.first.path.length }.forEach { (target, mtime) -> setModified(target, mtime) }
    }

    /**
     * Writes the snapshot [backupId] into [output] as a ZIP, rebuilding every file from its chunks
     * as it goes, and finishes (but does not close) the stream.
     *
     * The same shape as the file manager's multi-path download: directories as `name/` entries,
     * every entry stamped with its recorded modification time. Every chunk is checked on the way
     * through, and more than [maxBytes] of file content is refused mid-stream rather than
     * silently cut short.
     */
    fun writeZip(backupId: String, output: OutputStream, maxBytes: Long = Long.MAX_VALUE) {
        val manifest = readManifest(backupId)

        val zip = ZipOutputStream(output)
        var total = 0L

        manifest.entries.forEach { entry ->
            val name = ServerFileDenylist.normalise(entry.path)

            if (name.isEmpty() || ServerFileDenylist.isDenied(name)) {
                return@forEach
            }

            val zipEntry = ZipEntry(if (entry.directory) "$name/" else name)

            zipEntry.lastModifiedTime = FileTime.fromMillis(entry.mtime)

            zip.putNextEntry(zipEntry)

            if (!entry.directory) {
                total += entry.size

                if (total > maxBytes) {
                    throw IllegalStateException(ERROR_TOO_LARGE)
                }

                entry.chunks.forEach { chunkId -> zip.write(readChunk(chunkId)) }
            }

            zip.closeEntry()
        }

        zip.finish()
    }

    private fun inflate(stored: ByteArray, chunkId: String): ByteArray {
        val inflater = Inflater(false)
        val output = ByteArrayOutputStream(stored.size * 2)
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            inflater.setInput(stored, 1, stored.size - 1)

            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)

                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw CorruptChunkException(chunkId)
                }

                output.write(buffer, 0, count)

                // No chunk is ever larger than the chunker's maximum; a stream that says otherwise
                // is not one of ours.
                if (output.size() > FastCdc.MAX_SIZE) {
                    throw CorruptChunkException(chunkId)
                }
            }
        } catch (_: DataFormatException) {
            throw CorruptChunkException(chunkId)
        } finally {
            inflater.end()
        }

        return output.toByteArray()
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}$TEMPORARY_SUFFIX")

        target.parentFile?.mkdirs()

        temporary.outputStream().use { it.write(bytes) }

        move(temporary, target)
    }

    private fun setModified(file: File, mtime: Long) {
        try {
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(mtime))
        } catch (_: IOException) {
            // A time that could not be set is cosmetic; the content is what the restore promised.
        }
    }

    companion object {
        const val CONFIG_FILE = "config.json"
        const val DATA_DIRECTORY = "data"
        const val SNAPSHOTS_DIRECTORY = "snapshots"
        const val LOCK_FILE = "lock"
        const val MANIFEST_SUFFIX = ".json.gz"
        const val TEMPORARY_SUFFIX = ".tmp"

        /** config.json exactly as a new repository gets it; an existing one is parsed and checked field by field. */
        const val CONFIG_JSON =
            "{\"version\":1,\"chunker\":\"fastcdc\",\"min\":${FastCdc.MIN_SIZE},\"avg\":${FastCdc.AVG_SIZE},\"max\":${FastCdc.MAX_SIZE}}"

        const val ERROR_REPO_BUSY = "REPO_BUSY"
        const val ERROR_CORRUPT_CHUNK = "CORRUPT_CHUNK"
        const val ERROR_TOO_LARGE = "TOO_LARGE"

        const val HEADER_RAW: Byte = 0x00
        const val HEADER_DEFLATE: Byte = 0x01

        /** zlib level for chunk payloads. */
        const val DEFLATE_LEVEL = 6

        private const val BUFFER_SIZE = 64 * 1024

        /** Repositories some thread of this JVM is holding right now. */
        private val HELD: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val CHUNK_ID = Regex("^[0-9a-f]{64}$")

        fun isChunkId(value: String): Boolean = CHUNK_ID.matches(value)

        /** Lowercase hex SHA-256 of the first [length] bytes of [bytes]. */
        fun sha256(bytes: ByteArray, length: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")

            digest.update(bytes, 0, length)

            return hex(digest.digest())
        }

        private val HEX = "0123456789abcdef".toCharArray()

        fun hex(bytes: ByteArray): String {
            val chars = CharArray(bytes.size * 2)

            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xFF

                chars[index * 2] = HEX[value ushr 4]
                chars[index * 2 + 1] = HEX[value and 0x0F]
            }

            return String(chars)
        }

        /**
         * The on-disk form of a chunk: header byte, then either the raw bytes or their zlib
         * stream, whichever the 5 % rule picks.
         */
        fun encodeChunk(bytes: ByteArray, length: Int): ByteArray {
            val deflater = Deflater(DEFLATE_LEVEL, false)
            val compressed = ByteArrayOutputStream(length / 2 + 64)
            val buffer = ByteArray(BUFFER_SIZE)

            try {
                deflater.setInput(bytes, 0, length)
                deflater.finish()

                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)

                    compressed.write(buffer, 0, count)
                }
            } finally {
                deflater.end()
            }

            return if (compressed.size().toLong() * 100 <= length.toLong() * 95) {
                ByteArray(compressed.size() + 1).also { out ->
                    out[0] = HEADER_DEFLATE
                    System.arraycopy(compressed.toByteArray(), 0, out, 1, compressed.size())
                }
            } else {
                ByteArray(length + 1).also { out ->
                    out[0] = HEADER_RAW
                    System.arraycopy(bytes, 0, out, 1, length)
                }
            }
        }
    }
}
