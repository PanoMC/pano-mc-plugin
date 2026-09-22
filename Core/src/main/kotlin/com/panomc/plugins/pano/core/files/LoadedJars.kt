package com.panomc.plugins.pano.core.files

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import java.io.File

/**
 * The jars this server is running out of, which the file manager refuses to change.
 *
 * The node daemon can rewrite any file it likes, because it only ever does so while the server
 * process is stopped. A plugin has no such luxury: it *is* the running server, and overwriting a
 * jar the JVM has open produces anything from a silently corrupted plugin to a crash three hours
 * later that looks like a bug in somebody else's code. So a write, delete or rename aimed at a
 * loaded jar is answered `IN_USE` (AGENT.md 2.4.17 C) and the panel says the server has to be
 * stopped - through a node, or by hand - to replace it.
 *
 * Three sources, because no single one of them sees everything:
 *
 * - the JVM's own class path and launch command, which is where the server jar itself is named;
 * - this plugin's class loader, which is the only thing that knows its own jar's name on a
 *   platform whose plugin list does not report file names;
 * - the loaded plugin/mod list, which is the live truth about `plugins/` and `mods/` and is read
 *   fresh every time, because a plugin can be disabled and its jar replaced during one session.
 *
 * A jar that is merely *sitting* in the server directory without being loaded is not in use, and
 * is left changeable on purpose: installing an update next to a disabled jar is a normal thing to
 * want to do.
 */
class LoadedJars(private val pluginMain: PanoPluginMain) {
    /**
     * Jar names the JVM was started with, lower-cased.
     *
     * Read once: the class path of a running JVM does not change, and the alternative is parsing
     * it again on every keystroke in a file manager.
     */
    private val runtimeJarNames: Set<String> by lazy { readRuntimeJarNames() }

    /** Whether [path] names a jar this server has loaded. */
    fun isInUse(path: String?): Boolean {
        val normalised = ServerFileDenylist.normalise(path)

        if (!normalised.lowercase().endsWith(JAR_SUFFIX)) {
            return false
        }

        val name = normalised.substringAfterLast('/').lowercase()

        if (name in runtimeJarNames) {
            return true
        }

        val directory = normalised.substringBeforeLast('/', "").lowercase()

        if (directory.isEmpty() || PLUGIN_DIRECTORIES.none { directory == it || directory.startsWith("$it/") }) {
            return false
        }

        return loadedPluginFileNames().any { it == name }
    }

    /** The file names of the plugins or mods this server has loaded, lower-cased. */
    private fun loadedPluginFileNames(): Set<String> = try {
        pluginMain.getInstalledPlugins().mapNotNull { it.file?.lowercase() }.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun readRuntimeJarNames(): Set<String> {
        val names = mutableSetOf<String>()

        // The server jar, however it was started: `-jar server.jar` lands in sun.java.command,
        // `-cp` lands in java.class.path, and a launcher may use either.
        addJarNames(names, System.getProperty("java.class.path").orEmpty().split(File.pathSeparator))
        addJarNames(names, System.getProperty("sun.java.command").orEmpty().split(' '))

        // This plugin's own jar, which is the one file a panel must never be able to delete from
        // the panel that is talking through it.
        try {
            pluginMain.getPluginClassLoader().urLs.forEach { url ->
                addJarNames(names, listOf(url.path.orEmpty()))
            }
        } catch (_: Throwable) {
            // A platform whose class loader is not a URLClassLoader tells us nothing here; the
            // plugin list below still covers it.
        }

        return names
    }

    private fun addJarNames(into: MutableSet<String>, candidates: List<String>) {
        candidates.forEach { candidate ->
            val name = candidate.trim()
                .replace('\\', '/')
                .substringAfterLast('/')
                .lowercase()

            if (name.endsWith(JAR_SUFFIX)) {
                into.add(name)
            }
        }
    }

    companion object {
        private const val JAR_SUFFIX = ".jar"

        /** Where a server loads jars from; anywhere else a jar is just a file. */
        private val PLUGIN_DIRECTORIES = listOf("plugins", "mods")

        /** What the file manager answers for a file the running server is holding open. */
        const val ERROR_IN_USE = "IN_USE"
    }
}
