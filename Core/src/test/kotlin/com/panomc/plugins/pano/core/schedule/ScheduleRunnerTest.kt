package com.panomc.plugins.pano.core.schedule

import com.panomc.plugins.pano.core.files.TestPluginMain
import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.message.response.BackupCreateMessage
import com.panomc.plugins.pano.core.platform.message.response.SyncScheduleEntry
import com.panomc.plugins.pano.core.platform.message.response.SyncSchedulesMessage
import com.panomc.plugins.pano.core.platform.message.response.SyncScheduleTaskEntry
import com.panomc.plugins.pano.core.platform.request.ScheduleRunRequest
import io.vertx.core.Vertx
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a schedule actually does when its minute comes round, and what survives a restart.
 *
 * The Vert.x minute timer is bypassed by calling [ScheduleRunner.tick] with the instant to
 * pretend it is, so the assertions are about the rules rather than about the clock.
 */
class ScheduleRunnerTest {
    private lateinit var vertx: Vertx
    private lateinit var root: File
    private lateinit var dataFolder: File
    private lateinit var pluginMain: TestPluginMain
    private lateinit var store: ScheduleStore
    private lateinit var runner: ScheduleRunner

    private val sent = mutableListOf<PlatformRequest>()
    private val backups = mutableListOf<BackupCreateMessage>()

    private val logger: Logger = Logger.getLogger("pano-test")

    @BeforeTest
    fun setUp() {
        vertx = Vertx.vertx()
        root = Files.createTempDirectory("pano-schedules").toFile()
        dataFolder = File(root, "plugins/Pano").apply { mkdirs() }
        pluginMain = TestPluginMain(root)
        store = ScheduleStore(dataFolder, logger)

        sent.clear()
        backups.clear()

        runner = ScheduleRunner(vertx, pluginMain, store, { sent.add(it) }, logger) { backups.add(it) }
    }

    @AfterTest
    fun tearDown() {
        runner.stop()

        runBlocking { vertx.close().toCompletionStage().toCompletableFuture().get() }

        root.deleteRecursively()
    }

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun schedule(
        uuid: String = "schedule-1",
        cron: String = "0 4 * * *",
        enabled: Boolean = true,
        warnMinutes: Int = 0,
        tasks: List<SyncScheduleTaskEntry> = listOf(SyncScheduleTaskEntry("COMMAND", mapOf("command" to "save-all")))
    ) = SyncScheduleEntry(uuid, "Nightly", cron, "UTC", enabled, warnMinutes, tasks)

    private fun runs(): List<ScheduleRunRequest> = sent.filterIsInstance<ScheduleRunRequest>()

    @Test
    fun `runs a command on the due minute, once`() {
        runner.sync(SyncSchedulesMessage(schedules = listOf(schedule())))

        runBlocking {
            runner.tick(at("2026-06-01T04:00:00"))
            // The same minute again, a slow tick later: the guard is what stops a double run.
            runner.tick(at("2026-06-01T04:00:30"))
            runner.tick(at("2026-06-01T05:00:00"))
        }

        assertEquals(listOf("save-all"), pluginMain.dispatched)

        val run = runs().single()

        assertEquals("schedule-1", run.scheduleId)
        assertTrue(run.ok)
        assertEquals(null, run.error)
        assertEquals(listOf("COMMAND"), run.tasks.map { it.kind })
        assertTrue(run.finishedAt >= run.startedAt)
    }

    @Test
    fun `leaves a disabled schedule alone`() {
        runner.sync(SyncSchedulesMessage(schedules = listOf(schedule(enabled = false))))

        runBlocking { runner.tick(at("2026-06-01T04:00:00")) }

        assertTrue(pluginMain.dispatched.isEmpty())
        assertTrue(runs().isEmpty())
    }

    @Test
    fun `stops at the first step that fails`() {
        runner.sync(
            SyncSchedulesMessage(
                schedules = listOf(
                    schedule(
                        tasks = listOf(
                            SyncScheduleTaskEntry("TELEPORT", null),
                            SyncScheduleTaskEntry("POWER", mapOf("action" to "RESTART"))
                        )
                    )
                )
            )
        )

        runBlocking { runner.tick(at("2026-06-01T04:00:00")) }

        val run = runs().single()

        assertFalse(run.ok)
        assertNotNull(run.error)
        assertEquals(1, run.tasks.size)
        assertEquals(0, pluginMain.restarts)
    }

    @Test
    fun `powers the server down and restarts it on request`() {
        runner.sync(
            SyncSchedulesMessage(
                schedules = listOf(
                    schedule(uuid = "stop", cron = "0 4 * * *", tasks = listOf(SyncScheduleTaskEntry("POWER", mapOf("action" to "STOP")))),
                    schedule(uuid = "restart", cron = "0 5 * * *", tasks = listOf(SyncScheduleTaskEntry("POWER", mapOf("action" to "RESTART"))))
                )
            )
        )

        runBlocking {
            runner.tick(at("2026-06-01T04:00:00"))
            runner.tick(at("2026-06-01T05:00:00"))
        }

        assertEquals(1, pluginMain.shutdowns)
        assertEquals(1, pluginMain.restarts)
        assertTrue(runs().all { it.ok })
    }

    @Test
    fun `takes a backup, naming it after the schedule when Pano did not`() {
        runner.sync(
            SyncSchedulesMessage(schedules = listOf(schedule(tasks = listOf(SyncScheduleTaskEntry("BACKUP", null)))))
        )

        runBlocking { runner.tick(at("2026-06-01T04:00:00")) }

        val backup = backups.single()

        assertNotNull(backup.taskId)
        assertNotNull(backup.backupId)
        assertTrue(backup.name!!.startsWith("Nightly-"))
        assertTrue(runs().single().ok)
    }

    @Test
    fun `passes a backup step's mode, scope and paths through`() {
        runner.sync(
            SyncSchedulesMessage(
                schedules = listOf(
                    schedule(
                        tasks = listOf(
                            SyncScheduleTaskEntry(
                                "BACKUP",
                                mapOf(
                                    "name" to "Worlds",
                                    "keep" to 5,
                                    "mode" to "SNAPSHOT",
                                    "scope" to "CUSTOM",
                                    "include" to listOf("world", "plugins/LuckPerms"),
                                    "exclude" to listOf("logs/", "*.jar.tmp")
                                )
                            )
                        )
                    )
                )
            )
        )

        runBlocking { runner.tick(at("2026-06-01T04:00:00")) }

        val backup = backups.single()

        assertEquals("Worlds", backup.name)
        assertEquals("SNAPSHOT", backup.mode)
        assertEquals("CUSTOM", backup.scope)
        assertEquals(listOf("world", "plugins/LuckPerms"), backup.include)
        assertEquals(listOf("logs/", "*.jar.tmp"), backup.exclude)
    }

    @Test
    fun `warns the players before a power step, once per countdown`() {
        runner.sync(
            SyncSchedulesMessage(
                schedules = listOf(
                    schedule(
                        warnMinutes = 5,
                        tasks = listOf(SyncScheduleTaskEntry("POWER", mapOf("action" to "RESTART")))
                    )
                )
            )
        )

        runBlocking {
            runner.tick(at("2026-06-01T03:55:00"))
            runner.tick(at("2026-06-01T03:55:20"))
            runner.tick(at("2026-06-01T03:59:00"))
            runner.tick(at("2026-06-01T04:00:00"))
        }

        assertEquals(
            listOf("say Server restarts in 5 minutes", "say Server restarts in 1 minute"),
            pluginMain.dispatched
        )
        assertEquals(1, pluginMain.restarts)
    }

    @Test
    fun `drops a schedule whose cron nothing can read`() {
        runner.sync(
            SyncSchedulesMessage(
                schedules = listOf(schedule(uuid = "bad", cron = "not a cron"), schedule(uuid = "good"))
            )
        )

        assertEquals(listOf("good"), store.read().map { it.uuid })
    }

    @Test
    fun `keeps the synced schedules across a restart`() {
        runner.sync(SyncSchedulesMessage(schedules = listOf(schedule(warnMinutes = 5))))

        assertTrue(File(dataFolder, ScheduleStore.FILE_NAME).isFile)

        // A fresh runner, as a restarted server would build: nothing was synced into it, so
        // everything it knows came off the disk.
        val restarted = ScheduleRunner(vertx, pluginMain, store, { sent.add(it) }, logger) { backups.add(it) }

        restarted.start()

        try {
            runBlocking { restarted.tick(at("2026-06-01T04:00:00")) }
        } finally {
            restarted.stop()
        }

        assertEquals(listOf("save-all"), pluginMain.dispatched)

        val stored = store.read().single()

        assertEquals("schedule-1", stored.uuid)
        assertEquals("0 4 * * *", stored.cron)
        assertEquals("UTC", stored.timezone)
        assertEquals(5, stored.warnMinutes)
        assertEquals("COMMAND", stored.tasks?.single()?.kind)
        assertEquals("save-all", stored.tasks?.single()?.payload?.get("command"))
    }

    @Test
    fun `an empty sync clears what it was holding`() {
        runner.sync(SyncSchedulesMessage(schedules = listOf(schedule())))
        runner.sync(SyncSchedulesMessage(schedules = emptyList()))

        runBlocking { runner.tick(at("2026-06-01T04:00:00")) }

        assertTrue(pluginMain.dispatched.isEmpty())
        assertTrue(store.read().isEmpty())
    }
}
