package com.panomc.plugins.pano.core.backup.snapshot

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One path in a snapshot: a file with the chunks it is made of, or a directory.
 *
 * Serialised with the short keys the repository format uses (`p`, `t`, `s`, `m`, `c`) because a
 * server holds tens of thousands of files and every snapshot lists every one of them; a directory
 * carries only `p`, `t` and `m`.
 */
data class ManifestEntry(
    /** Relative to the server directory, `/`-separated, no leading slash. */
    val path: String,
    val directory: Boolean,
    /** Bytes in the file as it was read; always 0 for a directory. */
    val size: Long,
    /** Modification time in epoch milliseconds, restored along with the content. */
    val mtime: Long,
    /** SHA-256 ids of the chunks that make the file up, in order; empty for a directory or an empty file. */
    val chunks: List<String> = emptyList()
) {
    companion object {
        const val TYPE_FILE = "f"
        const val TYPE_DIRECTORY = "d"
    }
}

/**
 * Everything a snapshot is: which paths it covers and what each file was made of.
 *
 * The manifest is the snapshot - there is no other per-snapshot state in the repository - so it
 * is written last, after every chunk it names is safely on disk, and a snapshot either has a
 * complete manifest or does not exist. Stored as gzip-compressed JSON in the field order below,
 * which is the order both sides of the format write it in.
 */
data class SnapshotManifest(
    val version: Int = VERSION,
    val id: String,
    val name: String,
    val createdAt: Long,
    val scope: String,
    val include: List<String>,
    val exclude: List<String>,
    /** The top-level paths this snapshot covers, sorted. */
    val roots: List<String>,
    /** Every file and directory, sorted by path. */
    val entries: List<ManifestEntry>
) {
    /** Every chunk this snapshot needs, each once, in first-use order. */
    fun chunkIds(): Set<String> = LinkedHashSet<String>().also { ids -> entries.forEach { ids.addAll(it.chunks) } }

    companion object {
        const val VERSION = 1

        /**
         * Writes [manifest] to [output] as gzip JSON, finishing (but not closing) [output].
         *
         * Streamed rather than built as a tree first: a manifest for a large server is tens of
         * megabytes of JSON, and it never needs to exist anywhere but in the gzip stream.
         */
        fun write(manifest: SnapshotManifest, output: OutputStream) {
            val gzip = GZIPOutputStream(output, BUFFER_SIZE)
            val writer = JsonWriter(OutputStreamWriter(gzip, StandardCharsets.UTF_8))

            writer.beginObject()
            writer.name("version").value(manifest.version.toLong())
            writer.name("id").value(manifest.id)
            writer.name("name").value(manifest.name)
            writer.name("createdAt").value(manifest.createdAt)
            writer.name("scope").value(manifest.scope)
            writer.name("include").strings(manifest.include)
            writer.name("exclude").strings(manifest.exclude)
            writer.name("roots").strings(manifest.roots)
            writer.name("entries").beginArray()

            manifest.entries.forEach { entry ->
                writer.beginObject()
                writer.name("p").value(entry.path)

                if (entry.directory) {
                    writer.name("t").value(ManifestEntry.TYPE_DIRECTORY)
                    writer.name("m").value(entry.mtime)
                } else {
                    writer.name("t").value(ManifestEntry.TYPE_FILE)
                    writer.name("s").value(entry.size)
                    writer.name("m").value(entry.mtime)
                    writer.name("c").strings(entry.chunks)
                }

                writer.endObject()
            }

            writer.endArray()
            writer.endObject()
            writer.flush()

            gzip.finish()
        }

        /** Writes [manifest] to [file]. */
        fun write(manifest: SnapshotManifest, file: File) {
            file.outputStream().buffered().use { write(manifest, it) }
        }

        /** Reads a manifest written by [write] - or by the node daemon, which writes the same thing. */
        fun read(input: InputStream): SnapshotManifest {
            val reader = JsonReader(InputStreamReader(GZIPInputStream(input, BUFFER_SIZE), StandardCharsets.UTF_8))

            var version = VERSION
            var id = ""
            var name = ""
            var createdAt = 0L
            var scope = ""
            var include = emptyList<String>()
            var exclude = emptyList<String>()
            var roots = emptyList<String>()
            val entries = ArrayList<ManifestEntry>()

            reader.beginObject()

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "version" -> version = reader.nextInt()
                    "id" -> id = reader.nextString()
                    "name" -> name = reader.nextStringOrNull().orEmpty()
                    "createdAt" -> createdAt = reader.nextLong()
                    "scope" -> scope = reader.nextStringOrNull().orEmpty()
                    "include" -> include = reader.strings()
                    "exclude" -> exclude = reader.strings()
                    "roots" -> roots = reader.strings()
                    "entries" -> {
                        reader.beginArray()

                        while (reader.hasNext()) {
                            entries.add(reader.entry())
                        }

                        reader.endArray()
                    }

                    else -> reader.skipValue()
                }
            }

            reader.endObject()

            require(version == VERSION) { "Snapshot manifest version $version is not one this plugin can read." }

            return SnapshotManifest(version, id, name, createdAt, scope, include, exclude, roots, entries)
        }

        /** Reads the manifest in [file]. */
        fun read(file: File): SnapshotManifest = file.inputStream().buffered().use { read(it) }

        private fun JsonWriter.strings(values: List<String>) {
            beginArray()
            values.forEach { value(it) }
            endArray()
        }

        private fun JsonReader.strings(): List<String> {
            if (peek() == JsonToken.NULL) {
                nextNull()

                return emptyList()
            }

            val values = ArrayList<String>()

            beginArray()

            while (hasNext()) {
                values.add(nextString())
            }

            endArray()

            return values
        }

        private fun JsonReader.nextStringOrNull(): String? {
            if (peek() == JsonToken.NULL) {
                nextNull()

                return null
            }

            return nextString()
        }

        private fun JsonReader.entry(): ManifestEntry {
            var path = ""
            var type = ManifestEntry.TYPE_FILE
            var size = 0L
            var mtime = 0L
            var chunks = emptyList<String>()

            beginObject()

            while (hasNext()) {
                when (nextName()) {
                    "p" -> path = nextString()
                    "t" -> type = nextString()
                    "s" -> size = nextLong()
                    "m" -> mtime = nextLong()
                    "c" -> chunks = strings()
                    else -> skipValue()
                }
            }

            endObject()

            val directory = type == ManifestEntry.TYPE_DIRECTORY

            return ManifestEntry(path, directory, if (directory) 0 else size, mtime, if (directory) emptyList() else chunks)
        }

        private const val BUFFER_SIZE = 64 * 1024
    }
}
