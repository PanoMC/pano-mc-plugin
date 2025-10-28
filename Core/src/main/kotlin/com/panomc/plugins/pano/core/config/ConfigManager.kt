package com.panomc.plugins.pano.core.config

import com.panomc.plugins.pano.core.config.migration.ConfigMigration1To2
import com.panomc.plugins.pano.core.config.migration.ConfigMigration2To3
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import org.springframework.stereotype.Component
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

@Component
class ConfigManager(vertx: Vertx, private val logger: Logger, dataFolder: File) {
    private val defaultConfig by lazy {
        val latestVersion = migrations.maxByOrNull { it.to }?.to ?: 1

        PanoConfig(latestVersion)
    }

    fun saveConfig() {
        val renderOptions = ConfigRenderOptions
            .defaults()
            .setJson(false)           // false: HOCON, true: JSON
            .setOriginComments(false) // true: add comment showing the origin of a value
            .setComments(true)        // true: keep original comment
            .setFormatted(true)

        val parsedConfig = ConfigFactory.parseString(config.toString())

        if (configFile.parentFile != null && !configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }

        configFile.writeText(parsedConfig.root().render(renderOptions))
    }

    internal suspend fun init() {
        if (!configFile.exists()) {
            logger.warning("Config file not found, creating one...")

            updateConfig(JsonObject(defaultConfig.toString()))
            saveConfig()
            listenConfigFile()

            return
        }

        try {
            val configValues = configRetriever.config.coAwait()

            updateConfig(configValues)

            logger.info("Loaded config file.")
        } catch (e: Exception) {
            logger.severe("Config file is invalid! Error: $e")

            backupConfigFile()

            logger.info("Saving & using default config!")

            updateConfig(JsonObject(defaultConfig.toString()))
            saveConfig()
            listenConfigFile()

            return
        }

        logger.info("Checking available config migrations")

        migrate()

        listenConfigFile()
    }

    lateinit var config: PanoConfig
        private set

    private lateinit var configJsonObject: JsonObject

    private val migrations = listOf<ConfigMigration>(
        ConfigMigration1To2(),
        ConfigMigration2To3()
    )

    private val configFile = File(dataFolder.path + "/config.conf")

    private val fileStore = ConfigStoreOptions()
        .setType("file")
        .setFormat("hocon")
        .setConfig(JsonObject().put("path", configFile.path))

    private val options = ConfigRetrieverOptions().addStore(fileStore)

    private val configRetriever = ConfigRetriever.create(vertx, options)

    private fun migrate(
        configVersion: Int = configJsonObject.getInteger("config-version"),
        saveConfig: Boolean = true
    ) {
        migrations
            .find { configMigration -> configMigration.isMigratable(configVersion) }
            ?.let { migration ->
                logger.info("Migration Found! Migrating config from version ${migration.from} to ${migration.to}: ${migration.versionInfo}")

                configJsonObject.put("config-version", migration.to)

                migration.migrate(configJsonObject)

                migrate(migration.to, false)
            }

        if (saveConfig) {
            updateConfig(configJsonObject)
            saveConfig()
        }
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

        configFile.copyTo(File(filePath))

        logger.info("Config file backed up to: $filePath")
    }

    private fun updateConfig(newConfig: JsonObject) {
        config = PanoConfig.from(newConfig)
        configJsonObject = newConfig.copy()
    }
}