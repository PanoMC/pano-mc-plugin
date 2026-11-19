package com.panomc.plugins.pano.core.platform

/**
 * Holds the frames Pano sends before this end is ready to read them.
 *
 * Pano writes to a new connection the moment it accepts it (the plugin state, a console-stream
 * request), while the plugin still has to agree the channel from the upgrade response. A frame that
 * reached the socket before its message handler existed was dropped by Vert.x, and the next one then
 * failed the channel's sequence check ("Unexpected frame sequence 1, expected 0") and cost a
 * reconnect. The gate is installed as the handler before the connection is opened, keeps what
 * arrives until [open], and from then on hands every frame straight through, in arrival order.
 *
 * [open]'s reader returns false when a frame was refused and the connection is being closed: no
 * frame after that one is delivered, held or live.
 */
internal class FrameGate {
    private val lock = Any()
    private val held = ArrayList<String>()
    private var reader: ((String) -> Boolean)? = null
    private var stopped = false

    /** A frame from the socket, in arrival order. */
    fun receive(frame: String) {
        synchronized(lock) {
            if (stopped) {
                return
            }

            val current = reader

            if (current == null) {
                held.add(frame)
            } else if (!current(frame)) {
                stopped = true
            }
        }
    }

    /** Delivers the held frames to [read], then every later one as it arrives. */
    fun open(read: (String) -> Boolean) {
        synchronized(lock) {
            for (frame in held) {
                if (!read(frame)) {
                    stopped = true

                    break
                }
            }

            held.clear()

            if (!stopped) {
                reader = read
            }
        }
    }

    /** Drops what is held and delivers nothing more (the connection was refused before it was read). */
    fun close() {
        synchronized(lock) {
            stopped = true
            held.clear()
        }
    }
}
