package com.panomc.plugins.pano.core

import com.google.gson.GsonBuilder
import com.panomc.plugins.pano.core.annotation.Boot
import com.panomc.plugins.pano.core.command.CommandManager
import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.event.EventManager
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.schedule.ScheduleManager
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.net.URLClassLoader
import java.util.jar.Manifest
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.properties.Delegates

@Boot
class Pano(private val panoPluginMain: PanoPluginMain) : CoroutineVerticle() {
    companion object {
        private val options by lazy {
            VertxOptions()
        }

        private val vertx by lazy {
            Vertx.vertx(options)
        }

        private lateinit var urlClassLoader: URLClassLoader

        private val mode by lazy {
            try {
                val manifestUrl = urlClassLoader.findResource("META-INF/MANIFEST.MF")
                val manifest = Manifest(manifestUrl.openStream())

                manifest.mainAttributes.getValue("MODE").toString()
            } catch (e: Exception) {
                "RELEASE"
            }
        }

        var serverStartTime by Delegates.notNull<Long>()
            private set

        val ENVIRONMENT =
            if (mode != "DEVELOPMENT" && System.getenv("EnvironmentType").isNullOrEmpty())
                EnvironmentType.RELEASE
            else
                EnvironmentType.DEVELOPMENT

        val VERSION by lazy {
            try {
                val manifestUrl = urlClassLoader.findResource("META-INF/MANIFEST.MF")
                val manifest = Manifest(manifestUrl.openStream())

                manifest.mainAttributes.getValue("VERSION").toString()
            } catch (e: Exception) {
                System.getenv("PanoPluginVersion").toString()
            }
        }

        fun init(panoPluginMain: PanoPluginMain): Pano {
            urlClassLoader = panoPluginMain.getPluginClassLoader()

            val pano = Pano(panoPluginMain)

            runBlocking {
                vertx.deployVerticle(pano).coAwait()
            }

            return pano
        }

        enum class EnvironmentType {
            DEVELOPMENT, RELEASE
        }

        internal val gson by lazy {
            GsonBuilder()
                .create()
        }
    }

    private lateinit var applicationContext: AnnotationConfigApplicationContext
    private lateinit var configManager: ConfigManager
    lateinit var eventManager: EventManager
        private set
    private lateinit var commandManager: CommandManager
    private lateinit var scheduleManager: ScheduleManager
    lateinit var platformManager: PlatformManager
        private set
    private val logger = panoPluginMain.getPanoLogger()
    private var stopping = false

    override suspend fun start() {
        stopping = false
        logger.info(
            panoPluginMain.translateColor(
                "&9\n" +
                        " ______   ______     __   __     ______    \n" +
                        "/\\  == \\ /\\  __ \\   /\\ \"-.\\ \\   /\\  __ \\   \n" +
                        "\\ \\  _-/ \\ \\  __ \\  \\ \\ \\-.  \\  \\ \\ \\/\\ \\  \n" +
                        " \\ \\_\\    \\ \\_\\ \\_\\  \\ \\_\\\\\"\\_\\  \\ \\_____\\ \n" +
                        "  \\/_/     \\/_/\\/_/   \\/_/ \\/_/   \\/_____/  v${VERSION}\n" +
                        "                                           "
            )
        )

        logger.info("Initializing Pano MC")

        init()
    }

    override suspend fun stop() {
        logger.info("Disabling Pano")

        commandManager.disable()

        scheduleManager.disable()

        eventManager.disable()

        platformManager.stop()

        logger.info("Pano is disabled")
    }

    fun onServerStart() {
        serverStartTime = System.currentTimeMillis()

        platformManager.start()
    }

    fun disable() {
        if (stopping) {
            return
        }

        stopping = true

        runBlocking {
            vertx.close().coAwait()
        }
    }

    private suspend fun init() {
        logger.info("Initializing logger settings")

        Logger.getLogger("io.vertx").level = Level.SEVERE

        initDependencyInjection()

        initConfigManager()

        initCommandManager()

        initEventManager()
    }

    private fun initDependencyInjection() {
        logger.info("Initializing dependency injection")

        SpringConfig.setDefaults(vertx, panoPluginMain)

        applicationContext = AnnotationConfigApplicationContext(SpringConfig::class.java)
        scheduleManager = applicationContext.getBean(ScheduleManager::class.java)
        platformManager = applicationContext.getBean(PlatformManager::class.java)
    }

    private suspend fun initConfigManager() {
        logger.info("Initializing config manager")

        configManager = applicationContext.getBean(ConfigManager::class.java)

        try {
            configManager.init()
        } catch (e: Exception) {
            println(e)
        }
    }

    private fun initCommandManager() {
        logger.info("Initializing command manager")

        commandManager = applicationContext.getBean(CommandManager::class.java)

        commandManager.init()
    }

    private fun initEventManager() {
        logger.info("Initializing event manager")

        eventManager = applicationContext.getBean(EventManager::class.java)

        eventManager.init()
    }
}