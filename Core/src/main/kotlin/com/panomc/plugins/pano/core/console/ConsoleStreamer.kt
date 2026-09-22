package com.panomc.plugins.pano.core.console

import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.request.ConsoleLinesRequest
import io.vertx.core.Context
import io.vertx.core.Vertx
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Captures the server console and ships it to Pano in batches.
 *
 * Shape of the data path, per the console contract (AGENT.md 2.4.1):
 *
 * - A platform capture adapter (a log4j2 appender, or a `java.util.logging` handler on
 *   BungeeCord) calls [accept] from whatever thread logged the line. That call only ever
 *   enqueues - never encrypts, never writes to the socket, never blocks the server's main thread.
 * - While the plugin is running, the last [RING_BUFFER_SIZE] lines are always kept locally, even
 *   when nobody is watching, so the panel gets scrollback the moment it opens.
 * - Streaming itself starts off and is switched on by `CONSOLE_STREAM`. Turning it on replays
 *   the ring buffer (oldest first) and then keeps streaming live.
 * - A Vert.x timer flushes every [FLUSH_INTERVAL_MILLIS] ms in batches of at most
 *   [BATCH_SIZE] lines, capped at [MAX_LINES_PER_SECOND] lines per second. Anything over that
 *   budget queues, and a queue that overflows drops its oldest lines and counts them into the
 *   `dropped` field of the next batch.
 *
 * `console.enabled = false` in config.conf is the master switch: no capture is installed at all
 * and `CONSOLE_STREAM` is ignored.
 */
class ConsoleStreamer(
    private val vertx: Vertx,
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val pluginMain: PanoPluginMain,
    private val send: (PlatformRequest) -> Unit,
    private val isConnected: () -> Boolean
) {
    companion object {
        /** Lines kept locally so a newly opened console has scrollback. */
        const val RING_BUFFER_SIZE = 500

        /** Lines waiting to be sent. Sized to hold a full ring-buffer replay plus live headroom. */
        const val PENDING_LIMIT = 2_000

        /** Maximum lines in one `CONSOLE_LINES` message. */
        const val BATCH_SIZE = 100

        const val FLUSH_INTERVAL_MILLIS = 250L

        const val MAX_LINES_PER_SECOND = 500

        private const val RATE_WINDOW_MILLIS = 1_000L
    }

    // Guards every queue/counter below. Held only for list surgery - never across the encrypt +
    // socket write in send(), which runs on the flush timer's context outside this lock.
    private val lock = Any()

    private val ringBuffer = ArrayDeque<ConsoleLine>()
    private val pending = ArrayDeque<ConsoleLine>()
    private var dropped = 0L

    // Start of the current one-second rate window and how many lines have been sent inside it.
    private var windowStartedAt = 0L
    private var sentInWindow = 0

    // @Volatile on all four: written from the plugin's lifecycle thread and the Vert.x event loop,
    // read from every thread that logs a line (so, potentially, every thread on the server).
    @Volatile
    private var running = false

    @Volatile
    private var streaming = false

    @Volatile
    private var capture: AutoCloseable? = null

    @Volatile
    private var flushTimerId: Long? = null

    // The context the flush timer runs on, captured at start() so accept() can ask for an early
    // flush without vertx.runOnContext() spinning up an ad-hoc context per logging thread.
    @Volatile
    private var flushContext: Context? = null

    // Only one flush at a time: the periodic timer and an early flush requested by accept() can
    // otherwise interleave and emit two batches with the same lines in the wrong order.
    private val flushing = AtomicBoolean(false)

    // Set by accept() when the queue reaches a full batch, cleared by flush(); keeps a burst of
    // log lines from scheduling one runOnContext each.
    private val flushRequested = AtomicBoolean(false)

    // Visible for testing, and only ever false there: with the Vert.x timer running, a test that
    // drives flush() itself races the timer and the back-pressure assertions stop being
    // deterministic. Must be set before start().
    internal var scheduleFlushes = true

    // Set while this streamer is inside send(): anything the send path itself logs (an encryption
    // failure, a Vert.x warning) would otherwise come straight back in through accept() and feed
    // itself. Per-thread, because send() can run on more than one.
    private val inSend = ThreadLocal.withInitial { false }

    /** Whether the operator has left console capture on in config.conf. */
    fun isEnabled(): Boolean = try {
        configManager.config.console?.enabled ?: true
    } catch (_: Throwable) {
        // config is lateinit; a capture installed before the config manager finished would
        // otherwise throw on every line.
        false
    }

    /** Whether lines are currently being streamed to Pano. */
    fun isStreaming(): Boolean = streaming

    /**
     * Installs the platform capture adapter and starts the flush timer. A platform with no
     * adapter (or a server whose logging backend could not be reached) simply gets no console;
     * command echo still works so the panel stays consistent.
     */
    fun start() {
        if (running || !isEnabled()) {
            return
        }

        running = true

        capture = try {
            pluginMain.installConsoleCapture(::accept)
        } catch (exception: Throwable) {
            logger.warning("Could not install console capture: ${exception.javaClass.simpleName}: ${exception.message}")

            null
        }

        if (capture == null) {
            logger.warning("Console capture is not available on this server platform; Pano's live console will stay empty.")
        }

        if (scheduleFlushes) {
            flushContext = vertx.getOrCreateContext()
            flushTimerId = vertx.setPeriodic(FLUSH_INTERVAL_MILLIS) { flush() }
        }
    }

    /** Removes the capture adapter, stops the timer and drops everything buffered. */
    fun stop() {
        running = false
        streaming = false

        flushTimerId?.let { vertx.cancelTimer(it) }
        flushTimerId = null
        flushContext = null

        try {
            capture?.close()
        } catch (exception: Throwable) {
            logger.warning("Failed to remove console capture: ${exception.javaClass.simpleName}: ${exception.message}")
        }

        capture = null

        synchronized(lock) {
            ringBuffer.clear()
            pending.clear()
            dropped = 0
        }
    }

    /**
     * Turns the live stream on or off in response to `CONSOLE_STREAM`.
     *
     * Turning it on queues the whole ring buffer first, oldest line first, so the panel opens on
     * scrollback instead of on an empty screen.
     */
    fun setStreaming(enabled: Boolean) {
        if (!running || !isEnabled()) {
            return
        }

        if (!enabled) {
            synchronized(lock) {
                streaming = false
                pending.clear()
                dropped = 0
            }

            return
        }

        synchronized(lock) {
            if (streaming) {
                return
            }

            pending.clear()
            dropped = 0
            pending.addAll(ringBuffer)
            streaming = true
        }

        requestFlush()
    }

    /**
     * Stops streaming but keeps buffering: the connection is gone, yet the lines produced while
     * it is down are exactly the ones an operator wants to see once it comes back.
     */
    fun onDisconnect() {
        synchronized(lock) {
            streaming = false
            pending.clear()
            dropped = 0
        }
    }

    /**
     * Adds a line produced by Pano itself (currently the echo of a command a panel user sent) to
     * the same stream, so it interleaves with the server's own output in the right order.
     */
    fun emit(level: ConsoleLevel, message: String) {
        ConsoleLines.toLines(System.currentTimeMillis(), level, message).forEach(::accept)
    }

    /**
     * The sink handed to the platform capture adapters. Called on arbitrary threads, including
     * the server main thread, so it does list surgery and nothing else.
     */
    fun accept(line: ConsoleLine) {
        if (!running || inSend.get()) {
            return
        }

        val shouldFlush: Boolean

        synchronized(lock) {
            ringBuffer.addLast(line)

            while (ringBuffer.size > RING_BUFFER_SIZE) {
                ringBuffer.removeFirst()
            }

            if (!streaming) {
                return
            }

            pending.addLast(line)

            while (pending.size > PENDING_LIMIT) {
                pending.removeFirst()
                dropped++
            }

            // compareAndSet, not a bare size check: while the queue sits above the batch size
            // every single line would otherwise schedule its own runOnContext.
            shouldFlush = scheduleFlushes &&
                    pending.size >= BATCH_SIZE &&
                    flushRequested.compareAndSet(false, true)
        }

        if (shouldFlush) {
            requestFlush()
        }
    }

    private fun requestFlush() {
        val context = flushContext

        if (context == null) {
            flushRequested.set(false)

            return
        }

        context.runOnContext { flush() }
    }

    // internal rather than private so ConsoleStreamerTest can drive a flush deterministically
    // instead of racing the 250 ms timer.
    internal fun flush() {
        flushRequested.set(false)

        if (!running || !streaming) {
            return
        }

        // A send while the socket is down would throw per line; leave everything queued instead
        // (it ages out of the queue oldest-first if the outage lasts).
        if (!isConnected()) {
            return
        }

        if (!flushing.compareAndSet(false, true)) {
            return
        }

        try {
            while (true) {
                val batch = synchronized(lock) { takeBatchLocked() } ?: return

                if (!sendBatch(batch.lines, batch.dropped)) {
                    return
                }

                if (batch.lines.size < BATCH_SIZE) {
                    return
                }
            }
        } finally {
            flushing.set(false)
        }
    }

    private class Batch(val lines: List<ConsoleLine>, val dropped: Long)

    /** Next batch to send, or null when the budget is spent or there is nothing to send. Call under [lock]. */
    private fun takeBatchLocked(): Batch? {
        val budget = remainingBudgetLocked()

        if (budget <= 0) {
            return null
        }

        if (pending.isEmpty() && dropped == 0L) {
            return null
        }

        val take = minOf(BATCH_SIZE, budget, pending.size)
        val taken = ArrayList<ConsoleLine>(take)

        repeat(take) { taken.add(pending.removeFirst()) }

        val droppedInBatch = dropped

        dropped = 0
        sentInWindow += take

        return Batch(taken, droppedInBatch)
    }

    /** Lines still allowed inside the current one-second window. Call under [lock]. */
    private fun remainingBudgetLocked(): Int {
        val now = System.currentTimeMillis()

        if (now - windowStartedAt >= RATE_WINDOW_MILLIS) {
            windowStartedAt = now
            sentInWindow = 0
        }

        return (MAX_LINES_PER_SECOND - sentInWindow).coerceAtLeast(0)
    }

    /** Returns false when the batch could not be sent (and was therefore counted as dropped). */
    private fun sendBatch(batch: List<ConsoleLine>, droppedInBatch: Long): Boolean {
        val request = ConsoleLinesRequest(
            batch.map { ConsoleLinesRequest.Line(it.timestamp, it.level.name, it.message, it.spans?.map(ColorSpan::toWire)) },
            droppedInBatch
        )

        inSend.set(true)

        return try {
            send(request)

            true
        } catch (exception: Throwable) {
            synchronized(lock) {
                dropped += batch.size + droppedInBatch
            }

            false
        } finally {
            inSend.set(false)
        }
    }
}
