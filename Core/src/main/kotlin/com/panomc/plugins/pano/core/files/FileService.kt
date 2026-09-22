package com.panomc.plugins.pano.core.files

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.message.response.FileRequestMessage
import com.panomc.plugins.pano.core.util.FileHash
import com.panomc.plugins.pano.core.util.Murmur2
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.Logger

/**
 * Every `FILE_*` request, answered from inside this server's directory and nowhere else.
 *
 * The node daemon's [com.panomc.node.files.FileService] equivalent, transliterated so a panel
 * talking to a plugin and a panel talking to a node get the same answers to the same requests
 * (AGENT.md 2.4.17 C). Two differences, both because this one runs *inside* the server rather
 * than beside it: the root is the directory this process is running in rather than one of many a
 * daemon holds, and a jar the server has loaded is refused with `IN_USE` instead of being
 * overwritten under a running JVM.
 *
 * Each operation resolves its path through [PathSafety.resolveRelative], which rejects traversal,
 * absolute paths and symlinks that leave the directory, and then through [ServerFileDenylist],
 * which hides this server's own credentials. Both checks happen here rather than at the call
 * sites: a new operation that forgets one of them would be a sandbox escape, so there is exactly
 * one door.
 *
 * Nothing throws out of [handle]. Everything a caller could have done wrong comes back as
 * `{ ok: false, error }`, because the other end of this is a panel showing a message, not a
 * server that should fall over.
 */
class FileService(
    private val pluginMain: PanoPluginMain,
    private val logger: Logger,
    private val loadedJars: LoadedJars = LoadedJars(pluginMain)
) {
    /** Answers one request, as the payload of the `FILE_RESULT` the caller sends back. */
    fun handle(event: String, message: FileRequestMessage): JsonObject {
        val root = try {
            pluginMain.getServerDirectory().absoluteFile
        } catch (exception: Throwable) {
            logger.warning("Could not work out this server's own directory: ${exception.javaClass.simpleName}: ${exception.message}")

            return failure(ERROR_NO_SERVER_DIRECTORY)
        }

        return try {
            when (event) {
                FILE_LIST -> list(root, message.path)
                FILE_READ -> read(root, message.path, message.maxBytes)
                FILE_WRITE -> write(root, message.path, message.content)
                FILE_MKDIR -> mkdir(root, message.path)
                FILE_DELETE -> delete(root, message.paths)
                FILE_RENAME -> rename(root, message.from, message.to)
                FILE_ARCHIVE -> archive(root, message.paths, message.target)
                FILE_UNARCHIVE -> unarchive(root, message.path, message.target)
                FILE_CHMOD -> chmod(root, message.path, message.mode)
                FILE_HASHES -> hashes(root, message.path, message.names)
                else -> failure(ERROR_UNKNOWN_OPERATION)
            }
        } catch (exception: InUseException) {
            failure(LoadedJars.ERROR_IN_USE)
        } catch (exception: IllegalArgumentException) {
            // The only thing that throws this is a path check, and it is not worth logging an
            // operator's typo at warning level every time.
            failure(ERROR_PATH_DENIED)
        } catch (exception: Throwable) {
            logger.warning("$event failed: ${exception.javaClass.simpleName}: ${exception.message}")

            failure(exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun list(root: File, path: String?): JsonObject {
        val directory = resolve(root, path)

        if (!directory.isDirectory) {
            return failure(ERROR_NOT_FOUND)
        }

        val relative = ServerFileDenylist.normalise(path)
        val entries = JsonArray()

        val children = directory.listFiles() ?: return failure(ERROR_NOT_FOUND)

        children
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { child ->
                val childPath = if (relative.isEmpty()) child.name else "$relative/${child.name}"

                if (ServerFileDenylist.isDenied(childPath)) {
                    return@forEach
                }

                if (entries.size() >= MAX_ENTRIES) {
                    return@forEach
                }

                entries.add(describe(child))
            }

        return success()
            .put("path", relative)
            .put("entries", entries)
            .put("truncated", children.size > entries.size())
    }

    private fun describe(file: File): JsonObject {
        val symlink = Files.isSymbolicLink(file.toPath())

        val type = when {
            symlink -> "symlink"
            file.isDirectory -> "dir"
            else -> "file"
        }

        val entry = JsonObject()
            .put("name", file.name)
            .put("type", type)
            .put("size", if (file.isDirectory) 0L else file.length())
            .put("modified", file.lastModified())

        mode(file)?.let { entry.put("mode", it) }

        return entry
    }

    private fun read(root: File, path: String?, maxBytes: Int?): JsonObject {
        val file = resolve(root, path)

        if (!file.isFile) {
            return failure(ERROR_NOT_FOUND)
        }

        val limit = (maxBytes ?: DEFAULT_READ_BYTES).coerceIn(1, MAX_READ_BYTES)
        val size = file.length()

        val bytes = file.inputStream().use { input ->
            input.readNBytes(limit)
        }

        // Decoded strictly so a jar or a world file comes back flagged rather than as a screenful
        // of replacement characters an editor would then happily save back over it.
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        return success()
            .put("path", ServerFileDenylist.normalise(path))
            .put("content", decoded ?: "")
            .put("size", size)
            .put("truncated", size > limit)
            .put("binary", decoded == null)
    }

    private fun write(root: File, path: String?, content: String?): JsonObject {
        val text = content ?: ""
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (bytes.size > MAX_WRITE_BYTES) {
            return failure(ERROR_TOO_LARGE)
        }

        val file = resolveMutable(root, path)

        if (file.isDirectory) {
            return failure(ERROR_NOT_A_FILE)
        }

        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        return success().put("size", bytes.size.toLong())
    }

    private fun mkdir(root: File, path: String?): JsonObject {
        val directory = resolveMutable(root, path)

        if (directory.isDirectory) {
            return success()
        }

        if (!directory.mkdirs()) {
            return failure(ERROR_NOT_CREATED)
        }

        return success()
    }

    private fun delete(root: File, paths: List<String>?): JsonObject {
        val requested = paths.orEmpty().filter { it.isNotBlank() }

        if (requested.isEmpty()) {
            return failure(ERROR_NOTHING_SELECTED)
        }

        if (requested.size > MAX_BATCH) {
            return failure(ERROR_TOO_MANY)
        }

        // Every path is vetted before a single file is removed, so a selection holding one loaded
        // jar is refused whole rather than half-applied.
        requested.forEach { path -> resolveMutable(root, path) }

        var removed = 0

        requested.forEach { path ->
            val file = resolveMutable(root, path)

            if (!file.exists()) {
                return@forEach
            }

            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()

            if (!ok) {
                throw IllegalStateException("${ServerFileDenylist.normalise(path)} could not be removed.")
            }

            removed++
        }

        return success().put("removed", removed)
    }

    private fun rename(root: File, from: String?, to: String?): JsonObject {
        val source = resolveMutable(root, from)
        val target = resolveMutable(root, to)

        if (!source.exists()) {
            return failure(ERROR_NOT_FOUND)
        }

        if (target.exists()) {
            return failure(ERROR_EXISTS)
        }

        target.parentFile?.mkdirs()

        if (!source.renameTo(target)) {
            return failure(ERROR_NOT_MOVED)
        }

        return success()
    }

    private fun archive(root: File, paths: List<String>?, target: String?): JsonObject {
        val requested = paths.orEmpty().filter { it.isNotBlank() }

        if (requested.isEmpty()) {
            return failure(ERROR_NOTHING_SELECTED)
        }

        // Every source is resolved (and denied where it has to be) before a single byte is read.
        requested.forEach { resolve(root, it) }

        val archiveFile = resolveMutable(root, target)

        if (archiveFile.exists()) {
            return failure(ERROR_EXISTS)
        }

        val written = ZipTool.archive(root, requested, archiveFile)

        return success().put("size", written).put("archiveSize", archiveFile.length())
    }

    private fun unarchive(root: File, path: String?, target: String?): JsonObject {
        val archiveFile = resolve(root, path)

        if (!archiveFile.isFile) {
            return failure(ERROR_NOT_FOUND)
        }

        if (!ZipTool.isZip(archiveFile)) {
            return failure(ERROR_NOT_AN_ARCHIVE)
        }

        val directory = resolveMutable(root, target)

        // A loaded jar is skipped rather than failing the whole extraction: an archive of a
        // server directory carries the jars it was taken with, and refusing to unpack any of it
        // because one of them is running would make the feature useless.
        ZipTool.extract(root, archiveFile, directory) { relative -> loadedJars.isInUse(relative) }

        return success()
    }

    private fun chmod(root: File, path: String?, mode: String?): JsonObject {
        val file = resolveMutable(root, path)

        if (!file.exists()) {
            return failure(ERROR_NOT_FOUND)
        }

        val requested = mode?.trim().orEmpty()

        if (!requested.matches(MODE_PATTERN)) {
            return failure(ERROR_BAD_MODE)
        }

        val view = Files.getFileAttributeView(
            file.toPath(),
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS
        ) ?: return failure(ERROR_NO_POSIX)

        view.setPermissions(PosixFilePermissions.fromString(toSymbolic(requested)))

        return success().put("mode", requested)
    }

    /**
     * The hashes of the named jars in one directory.
     *
     * Only ever the names Pano asked about, and only jars: this exists so Pano can ask a directory
     * "what are these files" and get an answer three plugin sites can look up (SM-48), not so a
     * panel can walk a server hashing whatever it likes. A name that is not a jar, is not a single
     * path segment or is not there is simply absent from the answer, because a batch that failed
     * because one plugin was deleted a second ago would be a worse answer than one row short.
     */
    private fun hashes(root: File, path: String?, names: List<String>?): JsonObject {
        val requested = names.orEmpty().filter { it.isNotBlank() }

        if (requested.isEmpty()) {
            return failure(ERROR_NOTHING_SELECTED)
        }

        if (requested.size > MAX_HASHES) {
            return failure(ERROR_TOO_MANY)
        }

        // The directory is resolved first, so a denied or escaping path fails once rather than
        // once per name.
        val directory = resolve(root, path)

        if (!directory.isDirectory) {
            return failure(ERROR_NOT_FOUND)
        }

        val prefix = ServerFileDenylist.normalise(path)
        val files = JsonArray()

        requested.distinct().forEach { name ->
            if (!PathSafety.isSafeSegment(name) || !name.lowercase().endsWith(JAR_SUFFIX)) {
                return@forEach
            }

            val relative = if (prefix.isEmpty()) name else "$prefix/$name"

            val file = try {
                resolve(root, relative)
            } catch (_: IllegalArgumentException) {
                return@forEach
            }

            if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
                return@forEach
            }

            files.add(
                JsonObject()
                    .put("name", name)
                    .put("size", file.length())
                    .put("sha1", hashOrNull(file, "SHA-1"))
                    .put("sha512", hashOrNull(file, "SHA-512"))
                    .put("murmur2", Murmur2.fingerprint(file))
            )
        }

        return success().put("path", prefix).put("files", files)
    }

    private fun hashOrNull(file: File, algorithm: String): String? = try {
        FileHash.of(file, algorithm)
    } catch (_: Exception) {
        null
    }

    /** Resolves a path for a read-only operation. */
    private fun resolve(root: File, path: String?): File {
        if (ServerFileDenylist.isDenied(path)) {
            throw IllegalArgumentException(ERROR_PATH_DENIED)
        }

        return PathSafety.resolveRelative(root, path)
    }

    /** Resolves a path for an operation that changes or removes something. */
    private fun resolveMutable(root: File, path: String?): File {
        if (!ServerFileDenylist.isMutable(path)) {
            throw IllegalArgumentException(ERROR_PATH_DENIED)
        }

        if (loadedJars.isInUse(path)) {
            throw InUseException()
        }

        return PathSafety.resolveRelative(root, path)
    }

    private fun mode(file: File): String? = try {
        val permissions = Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS)

        toOctal(PosixFilePermissions.toString(permissions))
    } catch (_: Exception) {
        null
    }

    /** Thrown by the path check, answered as `IN_USE`; never leaves [handle]. */
    private class InUseException : RuntimeException(LoadedJars.ERROR_IN_USE)

    companion object {
        const val FILE_LIST = "FILE_LIST"
        const val FILE_READ = "FILE_READ"
        const val FILE_WRITE = "FILE_WRITE"
        const val FILE_MKDIR = "FILE_MKDIR"
        const val FILE_DELETE = "FILE_DELETE"
        const val FILE_RENAME = "FILE_RENAME"
        const val FILE_ARCHIVE = "FILE_ARCHIVE"
        const val FILE_UNARCHIVE = "FILE_UNARCHIVE"
        const val FILE_CHMOD = "FILE_CHMOD"
        const val FILE_HASHES = "FILE_HASHES"

        const val DEFAULT_READ_BYTES = 262_144
        const val MAX_READ_BYTES = 1024 * 1024
        const val MAX_WRITE_BYTES = 1024 * 1024
        const val MAX_ENTRIES = 5_000
        const val MAX_BATCH = 500

        /** Most jars one `FILE_HASHES` request may name; hashing is the one operation here that reads whole files. */
        const val MAX_HASHES = 200

        private const val JAR_SUFFIX = ".jar"

        const val ERROR_PATH_DENIED = "PATH_DENIED"
        const val ERROR_NOT_FOUND = "NOT_FOUND"
        const val ERROR_TOO_LARGE = "TOO_LARGE"
        const val ERROR_EXISTS = "ALREADY_EXISTS"
        const val ERROR_UNKNOWN_OPERATION = "UNKNOWN_OPERATION"
        const val ERROR_NO_SERVER_DIRECTORY = "NO_SERVER_DIRECTORY"
        const val ERROR_NOT_A_FILE = "NOT_A_FILE"
        const val ERROR_NOT_AN_ARCHIVE = "NOT_AN_ARCHIVE"
        const val ERROR_NOT_CREATED = "NOT_CREATED"
        const val ERROR_NOT_MOVED = "NOT_MOVED"
        const val ERROR_NOTHING_SELECTED = "NOTHING_SELECTED"
        const val ERROR_TOO_MANY = "TOO_MANY"
        const val ERROR_BAD_MODE = "BAD_MODE"
        const val ERROR_NO_POSIX = "NO_POSIX"

        /** Every operation this service answers, which is also the set of handler names. */
        val OPERATIONS = listOf(
            FILE_LIST,
            FILE_READ,
            FILE_WRITE,
            FILE_MKDIR,
            FILE_DELETE,
            FILE_RENAME,
            FILE_ARCHIVE,
            FILE_UNARCHIVE,
            FILE_CHMOD,
            FILE_HASHES
        )

        private val MODE_PATTERN = Regex("^[0-7]{3}$")

        fun success(): JsonObject = JsonObject().put("ok", true)

        fun failure(error: String): JsonObject = JsonObject().put("ok", false).put("error", error)

        /** `755` as the `rwxr-xr-x` [PosixFilePermissions] wants. */
        fun toSymbolic(octal: String): String = octal.map { digit ->
            val bits = digit - '0'

            StringBuilder()
                .append(if (bits and 4 != 0) 'r' else '-')
                .append(if (bits and 2 != 0) 'w' else '-')
                .append(if (bits and 1 != 0) 'x' else '-')
                .toString()
        }.joinToString("")

        /** `rwxr-xr-x` back to `755`, for what a listing reports. */
        fun toOctal(symbolic: String): String = symbolic.chunked(3).joinToString("") { part ->
            var bits = 0

            if (part.getOrNull(0) == 'r') bits += 4
            if (part.getOrNull(1) == 'w') bits += 2
            if (part.getOrNull(2) == 'x') bits += 1

            bits.toString()
        }
    }
}
