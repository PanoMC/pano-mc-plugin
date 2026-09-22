package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest

/**
 * `TASK_PROGRESS` - one frame of how a long job is going (AGENT.md 2.4.4).
 *
 * The node daemon's frame minus its `serverUuid`: a message arriving down this socket is already
 * about this server, and Pano knows which one that is. [status] is `RUNNING`, `DONE` or `FAILED`,
 * the last two being terminal.
 */
data class TaskProgressRequest(
    val taskId: String,
    val kind: String,
    val status: String,
    val percent: Int,
    val message: String?,
    val error: String?,
    /** Set on the terminal frame of an install: the jar is in place but only loads on a restart. */
    val restartRequired: Boolean? = null
) : PlatformRequest()
