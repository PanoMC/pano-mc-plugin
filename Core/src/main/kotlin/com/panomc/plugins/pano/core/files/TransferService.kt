package com.panomc.plugins.pano.core.files

import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.message.response.TransferPullMessage
import com.panomc.plugins.pano.core.platform.message.response.TransferPushMessage
import io.vertx.core.json.JsonObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.logging.Logger

/**
 * What a virtual transfer path such as `@backup/<id>` stands for.
 *
 * Either an ordinary file somewhere outside the server directory (a `FULL` backup's archive), or
 * something that only exists as a stream - a `SNAPSHOT`, whose ZIP is assembled from its chunks
 * while it is being sent. [Streamed.sizeBytes] is the logical size of its content, checked against
 * the transfer ceiling before a byte goes out.
 */
sealed class VirtualSource {
    class OfFile(val file: File) : VirtualSource()

    class Streamed(val name: String, val sizeBytes: Long, val write: (OutputStream) -> Unit) : VirtualSource()
}

/**
 * Moves whole files between this server's directory and a browser, without either end holding one.
 *
 * `FILE_READ`/`FILE_WRITE` carry their content inside a WebSocket frame, which is fine for a
 * `server.properties` and impossible for a 400 MB world. A transfer instead uses a one-shot ticket
 * and an ordinary HTTP body: Pano issues the ticket, tells this plugin about it over the socket,
 * and the plugin opens a plain request to Pano that streams the bytes. Nothing is buffered on
 * either side, and the ticket is what ties that anonymous-looking request back to the person who
 * asked for it.
 *
 * The same endpoint and the same headers the node daemon uses (`/api/node/transfer/:ticket`,
 * AGENT.md 2.4.4) - it is one door, and which credential opened it is Pano's business, not this
 * side's. The direction names are from Pano's point of view: a PULL moves a file *out of* the
 * server to a waiting browser, a PUSH moves an uploaded file *into* it.
 */
class TransferService(
    private val configManager: ConfigManager,
    private val pluginMain: PanoPluginMain,
    private val logger: Logger,
    private val loadedJars: LoadedJars = LoadedJars(pluginMain),
    /** Lets a virtual path such as `@backup/<id>` resolve to something outside the server directory. */
    private val virtualSource: (path: String) -> VirtualSource? = { null }
) {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** Streams a file up to Pano, which is pumping it into the browser that asked for it. */
    fun pull(message: TransferPullMessage) {
        val ticket = message.ticket?.takeIf { it.isNotBlank() } ?: return

        val selection = message.paths.orEmpty()

        if (selection.isNotEmpty()) {
            pullZip(ticket, message.path, selection)

            return
        }

        if (message.path.orEmpty().startsWith(VIRTUAL_PREFIX)) {
            val virtual = try {
                virtualSource(message.path.orEmpty())
            } catch (exception: Exception) {
                logger.warning("Could not resolve ${message.path}: ${exception.message}")

                null
            }

            if (virtual is VirtualSource.Streamed) {
                if (virtual.sizeBytes > MAX_TRANSFER_BYTES) {
                    reportFailure(ticket, FileService.ERROR_TOO_LARGE)

                    return
                }

                pipe(ticket, virtual.name, virtual.write)

                return
            }
        }

        val source = try {
            resolveSource(message.path)
        } catch (exception: Exception) {
            logger.warning("Refused a transfer of ${message.path}: ${exception.message}")

            reportFailure(ticket, FileService.ERROR_PATH_DENIED)

            return
        }

        if (source == null || !source.isFile) {
            reportFailure(ticket, FileService.ERROR_NOT_FOUND)

            return
        }

        if (source.length() > MAX_TRANSFER_BYTES) {
            reportFailure(ticket, FileService.ERROR_TOO_LARGE)

            return
        }

        try {
            val request = HttpRequest.newBuilder(uri(ticket))
                .timeout(TRANSFER_TIMEOUT)
                .header("Authorization", "Bearer ${token()}")
                .header("Content-Type", "application/octet-stream")
                .header(FILE_NAME_HEADER, source.name)
                .PUT(HttpRequest.BodyPublishers.ofFile(source.toPath()))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.discarding())

            if (response.statusCode() !in 200..299) {
                logger.warning("Pano answered ${response.statusCode()} to the upload of ${source.name}.")
            }
        } catch (exception: Exception) {
            logger.warning("Could not send ${source.name} to Pano: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    /**
     * Streams a ZIP of [paths] - all inside [base] - up to Pano, built while it is being sent.
     *
     * A selection of fifty files, or a whole `world` directory, is one download for the browser
     * and would be fifty round trips or a temporary archive the size of the world for anything
     * less. Instead the archive is written by a dedicated thread into a pipe whose other end is
     * the request body, so it exists only as the bytes currently in flight: no temporary file, no
     * buffer holding it, and no Content-Length, because nobody knows it until the last entry is
     * written.
     *
     * Every selected entry is checked the way a single pull checks its one file, and a refusal is
     * reported the way a single pull reports it - before a byte is sent, because once the body has
     * started the only thing left to say is that it broke. Inside a selected directory the rules
     * soften to what [ZipTool.archive] does: a denied file is left out rather than failing the
     * download of everything around it.
     */
    private fun pullZip(ticket: String, base: String?, paths: List<String>) {
        val root = pluginMain.getServerDirectory().absoluteFile

        val error = validateSelection(root, base, paths)

        if (error != null) {
            logger.warning("Refused a download of ${paths.size} path(s) under \"${base.orEmpty()}\": $error")

            reportFailure(ticket, error)

            return
        }

        pipe(ticket, zipName(base, paths)) { output -> writeZip(root, base, paths, output) }
    }

    /**
     * Uploads what [write] produces as a ZIP named [name], built while it is being sent.
     *
     * The writer runs on its own thread into a pipe whose other end is the request body, so the
     * archive only ever exists as the bytes in flight - the multi-path download and a snapshot's
     * download both go through here.
     */
    private fun pipe(ticket: String, name: String, write: (OutputStream) -> Unit) {
        val input = PipedInputStream(PIPE_SIZE)
        val pipe = PipedOutputStream(input)

        // Written from the zip thread and read by whoever drains the pipe, so the reader can turn
        // a clean-looking end of stream into the failure it actually is.
        val failure = AtomicReference<Throwable?>(null)

        val writer = Thread({
            try {
                pipe.use { output -> write(output) }
            } catch (exception: Throwable) {
                failure.set(exception)

                try {
                    pipe.close()
                } catch (_: IOException) {
                    // The reader is gone already, which is how most of these end.
                }
            }
        }, "Pano-transfer-zip")

        writer.isDaemon = true

        // An archive that stopped halfway must not reach the browser as a smaller, valid-looking
        // download: an end of stream after a failure is rethrown, which breaks the request body
        // instead of finishing it.
        val body = object : FilterInputStream(input) {
            override fun read(): Int = checked(super.read())

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                checked(super.read(buffer, offset, length))

            private fun checked(result: Int): Int {
                if (result < 0) {
                    failure.get()?.let { throw IOException("The archive could not be completed: ${it.message}", it) }
                }

                return result
            }
        }

        try {
            writer.start()

            val request = HttpRequest.newBuilder(uri(ticket))
                .timeout(TRANSFER_TIMEOUT)
                .header("Authorization", "Bearer ${token()}")
                .header("Content-Type", "application/zip")
                .header(FILE_NAME_HEADER, name)
                .PUT(HttpRequest.BodyPublishers.ofInputStream { body })
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.discarding())

            if (response.statusCode() !in 200..299) {
                logger.warning("Pano answered ${response.statusCode()} to the upload of $name.")
            }
        } catch (exception: Exception) {
            logger.warning("Could not send $name to Pano: ${exception.javaClass.simpleName}: ${exception.message}")
        } finally {
            // Closing the reading end is what stops a writer still blocked on a full pipe after
            // the request has been abandoned.
            try {
                input.close()
            } catch (_: IOException) {
            }
        }

        failure.get()?.let { logger.warning("Stopped building $name early: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Downloads what the browser uploaded and writes it into the server, reporting the outcome.
     *
     * The reply is what the panel's upload request is waiting on, so every path out of here
     * produces one.
     */
    fun push(message: TransferPushMessage): JsonObject {
        val ticket = message.ticket?.takeIf { it.isNotBlank() }
            ?: return FileService.failure(FileService.ERROR_NOT_FOUND)

        if (!ServerFileDenylist.isMutable(message.path)) {
            return FileService.failure(FileService.ERROR_PATH_DENIED)
        }

        // The same refusal the file manager gives, for the same reason: this is a write, and a
        // jar the JVM has open is not something to write over from underneath it.
        if (loadedJars.isInUse(message.path)) {
            return FileService.failure(LoadedJars.ERROR_IN_USE)
        }

        if ((message.size ?: 0L) > MAX_TRANSFER_BYTES) {
            return FileService.failure(FileService.ERROR_TOO_LARGE)
        }

        val target = try {
            PathSafety.resolveRelative(pluginMain.getServerDirectory().absoluteFile, message.path)
        } catch (_: Exception) {
            return FileService.failure(FileService.ERROR_PATH_DENIED)
        }

        if (target.isDirectory) {
            return FileService.failure(FileService.ERROR_NOT_A_FILE)
        }

        // Written next to the destination and moved into place, so a transfer that dies halfway
        // does not leave a half-written jar where a server will try to load one.
        val temporary = File(target.parentFile, "${target.name}$PARTIAL_SUFFIX")

        return try {
            target.parentFile?.mkdirs()

            val request = HttpRequest.newBuilder(uri(ticket))
                .timeout(TRANSFER_TIMEOUT)
                .header("Authorization", "Bearer ${token()}")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299) {
                response.body().close()

                return FileService.failure("Pano answered HTTP ${response.statusCode()}.")
            }

            var copied = 0L

            response.body().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)

                    while (true) {
                        val read = input.read(buffer)

                        if (read <= 0) {
                            break
                        }

                        copied += read

                        if (copied > MAX_TRANSFER_BYTES) {
                            throw IllegalStateException(FileService.ERROR_TOO_LARGE)
                        }

                        output.write(buffer, 0, read)
                    }
                }
            }

            target.delete()

            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }

            FileService.success().put("size", copied)
        } catch (exception: Exception) {
            temporary.delete()

            logger.warning("Could not store an upload: ${exception.javaClass.simpleName}: ${exception.message}")

            FileService.failure(exception.message ?: exception.javaClass.simpleName)
        }
    }

    /**
     * Tells Pano this transfer will never arrive.
     *
     * Sent as an empty PUT with an error header rather than over the socket: the browser is
     * blocked on that exact HTTP exchange, and failing it there is what releases it - a message on
     * the socket would have to find its way back to the same request anyway.
     */
    private fun reportFailure(ticket: String, error: String) {
        try {
            val request = HttpRequest.newBuilder(uri(ticket))
                .timeout(CONNECT_TIMEOUT)
                .header("Authorization", "Bearer ${token()}")
                .header(ERROR_HEADER, error)
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build()

            client.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (exception: Exception) {
            logger.warning("Could not report a failed transfer to Pano: ${exception.message}")
        }
    }

    private fun resolveSource(path: String?): File? {
        val requested = path.orEmpty()

        if (requested.startsWith(VIRTUAL_PREFIX)) {
            return (virtualSource(requested) as? VirtualSource.OfFile)?.file
        }

        if (ServerFileDenylist.isDenied(requested)) {
            throw IllegalArgumentException(FileService.ERROR_PATH_DENIED)
        }

        return PathSafety.resolveRelative(pluginMain.getServerDirectory().absoluteFile, requested)
    }

    /** What the browser saves the archive as: the one thing selected, or the folder it was in. */
    private fun zipName(base: String?, paths: List<String>): String {
        val single = paths.singleOrNull()?.let { ServerFileDenylist.normalise(it).substringAfterLast('/') }
        val folder = ServerFileDenylist.normalise(base).substringAfterLast('/')

        val name = single?.takeIf { it.isNotEmpty() } ?: folder.takeIf { it.isNotEmpty() } ?: DEFAULT_ZIP_NAME

        return "$name.zip"
    }

    private fun token(): String = configManager.config.platform?.token.orEmpty()

    /**
     * Where Pano is, as an ordinary HTTP URL.
     *
     * Built from the same host, port and TLS flag the WebSocket connected with, because a plugin
     * that could be talked into PUTting a server's files at an address of somebody else's
     * choosing would be a far more interesting bug than a broken download.
     */
    private fun uri(ticket: String): URI {
        require(PathSafety.isSafeSegment(ticket)) { "Rejected an unusable transfer ticket." }

        val platform = configManager.config.platform

        requireNotNull(platform) { "This server is not connected to a Pano platform." }

        val scheme = if (platform.ssl) "https" else "http"
        val host = platform.host.trim()

        require(host.isNotEmpty()) { "This server is not connected to a Pano platform." }

        return URI.create("$scheme://$host:${platform.port}$TRANSFER_PATH$ticket")
    }

    companion object {
        /** Ceiling for one transfer, in either direction. */
        const val MAX_TRANSFER_BYTES = 1024L * 1024 * 1024

        /** What a selection of several things made at the top of the server directory is called. */
        const val DEFAULT_ZIP_NAME = "server"

        /**
         * Why a multi-path download of [paths] under [base] cannot go ahead, or null if it can.
         *
         * The same answers a single pull gives: a denied or unsafe path is
         * [FileService.ERROR_PATH_DENIED], one that is not there is [FileService.ERROR_NOT_FOUND].
         * A path outside [base] counts as denied - the archive names its entries relative to
         * [base], and one that is not under it has no name to be given - and so does [base]
         * itself, whose entry name would be empty. A virtual `@` path is never a folder to zip.
         */
        fun validateSelection(root: File, base: String?, paths: List<String>): String? {
            if (base.orEmpty().startsWith(VIRTUAL_PREFIX) || ServerFileDenylist.isDenied(base)) {
                return FileService.ERROR_PATH_DENIED
            }

            val baseDirectory = try {
                PathSafety.resolveRelative(root, base)
            } catch (_: Exception) {
                return FileService.ERROR_PATH_DENIED
            }

            if (!baseDirectory.isDirectory) {
                return FileService.ERROR_NOT_FOUND
            }

            val prefix = ServerFileDenylist.normalise(base).let { if (it.isEmpty()) "" else "$it/" }

            paths.forEach { path ->
                val normalised = ServerFileDenylist.normalise(path)

                if (normalised.isEmpty() || !normalised.startsWith(prefix) || normalised == prefix.removeSuffix("/")) {
                    return FileService.ERROR_PATH_DENIED
                }

                if (path.startsWith(VIRTUAL_PREFIX) || ServerFileDenylist.isDenied(normalised)) {
                    return FileService.ERROR_PATH_DENIED
                }

                val source = try {
                    PathSafety.resolveRelative(root, normalised)
                } catch (_: Exception) {
                    return FileService.ERROR_PATH_DENIED
                }

                if (!Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    return FileService.ERROR_NOT_FOUND
                }
            }

            return null
        }

        /**
         * Writes a ZIP of [paths], named relative to [base], into [output], and finishes it.
         *
         * Split out of the transfer so it can be exercised against any stream, and kept to the
         * rules the transfer promises: directories are walked recursively and an empty one still
         * gets its `name/` entry, symbolic links are skipped rather than followed (a link to `/`
         * inside `plugins` is not an invitation to archive the host), children the denylist
         * denies are left out without a word, and every entry carries its file's modification
         * time. The paths are expected to have passed [validateSelection] already; one that has
         * disappeared since is skipped.
         *
         * Throws once more than [maxBytes] of file content has been read, which is what turns a
         * download of something too large into a broken stream rather than a truncated archive
         * nobody notices is incomplete. [output] is finished but not closed.
         */
        fun writeZip(
            root: File,
            base: String?,
            paths: List<String>,
            output: OutputStream,
            maxBytes: Long = MAX_TRANSFER_BYTES
        ) {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            val prefix = ServerFileDenylist.normalise(base).let { if (it.isEmpty()) "" else "$it/" }

            val zip = ZipOutputStream(output)
            val buffer = ByteArray(BUFFER_SIZE)

            var total = 0L
            var entries = 0

            fun attributes(path: Path): BasicFileAttributes? = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                null
            }

            fun nextEntry(name: String, attributes: BasicFileAttributes) {
                if (++entries > ZipTool.MAX_ENTRIES) {
                    throw IllegalStateException("This selection holds more than ${ZipTool.MAX_ENTRIES} files.")
                }

                zip.putNextEntry(ZipEntry(name).apply { lastModifiedTime = attributes.lastModifiedTime() })
            }

            // Returns whether anything was written for [path], so a directory whose every child
            // was skipped is still archived - as the empty directory it now looks like.
            fun add(path: Path, relative: String): Boolean {
                val attributes = attributes(path) ?: return false

                if (attributes.isSymbolicLink || ServerFileDenylist.isDenied(relative)) {
                    return false
                }

                val name = relative.removePrefix(prefix)

                if (attributes.isDirectory) {
                    val children: List<String> = try {
                        Files.list(path).use { stream ->
                            stream.iterator().asSequence().map { it.fileName.toString() }.sorted().toList()
                        }
                    } catch (_: IOException) {
                        emptyList()
                    }

                    var wroteChild = false

                    children.forEach { child ->
                        if (add(path.resolve(child), "$relative/$child")) {
                            wroteChild = true
                        }
                    }

                    if (!wroteChild) {
                        nextEntry("$name/", attributes)
                        zip.closeEntry()
                    }

                    return true
                }

                if (!attributes.isRegularFile) {
                    return false
                }

                nextEntry(name, attributes)

                Files.newInputStream(path).use { input ->
                    while (true) {
                        val read = input.read(buffer)

                        if (read < 0) {
                            break
                        }

                        total += read

                        if (total > maxBytes) {
                            throw IllegalStateException(FileService.ERROR_TOO_LARGE)
                        }

                        zip.write(buffer, 0, read)
                    }
                }

                zip.closeEntry()

                return true
            }

            paths.forEach { path ->
                val relative = ServerFileDenylist.normalise(path)

                if (relative.isEmpty() || !relative.startsWith(prefix)) {
                    return@forEach
                }

                add(rootPath.resolve(relative).normalize(), relative)
            }

            zip.finish()
        }

        const val TRANSFER_PATH = "/api/node/transfer/"

        /** Set on an empty PUT to fail the browser request waiting on this ticket. */
        const val ERROR_HEADER = "X-Pano-Transfer-Error"

        /** Lets Pano name the download without having to trust the browser's own path. */
        const val FILE_NAME_HEADER = "X-Pano-File-Name"

        /** Paths that are not inside the server directory, such as `@backup/<id>`. */
        const val VIRTUAL_PREFIX = "@"

        private const val PARTIAL_SUFFIX = ".part"
        private const val BUFFER_SIZE = 64 * 1024

        /** How far the zip thread may run ahead of the request body before it waits. */
        private const val PIPE_SIZE = 256 * 1024

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val TRANSFER_TIMEOUT: Duration = Duration.ofMinutes(30)
    }
}
