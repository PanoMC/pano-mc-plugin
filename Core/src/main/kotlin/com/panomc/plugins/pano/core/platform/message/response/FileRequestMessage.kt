package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessage

/**
 * Every `FILE_*` request in one shape (AGENT.md 2.4.4).
 *
 * A class per operation would be ten classes differing by one field each, and Gson reads an
 * absent field as null regardless - so the operation is the message *name*, which is what the
 * handler that receives this is called after, and the fields it does not use simply stay null.
 * This is the one inbound message whose class name is not the event name; nothing depends on it
 * being, because an inbound push is routed by handler name.
 *
 * [eventId] is the id Pano is holding the panel's request open on, echoed back on the
 * `FILE_RESULT` this produces. Everything is nullable because a message from a newer Pano is not
 * something an older plugin may choke on.
 */
data class FileRequestMessage(
    val eventId: String? = null,
    val path: String? = null,
    val paths: List<String>? = null,
    val from: String? = null,
    val to: String? = null,
    val target: String? = null,
    val content: String? = null,
    val maxBytes: Int? = null,
    val mode: String? = null,
    /** `FILE_HASHES` only: the file names inside [path] to hash. */
    val names: List<String>? = null
) : PlatformMessage
