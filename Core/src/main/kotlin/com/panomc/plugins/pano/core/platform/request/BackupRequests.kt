package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest
import io.vertx.core.json.JsonObject

/**
 * `BACKUP_CREATED` - a backup that finished, so Pano can fill in the row it will list it from.
 *
 * Pushed rather than returned, exactly as the node daemon does it: the request that started the
 * backup was answered the moment the task was accepted, minutes before there was an archive to
 * describe.
 *
 * [backup] carries `id`, `name`, `sizeBytes`, `sha256`, `createdAt` and, since backups v2, `mode`,
 * `scope`, `fileCount` and `storedBytes`. For a `SNAPSHOT`, `sizeBytes` is the logical size of
 * every file in it, `sha256` is null (there is no single archive to hash) and `storedBytes` is
 * what this snapshot actually added to the repository.
 */
class BackupCreatedRequest(private val backup: JsonObject) : PlatformRequest() {
    override fun encode(): String = JsonObject()
        .put("event", eventName)
        .put("eventId", eventId.toString())
        .put("backup", backup)
        .encode()
}

/**
 * `BACKUP_RESTORED` - how the restore that was armed for this start actually went.
 *
 * Sent right after `ON_SERVER_CONNECT`, because that is the first moment there is anywhere to
 * send it: the restore itself happened before the server had loaded a world, let alone opened a
 * socket. [taskId] is the one Pano left in `PENDING_RESTART`, which this is what finishes.
 */
data class BackupRestoredRequest(
    val backupId: String,
    val taskId: String?,
    val ok: Boolean,
    val error: String?
) : PlatformRequest()
