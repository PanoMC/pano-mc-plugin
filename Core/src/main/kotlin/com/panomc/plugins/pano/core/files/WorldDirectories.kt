package com.panomc.plugins.pano.core.files

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

/**
 * Which directories of a server are its worlds, and how a restore puts one back whole.
 *
 * Two different questions with the same answer shape. Before a backup, "which directories are
 * worlds" is asked of the live server: the world `server.properties` names (plus the nether and
 * the end next to it, which vanilla and Bukkit both keep as siblings), and any other top-level
 * directory holding a `level.dat` - Multiverse and friends put every extra world at the top level
 * of the world container. At restore time the question is asked of the backup instead: a
 * top-level directory with a `level.dat` inside the archive or snapshot is a world, whatever the
 * server looks like now.
 */
object WorldDirectories {
    const val LEVEL_DAT = "level.dat"
    const val PROPERTIES_FILE = "server.properties"
    const val LEVEL_NAME_KEY = "level-name"
    const val DEFAULT_LEVEL_NAME = "world"

    private val DIMENSION_SUFFIXES = listOf("_nether", "_the_end")

    /**
     * The world directories of the server in [root], sorted, relative to it.
     *
     * `level-name` comes from `server.properties` and defaults to `world` when the file or the key
     * is missing; it and its `_nether`/`_the_end` siblings count whenever they exist as
     * directories, `level.dat` or not, because a freshly generated nether has none. Anything
     * [skip] names - denied paths, the backups directory - is never a world.
     */
    fun detect(root: File, skip: (String) -> Boolean = { false }): List<String> {
        val worlds = sortedSetOf<String>()
        val level = levelName(root)

        (listOf(level) + DIMENSION_SUFFIXES.map { "$level$it" }).forEach { candidate ->
            val normalised = ServerFileDenylist.normalise(candidate)

            if (normalised.isEmpty() || skip(normalised) || ServerFileDenylist.isDenied(normalised)) {
                return@forEach
            }

            val directory = try {
                PathSafety.resolveRelative(root, normalised)
            } catch (_: Exception) {
                return@forEach
            }

            if (isRealDirectory(directory.toPath())) {
                worlds.add(normalised)
            }
        }

        root.listFiles()?.forEach { child ->
            val name = child.name

            if (skip(name) || ServerFileDenylist.isDenied(name) || !isRealDirectory(child.toPath())) {
                return@forEach
            }

            if (File(child, LEVEL_DAT).isFile) {
                worlds.add(name)
            }
        }

        return worlds.toList()
    }

    /** `level-name` from `server.properties` in [root], or `world`. */
    fun levelName(root: File): String {
        val file = File(root, PROPERTIES_FILE)

        if (!file.isFile) {
            return DEFAULT_LEVEL_NAME
        }

        return try {
            val properties = Properties()

            file.inputStream().use { properties.load(it) }

            properties.getProperty(LEVEL_NAME_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LEVEL_NAME
        } catch (_: Exception) {
            DEFAULT_LEVEL_NAME
        }
    }

    /**
     * The world directories among [paths] - the file paths of a backup - i.e. every top-level
     * directory with a `level.dat` directly inside it.
     */
    fun worldDirectoriesIn(paths: Collection<String>): Set<String> {
        val worlds = sortedSetOf<String>()

        paths.forEach { path ->
            val segments = ServerFileDenylist.normalise(path).split('/')

            if (segments.size == 2 && segments[1] == LEVEL_DAT && segments[0].isNotEmpty()) {
                worlds.add(segments[0])
            }
        }

        return worlds
    }

    /**
     * Deletes everything inside [relative] (a directory under [root]), keeping the directory
     * itself.
     *
     * What a restore does to a world before writing the backup's copy of it: region files the
     * world grew after the backup was taken must not survive into it. Denied paths and whatever
     * [keep] names (given paths relative to [root]) are left where they are, along with the
     * directories that hold them. Symbolic links are removed as links, never followed.
     */
    fun clearContents(root: File, relative: String, keep: (String) -> Boolean = { false }) {
        val normalised = ServerFileDenylist.normalise(relative)

        if (normalised.isEmpty() || ServerFileDenylist.isDenied(normalised) || keep(normalised)) {
            return
        }

        val directory = PathSafety.resolveRelative(root, normalised).toPath()

        if (!isRealDirectory(directory)) {
            return
        }

        children(directory).forEach { child -> remove(child, "$normalised/${child.fileName}", keep) }
    }

    /** Removes [path] and returns whether it is gone. */
    private fun remove(path: Path, relative: String, keep: (String) -> Boolean): Boolean {
        if (ServerFileDenylist.isDenied(relative) || keep(relative)) {
            return false
        }

        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return true
        }

        if (attributes.isDirectory) {
            var emptied = true

            children(path).forEach { child ->
                if (!remove(child, "$relative/${child.fileName}", keep)) {
                    emptied = false
                }
            }

            if (!emptied) {
                return false
            }
        }

        Files.deleteIfExists(path)

        return true
    }

    private fun children(directory: Path): List<Path> = try {
        Files.list(directory).use { stream -> stream.iterator().asSequence().toList() }
    } catch (_: IOException) {
        emptyList()
    }

    private fun isRealDirectory(path: Path): Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
}
