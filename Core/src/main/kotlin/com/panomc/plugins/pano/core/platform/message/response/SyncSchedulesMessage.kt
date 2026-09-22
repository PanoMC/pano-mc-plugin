package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `SYNC_SCHEDULES` - the complete schedule set for this server, replacing whatever it was holding
 * (AGENT.md 2.4.6).
 *
 * Always the whole set. Pano sends it on every change and again after every connect, so this side
 * never has to reconcile anything: what arrives is the truth, and what it had is discarded.
 */
data class SyncSchedulesMessage(
    val eventId: String? = null,
    val schedules: List<SyncScheduleEntry>? = null
) : PlatformMessage

/**
 * One schedule: when it runs, in which zone, and what it does when it does.
 *
 * [uuid] is Pano's id for the schedule and is what a run is reported against. [warnMinutes] is
 * the countdown before a power step, zero for none.
 */
data class SyncScheduleEntry(
    val uuid: String? = null,
    val name: String? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val enabled: Boolean? = null,
    val warnMinutes: Int? = null,
    val tasks: List<SyncScheduleTaskEntry>? = null
)

/** One step of a schedule: `POWER`, `COMMAND` or `BACKUP`, with whatever that kind needs. */
data class SyncScheduleTaskEntry(
    val kind: String? = null,
    val payload: Map<String, Any?>? = null
)
