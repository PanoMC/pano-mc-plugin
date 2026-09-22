package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `CONSOLE_SEARCH` - Pano asking for one slice of a search across every log file this server has.
 *
 * Unlike the [query] of `CONSOLE_HISTORY`, which only looks as far back as "load older" can page,
 * this walks `latest.log` and every rotation, newest first, and is paged by an opaque [cursor]
 * the previous answer handed out (null for the first slice). Like `CONSOLE_HISTORY` it expects an
 * answer, `CONSOLE_SEARCH_RESULT`, carrying the same [eventId].
 *
 * Every field is nullable so an odd message is answered rather than choked on: a missing [limit]
 * or [budgetMs] takes the default, and a missing or blank [query] is answered with `BAD_QUERY`.
 */
data class ConsoleSearchMessage(
    val eventId: String? = null,
    val query: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
    val budgetMs: Long? = null
) : PlatformMessage
