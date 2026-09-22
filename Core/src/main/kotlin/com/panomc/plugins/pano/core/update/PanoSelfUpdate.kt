package com.panomc.plugins.pano.core.update

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.plugins.pano.core.files.ZipTool
import com.panomc.plugins.pano.core.util.Sha256
import java.io.File
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger
import java.util.zip.ZipFile

/**
 * The file-level half of the plugin replacing itself (`PANO_PLUGIN_UPDATE`, AGENT.md B3).
 *
 * A plugin cannot overwrite the jar it is running from: the JVM has it open, and a jar rewritten
 * underneath a running class loader is anything from a silently corrupted plugin to a crash an hour
 * later that looks like somebody else's bug. So an update happens in two steps with a restart in
 * between, and everything here exists to make the gap between them safe.
 *
 * The first step, while the server runs, is staging: the download lands on a `.part` name in
 * `<dataFolder>/update/`, is checked against the size and SHA-256 Pano announced and against the
 * zip header every jar starts with, and only then becomes `pano-<version>.jar`. Nothing about the
 * running server has changed yet, and a failure at any point leaves it exactly as it was.
 *
 * The second step is the swap, and it has two forms. The Bukkit family has one built in: a jar in
 * `plugins/update/` named like a loaded plugin's jar replaces it at the next boot, before anything
 * loads it (Paper matches by plugin name, Spigot by file name, so the running jar's own name
 * satisfies both). Everything else — Velocity, BungeeCord, Fabric — gets a marker instead, and the
 * plugin swaps its own jar as the server stops: the old one becomes `<name>.jar.bak` (one
 * generation, replaced by the next update) and the staged one takes its name, by an atomic rename
 * where the filesystem allows it and a copy and rename where it does not. A swap that could not
 * happen at shutdown — the process was killed, or the platform holds the jar locked — is tried
 * again the next time the plugin loads, which is also why the marker is only removed once it is
 * done.
 *
 * Pure file operations, no platform and no network, so every step can be exercised on a temporary
 * directory.
 */
object PanoSelfUpdate {
    /** Under the plugin's data folder: the staged jar and the marker for a swap on shutdown. */
    const val DIRECTORY = "update"

    const val MARKER_FILE = "pending-swap.json"

    const val PART_SUFFIX = ".part"

    /** The previous jar, kept beside the new one after a swap: `<name>.jar.bak`. */
    const val BACKUP_SUFFIX = ".bak"

    /** The platform could not say which jar it was loaded from, so there is nothing to replace. */
    const val ERROR_UNSUPPORTED = "UNSUPPORTED"

    /** The message was missing a task, a URL, a usable SHA-256 or a size. */
    const val ERROR_BAD_REQUEST = "BAD_REQUEST"

    /** Another self-update is already running in this server. */
    const val ERROR_BUSY = "BUSY"

    const val ERROR_SIZE_MISMATCH = "SIZE_MISMATCH"

    const val ERROR_CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"

    /** What arrived is not a zip at all — an error page served with a 200, most likely. */
    const val ERROR_NOT_A_JAR = "NOT_A_JAR"

    /** `mode` of an update Bukkit applies from `plugins/update/` at the next boot. */
    const val MODE_UPDATE_FOLDER = "update-folder"

    /** `mode` of an update this plugin swaps in itself when the server stops. */
    const val MODE_SWAP_ON_SHUTDOWN = "swap-on-shutdown"

    /** `mode` of an update that turned out to be the build already running; nothing was staged. */
    const val MODE_UP_TO_DATE = "up-to-date"

    /** The name the plugin carries in plugin.yml, bungee.yml, velocity-plugin.json and fabric.mod.json. */
    const val PLUGIN_NAME = "Pano"

    /** What a swap that is waiting for the server to stop looks like on disk. */
    data class PendingSwap(
        val staged: String? = null,
        val target: String? = null,
        val sha256: String? = null,
        val version: String? = null,
        val taskId: String? = null,
        val at: Long = 0
    )

    /** How one attempt at a pending swap went. */
    enum class SwapOutcome {
        /** There was no swap waiting. */
        NONE,

        /** The staged jar now has the running jar's name, and the old one is `<name>.jar.bak`. */
        APPLIED,

        /** The target already holds the staged bytes; only the marker was left to clean up. */
        ALREADY_APPLIED,

        /** The marker pointed at something missing or altered, and was dropped without a swap. */
        DISCARDED,

        /** The files could not be moved (a locked jar); the marker stays for the next attempt. */
        FAILED
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun stagingDirectory(dataFolder: File): File = File(dataFolder, DIRECTORY)

    fun markerFile(dataFolder: File): File = File(stagingDirectory(dataFolder), MARKER_FILE)

    /**
     * `pano-<version>.jar`, with the version cut down to characters that can never make a path.
     *
     * The version comes from Pano, which is trusted, but it still becomes a file name here, and a
     * name is cheaper to make safe than to reason about.
     */
    fun stagedName(version: String?): String {
        val safe = version
            ?.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '+' }
            ?.replace(DOT_RUN, ".")
            ?.trim('.', '-')
            ?.takeIf { it.isNotEmpty() }
            ?: "update"

        return "pano-$safe.jar"
    }

    /**
     * Checks a finished download against what Pano announced, and returns the error or null.
     *
     * Size first because it is free and catches the common failure (a truncated download) without
     * hashing anything; then the zip header, so an HTML page is named for what it is; then the hash,
     * which is the check that actually matters.
     */
    fun verify(file: File, expectedSha256: String, expectedSize: Long): String? {
        if (!file.isFile || file.length() != expectedSize) {
            return ERROR_SIZE_MISMATCH
        }

        if (!ZipTool.isZip(file)) {
            return ERROR_NOT_A_JAR
        }

        if (!Sha256.of(file).equals(expectedSha256.trim(), ignoreCase = true)) {
            return ERROR_CHECKSUM_MISMATCH
        }

        return null
    }

    /** Moves a verified `.part` onto its staged name. */
    fun promote(part: File, staged: File) {
        move(part, staged)
    }

    /**
     * The Bukkit route: keeps a `.bak` of [ownJar] and puts [staged] into [updateFolder] under
     * [ownJar]'s name, where the server picks it up at the next boot.
     *
     * The backup is a copy, not a move — the running jar has to stay exactly where it is until the
     * server replaces it itself.
     */
    fun stageIntoUpdateFolder(staged: File, ownJar: File, updateFolder: File): File {
        backup(ownJar)

        updateFolder.mkdirs()

        val target = File(updateFolder, ownJar.name)

        move(staged, target)

        return target
    }

    /**
     * Takes back what [stageIntoUpdateFolder] put in [updateFolder] for [ownJar], if anything.
     *
     * For an update that turned out not to be needed: a build staged earlier and still waiting there
     * would otherwise replace the jar that is already current at the next boot — with an older one.
     *
     * @return whether a staged jar was removed.
     */
    fun unstageFromUpdateFolder(ownJar: File, updateFolder: File): Boolean {
        val staged = File(updateFolder, ownJar.name)

        return staged.isFile && staged.delete()
    }

    /** Records a swap for the next shutdown (or load), replacing any earlier one. */
    fun recordSwap(dataFolder: File, swap: PendingSwap) {
        val file = markerFile(dataFolder)

        file.parentFile?.mkdirs()

        val temporary = File(file.parentFile, "$MARKER_FILE.tmp")

        temporary.writeText(gson.toJson(swap))

        move(temporary, file)
    }

    /** The recorded swap, or null when there is none or it cannot be read. */
    fun readSwap(dataFolder: File): PendingSwap? {
        val file = markerFile(dataFolder)

        if (!file.isFile) {
            return null
        }

        return try {
            gson.fromJson(file.readText(), PendingSwap::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun clearSwap(dataFolder: File) {
        markerFile(dataFolder).delete()
    }

    /** Removes staged jars and partial downloads other than [keep] from the staging directory. */
    fun cleanStaging(dataFolder: File, keep: File?) {
        stagingDirectory(dataFolder).listFiles()
            ?.filter { it.isFile && it != keep && (it.name.endsWith(".jar") || it.name.endsWith(PART_SUFFIX)) }
            ?.forEach { it.delete() }
    }

    /**
     * Swaps the staged jar in, if a swap is waiting. Called as the server stops and as the plugin
     * loads; never throws, because neither a shutdown nor a boot may be stopped by an update.
     *
     * The staged file is hashed again before anything moves: it has sat on disk since it was
     * verified, and the one thing worse than not updating is replacing a working plugin with a jar
     * nobody checked. A marker that names a file outside the staging directory, a target that is not
     * a jar, or a staged file that is gone or changed is dropped rather than trusted.
     */
    fun applyPendingSwap(dataFolder: File, logger: Logger): SwapOutcome {
        val swap = readSwap(dataFolder) ?: run {
            // Still clear a marker that exists but cannot be read, or it would be read again forever.
            clearSwap(dataFolder)

            return SwapOutcome.NONE
        }

        return try {
            applySwap(dataFolder, swap, logger)
        } catch (exception: Throwable) {
            logger.warning("Could not apply the staged Pano update: ${exception.javaClass.simpleName}: ${exception.message}")

            SwapOutcome.FAILED
        }
    }

    private fun applySwap(dataFolder: File, swap: PendingSwap, logger: Logger): SwapOutcome {
        val staged = swap.staged?.let { File(it) }
        val target = swap.target?.let { File(it) }
        val expected = swap.sha256?.trim()?.takeIf { it.isNotEmpty() }

        val stagingDirectory = stagingDirectory(dataFolder).canonicalFile

        if (staged == null || target == null || expected == null ||
            staged.canonicalFile.parentFile != stagingDirectory ||
            !target.name.lowercase().endsWith(".jar")
        ) {
            logger.warning("Dropped a pending Pano update whose marker did not describe a staged jar.")

            clearSwap(dataFolder)

            return SwapOutcome.DISCARDED
        }

        if (!staged.isFile) {
            clearSwap(dataFolder)

            if (target.isFile && Sha256.of(target).equals(expected, ignoreCase = true)) {
                return SwapOutcome.ALREADY_APPLIED
            }

            logger.warning("Dropped a pending Pano update: the staged jar ${staged.name} is gone.")

            return SwapOutcome.DISCARDED
        }

        if (!Sha256.of(staged).equals(expected, ignoreCase = true)) {
            logger.warning("Dropped a pending Pano update: ${staged.name} no longer matches its checksum.")

            staged.delete()
            clearSwap(dataFolder)

            return SwapOutcome.DISCARDED
        }

        val backup = File(target.parentFile, target.name + BACKUP_SUFFIX)

        if (target.exists()) {
            // A rename, not a copy: this is the moment the running jar's name is freed, and on a
            // POSIX filesystem the JVM keeps reading the file it already has open either way.
            move(target, backup)
        }

        try {
            move(staged, target)
        } catch (exception: Throwable) {
            // Put the old jar back under its own name, so a failed swap costs the update and never
            // the plugin.
            if (backup.isFile && !target.exists()) {
                try {
                    move(backup, target)
                } catch (_: Throwable) {
                    logger.severe("The Pano jar is at ${backup.absolutePath}; rename it back to ${target.name} by hand.")
                }
            }

            throw exception
        }

        clearSwap(dataFolder)

        logger.info("Swapped in the staged Pano update ${swap.version ?: staged.name}; the previous jar is ${backup.name}.")

        return SwapOutcome.APPLIED
    }

    /**
     * Keeps one previous generation of [jar] as `<name>.jar.bak`, replacing an older one.
     *
     * Beside the jar rather than in the data folder: that is where an admin who has to roll back by
     * hand will look, and no platform loads a file that does not end in `.jar`.
     */
    fun backup(jar: File): File {
        val backup = File(jar.parentFile, jar.name + BACKUP_SUFFIX)

        Files.copy(jar.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)

        return backup
    }

    /**
     * Moves [source] onto [target], replacing it, atomically where the filesystem allows it.
     *
     * Across filesystems an atomic rename is impossible, so the fallback copies to a temporary name
     * beside [target] first and renames that — the target is still never half-written, only the
     * source is left to delete afterwards.
     */
    fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            val temporary = File(target.parentFile, target.name + ".tmp")

            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)

            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            Files.deleteIfExists(source.toPath())
        }
    }

    /**
     * The jar [type] was loaded from, or null when it did not come from a jar file.
     *
     * The class's code source is the one answer every platform gives without an API of its own. A
     * development run from a classes directory, a nested jar or a remapped copy answers with
     * something that is not a plain `.jar` file, and null is the honest answer for all of them:
     * there is nothing there the plugin could safely replace.
     */
    fun jarOf(type: Class<*>): File? = try {
        type.protectionDomain?.codeSource?.location?.let { fileOf(it) }
            ?.takeIf { it.isFile && it.name.lowercase().endsWith(".jar") }
            ?.absoluteFile
    } catch (_: Throwable) {
        null
    }

    /** A `file:` URL, or the jar inside a `jar:file:...!/` URL, as a file. */
    fun fileOf(url: URL): File? {
        val raw = if (url.protocol == "jar") {
            URL(url.path.substringBefore("!/"))
        } else {
            url
        }

        if (raw.protocol != "file") {
            return null
        }

        return try {
            File(raw.toURI())
        } catch (_: Exception) {
            File(raw.path)
        }
    }

    /**
     * The jar in [pluginsDirectory] that [loaded] stands for.
     *
     * Usually [loaded] itself. Paper, though, may run a plugin from a remapped copy under
     * `plugins/.paper-remapped/`, and replacing that copy would achieve nothing — the original in
     * `plugins/` is what the next boot remaps again. So a jar outside [pluginsDirectory] is traced
     * back: first by its file name, then by the plugin name in the `plugin.yml` of each jar there.
     * Null when neither finds it.
     */
    fun installedJarFor(loaded: File?, pluginsDirectory: File, pluginName: String = PLUGIN_NAME): File? {
        val directory = pluginsDirectory.absoluteFile

        if (loaded != null && loaded.isFile && sameFile(loaded.absoluteFile.parentFile, directory)) {
            return loaded.absoluteFile
        }

        if (loaded != null) {
            val sibling = File(directory, loaded.name)

            if (sibling.isFile) {
                return sibling
            }
        }

        return findJarByPluginName(directory, pluginName)
    }

    /** The first jar directly in [directory] whose `plugin.yml` declares `name: <pluginName>`. */
    fun findJarByPluginName(directory: File, pluginName: String, descriptor: String = "plugin.yml"): File? {
        val pattern = Regex("^name:\\s*['\"]?${Regex.escape(pluginName)}['\"]?\\s*$", RegexOption.MULTILINE)

        return directory.listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".jar") }
            ?.sortedBy { it.name }
            ?.firstOrNull { jar ->
                try {
                    ZipFile(jar).use { zip ->
                        val entry = zip.getEntry(descriptor) ?: return@use false

                        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }

                        pattern.containsMatchIn(text)
                    }
                } catch (_: Exception) {
                    false
                }
            }
            ?.absoluteFile
    }

    private fun sameFile(a: File?, b: File?): Boolean {
        if (a == null || b == null) {
            return false
        }

        return try {
            a.canonicalFile == b.canonicalFile
        } catch (_: Exception) {
            a.absoluteFile == b.absoluteFile
        }
    }

    private val DOT_RUN = Regex("[.]{2,}")
}
