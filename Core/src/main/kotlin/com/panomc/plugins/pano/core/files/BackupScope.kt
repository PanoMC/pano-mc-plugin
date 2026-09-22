package com.panomc.plugins.pano.core.files

import com.panomc.plugins.pano.core.backup.snapshot.ScannedPath
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A backup request that cannot be carried out as asked; [message] is the wire error. */
class BackupScopeException(code: String) : IllegalArgumentException(code)

/**
 * What part of the server a backup covers.
 *
 * `ALL` is the whole server directory, the only thing a backup could be before scopes existed.
 * `WORLDS` is just the worlds ([WorldDirectories.detect]) - the part that cannot be downloaded
 * again, and on most servers the part that is almost all of the bytes. `CUSTOM` is whatever
 * `include` lists. In every scope the excludes still apply inside what was chosen, so a `WORLDS`
 * backup can still leave out `world/datapacks/`.
 *
 * The answer is a list of *roots*: top-level-ish paths relative to the server directory that the
 * backup walks, with `""` meaning the whole directory. The same roots feed both a FULL archive and
 * a SNAPSHOT scan, which is what keeps the two modes covering exactly the same files.
 */
object BackupScope {
    const val MODE_FULL = "FULL"
    const val MODE_SNAPSHOT = "SNAPSHOT"

    const val SCOPE_ALL = "ALL"
    const val SCOPE_WORLDS = "WORLDS"
    const val SCOPE_CUSTOM = "CUSTOM"

    const val ERROR_NO_WORLDS = "NO_WORLDS"
    const val ERROR_INVALID_SCOPE = "INVALID_SCOPE"

    private val MODES = setOf(MODE_FULL, MODE_SNAPSHOT)
    private val SCOPES = setOf(SCOPE_ALL, SCOPE_WORLDS, SCOPE_CUSTOM)

    /** [value] as a mode, `FULL` when absent; throws `INVALID_SCOPE` for anything unknown. */
    fun mode(value: String?): String = normaliseChoice(value, MODE_FULL, MODES)

    /** [value] as a scope, `ALL` when absent; throws `INVALID_SCOPE` for anything unknown. */
    fun scope(value: String?): String = normaliseChoice(value, SCOPE_ALL, SCOPES)

    private fun normaliseChoice(value: String?, default: String, allowed: Set<String>): String {
        val trimmed = value?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return default

        if (trimmed !in allowed) {
            throw BackupScopeException(ERROR_INVALID_SCOPE)
        }

        return trimmed
    }

    /**
     * The roots [scope] covers in [root], sorted and with none inside another.
     *
     * Throws [BackupScopeException] with `NO_WORLDS` for a `WORLDS` backup of a server without a
     * world, and with `INVALID_SCOPE` for a `CUSTOM` one whose `include` is empty or names nothing
     * that exists - a backup that would silently contain no files at all is not one anybody asked
     * for. An include entry that is a glob matches top-level names; anything else is a relative
     * path - one that is denied, or inside `backups/`, is ignored, and one that tries to climb out
     * of the server is `INVALID_SCOPE`. [skip] (the backups directory and the excludes) decides
     * which worlds count; inside a `CUSTOM` root the excludes apply at scan time instead, so the
     * roots are the same ones the node daemon would record.
     */
    fun roots(root: File, scope: String, include: List<String>?, skip: (String) -> Boolean): List<String> =
        when (scope) {
            SCOPE_ALL -> listOf("")

            SCOPE_WORLDS -> WorldDirectories.detect(root, skip).ifEmpty { throw BackupScopeException(ERROR_NO_WORLDS) }

            SCOPE_CUSTOM -> {
                val entries = include.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }

                if (entries.isEmpty()) {
                    throw BackupScopeException(ERROR_INVALID_SCOPE)
                }

                val selected = ArrayList<String>()

                entries.forEach { entry ->
                    val normalised = ServerFileDenylist.normalise(entry)

                    if (normalised.isEmpty()) {
                        // "/" or "." - the whole server, which is what ALL is for; taken as asked.
                        selected.addAll(topLevel(root))

                        return@forEach
                    }

                    if (normalised.contains('*')) {
                        // A glob selects matching top-level entries, the only place it is unambiguous.
                        val pattern = BackupExcludeMatcher.globToRegex(normalised)

                        root.listFiles()?.forEach { child ->
                            if (pattern.matches(child.name) && !Files.isSymbolicLink(child.toPath()) &&
                                !ServerFileDenylist.isDenied(child.name) && !isBackupsDirectory(child.name)
                            ) {
                                selected.add(child.name)
                            }
                        }

                        return@forEach
                    }

                    // Somebody typed "../" into an include list: not a path this backup can mean.
                    if (normalised.split('/').any { !PathSafety.isSafeSegment(it) }) {
                        throw BackupScopeException(ERROR_INVALID_SCOPE)
                    }

                    if (ServerFileDenylist.isDenied(normalised) || isBackupsDirectory(normalised)) {
                        return@forEach
                    }

                    val file = try {
                        PathSafety.resolveRelative(root, normalised)
                    } catch (_: Exception) {
                        throw BackupScopeException(ERROR_INVALID_SCOPE)
                    }

                    if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) {
                        selected.add(normalised)
                    }
                }

                collapse(selected).ifEmpty { throw BackupScopeException(ERROR_INVALID_SCOPE) }
            }

            else -> throw BackupScopeException(ERROR_INVALID_SCOPE)
        }

    /** [paths] sorted, without any that sit inside another one of them. */
    fun collapse(paths: Collection<String>): List<String> {
        val sorted = paths.map { ServerFileDenylist.normalise(it) }.distinct().sorted()

        if ("" in sorted) {
            return listOf("")
        }

        return sorted.filter { path -> sorted.none { other -> other != path && path.startsWith("$other/") } }
    }

    /**
     * Every directory and regular file under [roots], sorted by path, that [skip] lets through.
     *
     * Symbolic links are skipped rather than followed - a link to `/` inside `plugins` is not an
     * invitation to back up the host - and a skipped directory is not descended into, so an
     * excluded `logs/` costs nothing to leave out. The roots themselves are listed too (except
     * the server directory, `""`), which is how an empty directory that was chosen survives a
     * restore.
     */
    fun scan(root: File, roots: List<String>, skip: (String) -> Boolean): List<ScannedPath> {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val found = sortedMapOf<String, ScannedPath>()

        fun visit(path: Path, relative: String) {
            if (relative.isEmpty() || ServerFileDenylist.isDenied(relative) || skip(relative)) {
                return
            }

            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return
            }

            if (attributes.isSymbolicLink) {
                return
            }

            val mtime = attributes.lastModifiedTime().toMillis()

            if (attributes.isDirectory) {
                found[relative] = ScannedPath(relative, true, 0, mtime)

                children(path).forEach { child -> visit(path.resolve(child), "$relative/$child") }

                return
            }

            if (attributes.isRegularFile) {
                found[relative] = ScannedPath(relative, false, attributes.size(), mtime)
            }
        }

        collapse(roots).forEach { relative ->
            if (relative.isEmpty()) {
                children(rootPath).forEach { child -> visit(rootPath.resolve(child), child) }
            } else {
                visit(rootPath.resolve(relative).normalize(), relative)
            }
        }

        return found.values.toList()
    }

    /**
     * What a snapshot of `ALL` records as its `roots`: every top-level entry of [root] that is
     * not a symbolic link, not denied and not the backups directory, sorted. Excluded entries
     * such as `logs` are still listed - the roots say what the backup covered, the excludes what
     * it left out inside that.
     */
    fun topLevel(root: File): List<String> = root.listFiles()
        ?.filterNot { Files.isSymbolicLink(it.toPath()) }
        ?.map { it.name }
        ?.filterNot { ServerFileDenylist.isDenied(it) || isBackupsDirectory(it) }
        ?.sorted()
        .orEmpty()

    private fun isBackupsDirectory(path: String): Boolean {
        val normalised = ServerFileDenylist.normalise(path)

        return normalised == RestorePending.BACKUPS_DIRECTORY || normalised.startsWith("${RestorePending.BACKUPS_DIRECTORY}/")
    }

    private fun children(directory: Path): List<String> = try {
        Files.list(directory).use { stream -> stream.iterator().asSequence().map { it.fileName.toString() }.sorted().toList() }
    } catch (_: IOException) {
        emptyList()
    }
}
