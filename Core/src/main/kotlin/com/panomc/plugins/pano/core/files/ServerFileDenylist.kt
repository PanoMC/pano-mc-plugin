package com.panomc.plugins.pano.core.files

/**
 * The files inside this server that Pano's file manager may never touch.
 *
 * `plugins/Pano/config.conf` (and its Velocity and Fabric equivalents) holds the token and the
 * AES key that let this process act as this server against Pano, so handing it to a panel would
 * hand over the server itself; `.pano/` is where a pending restore is recorded, and a writable
 * one would let the file manager schedule a restore nobody asked for; `server.json` is what a
 * managed server's node reads its launch arguments from. Velocity's `forwarding.secret` and the
 * usual key material (`*.pem`, `*.p12`, keystores) are credentials for the same reason. `hs_err_*`
 * crash dumps are excluded because they are JVM memory dumps, not because they are dangerous.
 *
 * Two levels, on purpose. A denied path is invisible and untouchable. A directory that *contains*
 * one stays readable and listable - `plugins/` has to be, it is the point of the file manager -
 * but cannot be deleted or renamed out from under the file it holds.
 *
 * The list is the node daemon's, extended rather than rewritten: the same `FILE_*` requests reach
 * both, and a file that is a secret on a managed server is a secret on a linked one too.
 */
object ServerFileDenylist {
    /** Paths, relative to the server directory, that are never listed, read, written or removed. */
    val DENIED_PATHS = listOf(
        "server.json",
        // The node daemon's ownership record, on a server that has one.
        ".pano-node",
        // This plugin's own state directory, which is how a restore is armed for the next start.
        ".pano",
        // Every data folder the Pano plugin uses, because which one applies depends on the
        // platform and nothing here has to know which one this server is.
        "plugins/Pano/config.conf",
        "plugins/pano/config.conf",
        "config/pano/config.conf",
        // Velocity's player-forwarding secret: whoever holds it can join any backend as anyone.
        "forwarding.secret",
        // The snapshot repository. Chunks are named by their own hash and manifests by the backup
        // they describe; one file edited or deleted through the file manager silently breaks every
        // snapshot that uses it, and none of it means anything to a person browsing the server.
        // Snapshots are downloaded through `@backup/<id>`, which does not come through here.
        "backups/repo"
    )

    private const val CRASH_DUMP_PREFIX = "hs_err_"

    /** Key material, by extension, wherever in the server it happens to live. */
    private val KEY_SUFFIXES = listOf(".pem", ".p12", ".pfx", ".jks", ".keystore")

    /** [path] with separators normalised and any leading or trailing slash removed. */
    fun normalise(path: String?): String = (path ?: "")
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." }
        .joinToString("/")

    /**
     * Whether [path] is one of the files that does not exist as far as Pano is concerned.
     *
     * Case-insensitive: the same file is `plugins/Pano` on Linux and `plugins/pano` on a
     * case-folding filesystem, and a rule that only holds on one of them is not a rule.
     */
    fun isDenied(path: String?): Boolean {
        val normalised = normalise(path)

        if (normalised.isEmpty()) {
            return false
        }

        val lower = normalised.lowercase()
        val name = lower.substringAfterLast('/')

        if (name.startsWith(CRASH_DUMP_PREFIX)) {
            return true
        }

        if (KEY_SUFFIXES.any { name.endsWith(it) }) {
            return true
        }

        return DENIED_PATHS.any { denied ->
            val deniedLower = denied.lowercase()

            lower == deniedLower || lower.startsWith("$deniedLower/")
        }
    }

    /**
     * Whether [path] is a directory a denied file lives in.
     *
     * Used by the destructive operations only: deleting `plugins` would take this server's
     * credentials with it, and renaming it would leave the plugin looking at a folder that is no
     * longer there.
     */
    fun containsDenied(path: String?): Boolean {
        val normalised = normalise(path)

        if (normalised.isEmpty()) {
            // The server directory itself contains all of them, and nothing is allowed to remove
            // or rename it through the file manager anyway.
            return true
        }

        val lower = normalised.lowercase()

        return DENIED_PATHS.any { denied -> denied.lowercase().startsWith("$lower/") }
    }

    /** Whether a destructive operation may touch [path]. */
    fun isMutable(path: String?): Boolean = !isDenied(path) && !containsDenied(path)
}
