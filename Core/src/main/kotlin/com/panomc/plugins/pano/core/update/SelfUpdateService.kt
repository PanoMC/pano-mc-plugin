package com.panomc.plugins.pano.core.update

import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.files.FileAgent
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.message.response.PanoPluginUpdateMessage
import com.panomc.plugins.pano.core.platform.request.PanoPluginUpdateResultRequest
import com.panomc.plugins.pano.core.task.TaskReporter
import com.panomc.plugins.pano.core.util.Sha256
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Handles `PANO_PLUGIN_UPDATE`: fetches the newer build Pano offers, checks it and stages it
 * (AGENT.md B3).
 *
 * The download comes from Pano itself (`/api/server/pano-plugin/jar`), resolved against the address
 * this server is connected to and sent with the platform token from `config.conf` — the same
 * credential and the same kind of request the transfers use. A URL on any other host is fetched
 * without the token: a bearer token does not travel to strangers, whatever the message says.
 *
 * Progress is reported as a `PLUGIN_INSTALL` task, like any other jar Pano puts in a server, and the
 * end twice over: once as the task's terminal frame, and once as `PANO_PLUGIN_UPDATE_RESULT`, which
 * is the frame that says which version is now waiting and how it will be applied. Pano ends the task
 * on whichever of the two it reads first.
 *
 * One update at a time. A second push while one is still downloading is refused as `BUSY` rather
 * than queued: two downloads racing into the same staging directory would be the one way to stage a
 * jar that was never verified.
 */
class SelfUpdateService(
    private val pluginMain: PanoPluginMain,
    private val configManager: ConfigManager,
    private val reporter: TaskReporter,
    private val send: (PlatformRequest) -> Unit,
    private val logger: Logger,
    private val downloader: Downloader = HttpDownloader()
) {
    /** Fetches [url] into [target], never more than [maxBytes], reporting a 0..1 fraction as it goes. */
    fun interface Downloader {
        fun download(url: String, headers: Map<String, String>, target: File, maxBytes: Long, onProgress: (Double) -> Unit)
    }

    private val running = AtomicBoolean(false)

    /**
     * Whether this plugin can replace itself at all, which is what `self-update` is announced on.
     *
     * The one precondition is knowing its own jar. Everything else — a reachable Pano, a writable
     * data folder — is checked when an update actually arrives and reported on its task.
     */
    fun isSupported(): Boolean = ownJar() != null

    fun update(message: PanoPluginUpdateMessage) {
        val taskId = message.taskId?.takeIf { it.isNotBlank() }

        if (taskId == null) {
            logger.warning("Ignoring a PANO_PLUGIN_UPDATE with no task id.")

            return
        }

        val eventId = FileAgent.eventIdOf(message.eventId) ?: UUID.randomUUID()

        if (!running.compareAndSet(false, true)) {
            fail(taskId, eventId, PanoSelfUpdate.ERROR_BUSY)

            return
        }

        try {
            run(taskId, eventId, message)
        } catch (exception: Throwable) {
            logger.warning("The Pano update failed: ${exception.javaClass.simpleName}: ${exception.message}")

            fail(taskId, eventId, exception.message ?: exception.javaClass.simpleName)
        } finally {
            running.set(false)
        }
    }

    private fun run(taskId: String, eventId: UUID, message: PanoPluginUpdateMessage) {
        val ownJar = ownJar() ?: return fail(taskId, eventId, PanoSelfUpdate.ERROR_UNSUPPORTED)

        val expectedSha256 = message.sha256?.trim()?.lowercase()?.takeIf { SHA256_PATTERN.matches(it) }
        val expectedSize = message.size?.takeIf { it in 1..MAX_DOWNLOAD_BYTES }
        val url = resolveUrl(message.url)

        if (expectedSha256 == null || expectedSize == null || url.isEmpty()) {
            return fail(taskId, eventId, PanoSelfUpdate.ERROR_BAD_REQUEST)
        }

        val version = message.version?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_VERSION_LENGTH)
        val dataFolder = pluginMain.getDataFolder()

        // Already these exact bytes: a development build calls every jar `local-build`, so the
        // checksum is the only way to tell that there is nothing to do.
        if (Sha256.of(ownJar).equals(expectedSha256, ignoreCase = true)) {
            PanoSelfUpdate.clearSwap(dataFolder)
            PanoSelfUpdate.cleanStaging(dataFolder, keep = null)

            // An older build staged by an earlier update would still replace this one at the
            // next boot on Bukkit.
            pluginMain.getUpdateFolder()?.let { folder ->
                if (PanoSelfUpdate.unstageFromUpdateFolder(ownJar, folder)) {
                    logger.info("Removed the Pano update staged in ${folder.name}/; this build is already the current one.")
                }
            }

            reporter.done(taskId, TaskReporter.KIND_PLUGIN_INSTALL, "The Pano plugin is already running this build", restartRequired = false)

            return result(eventId, taskId, ok = true, error = null, stagedVersion = version, mode = PanoSelfUpdate.MODE_UP_TO_DATE)
        }

        val directory = PanoSelfUpdate.stagingDirectory(dataFolder)

        directory.mkdirs()

        val staged = File(directory, PanoSelfUpdate.stagedName(version))
        val part = File(directory, staged.name + PanoSelfUpdate.PART_SUFFIX)

        part.delete()

        reporter.running(taskId, TaskReporter.KIND_PLUGIN_INSTALL, START_PERCENT, "Downloading Pano ${version ?: ""}".trim())

        try {
            downloader.download(url, headersFor(url), part, MAX_DOWNLOAD_BYTES) { fraction ->
                val percent = START_PERCENT + (fraction * (VERIFY_PERCENT - START_PERCENT)).toInt()

                reporter.running(taskId, TaskReporter.KIND_PLUGIN_INSTALL, percent, "Downloading Pano ${version ?: ""}".trim())
            }

            reporter.running(taskId, TaskReporter.KIND_PLUGIN_INSTALL, VERIFY_PERCENT, "Verifying the download")

            val refused = PanoSelfUpdate.verify(part, expectedSha256, expectedSize)

            if (refused != null) {
                part.delete()

                return fail(taskId, eventId, refused)
            }

            PanoSelfUpdate.promote(part, staged)
        } catch (exception: Throwable) {
            part.delete()

            throw exception
        }

        reporter.running(taskId, TaskReporter.KIND_PLUGIN_INSTALL, STAGE_PERCENT, "Staging the update")

        val updateFolder = pluginMain.getUpdateFolder()

        val mode = if (updateFolder != null) {
            PanoSelfUpdate.stageIntoUpdateFolder(staged, ownJar, updateFolder)

            // Bukkit takes it from here; a swap recorded by an earlier update would now replace
            // the jar a second time, with an older build.
            PanoSelfUpdate.clearSwap(dataFolder)
            PanoSelfUpdate.cleanStaging(dataFolder, keep = null)

            PanoSelfUpdate.MODE_UPDATE_FOLDER
        } else {
            PanoSelfUpdate.recordSwap(
                dataFolder,
                PanoSelfUpdate.PendingSwap(
                    staged = staged.absolutePath,
                    target = ownJar.absolutePath,
                    sha256 = expectedSha256,
                    version = version,
                    taskId = taskId,
                    at = System.currentTimeMillis()
                )
            )

            PanoSelfUpdate.cleanStaging(dataFolder, keep = staged)

            PanoSelfUpdate.MODE_SWAP_ON_SHUTDOWN
        }

        logger.info("Staged Pano ${version ?: staged.name}; it replaces ${ownJar.name} on the next restart.")

        reporter.done(
            taskId,
            TaskReporter.KIND_PLUGIN_INSTALL,
            if (version == null) "The Pano plugin update is staged; restart the server to load it"
            else "Pano $version is staged; restart the server to load it",
            restartRequired = true
        )

        result(eventId, taskId, ok = true, error = null, stagedVersion = version, mode = mode)
    }

    /** The jar this plugin runs from, or null when the platform cannot say. */
    private fun ownJar(): File? = try {
        pluginMain.getOwnJarFile()?.takeIf { it.isFile }
    } catch (_: Throwable) {
        null
    }

    private fun fail(taskId: String, eventId: UUID, error: String) {
        reporter.failed(taskId, TaskReporter.KIND_PLUGIN_INSTALL, error)

        result(eventId, taskId, ok = false, error = error, stagedVersion = null, mode = null)
    }

    private fun result(eventId: UUID, taskId: String, ok: Boolean, error: String?, stagedVersion: String?, mode: String?) {
        try {
            send(PanoPluginUpdateResultRequest(eventId, taskId, ok, error, stagedVersion, mode))
        } catch (exception: Throwable) {
            // The task frame already said how it went; this one only adds detail.
            logger.fine("Could not report the Pano update result: ${exception.javaClass.simpleName}: ${exception.message}")
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

    private fun headersFor(url: String): Map<String, String> {
        val platform = configManager.config.platform ?: return emptyMap()
        val host = platform.host.trim()

        val isPlatform = host.isNotEmpty() && try {
            URI.create(url).host.equals(host, ignoreCase = true)
        } catch (_: Exception) {
            false
        }

        if (!isPlatform) {
            return emptyMap()
        }

        return mapOf("Authorization" to "Bearer ${platform.token}")
    }

    /** The real download: `java.net.http`, like the plugin installer and the transfers. */
    class HttpDownloader : Downloader {
        private val client: HttpClient by lazy {
            HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        }

        override fun download(url: String, headers: Map<String, String>, target: File, maxBytes: Long, onProgress: (Double) -> Unit) {
            val uri = URI.create(url)

            require(uri.scheme == "http" || uri.scheme == "https") { "Refused to download from \"${uri.scheme}\"." }

            val builder = HttpRequest.newBuilder(uri)
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "pano-plugin")
                .GET()

            headers.forEach { (name, value) -> builder.header(name, value) }

            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299) {
                response.body().close()

                throw IllegalStateException("Download failed with HTTP ${response.statusCode()}.")
            }

            val total = response.headers().firstValueAsLong("content-length").orElse(-1L)

            if (total > maxBytes) {
                response.body().close()

                throw IllegalStateException(PanoSelfUpdate.ERROR_SIZE_MISMATCH)
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

                        if (copied > maxBytes) {
                            throw IllegalStateException(PanoSelfUpdate.ERROR_SIZE_MISMATCH)
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
    }

    companion object {
        /** The Pano plugin is a few megabytes; anything on this scale is a mistake. */
        const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024

        private const val MAX_VERSION_LENGTH = 64

        private const val START_PERCENT = 5
        private const val VERIFY_PERCENT = 85
        private const val STAGE_PERCENT = 95
        private const val BUFFER_SIZE = 64 * 1024

        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}
