package com.panomc.plugins.pano.core.files

/**
 * Decides what stays out of a backup.
 *
 * Logs, caches, the backup directory itself and half-downloaded jars are the four things that are
 * always big, always regenerated and never worth restoring. A pattern ending in `/` excludes a
 * whole directory, one containing `*` is a glob on the file name, and anything else is an exact
 * relative path - the same three shapes a person would expect from a .gitignore, kept
 * deliberately small so what a backup contains stays predictable.
 *
 * The node daemon's matcher with one pattern added: its backups live in its own data directory,
 * while a plugin has nowhere to put them but `<server>/backups`, and an archive that swallowed
 * every previous archive would double in size every night.
 */
class BackupExcludeMatcher(patterns: List<String>?) {
    /** The patterns in force: Pano's list when it sent one, the defaults otherwise. */
    val patterns: List<String> = (patterns?.takeIf { it.isNotEmpty() } ?: DEFAULT_EXCLUDES)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private val entries = this.patterns

    fun matches(path: String): Boolean {
        val normalised = ServerFileDenylist.normalise(path)

        if (normalised.isEmpty()) {
            return false
        }

        val name = normalised.substringAfterLast('/')

        return entries.any { pattern ->
            when {
                pattern.endsWith("/") -> {
                    val directory = pattern.trimEnd('/')

                    normalised == directory || normalised.startsWith("$directory/")
                }

                pattern.contains('*') -> globToRegex(pattern).matches(name)

                else -> normalised == pattern
            }
        }
    }

    companion object {
        /** What a backup leaves out when Pano does not say otherwise. */
        val DEFAULT_EXCLUDES = listOf("logs/", "cache/", "backups/", "*.jar.tmp")

        /** A shell-style glob as a regex, with everything else in the pattern taken literally. */
        fun globToRegex(pattern: String): Regex {
            val escaped = pattern
                .split('*')
                .joinToString(".*") { Regex.escape(it) }

            return Regex("^$escaped$")
        }
    }
}
