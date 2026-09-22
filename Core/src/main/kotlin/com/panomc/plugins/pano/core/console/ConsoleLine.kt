package com.panomc.plugins.pano.core.console

/**
 * Severity of a captured console line, normalised to the five levels the Pano panel renders.
 *
 * The names are part of the wire protocol (`CONSOLE_LINES`'s `l` field), so they must stay
 * exactly these five: every platform-specific level (log4j2's FATAL, java.util.logging's
 * SEVERE/FINEST, ...) is folded onto one of them by the capture adapters.
 */
enum class ConsoleLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * One captured console line, already stripped of ANSI escapes and truncated.
 *
 * Immutable on purpose: log4j2 hands its appenders a mutable, reused `LogEvent`, so a capture
 * adapter has to copy everything it needs out of the event while it is still on the logging
 * thread. Everything downstream of that copy - the ring buffer, the pending queue, the batch
 * that is encoded into `CONSOLE_LINES` - works on these snapshots.
 *
 * [spans] are the colour runs over [message] (AGENT.md 2.4.21), already clipped to it, or null
 * for a line with none - which is every line read back from a log file, since those were written
 * without colour.
 */
data class ConsoleLine(
    val timestamp: Long,
    val level: ConsoleLevel,
    val message: String,
    val spans: List<ColorSpan>? = null
)
