package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest
import java.util.UUID

/**
 * `CONSOLE_HISTORY_RESULT` - one page of log-file scrollback, answering a `CONSOLE_HISTORY`.
 *
 * The [eventId] is the one Pano asked with, not a new one: this is a reply, and Pano matches it
 * against the request it is still holding open.
 *
 * [lines] are oldest first and use the same one-character field names as `CONSOLE_LINES`, so the
 * panel renders history and live output through one code path. [hasMore] tells the panel whether
 * "load older" still has anything to fetch. [disabled] is set when the operator has switched the
 * console off in config.conf (`console.enabled = false`), which is a different answer from "this
 * server has no history": the panel can then say so instead of pretending the log is empty.
 */
class ConsoleHistoryResultRequest(
    eventId: UUID,
    val lines: List<Line>,
    val hasMore: Boolean,
    val disabled: Boolean = false
) : PlatformRequest(eventId) {
    data class Line(
        val t: Long,
        val l: String,
        val m: String
    )
}
