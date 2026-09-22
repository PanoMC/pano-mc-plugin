package com.panomc.plugins.pano.core.metrics

import com.panomc.plugins.pano.core.files.RestorePending
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * How much disk this server's directory takes up, measured well away from the ten-second metrics
 * tick (AGENT.md 2.4.18 A).
 *
 * Adding up a forty-gigabyte world means stat()ing a few hundred thousand files, which is the one
 * thing a metrics sample must never do inline: the tick runs on a Vert.x thread, the answer is
 * wanted every ten seconds, and a walk that slow would pile ticks on top of each other long before
 * it stalled anything else. So the walk happens on the IO dispatcher, its result is kept for five
 * minutes - a server directory does not grow meaningfully faster than that - and a tick only ever
 * reads the last figure.
 *
 * The consequence, and it is deliberate: the first few samples after start carry `null` rather
 * than a number, and Pano shows "-" until the first walk lands.
 */
class DiskUsageProbe(
    /** Where to measure, resolved per walk because a platform can hand back a relative file. */
    private val directory: () -> File,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Runs a walk off the calling thread.
     *
     * Injected so a test can walk inline instead of waiting on a thread pool; in production it is
     * the IO dispatcher, which is the plugin's async executor for every other piece of disk work
     * too (see `FileAgent`).
     */
    private val runAsync: (() -> Unit) -> Unit = { block -> CoroutineScope(Dispatchers.IO).launch { block() } },
    /** The walk itself, injected for the same reason. */
    private val measure: (File) -> Long = ::measureDirectory
) {
    companion object {
        /** How long a measurement is considered good for. */
        const val CACHE_MILLIS = 5 * 60 * 1000L
    }

    // Written by whichever IO thread finished the walk and read by the metrics tick on a Vert.x
    // one: a plain Long? read out of order would hand a sample a size that belongs to no
    // measurement at all.
    @Volatile
    private var cached: Long? = null

    @Volatile
    private var measuredAt = 0L

    // The one thing a periodic caller must not do is start a second walk while the first is still
    // crawling the world folder, which on a slow disk is exactly what a ten-second tick would do.
    private val walking = AtomicBoolean(false)

    /**
     * The last measured size in bytes, or `null` while no walk has finished yet.
     *
     * Never blocks: it schedules a fresh walk when the cached figure has gone stale and answers
     * with what it already has, including while that walk is running.
     */
    fun read(): Long? {
        refreshIfStale()

        return cached
    }

    private fun refreshIfStale() {
        if (cached != null && clock() - measuredAt < CACHE_MILLIS) {
            return
        }

        if (!walking.compareAndSet(false, true)) {
            return
        }

        try {
            runAsync {
                try {
                    val size = measure(directory())

                    cached = size
                    measuredAt = clock()
                } catch (exception: Throwable) {
                    // A directory that cannot be walked - permissions, a server moved out from
                    // under us - keeps its last known size rather than flapping to null, and the
                    // timestamp is still moved on so a hopeless walk is retried every five
                    // minutes instead of on every single tick.
                    measuredAt = clock()

                    logger.fine(
                        "Failed to measure the server directory: " +
                            "${exception.javaClass.simpleName}: ${exception.message}"
                    )
                } finally {
                    walking.set(false)
                }
            }
        } catch (exception: Throwable) {
            // The dispatcher refused the job (shutting down): release the flag here, because the
            // block above that would have released it never ran.
            walking.set(false)

            logger.fine(
                "Could not schedule a server directory measurement: " +
                    "${exception.javaClass.simpleName}: ${exception.message}"
            )
        }
    }
}

/**
 * Bytes held by the regular files under [root], recursively.
 *
 * Four rules, all of which matter for a Minecraft server directory:
 *
 * - **Regular files only.** Directories have a size of their own on most filesystems, and
 *   counting it would make the figure disagree with `du --apparent-size` for no gain.
 * - **Symlinks are not followed** and count as nothing. A `plugins` folder symlinked to a shared
 *   library directory would otherwise be counted here and again wherever it really lives, and a
 *   loop would make the walk never finish at all.
 * - **Files may vanish mid-walk.** A running server rotates logs and rewrites region files while
 *   this is walking, so a file that disappears between being listed and being stat()ed is simply
 *   skipped - it is not an error, and it must not cost the whole measurement.
 * - **The plugin's own backups do not count.** `<server>/backups`
 *   ([RestorePending.BACKUPS_DIRECTORY]) only exists because a plugin has nowhere else to put an
 *   archive; a node keeps a managed server's backups outside the server directory entirely.
 *   Counting them would make the same server look bigger on a plugin than on a node, which is
 *   exactly the comparison the panel puts side by side (AGENT.md 2.4.18 A). Only that one
 *   directory, and only directly under the root: a `world/backups` a server owner made is theirs
 *   and is counted like anything else.
 */
internal fun measureDirectory(root: File): Long {
    val path = root.toPath()

    // The root is the one link that is followed: a server directory that is itself a symlink is
    // still the directory to measure. A path that is not a directory at all has no size to report.
    if (!Files.isDirectory(path)) {
        return 0L
    }

    var total = 0L

    // The no-options overload of walkFileTree never follows symlinks, which is what makes the
    // attributes below the link's own rather than its target's.
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            // Depth one only, which is what comparing the parent against the root gives: every
            // child of the walk's start is resolved against that exact path object.
            if (dir.parent == path && dir.fileName?.toString() == RestorePending.BACKUPS_DIRECTORY) {
                return FileVisitResult.SKIP_SUBTREE
            }

            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile) {
                total += attrs.size()
            }

            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult =
            FileVisitResult.CONTINUE

        override fun postVisitDirectory(dir: Path, exception: IOException?): FileVisitResult =
            FileVisitResult.CONTINUE
    })

    return total
}
