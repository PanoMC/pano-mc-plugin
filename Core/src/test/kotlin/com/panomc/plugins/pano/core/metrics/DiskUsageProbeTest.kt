package com.panomc.plugins.pano.core.metrics

import com.panomc.plugins.pano.core.files.RestorePending
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two halves of the disk figure in a `SERVER_METRICS` sample (AGENT.md 2.4.18 A): the walk
 * that adds a server directory up, and the cache that keeps a ten-second tick from ever waiting
 * on one.
 */
class DiskUsageProbeTest {
    private lateinit var root: File

    private val logger: Logger = Logger.getLogger("pano-test")

    @BeforeTest
    fun createServer() {
        root = Files.createTempDirectory("pano-disk").toFile()
    }

    @AfterTest
    fun removeServer() {
        root.deleteRecursively()
    }

    @Test
    fun `adds up every regular file in the tree`() {
        write("server.properties", 40)
        write("world/level.dat", 100)
        write("world/region/r.0.0.mca", 4096)
        write("plugins/Pano/config.conf", 12)

        // Directories themselves are not counted, so this is the sum of the four files and
        // nothing else - whatever the filesystem charges for the four directories holding them.
        assertEquals(40L + 100L + 4096L + 12L, measureDirectory(root))
    }

    @Test
    fun `leaves the plugin's own backup archives out of the figure`() {
        write("server.properties", 40)
        write("world/level.dat", 100)
        write("${RestorePending.BACKUPS_DIRECTORY}/nightly.zip", 500_000)
        write("${RestorePending.BACKUPS_DIRECTORY}/nightly.json", 200)

        // A node keeps a managed server's backups outside the server directory, so counting the
        // plugin's would make the same server look half a megabyte bigger here than there.
        assertEquals(140L, measureDirectory(root))
    }

    @Test
    fun `only the backups directory at the top counts as the plugin's`() {
        write("world/${RestorePending.BACKUPS_DIRECTORY}/mine.zip", 70)
        write("${RestorePending.BACKUPS_DIRECTORY}/nightly.zip", 500_000)

        // "backups" anywhere but directly under the server directory is a folder the owner made,
        // and skipping it would quietly under-report their disk.
        assertEquals(70L, measureDirectory(root))
    }

    @Test
    fun `an empty directory is zero rather than a failure`() {
        assertEquals(0L, measureDirectory(root))
    }

    @Test
    fun `a directory that is not there at all is zero`() {
        assertEquals(0L, measureDirectory(File(root, "gone")))
    }

    @Test
    fun `does not follow a symlink to a file or to a directory`() {
        write("world/level.dat", 100)

        val linkedAway = Files.createTempDirectory("pano-disk-elsewhere").toFile()

        try {
            File(linkedAway, "huge.bin").writeBytes(ByteArray(8192))

            if (!symlink("shortcut.dat", File(root, "world/level.dat"))) {
                return
            }

            symlink("shared", linkedAway)

            // Both links are skipped outright: counting the target would charge this server for
            // bytes that live somewhere else, and a link pointing back up the tree would make
            // the walk run forever.
            assertEquals(100L, measureDirectory(root))
        } finally {
            linkedAway.deleteRecursively()
        }
    }

    @Test
    fun `survives a symlink loop`() {
        write("world/level.dat", 100)

        if (!symlink("world/back", root)) {
            return
        }

        assertEquals(100L, measureDirectory(root))
    }

    @Test
    fun `survives files disappearing while it walks`() {
        val doomed = (0 until 400).map { write("world/chunk-$it.tmp", 64) }

        write("keep.dat", 10)

        val deleter = Thread {
            doomed.forEach { it.delete() }
        }

        deleter.start()

        // A running server rotates logs and rewrites region files underneath this walk; the only
        // promise is that it comes back with a number instead of an IOException.
        val size = measureDirectory(root)

        deleter.join()

        assertTrue(size >= 10L, "the file that was never deleted should still have been counted")
    }

    @Test
    fun `reports nothing until the first walk has finished`() {
        val pending = mutableListOf<() -> Unit>()
        val probe = probe(runAsync = { pending.add(it) }, measure = { 4096L })

        assertNull(probe.read(), "no walk has finished, so there is nothing to report yet")
        assertEquals(1, pending.size, "the first read should have asked for a walk")

        pending.removeAt(0).invoke()

        assertEquals(4096L, probe.read())
    }

    @Test
    fun `keeps a measurement for five minutes`() {
        var now = 1_700_000_000_000L
        var walks = 0
        val probe = probe(clock = { now }, measure = { walks++; 1024L })

        // This double walks inline, so the very first read already carries a figure; the probe's
        // own "null until the first walk lands" is covered by the test above.
        assertEquals(1024L, probe.read())
        assertEquals(1, walks)

        now += DiskUsageProbe.CACHE_MILLIS - 1

        assertEquals(1024L, probe.read())
        assertEquals(1, walks, "still inside the five minutes: no second walk")

        now += 1

        assertEquals(1024L, probe.read())
        assertEquals(2, walks, "the cache has expired, so the read asked for a fresh walk")
    }

    @Test
    fun `a ten second tick never starts a second walk on top of a running one`() {
        val pending = mutableListOf<() -> Unit>()
        var walks = 0
        val probe = probe(runAsync = { pending.add(it) }, measure = { walks++; 2048L })

        repeat(30) { probe.read() }

        assertEquals(1, pending.size, "thirty ticks, one walk in flight")
        assertEquals(0, walks)

        pending.removeAt(0).invoke()

        assertEquals(2048L, probe.read())
        assertEquals(1, walks)
    }

    @Test
    fun `a walk that is still running reports the previous size`() {
        var now = 1_700_000_000_000L
        val pending = mutableListOf<() -> Unit>()
        var next = 100L
        val probe = probe(clock = { now }, runAsync = { pending.add(it) }, measure = { next })

        probe.read()
        pending.removeAt(0).invoke()

        assertEquals(100L, probe.read())

        now += DiskUsageProbe.CACHE_MILLIS
        next = 999L

        // The stale read starts a walk that has not finished; the old figure is what a sample
        // carries in the meantime, never null and never a half-counted number.
        assertEquals(100L, probe.read())
        assertEquals(1, pending.size)

        pending.removeAt(0).invoke()

        assertEquals(999L, probe.read())
    }

    @Test
    fun `a failed walk keeps the last known size and backs off`() {
        var now = 1_700_000_000_000L
        var walks = 0
        var fail = false
        val probe = probe(clock = { now }, measure = {
            walks++

            if (fail) throw IOException("permission denied") else 512L
        })

        probe.read()

        assertEquals(512L, probe.read())

        now += DiskUsageProbe.CACHE_MILLIS
        fail = true

        assertEquals(512L, probe.read(), "a failure must not wipe the figure Pano already has")
        assertEquals(2, walks)

        // Backed off like a successful walk: a directory this JVM simply cannot read must not be
        // retried on every single tick for the next five minutes.
        assertEquals(512L, probe.read())
        assertEquals(2, walks)
    }

    @Test
    fun `measures on a background thread by default`() {
        write("world/level.dat", 300)

        val caller = Thread.currentThread()
        var walkedOn: Thread? = null

        // The default executor, i.e. what actually runs in the plugin: the walk must land
        // anywhere but the thread that asked for it.
        val probe = DiskUsageProbe({ root }, logger, measure = { file ->
            walkedOn = Thread.currentThread()

            measureDirectory(file)
        })

        val deadline = System.currentTimeMillis() + 5_000L

        while (probe.read() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertEquals(300L, probe.read(), "the background walk never landed")
        assertTrue(walkedOn !== caller, "the walk ran on the calling thread")
    }

    private fun probe(
        clock: () -> Long = { 0L },
        runAsync: (() -> Unit) -> Unit = { it() },
        measure: (File) -> Long
    ) = DiskUsageProbe({ root }, logger, clock, runAsync, measure)

    private fun write(path: String, bytes: Int): File {
        val file = File(root, path)

        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))

        return file
    }

    /** Returns false where this machine will not make symlinks (an unprivileged Windows box). */
    private fun symlink(path: String, target: File): Boolean = try {
        val link = File(root, path)

        link.parentFile?.mkdirs()

        Files.createSymbolicLink(link.toPath(), target.toPath())

        true
    } catch (_: Throwable) {
        false
    }
}
