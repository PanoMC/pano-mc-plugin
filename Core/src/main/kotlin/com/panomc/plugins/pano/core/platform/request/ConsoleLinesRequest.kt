package com.panomc.plugins.pano.core.platform.request

import com.fasterxml.jackson.annotation.JsonInclude
import com.panomc.plugins.pano.core.platform.PlatformRequest

/**
 * `CONSOLE_LINES` - a batch of captured console lines, pushed fire-and-forget to Pano.
 *
 * Field names are deliberately one character long: this is the highest-frequency message the
 * plugin sends (up to 500 lines a second), so the envelope is kept as small as the console
 * contract in AGENT.md 2.4.1 specifies - `t` epoch millis, `l` level, `m` text.
 *
 * [dropped] carries the number of lines thrown away (oldest first) since the previous batch,
 * either because the per-second cap was hit or because the local queue overflowed. Pano renders
 * it as a "N lines dropped" marker.
 *
 * A line's `c` is its colour spans, `[[start, end, color, flags], ...]` (AGENT.md 2.4.21), and is
 * left off the wire entirely - not sent as null - for a line without colour, which is most of
 * them: it would be four bytes of nothing on the busiest message there is.
 */
data class ConsoleLinesRequest(
    val lines: List<Line>,
    val dropped: Long
) : PlatformRequest() {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Line(
        val t: Long,
        val l: String,
        val m: String,
        val c: List<List<Any?>>? = null
    )
}
