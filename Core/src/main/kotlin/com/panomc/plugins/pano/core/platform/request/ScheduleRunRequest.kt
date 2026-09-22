package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest

/**
 * `SCHEDULE_RUN` - how one scheduled run went, so Pano can show it and work out the next one.
 *
 * [ok] is the run as a whole, which fails at the first step that does: "back up, then restart"
 * must not restart when the backup failed, and Pano needs to be able to say which half happened.
 * That is what [tasks] is for - one entry per step that was actually reached, in order.
 */
data class ScheduleRunRequest(
    val scheduleId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val ok: Boolean,
    val error: String?,
    val tasks: List<TaskResult>
) : PlatformRequest() {
    data class TaskResult(
        val kind: String?,
        val ok: Boolean,
        val error: String?
    )
}
