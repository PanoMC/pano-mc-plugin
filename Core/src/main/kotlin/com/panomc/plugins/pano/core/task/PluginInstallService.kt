package com.panomc.plugins.pano.core.task

import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.files.FileService
import com.panomc.plugins.pano.core.files.LoadedJars
import com.panomc.plugins.pano.core.files.PathSafety
import com.panomc.plugins.pano.core.files.ZipTool
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.message.response.InstallPluginMessage
import com.panomc.plugins.pano.core.util.FileHash
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.logging.Logger

/**
 * Downloads one plugin or mod jar into this server and leaves nothing half-written behind.
 *
 * The node daemon's installer, transliterated (AGENT.md 2.4.5), because everything it does exists
 * for a reason that is just as true inside a running server: the download lands on a `.part` name
 * so a server that starts mid-download never sees a truncated jar; the checksum the source
 * published is verified here rather than in Pano, which never touches the bytes; the zip magic is
 * checked because a CDN outage answers with an HTML error page that would otherwise be saved
 * under a `.jar` name and crash the server on boot; and only then is it moved into place,
 * atomically where the filesystem allows it.
 *
 * `replaceFilename` is deleted last, after the replacement is in place: an update that fails
 * halfway should leave the old jar working, not a server with no plugin at all. And unlike the
 * node's, this one can be asked to replace a jar the JVM it is running in has open - which it
 * refuses, because the new jar only ever loads on a restart anyway and a half-overwritten one
 * would take the restart with it.
 */
class PluginInstallService(
    private val pluginMain: PanoPluginMain,
    private val configManager: ConfigManager,
    private val reporter: TaskReporter,
    private val logger: Logger,
    private val loadedJars: LoadedJars = LoadedJars(pluginMain)
) {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** Installs one jar, reporting the whole thing as a `PLUGIN_INSTALL` task. */
    fun install(message: InstallPluginMessage) {
        val taskId = message.taskId?.takeIf { it.isNotBlank() }

        if (taskId == null) {
            logger.warning("Ignoring an INSTALL_PLUGIN with no task id.")

            return
        }

        try {
            run(taskId, message)
        } catch (exception: Throwable) {
            logger.warning("A plugin install failed: ${exception.javaClass.simpleName}: ${exception.message}")

            reporter.failed(taskId, TaskReporter.KIND_PLUGIN_INSTALL, exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun run(taskId: String, message: InstallPluginMessage) {
        val filename = message.filename?.trim().orEmpty()

        // A filename is a single path segment and nothing else: it is the one field of this
        // message that becomes a real name on disk, and `../../start.sh` would otherwise be a
        // write anywhere this server can reach.
        if (!isJarName(filename)) {
            reporter.failed(taskId, TaskReporter.KIND_PLUGIN_INSTALL, "\"$filename\" is not a usable jar file name.")

            return
        }

        val targetDir = message.targetDir?.trim()?.takeIf { it.isNotEmpty() } ?: pluginMain.getPluginDirectoryName()

        if (targetDir !in ALLOWED_TARGET_DIRS) {
            reporter.failed(taskId, TaskReporter.KIND_PLUGIN_INSTALL, "Plugins may only be installed into plugins/ or mods/.")

            return
        }

        val replaceFilename = message.replaceFilename?.trim()?.takeIf { it.isNotEmpty() }

        if (replaceFilename != null && !isJarName(replaceFilename)) {
            reporter.failed(taskId, TaskReporter.KIND_PLUGIN_INSTALL, "\"$replaceFilename\" is not a usable jar file name.")

            return
        }

        // Third-party downloads are absolute and pass straight through; a path means Pano is
        // serving the file itself and only this side knows which address it reaches Pano on.
        val downloadUrl = resolveUrl(message.downloadUrl)

        if (downloadUrl.isEmpty()) {
            reporter.failed(taskId, TaskReporter.KIND_PLUGIN_INSTALL, "Pano did not resolve a download for this version.")

            return
        }

        val inUse = listOfNotNull(filename, replaceFilename).firstOrNull { name ->
            loadedJars.isInUse("$targetDir/$name")
        }

        if (inUse != null) {
            reporter.failed(taskId, TaskReporter.KIND_PLUGIN_INSTALL, LoadedJars.ERROR_IN_USE)

            return
        }

        val root = pluginMain.getServerDirectory().absoluteFile
        val directory = PathSafety.resolveRelative(root, targetDir)

        directory.mkdirs()

        val target = File(directory, filename)
        val part = File(directory, "$filename$PART_SUFFIX")

        part.delete()

        reporter.running(taskId, TaskReporter.KIND_PLUGIN_INSTALL, START_PERCENT, "Downloading $filename")

        try {
            download(downloadUrl, part) { fraction ->
                val percent = START_PERCENT + (fraction * (VERIFY_PERCENT - START_PERCENT)).toInt()

                reporter.running(taskId, TaskReporter.KIND_PLUGIN_INSTALL, percent, "Downloading $filename")
            }

            reporter.running(taskId, TaskReporter.KIND_PLUGIN_INSTALL, VERIFY_PERCENT, "Verifying $filename")

            verify(part, message)

            if (!ZipTool.isZip(part)) {
                throw IllegalStateException("The download is not a jar file.")
            }

            move(part, target)
        } catch (exception: Throwable) {
            part.delete()

            throw exception
        }

        // Last, and only on success: the old jar is the fallback while anything can still fail.
        if (replaceFilename != null && replaceFilename != filename) {
            val previous = File(directory, replaceFilename)

            if (previous.isFile && !previous.delete()) {
                logger.warning("Installed $filename but could not remove the replaced $replaceFilename.")
            }
        }

        logger.info("Pano installed $filename into $targetDir; it loads on the next restart.")

        reporter.done(
            taskId,
            TaskReporter.KIND_PLUGIN_INSTALL,
            "Installed $filename",
            // Always: a running server loads its plugins once, at boot, on every platform this
            // plugin runs on.
            restartRequired = true
        )
    }

    /**
     * Checks the download against whichever hash the source published.
     *
     * Absent hashes are not an error: Hangar's external downloads carry none at all, and refusing
     * those would mean refusing half of Hangar. The zip check downstream is what every download
     * gets regardless.
     */
    private fun verify(file: File, message: InstallPluginMessage) {
        val checks = listOf(
            "SHA-512" to message.sha512,
            "SHA-256" to message.sha256,
            "SHA-1" to message.sha1,
            "MD5" to message.md5
        )

        checks.forEach { (algorithm, expected) ->
            if (expected.isNullOrBlank()) {
                return@forEach
            }

            if (!FileHash.matches(file, algorithm, expected)) {
                throw IllegalStateException("The download did not match its $algorithm checksum.")
            }
        }
    }

    /**
     * Downloads [url] to [target], calling [onProgress] with a 0..1 fraction as it goes.
     *
     * The fraction is only meaningful when the upstream sent a content length; without one the
     * callback is never invoked and the caller keeps whatever percent it had.
     */
    private fun download(url: String, target: File, onProgress: (Double) -> Unit) {
        val uri = URI.create(url)

        require(uri.scheme == "http" || uri.scheme == "https") { "Refused to download from \"${uri.scheme}\"." }

        val builder = HttpRequest.newBuilder(uri)
            .timeout(DOWNLOAD_TIMEOUT)
            .header("User-Agent", "pano-plugin")
            .GET()

        // Only what Pano is serving itself gets this server's credentials; a Modrinth or GitHub
        // URL is a stranger, and a bearer token does not travel to strangers.
        if (isPlatformUrl(url)) {
            builder.header("Authorization", "Bearer ${configManager.config.platform?.token.orEmpty()}")
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() !in 200..299) {
            response.body().close()

            throw IllegalStateException("Download failed with HTTP ${response.statusCode()}.")
        }

        val total = response.headers().firstValueAsLong("content-length").orElse(-1L)

        if (total > MAX_DOWNLOAD_BYTES) {
            response.body().close()

            throw IllegalStateException(FileService.ERROR_TOO_LARGE)
        }

        target.parentFile?.mkdirs()

        var copied = 0L
        var lastReported = -1

        response.body().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)

                while (true) {
                    val read = input.read(buffer)

                    if (read <= 0) {
                        break
                    }

                    output.write(buffer, 0, read)

                    copied += read

                    if (copied > MAX_DOWNLOAD_BYTES) {
                        throw IllegalStateException(FileService.ERROR_TOO_LARGE)
                    }

                    if (total > 0) {
                        val percent = ((copied * 100) / total).toInt()

                        if (percent != lastReported) {
                            lastReported = percent

                            onProgress(percent / 100.0)
                        }
                    }
                }
            }
        }
    }

    /** [url] made absolute against the Pano this server is connected to, when it is a path. */
    private fun resolveUrl(url: String?): String {
        val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return ""

        if (!trimmed.startsWith("/") || trimmed.startsWith("//")) {
            return trimmed
        }

        val platform = configManager.config.platform ?: return ""
        val host = platform.host.trim()

        if (host.isEmpty()) {
            return ""
        }

        return "${if (platform.ssl) "https" else "http"}://$host:${platform.port}$trimmed"
    }

    private fun isPlatformUrl(url: String): Boolean {
        val platform = configManager.config.platform ?: return false
        val host = platform.host.trim()

        if (host.isEmpty()) {
            return false
        }

        return try {
            URI.create(url).host.equals(host, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /** Moves the finished download onto its real name, atomically where the filesystem allows it. */
    private fun move(part: File, target: File) {
        try {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val PART_SUFFIX = ".part"

        /** A plugin jar is megabytes; anything on this scale is a mistake or an attack. */
        const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024

        private const val START_PERCENT = 5
        private const val VERIFY_PERCENT = 90
        private const val BUFFER_SIZE = 64 * 1024

        private val ALLOWED_TARGET_DIRS = setOf("plugins", "mods")

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(30)

        const val MAX_NAME_LENGTH = 200

        /**
         * Whether [name] is one path segment ending in `.jar`.
         *
         * Extension included deliberately: a server loads what is in its plugins directory, so the
         * only file that belongs there is the one it can load.
         */
        fun isJarName(name: String?): Boolean {
            if (!PathSafety.isSafeSegment(name)) {
                return false
            }

            val value = name!!

            if (!value.lowercase().endsWith(".jar") || value.length <= ".jar".length) {
                return false
            }

            return value.length <= MAX_NAME_LENGTH && value.none { it.isISOControl() }
        }
    }
}
