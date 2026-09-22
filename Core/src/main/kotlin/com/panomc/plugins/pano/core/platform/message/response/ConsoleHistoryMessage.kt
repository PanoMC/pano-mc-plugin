package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * `CONSOLE_HISTORY` - Pano asking for the scrollback this server wrote to its log files.
 *
 * Sent when a panel user opens a console with nothing in Pano's own ring buffer, and again for
 * every "load older" click with [skip] set to how much of the history the panel is already
 * showing. Unlike every other inbound push this one expects an answer, so it carries the
 * [eventId] the plugin has to echo on its `CONSOLE_HISTORY_RESULT`.
 *
 * Every field is nullable because a message from a newer Pano is not something an older plugin
 * may choke on: a missing [limit] means "a screenful", a missing [skip] means "the newest lines".
 *
 * [query] (AGENT.md 2.4.20, additive on protocol 2) turns the request into a search of the lines
 * "load older" could reach: only lines containing it, case-insensitively, come back, and [skip]
 * then counts matches. A missing or blank one is the plain history request.
 */
data class ConsoleHistoryMessage(
    val eventId: String? = null,
    val limit: Int? = null,
    val skip: Int? = null,
    val query: String? = null
) : PlatformMessage
