package com.panomc.plugins.pano.core.schedule

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.message.response.BackupCreateMessage
import com.panomc.plugins.pano.core.platform.message.response.SyncScheduleEntry
import com.panomc.plugins.pano.core.platform.message.response.SyncSchedulesMessage
import com.panomc.plugins.pano.core.platform.message.response.SyncScheduleTaskEntry
import com.panomc.plugins.pano.core.platform.request.ScheduleRunRequest
import io.vertx.core.Vertx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * The clock behind this server's schedules.
 *
 * It runs here rather than in Pano for one reason: this server is still here when Pano is not. A
 * platform being restarted at 03:59 must not cost a server its 04:00 backup, and a server that
 * has been handed its schedules can keep them without anyone watching - which is also why they are
 * written to disk (see [ScheduleStore]) and reloaded on the next start.
 *
 * A tick is one minute, because cron has no finer resolution and because the timer never fires on
 * the same millisecond twice - so "is this due?" is asked about the minute, and a per-schedule
 * guard keyed by that minute is what stops a slow tick from running the same job twice.
 *
 * Steps run in order and stop at the first failure, which is the whole reason they are ordered:
 * "back up, then restart" must not restart when the backup failed.
 */
class ScheduleRunner(
    private val vertx: Vertx,
    private val pluginMain: PanoPluginMain,
    private val store: ScheduleStore,
    private val send: (PlatformRequest) -> Unit,
    private val logger: Logger,
    /** Takes a backup, through whatever serialises the heavy file work. */
    private val backup: suspend (BackupCreateMessage) -> Unit
) {
    // @Volatile: replaced wholesale by sync() on a Vert.x thread and read by the tick on an IO
    // one. A whole list at a time, never mutated in place, so there is nothing to tear.
    @Volatile
    private var schedules: List<SyncScheduleEntry> = emptyList()

    private val lastFiredMinute = ConcurrentHashMap<String, Long>()

    private val lastWarnedMinute = ConcurrentHashMap<String, Long>()

    @Volatile
    private var timerId: Long? = null

    @Volatile
    private var scope: CoroutineScope? = null

    /** Loads what this server was last told and starts the minute tick. */
    fun start() {
        if (timerId != null) {
            return
        }

        schedules = store.read().filter { isUsable(it) }

        if (schedules.isNotEmpty()) {
            logger.info("Holding ${schedules.size} schedule(s) from the last time Pano synced them.")
        }

        val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope = runnerScope

        timerId = vertx.setPeriodic(TICK_INTERVAL_MILLIS) {
            runnerScope.launch {
                try {
                    tick()
                } catch (exception: Throwable) {
                    logger.warning("A schedule tick failed: ${exception.javaClass.simpleName}: ${exception.message}")
                }
            }
        }
    }

    fun stop() {
        timerId?.let { vertx.cancelTimer(it) }
        timerId = null

        scope?.cancel()
        scope = null
    }

    /** Replaces everything this server knew about its schedules, and remembers it across restarts. */
    fun sync(message: SyncSchedulesMessage) {
        val entries = (message.schedules ?: emptyList()).filter { entry ->
            val usable = isUsable(entry)

            if (!usable) {
                logger.warning("Dropping an unusable schedule (\"${entry.name}\", cron \"${entry.cron}\").")
            }

            usable
        }

        schedules = entries

        store.write(entries)

        logger.info("Now holding ${entries.size} schedule(s) from Pano.")
    }

    /** One pass over everything this server holds. */
    suspend fun tick(now: Long = System.currentTimeMillis()) {
        val minute = now / MINUTE_MILLIS

        schedules.forEach { entry ->
            if (entry.enabled == false) {
                return@forEach
            }

            try {
                apply(entry, now, minute)
            } catch (exception: Throwable) {
                logger.warning("Schedule \"${entry.name}\" failed: ${exception.javaClass.simpleName}: ${exception.message}")
            }
        }

        prune(minute)
    }

    private suspend fun apply(entry: SyncScheduleEntry, now: Long, minute: Long) {
        val uuid = entry.uuid ?: return

        if (CronSchedules.firesAt(entry.cron, entry.timezone, now)) {
            if (lastFiredMinute.put(uuid, minute) == minute) {
                return
            }

            run(entry)

            return
        }

        warnIfDue(entry, now, minute)
    }

    /**
     * Sends the countdown lines that precede a power step.
     *
     * Asked forwards - "does this fire five minutes from now?" - rather than derived from a stored
     * next-run time, so it needs no state that could be stale and no arithmetic that a daylight
     * saving change would break.
     */
    private fun warnIfDue(entry: SyncScheduleEntry, now: Long, minute: Long) {
        val offsets = ScheduleWarnings.offsetsFor(entry.warnMinutes ?: 0)

        if (offsets.isEmpty() || isProxy()) {
            // A proxy has no `say`: warning its players would mean an unknown-command line in the
            // console every five minutes and nothing at all in anybody's chat.
            return
        }

        val power = entry.tasks.orEmpty().firstOrNull { KIND_POWER.equals(it.kind, ignoreCase = true) } ?: return

        val due = offsets.firstOrNull { offset ->
            CronSchedules.firesAt(entry.cron, entry.timezone, now + offset * MINUTE_MILLIS)
        } ?: return

        val key = "${entry.uuid}:$due"

        if (lastWarnedMinute.put(key, minute) == minute) {
            return
        }

        val restarting = ACTION_RESTART.equals(power.payload?.get("action")?.toString(), ignoreCase = true)

        dispatch(ScheduleWarnings.message(due, restarting))
    }

    /** Runs one schedule's steps and reports the outcome to Pano. */
    private suspend fun run(entry: SyncScheduleEntry) {
        val startedAt = System.currentTimeMillis()
        val results = mutableListOf<ScheduleRunRequest.TaskResult>()

        var error: String? = null

        logger.info("Running the schedule \"${entry.name}\".")

        for (task in entry.tasks.orEmpty()) {
            error = try {
                runTask(entry, task)
            } catch (exception: Throwable) {
                exception.message ?: exception.javaClass.simpleName
            }

            results.add(ScheduleRunRequest.TaskResult(task.kind, error == null, error))

            if (error != null) {
                break
            }
        }

        report(entry, startedAt, error, results)
    }

    /** Returns null on success, or the reason the step failed. */
    private suspend fun runTask(entry: SyncScheduleEntry, task: SyncScheduleTaskEntry): String? {
        val payload = task.payload

        return when (task.kind?.uppercase()) {
            KIND_COMMAND -> {
                val command = payload?.get("command")?.toString().orEmpty().trim().removePrefix("/").trim()

                if (command.isEmpty()) {
                    "The command step had no command."
                } else {
                    dispatch(command)
                }
            }

            KIND_POWER -> when (payload?.get("action")?.toString()?.uppercase()) {
                ACTION_STOP -> {
                    pluginMain.shutdown()

                    null
                }

                ACTION_RESTART -> {
                    pluginMain.restart()

                    null
                }

                else -> "\"${payload?.get("action")}\" is not something a schedule may do."
            }

            KIND_BACKUP -> {
                // Ids are invented here because this run is this server's: Pano first hears about
                // the archive when BACKUP_CREATED arrives and writes its row from that. The mode,
                // scope and path lists pass through untouched - they mean exactly what they mean
                // on a BACKUP_CREATE, and a value the backup cannot use fails the backup there,
                // with the same error a panel-started one would get.
                backup(
                    BackupCreateMessage(
                        taskId = UUID.randomUUID().toString(),
                        backupId = UUID.randomUUID().toString(),
                        name = payload?.get("name")?.toString()?.takeIf { it.isNotBlank() }
                            ?: "${entry.name.orEmpty().ifEmpty { "schedule" }}-${System.currentTimeMillis()}",
                        exclude = stringList(payload?.get("exclude")),
                        mode = payload?.get("mode")?.toString()?.takeIf { it.isNotBlank() },
                        scope = payload?.get("scope")?.toString()?.takeIf { it.isNotBlank() },
                        include = stringList(payload?.get("include"))
                    )
                )

                null
            }

            else -> "\"${task.kind}\" is not a step this server understands."
        }
    }

    /** A payload value that should be a list of strings, or null when it is absent or is not one. */
    private fun stringList(value: Any?): List<String>? =
        (value as? Collection<*>)?.mapNotNull { it?.toString() }

    /** Runs [command] on the server's main thread, reporting a dispatcher that refused it. */
    private fun dispatch(command: String): String? = try {
        pluginMain.dispatchConsoleCommand(command)

        null
    } catch (exception: Throwable) {
        exception.message ?: exception.javaClass.simpleName
    }

    private fun report(
        entry: SyncScheduleEntry,
        startedAt: Long,
        error: String?,
        tasks: List<ScheduleRunRequest.TaskResult>
    ) {
        try {
            send(
                ScheduleRunRequest(
                    scheduleId = entry.uuid.orEmpty(),
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    ok = error == null,
                    error = error,
                    tasks = tasks
                )
            )
        } catch (exception: Throwable) {
            // A schedule that ran and could not be reported still ran; Pano learns the state from
            // the next sync rather than from a crashed tick.
            logger.warning("Could not report a schedule run: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    private fun isUsable(entry: SyncScheduleEntry): Boolean =
        !entry.uuid.isNullOrBlank() && CronSchedules.isValid(entry.cron)

    private fun isProxy(): Boolean = try {
        pluginMain.getServerData().serverType() in PROXY_TYPES
    } catch (_: Throwable) {
        false
    }

    private fun prune(minute: Long) {
        lastFiredMinute.entries.removeIf { minute - it.value > GUARD_RETENTION_MINUTES }
        lastWarnedMinute.entries.removeIf { minute - it.value > GUARD_RETENTION_MINUTES }
    }

    companion object {
        const val TICK_INTERVAL_MILLIS = 60_000L

        private const val MINUTE_MILLIS = 60_000L
        private const val GUARD_RETENTION_MINUTES = 5

        private const val KIND_POWER = "POWER"
        private const val KIND_COMMAND = "COMMAND"
        private const val KIND_BACKUP = "BACKUP"

        private const val ACTION_STOP = "STOP"
        private const val ACTION_RESTART = "RESTART"

        private val PROXY_TYPES = setOf(ServerType.BUNGEECORD, ServerType.VELOCITY)
    }
}
