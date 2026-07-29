package com.panomc.plugins.pano.core.config

import com.panomc.plugins.pano.core.config.migration.ConfigMigration1To2
import com.panomc.plugins.pano.core.config.migration.ConfigMigration2To3
import com.panomc.plugins.pano.core.config.migration.ConfigMigration3To4
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueFactory
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

@Component
class ConfigManager(vertx: Vertx, private val logger: Logger, dataFolder: File) {
    private val defaultConfig by lazy {
        val latestVersion = migrations.maxByOrNull { it.to }?.to ?: 1

        PanoConfig(latestVersion)
    }

    // saveConfig() is reachable concurrently from the Vert.x event loop (platform
    // save/remove) and the command thread (e.g. /pano runs off-thread), so the whole
    // read-merge-write-move cycle is serialized on this lock to avoid two overlapping
    // saves publishing a half-written merge over config.conf.
    private val saveLock = Any()

    fun saveConfig(): Boolean {
        val renderOptions = ConfigRenderOptions
            .defaults()
            .setJson(false)           // false: HOCON, true: JSON
            .setOriginComments(false) // true: add comment showing the origin of a value
            .setComments(true)        // true: keep original comment
            .setFormatted(true)

        // Write to a temp file and atomically move it into place. config.conf holds the RSA
        // private key and platform token, so an in-place truncating write left half-done by a
        // crash or full disk would destroy them with no backup.
        synchronized(saveLock) {
            var tmpFile: File? = null

            try {
                if (configFile.parentFile != null && !configFile.parentFile.exists()) {
                    configFile.parentFile.mkdirs()
                }

                // Merge onto the file's own parsed source instead of a fresh Gson blob, so user
                // comments, HOCON includes and any key PanoConfig doesn't model survive the
                // rewrite. If the existing file doesn't even parse (the exact case saveConfig()
                // is called to recover from) fall back to an empty base instead of aborting.
                // Note: this only ever overwrites/adds paths present in the model's JSON, it
                // never removes a path that disappears from the model. That's acceptable today
                // because every caller (default config, migrations, platform save/remove)
                // writes explicit non-null values for every field it touches instead of
                // deleting a key outright; a future nullable field cleared to null would need
                // an explicit withoutPath() to actually disappear from disk.
                val baseConfig = if (configFile.exists()) {
                    try {
                        ConfigFactory.parseFile(configFile)
                    } catch (e: Exception) {
                        ConfigFactory.empty()
                    }
                } else {
                    ConfigFactory.empty()
                }

                val mergedConfig = applyJsonObject(baseConfig, JsonObject(config.toString()))

                // Unique temp file per call: two overlapping saves must not write the same
                // temp file and race each other's Files.move.
                tmpFile = File.createTempFile("config", ".tmp", configFile.parentFile)
                tmpFile.writeText(mergedConfig.root().render(renderOptions))

                Files.move(
                    tmpFile.toPath(),
                    configFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )

                return true
            } catch (e: Exception) {
                logger.severe("Failed to save config file: $e")

                tmpFile?.delete()

                return false
            }
        }
    }

    /**
     * Applies every field of [json] onto [base] at its HOCON path, recursing into nested
     * objects (e.g. "platform.host"). Used instead of a full JSON round-trip so unrelated
     * parts of the parsed source (comments, includes, unknown keys) are left untouched.
     */
    private fun applyJsonObject(base: Config, json: JsonObject, prefix: String = ""): Config {
        var result = base

        for (fieldName in json.fieldNames()) {
            val path = if (prefix.isEmpty()) fieldName else "$prefix.$fieldName"
            val value = json.getValue(fieldName)

            result = if (value is JsonObject) {
                applyJsonObject(result, value, path)
            } else {
                result.withValue(path, ConfigValueFactory.fromAnyRef(value))
            }
        }

        return result
    }

    internal suspend fun init() {
        if (!configFile.exists()) {
            logger.warning("Config file not found, creating one...")

            updateConfig(JsonObject(defaultConfig.toString()))

            if (!saveConfig()) {
                logger.severe("Failed to write the new default config file to disk.")
            }

            listenConfigFile()

            return
        }

        try {
            val configValues = configRetriever.config.coAwait()

            // An empty file or one missing "config-version" is still valid HOCON, so the
            // retriever won't throw on it — treat it the same as a parse failure instead of
            // silently booting with a blank/half-migrated config.
            if (configValues.isEmpty() || !configValues.containsKey("config-version")) {
                throw IllegalStateException("Config file is empty or missing config-version")
            }

            updateConfig(configValues)

            logger.info("Loaded config file.")
        } catch (e: Exception) {
            logger.severe("Config file is invalid! Error: $e")

            // Assign the fallback config before attempting the backup: a failed backup (e.g.
            // read-only data dir) must not leave the lateinit `config` uninitialized.
            updateConfig(JsonObject(defaultConfig.toString()))

            try {
                backupConfigFile()
            } catch (backupException: Exception) {
                logger.warning("Could not back up the invalid config file: $backupException")
            }

            logger.info("Saving & using default config!")

            if (!saveConfig()) {
                logger.severe("Failed to write the default config file to disk after recovery.")
            }

            listenConfigFile()

            return
        }

        // Deliberately outside the try/catch above: a migration failure here means the
        // loaded file was readable and valid, just on an older schema. It must NOT be
        // treated the same as an unparseable file and fall into the reset-to-defaults
        // recovery path, which would discard a valid RSA keypair and platform token. Let it
        // propagate out of init() and leave config.conf untouched instead.
        logger.info("Checking available config migrations")

        migrate()

        listenConfigFile()
    }

    lateinit var config: PanoConfig
        private set

    private lateinit var configJsonObject: JsonObject

    private val migrations = listOf<ConfigMigration>(
        ConfigMigration1To2(),
        ConfigMigration2To3(),
        ConfigMigration3To4()
    )

    private val configFile = File(dataFolder.path + "/config.conf")

    private val fileStore = ConfigStoreOptions()
        .setType("file")
        .setFormat("hocon")
        .setConfig(JsonObject().put("path", configFile.path))

    private val options = ConfigRetrieverOptions().addStore(fileStore)

    private val configRetriever = ConfigRetriever.create(vertx, options)

    private fun migrate(
        // Defensive default: getInteger() used to unbox a null/non-numeric "config-version"
        // straight into NPE/CCE. Fall back to 1 instead. Must widen to Number first: Vert.x's
        // JSON parsing can hand back a Long/Double/BigDecimal for a numeric value, and `as?
        // Int` yields null for all of those (silently falling back to 1 and re-running old
        // migrations on an up-to-date config).
        configVersion: Int = (configJsonObject.getValue("config-version") as? Number)?.toInt() ?: 1,
        saveConfig: Boolean = true
    ): Boolean {
        val migrated = migrations
            .find { configMigration -> configMigration.isMigratable(configVersion) }
            ?.let { migration ->
                logger.info("Migration Found! Migrating config from version ${migration.from} to ${migration.to}: ${migration.versionInfo}")

                configJsonObject.put("config-version", migration.to)

                try {
                    migration.migrate(configJsonObject)
                } catch (e: Exception) {
                    logger.severe("Migration from version ${migration.from} to ${migration.to} failed: $e")

                    throw IllegalStateException("Config migration ${migration.from} -> ${migration.to} failed", e)
                }

                migrate(migration.to, false)

                true
            } ?: false

        // Only rewrite config.conf when a migration actually changed something — this used
        // to run unconditionally on every boot, wiping comments/includes for nothing.
        if (saveConfig && migrated) {
            updateConfig(configJsonObject)

            if (!saveConfig()) {
                logger.severe("Failed to persist config after migrating to version ${configJsonObject.getValue("config-version")}.")
            }
        }

        return migrated
    }

    private fun listenConfigFile() {
        logger.info("Started to listen config file changes.")

        configRetriever.listen { change ->
            if (change.previousConfiguration.encode() != change.newConfiguration.encode()) {
                logger.info("Config is updated, reloading...")
            }

            updateConfig(change.newConfiguration)
        }
    }

    private fun backupConfigFile() {
        logger.info("Backing up config file...")

        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss") // Exp: 2025-01-28_15-45-30
        val formattedDate = now.format(formatter)

        val filePath = configFile.parentFile.absolutePath + File.separator + "config-backup-$formattedDate.conf"

        // overwrite: a same-second retry (e.g. after a prior backup attempt failed) must not
        // throw FileAlreadyExistsException.
        configFile.copyTo(File(filePath), overwrite = true)

        logger.info("Config file backed up to: $filePath")
    }

    private fun updateConfig(newConfig: JsonObject) {
        config = PanoConfig.from(newConfig)
        configJsonObject = newConfig.copy()
    }
}