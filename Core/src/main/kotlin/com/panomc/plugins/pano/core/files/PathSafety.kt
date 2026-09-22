package com.panomc.plugins.pano.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The one place a path coming from Pano is allowed to become a real file inside this server.
 *
 * Pano is trusted, but a compromised or simply buggy platform must not be able to talk a game
 * server into writing outside its own directory: everything is resolved under the server root and
 * then checked again after [Path.toRealPath], which is what catches a symlink pointing somewhere
 * else. The pre-check on the raw string exists because the real-path check cannot run on a file
 * that does not exist yet.
 *
 * Deliberately a transliteration of the node daemon's `PathSafety` rather than an interpretation
 * of it: the same requests arrive here as arrive there (AGENT.md 2.4.17 C), and two sandboxes
 * that disagree about what `a/../..` means are two different sandboxes.
 */
object PathSafety {
    /** Segments that can only ever be an attempt to climb out of the sandbox. */
    private val FORBIDDEN = listOf("..", "\u0000")

    /** Whether [value] is safe to use as a single path segment or inside one. */
    fun isSafeSegment(value: String?): Boolean {
        if (value.isNullOrBlank()) {
            return false
        }

        if (FORBIDDEN.any { value.contains(it) }) {
            return false
        }

        return !value.contains('/') && !value.contains('\\')
    }

    /**
     * Resolves [name] under [root] and fails loudly when the result would escape it.
     *
     * The file does not have to exist; when it does, the check is repeated against its real path
     * so a symlinked directory cannot be used as a way out.
     */
    fun resolveUnder(root: File, name: String): File {
        require(isSafeSegment(name)) { "Rejected unsafe path segment \"$name\"." }

        val rootPath = root.toPath().toAbsolutePath().normalize()
        val resolved = rootPath.resolve(name).normalize()

        require(resolved.startsWith(rootPath)) { "Rejected path escaping the server directory." }

        if (Files.exists(resolved)) {
            val realRoot = rootPath.toRealPath()
            val realResolved = resolved.toRealPath()

            require(realResolved.startsWith(realRoot)) { "Rejected symlinked path escaping the server directory." }
        }

        return resolved.toFile()
    }

    /**
     * Resolves a multi-segment path like `plugins/LuckPerms/config.yml` under [root].
     *
     * The same guarantee as [resolveUnder], extended to the paths a file manager deals in: every
     * segment is checked on its own, so `a/../../etc` is rejected before any filesystem call, and
     * the result is checked against [root] both as written and, for whatever part of it already
     * exists, after resolving symlinks. The nearest existing ancestor is what gets the real-path
     * check, because that is the link a new file would be created through.
     *
     * A blank or null path is [root] itself, which is what a listing of the server directory asks
     * for.
     */
    fun resolveRelative(root: File, path: String?): File {
        val rootPath = root.toPath().toAbsolutePath().normalize()

        val segments = (path ?: "")
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }

        require(segments.all { isSafeSegment(it) }) { "Rejected unsafe path \"$path\"." }

        var resolved = rootPath

        segments.forEach { segment -> resolved = resolved.resolve(segment) }

        resolved = resolved.normalize()

        require(resolved.startsWith(rootPath)) { "Rejected path escaping the server directory." }

        requireRealPathInside(rootPath, resolved)

        return resolved.toFile()
    }

    /** Walks up to the nearest existing ancestor and checks it really lives under [rootPath]. */
    private fun requireRealPathInside(rootPath: Path, resolved: Path) {
        val realRoot = try {
            rootPath.toRealPath()
        } catch (_: Exception) {
            // The root itself does not exist yet, so nothing under it can be a symlink out.
            return
        }

        var candidate: Path? = resolved

        while (candidate != null && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = candidate.parent
        }

        val existing = candidate ?: return

        val realExisting = try {
            existing.toRealPath()
        } catch (_: Exception) {
            throw IllegalArgumentException("Rejected a path that could not be resolved.")
        }

        require(realExisting.startsWith(realRoot)) { "Rejected symlinked path escaping the server directory." }
    }

    /** Whether any string in [values] carries a traversal attempt. Used to vet whole requests. */
    fun hasTraversal(values: Collection<String?>): Boolean =
        values.any { value -> value != null && FORBIDDEN.any { value.contains(it) } }
}
