package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `BACKUP_CREATE` - take a copy of this server's directory (AGENT.md 2.4.4).
 *
 * [backupId] is Pano's, not this side's: Pano writes the row before the archive exists so the
 * panel can show a backup in progress, and `BACKUP_CREATED` fills the rest in afterwards.
 * [exclude] overrides the default exclusions; an absent or empty list means the defaults, never
 * "exclude nothing". Pano sends the complete list (its defaults plus the user's extras) whenever
 * it sends one at all.
 *
 * Backups v2 added three optional fields, and leaving all of them out is exactly the old request:
 * [mode] is `FULL` (one self-contained zip, the default) or `SNAPSHOT` (incremental, into the
 * deduplicating repository in `backups/repo/`); [scope] is `ALL` (the default), `WORLDS` or
 * `CUSTOM`; and [include] lists the server-relative paths a `CUSTOM` backup covers.
 */
data class BackupCreateMessage(
    val eventId: String? = null,
    val taskId: String? = null,
    val backupId: String? = null,
    val name: String? = null,
    val exclude: List<String>? = null,
    val mode: String? = null,
    val scope: String? = null,
    val include: List<String>? = null
) : PlatformMessage

/** `BACKUP_LIST` - what backups this server holds. A request; the reply carries them. */
data class BackupListMessage(
    val eventId: String? = null
) : PlatformMessage

/** `BACKUP_DELETE` - remove one backup. A request, so retention can tell whether it worked. */
data class BackupDeleteMessage(
    val eventId: String? = null,
    val backupId: String? = null
) : PlatformMessage

/**
 * `BACKUP_RESTORE` - put a backup back.
 *
 * Answered with `mode: "next-start"` rather than done on the spot: the files a restore replaces
 * are the ones this JVM has open. [requestedBy] is recorded in the marker so the server log names
 * whoever armed it, even if Pano is unreachable by the time it actually happens.
 */
data class BackupRestoreMessage(
    val eventId: String? = null,
    val backupId: String? = null,
    val taskId: String? = null,
    val requestedBy: String? = null
) : PlatformMessage
