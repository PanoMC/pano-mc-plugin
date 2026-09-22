package com.panomc.plugins.pano.core.platform

import com.panomc.plugins.pano.core.ServerType
import com.panomc.plugins.pano.core.files.FileAgent
import com.panomc.plugins.pano.core.files.FileService
import com.panomc.plugins.pano.core.files.TestPluginMain
import com.panomc.plugins.pano.core.platform.PlatformMessage.Companion.responseName
import com.panomc.plugins.pano.core.platform.entity.InstalledPlugin
import com.panomc.plugins.pano.core.platform.entity.PlayerData
import com.panomc.plugins.pano.core.platform.message.handler.FileArchiveHandler
import com.panomc.plugins.pano.core.platform.message.handler.FileChmodHandler
import com.panomc.plugins.pano.core.platform.message.handler.FileDeleteHandler
import com.panomc.plugins.pano.core.platform.message.handler.FileHashesHandler
import com.panomc.plugins.pano.core.platform.message.handler.FileListHandler
import com.panomc.plugins.pano.core.platform.message.handler.FileMkdirHandler
import com.panomc.plugins.pano.core.platform.message.handler.FileReadHandler
import com.panomc.plugins.pano.core.platform.message.handler.FileRenameHandler
import com.panomc.plugins.pano.core.platform.message.handler.FileUnarchiveHandler
import com.panomc.plugins.pano.core.platform.message.handler.FileWriteHandler
import com.panomc.plugins.pano.core.platform.message.handler.PanoPluginUpdateHandler
import com.panomc.plugins.pano.core.platform.message.response.BackupCreateMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupDeleteMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupListMessage
import com.panomc.plugins.pano.core.platform.message.response.BackupRestoreMessage
import com.panomc.plugins.pano.core.platform.message.response.ConsoleHistoryMessage
import com.panomc.plugins.pano.core.platform.message.response.ConsoleSearchMessage
import com.panomc.plugins.pano.core.platform.message.response.ConsoleStreamMessage
import com.panomc.plugins.pano.core.platform.message.response.ExecuteCommandMessage
import com.panomc.plugins.pano.core.platform.message.response.PlayerActionMessage
import com.panomc.plugins.pano.core.platform.message.response.InstallPluginMessage
import com.panomc.plugins.pano.core.platform.message.response.PanoPluginUpdateMessage
import com.panomc.plugins.pano.core.platform.message.response.PowerMessage
import com.panomc.plugins.pano.core.platform.message.response.SetMetricsIntervalMessage
import com.panomc.plugins.pano.core.platform.message.response.SetPluginEnabledMessage
import com.panomc.plugins.pano.core.platform.message.response.SyncSchedulesMessage
import com.panomc.plugins.pano.core.platform.message.response.TransferPullMessage
import com.panomc.plugins.pano.core.platform.message.response.TransferPushMessage
import com.panomc.plugins.pano.core.platform.request.BackupCreatedRequest
import com.panomc.plugins.pano.core.platform.request.BackupRestoredRequest
import com.panomc.plugins.pano.core.platform.request.ConsoleHistoryResultRequest
import com.panomc.plugins.pano.core.platform.request.ConsoleLinesRequest
import com.panomc.plugins.pano.core.platform.request.ConsoleSearchResultRequest
import com.panomc.plugins.pano.core.platform.request.FileResultRequest
import com.panomc.plugins.pano.core.platform.request.InstalledPluginsRequest
import com.panomc.plugins.pano.core.platform.request.ScheduleRunRequest
import com.panomc.plugins.pano.core.platform.request.OnServerConnectRequest
import com.panomc.plugins.pano.core.platform.request.PanoPluginUpdateResultRequest
import com.panomc.plugins.pano.core.platform.request.ServerMetricsRequest
import com.panomc.plugins.pano.core.platform.request.TaskProgressRequest
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the wire names and field names of the server-management protocol (AGENT.md 2.4.1 and
 * 2.4.2). They are derived from class and property names, so an innocent-looking rename silently
 * breaks the contract with Pano - this is the test that stops it.
 */
class ProtocolNamesTest {
    private val agent = FileAgent(TestPluginMain(File(".")), {}, Logger.getLogger("pano-test"))

    private var vertx: io.vertx.core.Vertx? = null

    private var configManager: com.panomc.plugins.pano.core.config.ConfigManager? = null

    @Test
    fun `console lines carry the agreed event name and short fields`() {
        val encoded = JsonObject(
            ConsoleLinesRequest(listOf(ConsoleLinesRequest.Line(7L, "WARN", "hi")), 3L).encode()
        )

        assertEquals("CONSOLE_LINES", encoded.getString("event"))
        assertEquals(3L, encoded.getLong("dropped"))

        val line = encoded.getJsonArray("lines").getJsonObject(0)

        assertEquals(setOf("t", "l", "m"), line.fieldNames())
        assertEquals(7L, line.getLong("t"))
        assertEquals("WARN", line.getString("l"))
        assertEquals("hi", line.getString("m"))
    }

    @Test
    fun `a coloured console line carries c and a plain one leaves it off`() {
        val encoded = JsonObject(
            ConsoleLinesRequest(
                listOf(
                    ConsoleLinesRequest.Line(1L, "INFO", "red plain", listOf(listOf(0, 3, "#ff7b72", 0), listOf(4, 9, null, 6))),
                    ConsoleLinesRequest.Line(2L, "INFO", "plain", null)
                ),
                0L
            ).encode()
        )

        val lines = encoded.getJsonArray("lines")

        // AGENT.md 2.4.21 A: [[start, end, color, flags], ...], color null for the default.
        assertEquals("""[[0,3,"#ff7b72",0],[4,9,null,6]]""", lines.getJsonObject(0).getJsonArray("c").encode())
        assertEquals(setOf("t", "l", "m"), lines.getJsonObject(1).fieldNames(), "no colour, no c - not even a null")
    }

    @Test
    fun `server metrics carry the agreed event name and fields`() {
        val encoded = JsonObject(
            ServerMetricsRequest(
                t = 1L,
                tps = listOf(20.0, 19.5, 19.0),
                mspt = 4.2,
                memUsed = 100L,
                memMax = 200L,
                cpu = 12.5,
                playerCount = 1,
                maxPlayerCount = 20,
                players = listOf(PlayerData("uuid", "steve", 42L)),
                diskUsed = 4096L,
                diskTotal = 500_000L
            ).encode()
        )

        assertEquals("SERVER_METRICS", encoded.getString("event"))
        assertEquals(listOf(20.0, 19.5, 19.0), encoded.getJsonArray("tps").toList())
        assertEquals(4.2, encoded.getDouble("mspt"))
        assertEquals(100L, encoded.getLong("memUsed"))
        assertEquals(200L, encoded.getLong("memMax"))
        assertEquals(12.5, encoded.getDouble("cpu"))
        assertEquals(1, encoded.getInteger("playerCount"))
        assertEquals(20, encoded.getInteger("maxPlayerCount"))
        assertEquals(4096L, encoded.getLong("diskUsed"))
        assertEquals(500_000L, encoded.getLong("diskTotal"))

        val player = encoded.getJsonArray("players").getJsonObject(0)

        assertEquals(setOf("uuid", "username", "ping", "op", "whitelisted", "gamemode"), player.fieldNames())
    }

    @Test
    fun `a proxy sample keeps tps and mspt as explicit nulls`() {
        val encoded = JsonObject(
            ServerMetricsRequest(1L, null, null, 1L, 2L, null, 0, 0, emptyList()).encode()
        )

        assertEquals(true, encoded.containsKey("tps"))
        assertEquals(true, encoded.containsKey("mspt"))
        assertEquals(null, encoded.getValue("tps"))
        assertEquals(null, encoded.getValue("mspt"))
        assertEquals(null, encoded.getValue("cpu"))

        // Additive on protocol 2 (AGENT.md 2.4.18 A): the fields are always on the wire.
        // diskUsed is an explicit null while the first directory walk has not finished, and
        // diskTotal is one wherever this JVM cannot stat the partition at all.
        assertEquals(true, encoded.containsKey("diskUsed"))
        assertEquals(null, encoded.getValue("diskUsed"))
        assertEquals(true, encoded.containsKey("diskTotal"))
        assertEquals(null, encoded.getValue("diskTotal"))
    }

    @Test
    fun `installed plugins carry the agreed event name and fields`() {
        val encoded = JsonObject(
            InstalledPluginsRequest(
                listOf(InstalledPlugin("Pano", "1.0.0", listOf("panomc"), "desc", true, "pano.jar"))
            ).encode()
        )

        assertEquals("INSTALLED_PLUGINS", encoded.getString("event"))

        val plugin = encoded.getJsonArray("plugins").getJsonObject(0)

        assertEquals(setOf("name", "version", "authors", "description", "enabled", "file"), plugin.fieldNames())
    }

    @Test
    fun `console history answers with the id it was asked with`() {
        val eventId = UUID.randomUUID()

        val encoded = JsonObject(
            ConsoleHistoryResultRequest(
                eventId,
                listOf(ConsoleHistoryResultRequest.Line(7L, "WARN", "hi")),
                hasMore = true
            ).encode()
        )

        assertEquals("CONSOLE_HISTORY_RESULT", encoded.getString("event"))

        // The whole point of this message: Pano matches it against the request it is holding open.
        assertEquals(eventId.toString(), encoded.getString("eventId"))
        assertEquals(true, encoded.getBoolean("hasMore"))
        assertEquals(false, encoded.getBoolean("disabled"))

        val line = encoded.getJsonArray("lines").getJsonObject(0)

        assertEquals(setOf("t", "l", "m"), line.fieldNames())
        assertEquals(7L, line.getLong("t"))
        assertEquals("WARN", line.getString("l"))
        assertEquals("hi", line.getString("m"))
    }

    @Test
    fun `a console switched off answers with the disabled flag rather than an empty page`() {
        val encoded = JsonObject(
            ConsoleHistoryResultRequest(UUID.randomUUID(), emptyList(), hasMore = false, disabled = true).encode()
        )

        assertEquals("CONSOLE_HISTORY_RESULT", encoded.getString("event"))
        assertEquals(true, encoded.getBoolean("disabled"))
        assertEquals(false, encoded.getBoolean("hasMore"))
        assertEquals(0, encoded.getJsonArray("lines").size())
    }

    @Test
    fun `console search answers with the id it was asked with and the agreed payload`() {
        val eventId = UUID.randomUUID()

        val encoded = JsonObject(
            ConsoleSearchResultRequest(
                eventId,
                ok = true,
                error = null,
                lines = listOf(ConsoleSearchResultRequest.Line(7L, "WARN", "hi", "2026-09-22-3.log.gz")),
                cursor = "abc",
                done = false,
                scannedFiles = 1,
                totalFiles = 4,
                scannedBytes = 123L,
                capped = true
            ).encode()
        )

        assertEquals("CONSOLE_SEARCH_RESULT", encoded.getString("event"))
        assertEquals(eventId.toString(), encoded.getString("eventId"))
        assertEquals(true, encoded.getBoolean("ok"))
        assertEquals("abc", encoded.getString("cursor"))
        assertEquals(false, encoded.getBoolean("done"))
        assertEquals(1, encoded.getInteger("scannedFiles"))
        assertEquals(4, encoded.getInteger("totalFiles"))
        assertEquals(123L, encoded.getLong("scannedBytes"))
        assertEquals(true, encoded.getBoolean("capped"))

        val line = encoded.getJsonArray("lines").getJsonObject(0)

        // A file line has no colour, so `c` stays off the wire like on CONSOLE_LINES.
        assertEquals(setOf("t", "l", "m", "f"), line.fieldNames())
        assertEquals("2026-09-22-3.log.gz", line.getString("f"))

        val failed = JsonObject(ConsoleSearchResultRequest.failure(eventId, "BAD_CURSOR").encode())

        assertEquals(false, failed.getBoolean("ok"))
        assertEquals("BAD_CURSOR", failed.getString("error"))
        assertEquals(true, failed.getBoolean("done"))
        assertEquals(true, failed.containsKey("cursor"))
        assertEquals(null, failed.getValue("cursor"))
    }

    @Test
    fun `the connect request carries the game jvm time zone`() {
        fun connect(timeZone: String?) = JsonObject(
            OnServerConnectRequest(
                "survival", 1, 20, ServerType.PAPER, "1.21.4", "127.0.0.1", 25565, 1L, null, null,
                Protocol.VERSION, "1.0.0", emptyList(), timeZone
            ).encode()
        )

        val encoded = connect("Europe/Istanbul")

        assertEquals("ON_SERVER_CONNECT", encoded.getString("event"))
        assertEquals("Europe/Istanbul", encoded.getString("timeZone"))
        assertEquals(2, encoded.getInteger("protocolVersion"), "additive: the protocol stays 2")

        // Nullable and always on the wire, like every other optional connect field.
        assertEquals(true, connect(null).containsKey("timeZone"))
        assertEquals(null, connect(null).getValue("timeZone"))
    }

    @Test
    fun `inbound pushes resolve to the agreed event names`() {
        assertEquals("CONSOLE_HISTORY", ConsoleHistoryMessage::class.java.responseName())
        assertEquals("CONSOLE_SEARCH", ConsoleSearchMessage::class.java.responseName())
        assertEquals("CONSOLE_STREAM", ConsoleStreamMessage::class.java.responseName())
        assertEquals("EXECUTE_COMMAND", ExecuteCommandMessage::class.java.responseName())
        assertEquals("PLAYER_ACTION", PlayerActionMessage::class.java.responseName())
        assertEquals("POWER", PowerMessage::class.java.responseName())
        assertEquals("SET_PLUGIN_ENABLED", SetPluginEnabledMessage::class.java.responseName())
        assertEquals("TRANSFER_PULL", TransferPullMessage::class.java.responseName())
        assertEquals("TRANSFER_PUSH", TransferPushMessage::class.java.responseName())
        assertEquals("BACKUP_CREATE", BackupCreateMessage::class.java.responseName())
        assertEquals("BACKUP_LIST", BackupListMessage::class.java.responseName())
        assertEquals("BACKUP_DELETE", BackupDeleteMessage::class.java.responseName())
        assertEquals("BACKUP_RESTORE", BackupRestoreMessage::class.java.responseName())
        assertEquals("INSTALL_PLUGIN", InstallPluginMessage::class.java.responseName())
        assertEquals("SYNC_SCHEDULES", SyncSchedulesMessage::class.java.responseName())
        assertEquals("SET_METRICS_INTERVAL", SetMetricsIntervalMessage::class.java.responseName())
        assertEquals("PANO_PLUGIN_UPDATE", PanoPluginUpdateMessage::class.java.responseName())
    }

    @Test
    fun `a self-update is routed by its handler's name and decodes every field Pano sends`() {
        val handler = PanoPluginUpdateHandler(
            com.panomc.plugins.pano.core.update.SelfUpdateService(
                TestPluginMain(File(".")),
                com.panomc.plugins.pano.core.config.ConfigManager(
                    io.vertx.core.Vertx.vertx().also { vertx = it },
                    Logger.getLogger("pano-test"),
                    File(".")
                ).also { configManager = it },
                com.panomc.plugins.pano.core.task.TaskReporter({}, Logger.getLogger("pano-test")),
                {},
                Logger.getLogger("pano-test")
            ),
            Logger.getLogger("pano-test")
        )

        try {
            assertEquals("PANO_PLUGIN_UPDATE", handler.getHandlerName())
        } finally {
            configManager?.close()
            vertx?.close()
        }

        // Pano's PanoPluginUpdateMessage, field for field, as its socket sends it.
        val message = com.panomc.plugins.pano.core.Pano.gson.fromJson(
            """{"event":"PANO_PLUGIN_UPDATE","eventId":"8a4f0c9e-7d2b-4c1a-9f3e-2b6d5e8a1c07","taskId":"t-1",""" +
                """"url":"/api/server/pano-plugin/jar","sha256":"ab","size":42,""" +
                """"fileName":"pano-velocity-1.0.0.jar","version":"1.0.0"}""",
            PanoPluginUpdateMessage::class.java
        )

        assertEquals("t-1", message.taskId)
        assertEquals("/api/server/pano-plugin/jar", message.url)
        assertEquals(42L, message.size)
        assertEquals("pano-velocity-1.0.0.jar", message.fileName)
        assertEquals("1.0.0", message.version)
    }

    @Test
    fun `a self-update result answers with the id it was asked with and the agreed fields`() {
        val eventId = UUID.randomUUID()

        val encoded = JsonObject(
            PanoPluginUpdateResultRequest(eventId, "task-1", true, null, "1.0.0-alpha.63", "swap-on-shutdown").encode()
        )

        assertEquals("PANO_PLUGIN_UPDATE_RESULT", encoded.getString("event"))
        assertEquals(eventId.toString(), encoded.getString("eventId"))
        assertEquals("task-1", encoded.getString("taskId"))
        assertEquals(true, encoded.getBoolean("ok"))
        assertEquals(null, encoded.getValue("error"))
        assertEquals("1.0.0-alpha.63", encoded.getString("stagedVersion"))
        assertEquals("swap-on-shutdown", encoded.getString("mode"))
    }

    @Test
    fun `the metrics cadence command decodes its interval`() {
        // Pano's frames are decoded with the plugin's Gson (PlatformManager), field for field.
        val message = com.panomc.plugins.pano.core.Pano.gson.fromJson(
            """{"event":"SET_METRICS_INTERVAL","intervalMs":1000}""",
            SetMetricsIntervalMessage::class.java
        )

        assertEquals(1000L, message.intervalMs)
    }

    @Test
    fun `every file request is routed by the name of the handler that answers it`() {
        // The ten `FILE_*` operations share one message shape, so the handler's own name is the
        // route (AGENT.md 2.4.4) - and FileService dispatches on the same strings.
        val handlers = listOf(
            FileListHandler(agent),
            FileReadHandler(agent),
            FileWriteHandler(agent),
            FileMkdirHandler(agent),
            FileDeleteHandler(agent),
            FileRenameHandler(agent),
            FileArchiveHandler(agent),
            FileUnarchiveHandler(agent),
            FileChmodHandler(agent),
            FileHashesHandler(agent)
        )

        assertEquals(FileService.OPERATIONS, handlers.map { it.getHandlerName() })
    }

    @Test
    fun `a file result answers with the id it was asked with and the payload at the top level`() {
        val eventId = UUID.randomUUID()

        val encoded = JsonObject(
            FileResultRequest(eventId, FileService.success().put("entries", JsonArray())).encode()
        )

        assertEquals("FILE_RESULT", encoded.getString("event"))
        assertEquals(eventId.toString(), encoded.getString("eventId"))
        assertEquals(true, encoded.getBoolean("ok"))
        // Spread, not nested: Pano reads `{ eventId, ok, error?, ...payload }`.
        assertEquals(0, encoded.getJsonArray("entries").size())

        val failed = JsonObject(FileResultRequest(eventId, FileService.failure("PATH_DENIED")).encode())

        assertEquals(false, failed.getBoolean("ok"))
        assertEquals("PATH_DENIED", failed.getString("error"))
    }

    @Test
    fun `task progress carries the agreed event name and fields`() {
        val encoded = JsonObject(
            TaskProgressRequest("task-1", "BACKUP", "RUNNING", 15, "Archiving", null).encode()
        )

        assertEquals("TASK_PROGRESS", encoded.getString("event"))
        assertEquals("task-1", encoded.getString("taskId"))
        assertEquals("BACKUP", encoded.getString("kind"))
        assertEquals("RUNNING", encoded.getString("status"))
        assertEquals(15, encoded.getInteger("percent"))
        assertEquals("Archiving", encoded.getString("message"))
        assertEquals(null, encoded.getValue("error"))
    }

    @Test
    fun `a finished backup and an applied restore carry the agreed shapes`() {
        val created = JsonObject(
            BackupCreatedRequest(
                JsonObject()
                    .put("id", "backup-1")
                    .put("name", "Nightly")
                    .put("sizeBytes", 12L)
                    .put("sha256", "abc")
                    .put("createdAt", 7L)
            ).encode()
        )

        assertEquals("BACKUP_CREATED", created.getString("event"))
        assertEquals(
            setOf("id", "name", "sizeBytes", "sha256", "createdAt"),
            created.getJsonObject("backup").fieldNames()
        )

        val restored = JsonObject(BackupRestoredRequest("backup-1", "task-1", true, null).encode())

        assertEquals("BACKUP_RESTORED", restored.getString("event"))
        assertEquals("backup-1", restored.getString("backupId"))
        assertEquals("task-1", restored.getString("taskId"))
        assertEquals(true, restored.getBoolean("ok"))
        assertEquals(null, restored.getValue("error"))
    }

    @Test
    fun `a schedule run reports the whole run and every step of it`() {
        val encoded = JsonObject(
            ScheduleRunRequest(
                scheduleId = "schedule-1",
                startedAt = 1L,
                finishedAt = 2L,
                ok = false,
                error = "The server was not running.",
                tasks = listOf(ScheduleRunRequest.TaskResult("COMMAND", false, "The server was not running."))
            ).encode()
        )

        assertEquals("SCHEDULE_RUN", encoded.getString("event"))
        assertEquals("schedule-1", encoded.getString("scheduleId"))
        assertEquals(1L, encoded.getLong("startedAt"))
        assertEquals(2L, encoded.getLong("finishedAt"))
        assertEquals(false, encoded.getBoolean("ok"))

        assertEquals(setOf("kind", "ok", "error"), encoded.getJsonArray("tasks").getJsonObject(0).fieldNames())
    }
}
