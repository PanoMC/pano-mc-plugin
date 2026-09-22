package com.panomc.plugins.pano.core.console

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Turns whatever a logging backend hands a capture adapter into the [ConsoleLine]s the streamer
 * accepts.
 *
 * Kept separate from the adapters so all three of them - log4j2 (Paper/Folia/Velocity/Fabric),
 * java.util.logging (BungeeCord) and the synthetic echo of a command Pano sent - normalise
 * identically, and so the normalisation itself is unit-testable without a running server.
 */
object ConsoleLines {
    /**
     * Hard cap on a single line's `m` field, per the console contract (AGENT.md 2.4.1). Measured
     * in characters: a line this long is already a malformed/spammed one, and the cap exists to
     * bound the frame size rather than to be byte-exact.
     */
    const val MAX_MESSAGE_LENGTH = 4096

    /**
     * Normalises one log record into zero or more console lines: ANSI stripped, the stack trace of
     * [thrown] appended as its own lines, embedded newlines split apart, each line truncated to
     * [MAX_MESSAGE_LENGTH].
     *
     * Multi-line records are split rather than sent as one giant line so the panel can virtualise
     * and colour them the same way the server console does.
     */
    fun toLines(
        timestamp: Long,
        level: ConsoleLevel,
        message: String?,
        thrown: Throwable? = null
    ): List<ConsoleLine> {
        val text = buildString {
            if (!message.isNullOrEmpty()) {
                append(message)
            }

            if (thrown != null) {
                if (isNotEmpty()) {
                    append('\n')
                }

                append(stackTraceOf(thrown))
            }
        }

        if (text.isEmpty()) {
            return emptyList()
        }

        val lines = mutableListOf<ConsoleLine>()

        // split on the raw text, then strip per line: an escape sequence cannot span a newline,
        // and this keeps a single huge stack trace from being stripped as one 100 KB string.
        for (rawLine in text.split('\n')) {
            // The colours are read off the same pass that strips them (AGENT.md 2.4.21), so the
            // text is exactly what strip() gives and the spans point into it.
            val styled = AnsiStripper.style(rawLine)
            val stripped = styled.text.trimEnd('\r')

            if (stripped.isEmpty()) {
                continue
            }

            val message = if (stripped.length > MAX_MESSAGE_LENGTH) stripped.substring(0, MAX_MESSAGE_LENGTH) else stripped

            lines.add(ConsoleLine(timestamp, level, message, AnsiStripper.clip(styled.spans, message.length)))
        }

        return lines
    }

    private fun stackTraceOf(thrown: Throwable): String {
        return try {
            val writer = StringWriter()

            PrintWriter(writer).use { thrown.printStackTrace(it) }

            writer.toString()
        } catch (_: Throwable) {
            // A throwable whose own toString/printStackTrace blows up must not take the console
            // capture down with it - the message line above is already queued either way.
            thrown.javaClass.name
        }
    }
}
