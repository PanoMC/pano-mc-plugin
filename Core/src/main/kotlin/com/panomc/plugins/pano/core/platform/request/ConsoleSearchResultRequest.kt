package com.panomc.plugins.pano.core.platform.request

import com.fasterxml.jackson.annotation.JsonInclude
import com.panomc.plugins.pano.core.platform.PlatformRequest
import java.util.UUID

/**
 * `CONSOLE_SEARCH_RESULT` - one slice of a deep console search, answering a `CONSOLE_SEARCH`.
 *
 * The [eventId] is the one Pano asked with, exactly as on `CONSOLE_HISTORY_RESULT`.
 *
 * [lines] are the matches found by this call, newest first, each with the log file it came from
 * in `f`; `t`, `l` and `m` are the same as on `CONSOLE_HISTORY_RESULT`, and `c` is left off
 * because a line read back from a file has no colour. [cursor] is what the next call passes to
 * carry on, null once [done]. [scannedFiles] of [totalFiles] is the search's progress so far,
 * [scannedBytes] what this call read, and [capped] says a file was cut short by the per-file size
 * cap. [ok] false comes with an [error] code: `BAD_QUERY`, `BAD_CURSOR`, `DISABLED` (the operator
 * has switched the console off, which also sets [disabled]) or `READ_FAILED`.
 */
class ConsoleSearchResultRequest(
    eventId: UUID,
    val ok: Boolean,
    val error: String?,
    val lines: List<Line>,
    val cursor: String?,
    val done: Boolean,
    val scannedFiles: Int,
    val totalFiles: Int,
    val scannedBytes: Long,
    val capped: Boolean,
    val disabled: Boolean = false
) : PlatformRequest(eventId) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Line(
        val t: Long,
        val l: String,
        val m: String,
        val f: String,
        val c: List<List<Any?>>? = null
    )

    companion object {
        /** An answer carrying nothing but an error code. */
        fun failure(eventId: UUID, error: String, disabled: Boolean = false) =
            ConsoleSearchResultRequest(eventId, false, error, emptyList(), null, true, 0, 0, 0L, false, disabled)
    }
}
