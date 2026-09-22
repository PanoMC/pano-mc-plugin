package com.panomc.plugins.pano.core.update

import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.config.PanoConfig
import com.panomc.plugins.pano.core.files.TestPluginMain
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.message.response.PanoPluginUpdateMessage
import com.panomc.plugins.pano.core.platform.request.PanoPluginUpdateResultRequest
import com.panomc.plugins.pano.core.platform.request.TaskProgressRequest
import com.panomc.plugins.pano.core.task.TaskReporter
import com.panomc.plugins.pano.core.util.Sha256
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plugin replacing itself (AGENT.md B3), on a temporary server directory: a download that does
 * not match is never staged, the Bukkit route goes through `plugins/update/`, and the swap on
 * shutdown keeps exactly one `.bak` of what it replaced.
 */
class SelfUpdateTest {
    private lateinit var root: File
    private lateinit var vertx: Vertx
    private lateinit var configManager: ConfigManager

    private val logger: Logger = Logger.getLogger("pano-self-update-test")

    private val sent = mutableListOf<PlatformRequest>()

    /** What the fake downloader was asked for, as `url to headers`. */
    private val downloads = mutableListOf<Pair<String, Map<String, String>>>()

    private lateinit var ownJar: File
    private lateinit var oldBytes: ByteArray
    private lateinit var newBytes: ByteArray

    @BeforeTest
    fun createServer() {
        root = Files.createTempDirectory("pano-self-update").toFile()

        vertx = Vertx.vertx()
        configManager = ConfigManager(vertx, logger, File(root, "plugins/Pano"))

        runBlocking { configManager.init() }

        configManager.config.platform =
            PanoConfig.Companion.PlatformConfig(host = "pano.test", port = 8080, ssl = false, token = "server-token")

        oldBytes = jarBytes("1.0.0-alpha.62")
        newBytes = jarBytes("1.0.0-alpha.63")

        ownJar = File(root, "plugins/pano-velocity-1.0.0-alpha.62.jar").apply {
            parentFile.mkdirs()
            writeBytes(oldBytes)
        }
    }

    @AfterTest
    fun removeServer() {
        configManager.close()
        vertx.close()

        root.deleteRecursively()
    }

    // --- verification --------------------------------------------------------------------------

    @Test
    fun `verification names what is wrong with a download`() {
        val file = File(root, "download.jar").apply { writeBytes(newBytes) }
        val sha = Sha256.of(file)

        assertNull(PanoSelfUpdate.verify(file, sha, newBytes.size.toLong()))
        assertNull(PanoSelfUpdate.verify(file, sha.uppercase(), newBytes.size.toLong()), "hex case does not matter")
        assertEquals(PanoSelfUpdate.ERROR_SIZE_MISMATCH, PanoSelfUpdate.verify(file, sha, newBytes.size + 1L))
        assertEquals(PanoSelfUpdate.ERROR_CHECKSUM_MISMATCH, PanoSelfUpdate.verify(file, "0".repeat(64), newBytes.size.toLong()))

        val page = File(root, "page.jar").apply { writeText("<html>502 Bad Gateway</html>") }

        assertEquals(PanoSelfUpdate.ERROR_NOT_A_JAR, PanoSelfUpdate.verify(page, Sha256.of(page), page.length()))
    }

    @Test
    fun `a download whose checksum does not match is refused and nothing is staged`() {
        val service = service(pluginMain(updateFolder = null))

        service.update(message(sha256 = "0".repeat(64)))

        val terminal = terminalFrame()

        assertEquals(TaskReporter.STATUS_FAILED, terminal.status)
        assertEquals(PanoSelfUpdate.ERROR_CHECKSUM_MISMATCH, terminal.error)

        val result = result()

        assertFalse(result.ok)
        assertEquals(PanoSelfUpdate.ERROR_CHECKSUM_MISMATCH, result.error)

        // Nothing staged, nothing half-written, nothing armed, and the running jar untouched.
        assertTrue(stagingDirectory().listFiles().orEmpty().none { it.name.endsWith(".jar") || it.name.endsWith(".part") })
        assertNull(PanoSelfUpdate.readSwap(dataFolder()))
        assertContentEquals(oldBytes, ownJar.readBytes())
        assertEquals(PanoSelfUpdate.SwapOutcome.NONE, PanoSelfUpdate.applyPendingSwap(dataFolder(), logger))
    }

    @Test
    fun `a download of the wrong size is refused`() {
        val service = service(pluginMain(updateFolder = null))

        service.update(message(size = newBytes.size + 10L))

        assertEquals(PanoSelfUpdate.ERROR_SIZE_MISMATCH, result().error)
        assertNull(PanoSelfUpdate.readSwap(dataFolder()))
    }

    // --- the swap on shutdown ------------------------------------------------------------------

    @Test
    fun `off Bukkit the update is staged, swapped in on shutdown, and the old jar kept as bak`() {
        val service = service(pluginMain(updateFolder = null))

        service.update(message())

        val result = result()

        assertTrue(result.ok, result.error)
        assertEquals(PanoSelfUpdate.MODE_SWAP_ON_SHUTDOWN, result.mode)
        assertEquals("1.0.0-alpha.63", result.stagedVersion)
        assertEquals(TaskReporter.STATUS_DONE, terminalFrame().status)
        assertEquals(true, terminalFrame().restartRequired)

        // Staged where the spec says, the running jar still the old one until the server stops.
        val staged = File(stagingDirectory(), "pano-1.0.0-alpha.63.jar")

        assertContentEquals(newBytes, staged.readBytes())
        assertContentEquals(oldBytes, ownJar.readBytes())

        val swap = assertNotNull(PanoSelfUpdate.readSwap(dataFolder()))

        assertEquals(ownJar.absolutePath, swap.target)

        // The platform's shutdown hook.
        assertEquals(PanoSelfUpdate.SwapOutcome.APPLIED, PanoSelfUpdate.applyPendingSwap(dataFolder(), logger))

        assertContentEquals(newBytes, ownJar.readBytes())
        assertContentEquals(oldBytes, File(ownJar.path + ".bak").readBytes())
        assertFalse(staged.exists())
        assertNull(PanoSelfUpdate.readSwap(dataFolder()))

        // Nothing left to do on the next load.
        assertEquals(PanoSelfUpdate.SwapOutcome.NONE, PanoSelfUpdate.applyPendingSwap(dataFolder(), logger))
    }

    @Test
    fun `a second update keeps one bak, of the jar it replaced`() {
        val service = service(pluginMain(updateFolder = null))

        service.update(message())
        PanoSelfUpdate.applyPendingSwap(dataFolder(), logger)

        val newest = jarBytes("1.0.0-alpha.64")

        sent.clear()
        service.update(message(bytes = newest, version = "1.0.0-alpha.64"))

        assertTrue(result().ok)
        assertEquals(PanoSelfUpdate.SwapOutcome.APPLIED, PanoSelfUpdate.applyPendingSwap(dataFolder(), logger))

        assertContentEquals(newest, ownJar.readBytes())
        assertContentEquals(newBytes, File(ownJar.path + ".bak").readBytes())
        assertEquals(1, ownJar.parentFile.listFiles().orEmpty().count { it.name.endsWith(".bak") })
    }

    @Test
    fun `a swap that did not happen at shutdown happens at the next load`() {
        service(pluginMain(updateFolder = null)).update(message())

        // No shutdown hook ran (the process was killed); the next load is a fresh JVM that only
        // has the data folder to go on.
        assertEquals(PanoSelfUpdate.SwapOutcome.APPLIED, PanoSelfUpdate.applyPendingSwap(dataFolder(), logger))
        assertContentEquals(newBytes, ownJar.readBytes())
    }

    @Test
    fun `a staged jar changed after it was verified is dropped rather than swapped in`() {
        service(pluginMain(updateFolder = null)).update(message())

        File(stagingDirectory(), "pano-1.0.0-alpha.63.jar").writeBytes(jarBytes("tampered"))

        assertEquals(PanoSelfUpdate.SwapOutcome.DISCARDED, PanoSelfUpdate.applyPendingSwap(dataFolder(), logger))
        assertContentEquals(oldBytes, ownJar.readBytes())
        assertFalse(File(ownJar.path + ".bak").exists())
        assertNull(PanoSelfUpdate.readSwap(dataFolder()))
    }

    @Test
    fun `a marker pointing outside the staging directory is never trusted`() {
        val elsewhere = File(root, "elsewhere.jar").apply { writeBytes(newBytes) }

        PanoSelfUpdate.recordSwap(
            dataFolder(),
            PanoSelfUpdate.PendingSwap(elsewhere.absolutePath, ownJar.absolutePath, Sha256.of(elsewhere), "x", "t", 1L)
        )

        assertEquals(PanoSelfUpdate.SwapOutcome.DISCARDED, PanoSelfUpdate.applyPendingSwap(dataFolder(), logger))
        assertContentEquals(oldBytes, ownJar.readBytes())
        assertTrue(elsewhere.exists())
    }

    // --- the Bukkit route ----------------------------------------------------------------------

    @Test
    fun `on Bukkit the update goes into the update folder under the running jar's name`() {
        val updateFolder = File(root, "plugins/update")
        val service = service(pluginMain(updateFolder = updateFolder))

        service.update(message())

        val result = result()

        assertTrue(result.ok, result.error)
        assertEquals(PanoSelfUpdate.MODE_UPDATE_FOLDER, result.mode)

        // Exactly the name Spigot matches on; Paper matches on the plugin name, which it also has.
        assertContentEquals(newBytes, File(updateFolder, ownJar.name).readBytes())

        // The server applies it at boot; nothing of the running server changed, a bak is kept, and
        // no swap is left for a shutdown hook to apply a second time.
        assertContentEquals(oldBytes, ownJar.readBytes())
        assertContentEquals(oldBytes, File(ownJar.path + ".bak").readBytes())
        assertNull(PanoSelfUpdate.readSwap(dataFolder()))
        assertTrue(stagingDirectory().listFiles().orEmpty().none { it.name.endsWith(".jar") })
    }

    // --- refusals and short cuts ---------------------------------------------------------------

    @Test
    fun `a platform that cannot name its own jar refuses as unsupported`() {
        val main = pluginMain(updateFolder = null, jar = null)
        val service = service(main)

        assertFalse(service.isSupported())

        service.update(message())

        assertEquals(PanoSelfUpdate.ERROR_UNSUPPORTED, terminalFrame().error)
        assertEquals(PanoSelfUpdate.ERROR_UNSUPPORTED, result().error)
        assertTrue(downloads.isEmpty(), "nothing is downloaded for an update that cannot be applied")
    }

    @Test
    fun `the running build is not downloaded again`() {
        val service = service(pluginMain(updateFolder = null))

        service.update(message(bytes = oldBytes, version = "local-build"))

        val result = result()

        assertTrue(result.ok)
        assertEquals(PanoSelfUpdate.MODE_UP_TO_DATE, result.mode)
        assertEquals(false, terminalFrame().restartRequired)
        assertTrue(downloads.isEmpty())
        assertNull(PanoSelfUpdate.readSwap(dataFolder()))
    }

    @Test
    fun `on Bukkit an older build staged earlier is taken back when the running one is current`() {
        val updateFolder = File(root, "plugins/update")
        val service = service(pluginMain(updateFolder = updateFolder))

        // An earlier update staged a build; meanwhile the admin put the current one in place.
        updateFolder.mkdirs()
        File(updateFolder, ownJar.name).writeBytes(newBytes)

        service.update(message(bytes = oldBytes, version = "local-build"))

        assertEquals(PanoSelfUpdate.MODE_UP_TO_DATE, result().mode)
        assertFalse(File(updateFolder, ownJar.name).exists(), "the stale build must not replace the current jar at boot")
        assertContentEquals(oldBytes, ownJar.readBytes())
    }

    @Test
    fun `the jar is fetched from Pano with the server's own token, and only from Pano`() {
        val service = service(pluginMain(updateFolder = null))

        service.update(message())

        val (url, headers) = downloads.single()

        assertEquals("http://pano.test:8080/api/server/pano-plugin/jar", url)
        assertEquals("Bearer server-token", headers["Authorization"])

        downloads.clear()
        sent.clear()

        service.update(message(url = "https://github.com/PanoMC/pano-mc-plugin/releases/download/v1/pano.jar"))

        assertNull(downloads.single().second["Authorization"], "a bearer token does not travel to strangers")
    }

    @Test
    fun `a message missing its checksum is refused before anything is fetched`() {
        service(pluginMain(updateFolder = null)).update(message(sha256 = "not-a-sha"))

        assertEquals(PanoSelfUpdate.ERROR_BAD_REQUEST, result().error)
        assertTrue(downloads.isEmpty())
    }

    // --- finding the jar -----------------------------------------------------------------------

    @Test
    fun `a jar loaded from Paper's remapped copy is traced back to the one in plugins`() {
        // A server of its own: the fixture's Velocity jar in root/plugins declares `name: Pano` too.
        val plugins = File(root, "paper/plugins")
        val remapped = File(plugins, ".paper-remapped/pano-spigot-1.0.0.jar").apply {
            parentFile.mkdirs()
            writeBytes(oldBytes)
        }
        val original = File(plugins, "pano-spigot-1.0.0.jar").apply { writeBytes(oldBytes) }

        assertEquals(original.absoluteFile, PanoSelfUpdate.installedJarFor(remapped, plugins))
        assertEquals(original.absoluteFile, PanoSelfUpdate.installedJarFor(original, plugins))

        // Renamed on the way: found by the name in its plugin.yml instead.
        original.delete()

        val renamed = File(plugins, "zz-renamed.jar").apply { writeBytes(jarBytes("1.0.0", name = "Pano")) }

        File(plugins, "aa-other.jar").writeBytes(jarBytes("1.0.0", name = "PanoExtras"))

        assertEquals(renamed.absoluteFile, PanoSelfUpdate.installedJarFor(remapped, plugins))
    }

    @Test
    fun `a class that did not come from a jar has no jar to replace`() {
        // The test classes are loaded from a build directory, which is exactly the development
        // case the code source has to answer null for.
        assertNull(PanoSelfUpdate.jarOf(SelfUpdateTest::class.java))
    }

    @Test
    fun `the staged name can never become a path`() {
        assertEquals("pano-1.0.0-alpha.63.jar", PanoSelfUpdate.stagedName("1.0.0-alpha.63"))
        assertEquals("pano-local-build.jar", PanoSelfUpdate.stagedName("local-build"))
        assertEquals("pano-evil1.0.jar", PanoSelfUpdate.stagedName("../evil/1.0"))
        assertEquals("pano-update.jar", PanoSelfUpdate.stagedName(null))
        assertEquals("pano-update.jar", PanoSelfUpdate.stagedName("../"))
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun dataFolder(): File = File(root, "plugins/Pano")

    private fun stagingDirectory(): File = PanoSelfUpdate.stagingDirectory(dataFolder())

    private fun pluginMain(updateFolder: File?, jar: File? = ownJar): PanoPluginMain =
        SelfUpdatePluginMain(TestPluginMain(root), jar, updateFolder)

    /** A downloader that serves [bytes] from memory, remembering what it was asked for. */
    private var served: ByteArray = ByteArray(0)

    private fun service(main: PanoPluginMain) = SelfUpdateService(
        pluginMain = main,
        configManager = configManager,
        reporter = TaskReporter({ sent.add(it) }, logger),
        send = { sent.add(it) },
        logger = logger,
        downloader = { url, headers, target, _, onProgress ->
            downloads.add(url to headers)
            target.parentFile?.mkdirs()
            target.writeBytes(served)
            onProgress(1.0)
        }
    )

    private fun message(
        bytes: ByteArray = newBytes,
        version: String = "1.0.0-alpha.63",
        sha256: String? = null,
        size: Long? = null,
        url: String = "/api/server/pano-plugin/jar"
    ): PanoPluginUpdateMessage {
        served = bytes

        val probe = File(root, "probe-${UUID.randomUUID()}.jar").apply { writeBytes(bytes) }
        val hash = Sha256.of(probe)

        probe.delete()

        return PanoPluginUpdateMessage(
            eventId = UUID.randomUUID().toString(),
            taskId = UUID.randomUUID().toString(),
            url = url,
            sha256 = sha256 ?: hash,
            size = size ?: bytes.size.toLong(),
            fileName = "pano-velocity-$version.jar",
            version = version
        )
    }

    private fun terminalFrame(): TaskProgressRequest = sent.filterIsInstance<TaskProgressRequest>()
        .last { it.status == TaskReporter.STATUS_DONE || it.status == TaskReporter.STATUS_FAILED }

    private fun result(): PanoPluginUpdateResultRequest = sent.filterIsInstance<PanoPluginUpdateResultRequest>().single()

    /** A real (tiny) jar whose bytes differ per [marker], with a plugin.yml naming [name]. */
    private fun jarBytes(marker: String, name: String = "Pano"): ByteArray {
        val output = java.io.ByteArrayOutputStream()

        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.yml"))
            zip.write("name: $name\nversion: $marker\n".toByteArray())
            zip.closeEntry()
        }

        return output.toByteArray()
    }

    /** [TestPluginMain] with a jar of its own and, on the Bukkit family, an update folder. */
    private class SelfUpdatePluginMain(
        private val base: TestPluginMain,
        private val jar: File?,
        private val updateFolder: File?
    ) : PanoPluginMain by base {
        override fun getOwnJarFile(): File? = jar

        override fun getUpdateFolder(): File? = updateFolder
    }
}
