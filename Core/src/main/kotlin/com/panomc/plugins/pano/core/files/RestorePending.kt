package com.panomc.plugins.pano.core.files

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.plugins.pano.core.backup.snapshot.SnapshotRepository
import java.io.File
import java.util.zip.ZipFile
import java.util.logging.Logger

/**
 * A restore that has been asked for and happens on the next start.
 *
 * A node can restore a backup whenever it likes, because it does so with the server stopped. A
 * plugin cannot: the files it would be overwriting are the ones the JVM it is running in has
 * open, and half of them are worlds being written to as it works. So the plugin's answer to
 * `BACKUP_RESTORE` is "yes, next start" (AGENT.md 2.4.17 C) - a marker file here, and an
 * extraction at [apply] time, which every platform calls before its server has loaded anything.
 *
 * The marker lives in `.pano/`, which the file manager cannot see or write: it decides what
 * overwrites the whole server on the next boot, and a panel that could edit it directly would be
 * a panel that could restore anything it liked without asking.
 *
 * [take] is the other half. The restore happens long before there is a connection to report it
 * on, so the outcome is held here until the handshake asks for it.
 */
object RestorePending {
    /** The plugin's own state directory inside the server, denied to the file manager. */
    const val DIRECTORY = ".pano"

    const val FILE_NAME = "restore-pending.json"

    /** What a restore that has been armed but not yet applied looks like on disk. */
    data class Marker(
        val backupId: String? = null,
        val taskId: String? = null,
        val requestedBy: String? = null,
        val at: Long = 0
    )

    /** How an applied restore went, reported to Pano once there is a socket to report it on. */
    data class Outcome(
        val backupId: String,
        val taskId: String?,
        val ok: Boolean,
        val error: String?
    )

    // Written by apply() on the platform's own load thread, read by the connection handshake on a
    // Vert.x thread, so the two need the visibility @Volatile gives and nothing more: only one
    // side ever writes a value and only the other ever clears it.
    @Volatile
    private var outcome: Outcome? = null

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Where the marker lives inside [serverDirectory]. */
    fun markerFile(serverDirectory: File): File = File(File(serverDirectory, DIRECTORY), FILE_NAME)

    /** Records that [marker] should be applied on the next start. */
    fun arm(serverDirectory: File, marker: Marker) {
        val file = markerFile(serverDirectory)

        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(marker))
    }

    /** The marker as it is on disk, or null when there is none or it cannot be read. */
    fun read(serverDirectory: File): Marker? {
        val file = markerFile(serverDirectory)

        if (!file.isFile) {
            return null
        }

        return try {
            gson.fromJson(file.readText(), Marker::class.java)?.takeIf { !it.backupId.isNullOrBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Applies a pending restore, if there is one, and remembers how it went.
     *
     * Called from every platform's load step - `onLoad` on Bukkit, which runs before a single
     * world is read, and the first init callback on the proxies and on Fabric. Returns what
     * happened, or null when there was nothing to do, and never throws: a plugin that stopped a
     * server from booting because a backup was corrupt would be worse than the corrupt backup.
     *
     * The marker is removed whichever way it went. A restore that failed once will fail the same
     * way on every boot, and a server stuck in a restore loop is a server nobody can log into to
     * fix it.
     */
    fun apply(serverDirectory: File, logger: Logger): Outcome? {
        val marker = read(serverDirectory) ?: run {
            // Still clear a marker that could not be read, for the same reason as above.
            markerFile(serverDirectory).delete()

            return null
        }

        val backupId = marker.backupId.orEmpty()

        val result = try {
            restore(serverDirectory, backupId)

            logger.warning("Restored the backup $backupId over this server before it started.")

            Outcome(backupId, marker.taskId, ok = true, error = null)
        } catch (exception: Throwable) {
            logger.severe("Could not restore the backup $backupId: ${exception.javaClass.simpleName}: ${exception.message}")

            Outcome(backupId, marker.taskId, ok = false, error = exception.message ?: exception.javaClass.simpleName)
        } finally {
            markerFile(serverDirectory).delete()
        }

        outcome = result

        return result
    }

    /** The outcome of the restore applied on this start, once and once only. */
    fun take(): Outcome? {
        val taken = outcome

        outcome = null

        return taken
    }

    /** Forgets anything remembered from a previous start; used by the tests. */
    internal fun forget() {
        outcome = null
    }

    /**
     * Puts the backup [backupId] back over [serverDirectory], whichever kind it is.
     *
     * A `FULL` backup is its `<id>.zip`; anything else must be a snapshot in `backups/repo/`,
     * which [SnapshotRepository.restore] checks chunk by chunk before it touches a file. Both
     * replace every world directory the backup holds wholesale rather than overlaying it: a world
     * restored on top of itself keeps every region file generated after the backup, and a world
     * that is half one day and half another is a corrupted world, not a restored one. Everything
     * else keeps the overlay behaviour it always had.
     */
    private fun restore(serverDirectory: File, backupId: String) {
        require(PathSafety.isSafeSegment(backupId)) { "That backup id is not a usable name." }

        val backups = File(serverDirectory, BACKUPS_DIRECTORY)
        val archive = File(backups, "$backupId$ARCHIVE_SUFFIX")

        if (archive.isFile) {
            restoreArchive(serverDirectory, archive)

            return
        }

        val repository = SnapshotRepository(File(backups, BackupService.REPOSITORY_DIRECTORY))

        require(repository.hasSnapshot(backupId)) { "That backup is not on this server any more." }

        // The exclusion is about this plugin's own credentials, exactly as for an archive below.
        repository.locked { repository.restore(serverDirectory, backupId) { relative -> isPanoOwned(relative) } }
    }

    private fun restoreArchive(serverDirectory: File, archive: File) {
        require(ZipTool.isZip(archive)) { "That backup is not on this server any more." }

        // Read through once before anything is deleted: an archive whose directory cannot be read
        // fails here, and the worlds it holds are the ones about to be emptied. The declared sizes
        // are checked against the extractor's ceiling for the same reason - running into it
        // halfway through would leave the worlds already cleared.
        val names = ZipFile(archive).use { zip ->
            var declared = 0L

            zip.entries().toList().map { entry ->
                if (entry.size > 0) {
                    declared += entry.size
                }

                entry.name
            }.also {
                check(declared <= ZipTool.MAX_TOTAL_BYTES) { "This archive unpacks to more than ${ZipTool.MAX_TOTAL_BYTES} bytes." }
            }
        }

        WorldDirectories.worldDirectoriesIn(names).forEach { world ->
            WorldDirectories.clearContents(serverDirectory, world) { relative -> isPanoOwned(relative) }
        }

        // Zip-slip guarded by the extractor itself; the exclusion here is about this plugin's own
        // credentials, which an archive taken by an older version may still carry and which must
        // never be replaced by whatever this server was connected to when the backup was made.
        ZipTool.extract(serverDirectory, archive, serverDirectory) { relative -> isPanoOwned(relative) }
    }

    /** Whether [relative] is part of this plugin's own state rather than the server's. */
    private fun isPanoOwned(relative: String): Boolean {
        val lower = ServerFileDenylist.normalise(relative).lowercase()

        return PANO_OWNED_PREFIXES.any { lower == it || lower.startsWith("$it/") }
    }

    private val PANO_OWNED_PREFIXES = listOf("plugins/pano", "config/pano", DIRECTORY)

    /** Where a plugin's backups live; inside the server, because it has nowhere else. */
    const val BACKUPS_DIRECTORY = "backups"

    const val ARCHIVE_SUFFIX = ".zip"
}
