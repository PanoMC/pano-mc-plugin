package com.panomc.plugins.pano.core.schedule

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.plugins.pano.core.platform.message.response.SyncScheduleEntry
import java.io.File
import java.util.logging.Logger

/**
 * The schedules this server was last told about, kept across restarts.
 *
 * Pano re-syncs on every connect, so this file is never the long-term truth - it is what covers
 * the gap between a server starting and Pano getting round to telling it anything, which is
 * exactly the window a 04:00 restart would otherwise fall into on a night when the platform is
 * down. A file that cannot be read is simply "no schedules", never a boot failure.
 *
 * Written next to the plugin's own config (`plugins/Pano/schedules.json`), not into the server
 * directory: it is this plugin's state, and the file manager has no business showing it.
 */
class ScheduleStore(private val dataFolder: File, private val logger: Logger) {
    private val file: File get() = File(dataFolder, FILE_NAME)

    fun read(): List<SyncScheduleEntry> {
        val source = file

        if (!source.isFile) {
            return emptyList()
        }

        return try {
            gson.fromJson(source.readText(), Stored::class.java)?.schedules.orEmpty()
        } catch (exception: Exception) {
            logger.warning("Could not read ${source.absolutePath}: ${exception.javaClass.simpleName}: ${exception.message}")

            emptyList()
        }
    }

    fun write(schedules: List<SyncScheduleEntry>) {
        try {
            val target = file

            target.parentFile?.mkdirs()
            target.writeText(gson.toJson(Stored(schedules)))
        } catch (exception: Exception) {
            // Losing the file costs one restart's worth of schedules, which the next sync fixes;
            // it is not worth failing the sync that is already in hand over.
            logger.warning("Could not save the schedules: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    private data class Stored(val schedules: List<SyncScheduleEntry> = emptyList())

    companion object {
        const val FILE_NAME = "schedules.json"

        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    }
}
