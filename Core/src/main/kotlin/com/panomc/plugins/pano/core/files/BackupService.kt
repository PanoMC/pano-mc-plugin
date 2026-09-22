package com.panomc.plugins.pano.core.files

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.plugins.pano.core.backup.snapshot.CorruptChunkException
import com.panomc.plugins.pano.core.backup.snapshot.RepoBusyException
import com.panomc.plugins.pano.core.backup.snapshot.SnapshotManifest
import com.panomc.plugins.pano.core.backup.snapshot.SnapshotRepository
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.message.response.BackupCreateMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupDeleteMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupRestoreMessage
import com.panomc.plugins.pano.core.platform.request.BackupCreatedRequest
import com.panomc.plugins.pano.core.task.TaskReporter
import com.panomc.plugins.pano.core.util.Sha256
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.util.logging.Logger

/**
 * What `<backupId>.json` holds next to each backup, and what `BACKUP_LIST` answers with.
 *
 * Metas written before backups v2 have none of the last four fields; they read back as the
 * `FULL`/`ALL` backups they were, with the counts unknown. A `SNAPSHOT` has no archive, so no
 * [sha256], and its [sizeBytes] is the logical size of the files in it rather than anything on
 * disk - [storedBytes] is what it actually cost.
 */
data class BackupMeta(
    val id: String = "",
    val name: String = "",
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val createdAt: Long = 0,
    val mode: String = BackupScope.MODE_FULL,
    val scope: String = BackupScope.SCOPE_ALL,
    val fileCount: Long? = null,
    val storedBytes: Long? = null
)

/**
 * Takes copies of this server's directory, from inside the server.
 *
 * Two things make this more than "zip a folder". A running Minecraft server holds its world in
 * memory and writes it out when it feels like it, so a copy taken mid-tick is a copy of a world
 * that was never consistent - pausing autosaving and flushing first is what turns it into one
 * that was, and that is the one part of this that has to happen on the server's own thread. And a
 * restore overwrites the very files this JVM has open, so it never happens here at all: it is
 * armed for the next start (see [RestorePending]) and Pano is told `mode: "next-start"`.
 *
 * Backups live in `<server>/backups/`, which is where the node daemon's `@backup/<id>` download
 * path points too, and which every backup excludes so an archive can never swallow its
 * predecessors. A `FULL` backup is `<id>.zip` there; a `SNAPSHOT` is a manifest in the
 * deduplicating repository under `backups/repo/` ([SnapshotRepository]), with no archive of its
 * own. Both get the same `<id>.json` meta, which is what lists them side by side.
 */
class BackupService(
    private val pluginMain: PanoPluginMain,
    private val reporter: TaskReporter,
    private val send: (PlatformRequest) -> Unit,
    private val logger: Logger
) {
    /** `<server>/backups`, created on demand. */
    fun directory(): File = File(pluginMain.getServerDirectory().absoluteFile, RestorePending.BACKUPS_DIRECTORY)

    /** The snapshot repository, `<server>/backups/repo`. It only exists once a snapshot has been taken. */
    fun repository(): SnapshotRepository = SnapshotRepository(File(directory(), REPOSITORY_DIRECTORY))

    /**
     * Creates a backup and announces it, reporting progress as a task.
     *
     * Blocking, and deliberately so: it is called from the file agent's single-operation lock on
     * an IO dispatcher, which is what stops two backups and a file extraction from running over
     * each other.
     *
     * The mode and scope are settled before anything is paused, so a request that cannot be
     * carried out (`INVALID_SCOPE`, `NO_WORLDS`) fails without the server ever noticing.
     */
    fun create(message: BackupCreateMessage) {
        val taskId = message.taskId?.takeIf { it.isNotBlank() }
        val backupId = message.backupId?.takeIf { it.isNotBlank() }

        if (taskId == null || backupId == null) {
            logger.warning("Ignoring a BACKUP_CREATE with no task id or backup id.")

            return
        }

        if (!PathSafety.isSafeSegment(backupId)) {
            reporter.failed(taskId, TaskReporter.KIND_BACKUP, "The backup id is not a usable name.")

            return
        }

        val root = pluginMain.getServerDirectory().absoluteFile
        val exclude = BackupExcludeMatcher(message.exclude)

        // The backups directory is excluded whatever Pano asked for: it is inside the directory
        // being backed up, and a backup that contained the previous ones would double in size
        // every night until the disk ran out.
        val skip: (String) -> Boolean = { path -> isBackupsDirectory(path) || exclude.matches(path) }

        val plan = try {
            val mode = BackupScope.mode(message.mode)
            val scope = BackupScope.scope(message.scope)

            Plan(
                taskId = taskId,
                backupId = backupId,
                name = message.name?.takeIf { it.isNotBlank() } ?: backupId,
                mode = mode,
                scope = scope,
                include = if (scope == BackupScope.SCOPE_CUSTOM) {
                    message.include.orEmpty().map { ServerFileDenylist.normalise(it.trim()) }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                },
                exclude = exclude.patterns,
                roots = BackupScope.roots(root, scope, message.include, skip),
                skip = skip
            )
        } catch (exception: BackupScopeException) {
            logger.warning("Backup $backupId was refused: ${exception.message}")

            reporter.failed(taskId, TaskReporter.KIND_BACKUP, exception.message ?: BackupScope.ERROR_INVALID_SCOPE)

            return
        }

        val meta = try {
            if (plan.mode == BackupScope.MODE_SNAPSHOT) createSnapshot(root, plan) else createFull(root, plan)
        } catch (exception: Throwable) {
            logger.warning("Backup $backupId failed: ${exception.javaClass.simpleName}: ${exception.message}")

            reporter.failed(taskId, TaskReporter.KIND_BACKUP, exception.message ?: exception.javaClass.simpleName)

            return
        }

        writeMeta(meta.first)

        try {
            send(BackupCreatedRequest(toJson(meta.first)))
        } catch (exception: Throwable) {
            logger.warning("Backed up $backupId but could not tell Pano: ${exception.javaClass.simpleName}: ${exception.message}")
        }

        reporter.done(taskId, TaskReporter.KIND_BACKUP, meta.second)
    }

    /** One BACKUP_CREATE with everything about it settled. */
    private class Plan(
        val taskId: String,
        val backupId: String,
        val name: String,
        val mode: String,
        val scope: String,
        val include: List<String>,
        val exclude: List<String>,
        val roots: List<String>,
        val skip: (String) -> Boolean
    )

    /** Today's backup: one self-contained `<id>.zip` of [Plan.roots]. Returns the meta and the last line. */
    private fun createFull(root: File, plan: Plan): Pair<BackupMeta, String> {
        val archive = File(directory(), "${plan.backupId}${RestorePending.ARCHIVE_SUFFIX}")

        var paused = false
        var files = 0L

        try {
            reporter.running(plan.taskId, TaskReporter.KIND_BACKUP, PREPARE_PERCENT, "Preparing")

            paused = pauseWorldSaves()

            reporter.running(plan.taskId, TaskReporter.KIND_BACKUP, ARCHIVE_PERCENT, "Archiving the server directory")

            archive.parentFile?.mkdirs()

            ZipTool.archive(root, plan.roots, archive, onFile = { files++ }, exclude = plan.skip)
        } catch (exception: Throwable) {
            archive.delete()

            throw exception
        } finally {
            if (paused) {
                resumeWorldSaves()
            }
        }

        reporter.running(plan.taskId, TaskReporter.KIND_BACKUP, HASH_PERCENT, "Checksumming")

        val size = archive.length()

        val meta = BackupMeta(
            id = plan.backupId,
            name = plan.name,
            sizeBytes = size,
            sha256 = Sha256.of(archive),
            createdAt = System.currentTimeMillis(),
            mode = BackupScope.MODE_FULL,
            scope = plan.scope,
            fileCount = files,
            storedBytes = size
        )

        return meta to "Backed up $size bytes"
    }

    /**
     * An incremental snapshot into `backups/repo/`: only chunks the repository does not already
     * have are written. Holds the repository lock throughout, so a second snapshot, a delete or a
     * restore arriving meanwhile fails with `REPO_BUSY` instead of racing this one.
     */
    private fun createSnapshot(root: File, plan: Plan): Pair<BackupMeta, String> {
        val repository = repository()
        val createdAt = System.currentTimeMillis()

        var paused = false

        val result = try {
            reporter.running(plan.taskId, TaskReporter.KIND_BACKUP, PREPARE_PERCENT, "Preparing")

            paused = pauseWorldSaves()

            repository.locked {
                reporter.running(plan.taskId, TaskReporter.KIND_BACKUP, SCAN_PERCENT, "Scanning files")

                val scanned = BackupScope.scan(root, plan.roots, plan.skip)

                val template = SnapshotManifest(
                    id = plan.backupId,
                    name = plan.name,
                    createdAt = createdAt,
                    scope = plan.scope,
                    include = plan.include,
                    exclude = plan.exclude,
                    roots = if (plan.scope == BackupScope.SCOPE_ALL) BackupScope.topLevel(root) else plan.roots,
                    entries = emptyList()
                )

                repository.create(
                    root,
                    template,
                    scanned,
                    progress = { processed, total, done, count ->
                        val share = if (total <= 0) 1.0 else processed.toDouble() / total

                        reporter.running(
                            plan.taskId,
                            TaskReporter.KIND_BACKUP,
                            SCAN_PERCENT + (share * (READ_DONE_PERCENT - SCAN_PERCENT)).toInt(),
                            "Backing up $done/$count files"
                        )
                    },
                    beforeManifest = {
                        reporter.running(plan.taskId, TaskReporter.KIND_BACKUP, MANIFEST_PERCENT, "Writing snapshot")
                    }
                )
            }
        } finally {
            if (paused) {
                resumeWorldSaves()
            }
        }

        val meta = BackupMeta(
            id = plan.backupId,
            name = plan.name,
            sizeBytes = result.logicalBytes,
            sha256 = null,
            createdAt = createdAt,
            mode = BackupScope.MODE_SNAPSHOT,
            scope = plan.scope,
            fileCount = result.fileCount,
            storedBytes = result.storedBytes
        )

        return meta to "Snapshot: ${result.newChunks} new of ${result.totalChunks} (${result.fileCount} files)"
    }

    /** Every backup this server holds, newest first. */
    fun list(): JsonObject {
        val backups = JsonArray()

        readAll().forEach { meta -> backups.add(toJson(meta)) }

        return FileService.success().put("backups", backups)
    }

    /** Removes one backup and its meta. */
    fun delete(message: BackupDeleteMessage): JsonObject {
        val backupId = message.backupId?.takeIf { it.isNotBlank() }
            ?: return FileService.failure(FileService.ERROR_NOT_FOUND)

        if (!PathSafety.isSafeSegment(backupId)) {
            return FileService.failure(FileService.ERROR_PATH_DENIED)
        }

        val repository = repository()

        if (repository.hasSnapshot(backupId)) {
            // Manifest and meta first, then the collection that frees whatever only this snapshot
            // used - all under the lock, so a snapshot being taken right now cannot have its brand
            // new chunks collected as orphans.
            try {
                repository.locked {
                    val collected = try {
                        repository.delete(backupId)
                    } catch (exception: Exception) {
                        // The snapshot is gone either way; what is left is chunks nobody frees
                        // until a later collection succeeds, which is untidy rather than wrong.
                        logger.warning("Deleted the snapshot $backupId but could not collect its chunks: ${exception.message}")

                        null
                    } finally {
                        metaFile(backupId).delete()
                    }

                    collected?.let { logger.info("Collected ${it.removedChunks} unused chunk(s), ${it.removedBytes} bytes.") }
                }
            } catch (_: RepoBusyException) {
                return FileService.failure(SnapshotRepository.ERROR_REPO_BUSY)
            }
        } else {
            archiveFile(backupId).delete()
            metaFile(backupId).delete()
        }

        logger.info("Deleted the backup $backupId.")

        return FileService.success()
    }

    /**
     * Arms a restore for the next start rather than doing it now.
     *
     * A plugin is inside the process whose files a restore replaces; the only honest answer is
     * the one Pano's panel is built to show, `mode: "next-start"` (AGENT.md 2.4.17 C).
     */
    fun restore(message: BackupRestoreMessage): JsonObject {
        val backupId = message.backupId?.takeIf { it.isNotBlank() }
            ?: return FileService.failure(FileService.ERROR_NOT_FOUND)

        if (!PathSafety.isSafeSegment(backupId)) {
            return FileService.failure(FileService.ERROR_PATH_DENIED)
        }

        val archive = archiveFile(backupId)

        if (!archive.isFile || !ZipTool.isZip(archive)) {
            val repository = repository()

            if (!repository.hasSnapshot(backupId)) {
                return FileService.failure(FileService.ERROR_NOT_FOUND)
            }

            // Only a cheap presence check now; the restore itself reads and hashes every chunk
            // before touching anything. A chunk that is already missing is worth saying so while
            // somebody is still looking at the panel, rather than after a restart.
            val missing = try {
                repository.firstMissingChunk(repository.readManifest(backupId))
            } catch (exception: Exception) {
                logger.warning("Could not read the snapshot $backupId: ${exception.message}")

                return FileService.failure(FileService.ERROR_NOT_FOUND)
            }

            if (missing != null) {
                return FileService.failure(CorruptChunkException(missing).message ?: SnapshotRepository.ERROR_CORRUPT_CHUNK)
            }
        }

        return try {
            RestorePending.arm(
                pluginMain.getServerDirectory().absoluteFile,
                RestorePending.Marker(
                    backupId = backupId,
                    taskId = message.taskId,
                    requestedBy = message.requestedBy,
                    at = System.currentTimeMillis()
                )
            )

            logger.warning("Pano armed a restore of the backup $backupId; it is applied on the next start.")

            FileService.success().put("mode", MODE_NEXT_START)
        } catch (exception: Throwable) {
            logger.warning("Could not arm a restore of $backupId: ${exception.javaClass.simpleName}: ${exception.message}")

            FileService.failure(exception.message ?: exception.javaClass.simpleName)
        }
    }

    /**
     * What a `@backup/<id>` transfer path downloads.
     *
     * The one door to a backup that is not an ordinary file path, matching the node daemon's, so
     * Pano's download button works the same way whichever side is answering it. A `FULL` backup
     * is its archive; a `SNAPSHOT` has none, so it is a ZIP built on the fly from its manifest and
     * chunks while it is being sent.
     */
    fun resolveVirtual(path: String): VirtualSource? {
        if (!path.startsWith(VIRTUAL_PREFIX)) {
            return null
        }

        val backupId = path.removePrefix(VIRTUAL_PREFIX)

        if (!PathSafety.isSafeSegment(backupId)) {
            return null
        }

        archiveFile(backupId).takeIf { it.isFile }?.let { return VirtualSource.OfFile(it) }

        val repository = repository()

        if (!repository.hasSnapshot(backupId)) {
            return null
        }

        val size = try {
            repository.readManifest(backupId).entries.sumOf { it.size }
        } catch (exception: Exception) {
            logger.warning("Could not read the snapshot $backupId: ${exception.message}")

            return null
        }

        return VirtualSource.Streamed("$backupId${RestorePending.ARCHIVE_SUFFIX}", size) { output ->
            repository.writeZip(backupId, output, TransferService.MAX_TRANSFER_BYTES)
        }
    }

    /**
     * Tells the server to stop writing its worlds, and gives the flush a moment to land.
     *
     * Best effort by design: both proxies have no world at all and say so by returning false, and
     * a backup of a server that could not pause is still a backup - it is simply a backup of
     * whatever was on disk, which is what every naive tool gives you anyway.
     */
    private fun pauseWorldSaves(): Boolean {
        val paused = try {
            pluginMain.setWorldSaving(false)
        } catch (exception: Throwable) {
            logger.warning("Could not pause world saving: ${exception.javaClass.simpleName}: ${exception.message}")

            false
        }

        if (!paused) {
            return false
        }

        // No completion to wait on: the save is queued onto the server's main thread and reports
        // nothing back, so the only thing to do is give it a moment before reading the same files.
        try {
            Thread.sleep(SAVE_FLUSH_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        return true
    }

    private fun resumeWorldSaves() {
        try {
            pluginMain.setWorldSaving(true)
        } catch (exception: Throwable) {
            // Loud, because a server left with autosaving off loses everything on the next crash.
            logger.severe("Could not resume world saving after a backup: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    private fun readAll(): List<BackupMeta> = directory().listFiles()
        ?.filter { it.isFile && it.name.endsWith(META_SUFFIX) }
        ?.mapNotNull { file ->
            try {
                gson.fromJson(file.readText(), BackupMeta::class.java)?.takeIf { it.id.isNotBlank() }
            } catch (exception: Exception) {
                logger.warning("Could not read ${file.absolutePath}: ${exception.message}")

                null
            }
        }
        .orEmpty()
        .sortedByDescending { it.createdAt }

    private fun writeMeta(meta: BackupMeta) {
        val file = metaFile(meta.id)

        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(meta))
    }

    private fun archiveFile(backupId: String): File =
        PathSafety.resolveUnder(directory(), "$backupId${RestorePending.ARCHIVE_SUFFIX}")

    private fun metaFile(backupId: String): File = PathSafety.resolveUnder(directory(), "$backupId$META_SUFFIX")

    private fun isBackupsDirectory(path: String): Boolean {
        val normalised = ServerFileDenylist.normalise(path)

        return normalised == RestorePending.BACKUPS_DIRECTORY ||
            normalised.startsWith("${RestorePending.BACKUPS_DIRECTORY}/")
    }

    private fun toJson(meta: BackupMeta): JsonObject = JsonObject()
        .put("id", meta.id)
        .put("name", meta.name)
        .put("sizeBytes", meta.sizeBytes)
        .put("sha256", meta.sha256)
        .put("createdAt", meta.createdAt)
        .put("mode", meta.mode)
        .put("scope", meta.scope)
        .put("fileCount", meta.fileCount)
        .put("storedBytes", meta.storedBytes)

    companion object {
        const val META_SUFFIX = ".json"

        /** Transfer paths that mean "a backup", not "a file inside the server". */
        const val VIRTUAL_PREFIX = "@backup/"

        /** The only restore a plugin can honestly offer. */
        const val MODE_NEXT_START = "next-start"

        /** `<server>/backups/repo`: where snapshots live. Already inside the always-excluded `backups/`. */
        const val REPOSITORY_DIRECTORY = "repo"

        private const val PREPARE_PERCENT = 5
        private const val ARCHIVE_PERCENT = 15
        private const val HASH_PERCENT = 85

        private const val SCAN_PERCENT = 10
        private const val READ_DONE_PERCENT = 85
        private const val MANIFEST_PERCENT = 90

        /** How long the server is given to finish writing after autosaving is paused. */
        private const val SAVE_FLUSH_MILLIS = 2_000L

        // serializeNulls: a snapshot's meta says `"sha256": null` rather than leaving the key out,
        // so the file on disk has exactly the fields BACKUP_CREATED carries.
        private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    }
}
