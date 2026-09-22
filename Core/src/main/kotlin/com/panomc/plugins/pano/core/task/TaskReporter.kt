package com.panomc.plugins.pano.core.task

import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.request.TaskProgressRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Reports how a long job is going, makes sure it is only ever finished once, and keeps the
 * reporting down to what a panel can actually show.
 *
 * Pano treats DONE and FAILED as terminal: a backup that reports DONE is what writes the row the
 * panel lists it from, and a second terminal frame for the same task would either resurrect work
 * that has already had its consequences or apply them twice. The guard lives here rather than in
 * every caller so no future task kind can forget it.
 *
 * RUNNING frames are throttled for the opposite reason: a download reports every percent, and
 * Pano handles every frame it receives separately, so eighty frames in two seconds become a
 * handful. The last held frame is flushed just before the terminal one, so the panel's final
 * RUNNING is the real last step rather than whichever one happened to land on the interval.
 */
class TaskReporter(
    private val send: (PlatformRequest) -> Unit,
    private val logger: Logger
) {
    private val finished = ConcurrentHashMap.newKeySet<String>()

    private val held = ConcurrentHashMap<String, Frame>()

    private val lastSentAt = ConcurrentHashMap<String, Long>()

    fun running(taskId: String, kind: String, percent: Int, message: String?) {
        if (taskId in finished) {
            return
        }

        val frame = Frame(percent.coerceIn(0, 99), message)
        val now = System.currentTimeMillis()
        val previous = lastSentAt[taskId]

        if (previous != null && now - previous < MIN_INTERVAL_MILLIS) {
            held[taskId] = frame

            return
        }

        held.remove(taskId)
        lastSentAt[taskId] = now

        emit(taskId, kind, STATUS_RUNNING, frame.percent, frame.message, null, null)
    }

    /**
     * Reports a task as finished, optionally with the one fact a panel has to act on.
     *
     * [restartRequired] is a field rather than a sentence in [message]: a plugin that installed
     * fine but only loads on the next boot is still a DONE, and the panel needs to be able to say
     * so without parsing English.
     */
    fun done(taskId: String, kind: String, message: String?, restartRequired: Boolean? = null) {
        if (!finished.add(taskId)) {
            return
        }

        flush(taskId, kind)

        emit(taskId, kind, STATUS_DONE, 100, message, null, restartRequired)
    }

    fun failed(taskId: String, kind: String, error: String) {
        if (!finished.add(taskId)) {
            return
        }

        flush(taskId, kind)

        emit(taskId, kind, STATUS_FAILED, 100, null, error, null)
    }

    /** Sends whatever progress the throttle was still holding, so no step is lost under a DONE. */
    private fun flush(taskId: String, kind: String) {
        val frame = held.remove(taskId)

        lastSentAt.remove(taskId)

        if (frame != null) {
            emit(taskId, kind, STATUS_RUNNING, frame.percent, frame.message, null, null)
        }
    }

    private fun emit(
        taskId: String,
        kind: String,
        status: String,
        percent: Int,
        message: String?,
        error: String?,
        restartRequired: Boolean?
    ) {
        try {
            send(TaskProgressRequest(taskId, kind, status, percent, message, error, restartRequired))
        } catch (exception: Throwable) {
            // A progress frame is disposable; the work it describes is not, and must not be
            // aborted because the socket happened to be down for a moment.
            logger.fine("Could not report task $taskId: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    private data class Frame(val percent: Int, val message: String?)

    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"

        /** The `TASK_PROGRESS` kinds this plugin reports under, matching the node daemon's. */
        const val KIND_BACKUP = "BACKUP"
        const val KIND_RESTORE = "RESTORE"
        const val KIND_PLUGIN_INSTALL = "PLUGIN_INSTALL"

        private const val MIN_INTERVAL_MILLIS = 400L
    }
}
