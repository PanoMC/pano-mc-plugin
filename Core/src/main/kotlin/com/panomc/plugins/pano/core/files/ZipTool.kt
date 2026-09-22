package com.panomc.plugins.pano.core.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Zip and unzip inside the server directory, with the two things an archive tool has to get right.
 *
 * **Zip slip**: an entry name is attacker-controlled text, not a path, and `../../etc/cron.d/x`
 * inside a zip is a file write outside the extraction directory on every naive implementation.
 * Every entry goes through [PathSafety.resolveRelative] against the extraction root, which rejects
 * it before anything is opened.
 *
 * **Zip bombs**: a few hundred kilobytes can declare terabytes of output, so extraction stops at a
 * total size and an entry count rather than at whatever the disk can take.
 */
object ZipTool {
    /** Most bytes a single extract may write, across all entries. */
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    /** Most entries a single archive may hold, in either direction. */
    const val MAX_ENTRIES = 100_000

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Zips [paths] (relative to [root]) into [target].
     *
     * Directories go in whole. Denied files are skipped rather than refused, so archiving
     * `plugins` still works and simply produces an archive without this server's credentials in
     * it - which is also what makes the same code safe to reuse for backups.
     *
     * [onFile] hears the relative path of every file (not directory) that went in, which is how a
     * backup knows its `fileCount` without walking the tree twice.
     */
    fun archive(
        root: File,
        paths: List<String>,
        target: File,
        onFile: (String) -> Unit = {},
        exclude: (String) -> Boolean = { false }
    ): Long {
        target.parentFile?.mkdirs()

        var entries = 0
        var written = 0L

        ZipOutputStream(target.outputStream().buffered()).use { out ->
            paths.forEach { path ->
                val source = PathSafety.resolveRelative(root, path)
                val base = ServerFileDenylist.normalise(path)

                if (!source.exists()) {
                    return@forEach
                }

                source.walkTopDown().forEach inner@{ file ->
                    val relative = relativeName(root, file)

                    if (relative.isEmpty() || ServerFileDenylist.isDenied(relative) || exclude(relative)) {
                        return@inner
                    }

                    if (!relative.startsWith(base)) {
                        return@inner
                    }

                    if (++entries > MAX_ENTRIES) {
                        throw IllegalStateException("This selection holds more than $MAX_ENTRIES files.")
                    }

                    if (file.isDirectory) {
                        out.putNextEntry(ZipEntry("$relative/"))
                        out.closeEntry()

                        return@inner
                    }

                    out.putNextEntry(ZipEntry(relative))

                    written += copy(file.inputStream().buffered(), out)

                    out.closeEntry()

                    onFile(relative)
                }
            }
        }

        return written
    }

    /**
     * Extracts [archive] into [targetDirectory], which must be inside [root].
     *
     * Symlink entries are not recreated: a zip can declare one pointing anywhere, and following it
     * on the next write would put a file outside the sandbox that every path check had already
     * approved. [exclude] is how a restore keeps this plugin's own credentials out of an archive
     * that happens to carry an older copy of them.
     */
    fun extract(
        root: File,
        archive: File,
        targetDirectory: File,
        exclude: (String) -> Boolean = { false }
    ) {
        require(archive.isFile) { "The archive does not exist." }

        targetDirectory.mkdirs()

        var entries = 0
        var written = 0L

        ZipFile(archive).use { zip ->
            val iterator = zip.entries()

            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()

                if (++entries > MAX_ENTRIES) {
                    throw IllegalStateException("This archive holds more than $MAX_ENTRIES entries.")
                }

                // Resolved against the extraction directory, which is itself already inside the
                // server: an entry that climbs out of either is refused here, before the stream is
                // opened.
                val destination = PathSafety.resolveRelative(targetDirectory, entry.name)

                val relative = relativeName(root, destination)

                if (ServerFileDenylist.isDenied(relative) || exclude(relative)) {
                    continue
                }

                if (entry.isDirectory) {
                    destination.mkdirs()

                    continue
                }

                destination.parentFile?.mkdirs()

                zip.getInputStream(entry).use { input ->
                    destination.outputStream().buffered().use { output ->
                        written += copy(input, output)
                    }
                }

                if (written > MAX_TOTAL_BYTES) {
                    throw IllegalStateException("This archive unpacks to more than $MAX_TOTAL_BYTES bytes.")
                }
            }
        }
    }

    /**
     * Whether [file] starts with a zip local-file header.
     *
     * An HTML error page saved under a `.zip` name should fail as "this is not an archive", not
     * deep inside the zip reader.
     */
    fun isZip(file: File): Boolean {
        if (!file.isFile || file.length() < 4) {
            return false
        }

        return file.inputStream().use { input ->
            val header = ByteArray(4)

            if (input.read(header) != 4) {
                return@use false
            }

            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        }
    }

    /** The path of [file] relative to [root], with forward slashes and no leading one. */
    fun relativeName(root: File, file: File): String {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val filePath = file.toPath().toAbsolutePath().normalize()

        if (!filePath.startsWith(rootPath)) {
            return ""
        }

        return rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
    }

    private fun copy(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)

                if (read <= 0) {
                    break
                }

                output.write(buffer, 0, read)

                total += read
            }
        }

        return total
    }
}
