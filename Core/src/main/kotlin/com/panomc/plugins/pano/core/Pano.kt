package com.panomc.plugins.pano.core

import com.google.gson.GsonBuilder
import com.panomc.plugins.pano.core.annotation.Boot
import com.panomc.plugins.pano.core.command.CommandManager
import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.event.EventManager
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.i18n.I18nManager
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.schedule.ScheduleManager
import com.panomc.plugins.pano.core.util.deseriliazer.JsonObjectDeserializer
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference
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

        // Recreated on demand: disable() closes the Vert.x instance, and a platform may enable Pano
        // again afterwards (e.g. Velocity fires ProxyReloadEvent). A closed instance cannot be
        // reused, so the next init() has to build a fresh one.
        //
        // getPano()'s double-init race (round 8) puts a second thread inside Pano.init() concurrently
        // with the first, and dispose()/disable() can run from yet another thread, so the field itself
        // needs to be safe under concurrent access. AtomicReference keeps every touch of the field a
        // single lock-free CAS/getAndSet; it deliberately does NOT wrap Vertx.vertx(...), deployVerticle(),
        // or the runBlocking { vertx.close() } below -- holding anything across those would recreate the
        // exact event-loop-parked-on-its-own-close deadlock class the last three rounds removed.
        private val vertxInstanceRef = AtomicReference<Vertx?>(null)

        private fun getOrCreateVertx(): Vertx {
            while (true) {
                vertxInstanceRef.get()?.let { return it }

                val created = Vertx.vertx(options)

                if (vertxInstanceRef.compareAndSet(null, created)) {
                    return created
                }

                // Lost the race: another thread's getOrCreateVertx() already installed an instance (or
                // closeVertx() cleared it again right after). Ours is surplus -- close it in the
                // background, fire-and-forget, and loop to pick up whatever is now in vertxInstanceRef
                // instead of leaking it.
                created.close().onFailure { cause ->
                    Logger.getLogger(Pano::class.java.name)
                        .log(Level.WARNING, "Failed to close a surplus Vert.x instance created during a getOrCreateVertx() race", cause)
                }
            }
        }

        private fun closeVertx() {
            val currentVertx = vertxInstanceRef.getAndSet(null) ?: return

            runBlocking {
                currentVertx.close().coAwait()
            }
        }

        private lateinit var urlClassLoader: URLClassLoader

        // MODE comes from urlClassLoader, which init() only assigns once the platform calls Pano.init() —
        // so this (and everything derived from it) must stay lazy, never an eager `val`, or it evaluates
        // during <clinit> against an unset lateinit and silently falls back to "RELEASE" forever.
        private val mode by lazy {
            try {
                val manifestUrl = urlClassLoader.findResource("META-INF/MANIFEST.MF")

                // use{} so the manifest's underlying stream is closed instead of leaked (cross-cutting-14).
                manifestUrl.openStream().use { stream ->
                    Manifest(stream).mainAttributes.getValue("MODE") ?: "RELEASE"
                }
            } catch (e: Exception) {
                Logger.getLogger(Pano::class.java.name)
                    .log(Level.WARNING, "Failed to read MODE from manifest, defaulting to RELEASE", e)

                "RELEASE"
            }
        }

        var serverStartTime by Delegates.notNull<Long>()
            private set

        // by lazy: must be evaluated after init() assigns urlClassLoader (see the `mode` comment above).
        val ENVIRONMENT by lazy {
            // EnvironmentType != "DEVELOPMENT" (not isNullOrEmpty()): a *non-empty* override such as
            // "RELEASE" must not accidentally select DEVELOPMENT.
            if (mode != "DEVELOPMENT" && System.getenv("EnvironmentType") != "DEVELOPMENT")
                EnvironmentType.RELEASE
            else
                EnvironmentType.DEVELOPMENT
        }

        val VERSION by lazy {
            try {
                val manifestUrl = urlClassLoader.findResource("META-INF/MANIFEST.MF")

                // use{} so the manifest's underlying stream is closed instead of leaked (cross-cutting-14).
                manifestUrl.openStream().use { stream ->
                    Manifest(stream).mainAttributes.getValue("VERSION").toString()
                }
            } catch (e: Exception) {
                System.getenv("PanoPluginVersion").toString()
            }
        }

        fun init(panoPluginMain: PanoPluginMain): Pano {
            urlClassLoader = panoPluginMain.getPluginClassLoader()

            val pano = Pano(panoPluginMain)

            runBlocking {
                try {
                    pano.deploymentId = getOrCreateVertx().deployVerticle(pano).coAwait()
                } catch (deployException: Exception) {
                    // Deployment failed before the platform ever assigns its `pano` field, so this is
                    // the only chance to release the Vert.x instance getOrCreateVertx() just stored —
                    // otherwise its event-loop/worker threads leak for the life of the server, and on
                    // Spigot/Bungee (a throwing `lazy { Pano.init(this) }`) every later touch of `pano`
                    // re-enters init() and leaks another instance. The close itself must not shadow the
                    // real failure, so it's caught and only logged.
                    try {
                        closeVertx()
                    } catch (closeException: Exception) {
                        deployException.addSuppressed(closeException)

                        Logger.getLogger(Pano::class.java.name)
                            .log(Level.WARNING, "Failed to close Vert.x instance after failed deployment", closeException)
                    }

                    throw deployException
                }
            }

            return pano
        }

        enum class EnvironmentType {
            DEVELOPMENT, RELEASE
        }

        internal val gson by lazy {
            GsonBuilder()
                .registerTypeAdapter(JsonObject::class.java, JsonObjectDeserializer())
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
    lateinit var i18nManager: I18nManager
        private set
    private val logger = panoPluginMain.getPanoLogger()
    private var stopping = false

    // Set by init() from the deployVerticle result; identifies THIS verticle deployment only.
    // @Volatile: init() (which writes this) and dispose() (which reads/clears it) can run on different
    // threads under the round-8 double-init race and via getPano() from a Vert.x event-loop thread, so
    // a plain var risks the write never becoming visible to the later read.
    @Volatile
    private var deploymentId: String? = null

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

        // stop() also runs when Vert.x rolls back a failed deployVerticle (init() throwing, e.g.
        // from initConfigManager()) by undeploying this half-started verticle. At that point these
        // managers are lateinit and may not be assigned yet, so each is guarded with isInitialized
        // and given its own try/catch: an unguarded/uncaught access here would throw
        // UninitializedPropertyAccessException (or let one manager's failure abort the rest),
        // masking the real startup error and skipping the later teardown steps.
        var anyInitialized = false

        if (::commandManager.isInitialized) {
            anyInitialized = true

            try {
                commandManager.disable()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to disable command manager", e)
            }
        }

        if (::scheduleManager.isInitialized) {
            anyInitialized = true

            try {
                scheduleManager.disable()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to disable schedule manager", e)
            }
        }

        if (::eventManager.isInitialized) {
            anyInitialized = true

            try {
                eventManager.disable()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to disable event manager", e)
            }
        }

        if (::platformManager.isInitialized) {
            anyInitialized = true

            try {
                platformManager.stop()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to stop platform manager", e)
            }
        }

        if (anyInitialized) {
            logger.info("Pano is disabled")
        } else {
            logger.info("Pano was never fully initialized; nothing to disable")
        }
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

        closeVertx()
    }

    /**
     * Disposes ONLY this Pano instance's verticle deployment, leaving the shared Vert.x instance and any other
     * deployment untouched. Safe to call from any thread including a Vert.x event loop: it never blocks and never
     * calls runBlocking. Use this for a losing double-init race; use disable() only for a real platform shutdown.
     */
    fun dispose() {
        val id = deploymentId ?: return

        deploymentId = null

        // vertx here is this verticle's own AbstractVerticle-assigned instance (the one it was actually
        // deployed onto), never the companion's vertxInstance -- so this can never race disable()'s
        // closeVertx() over the same field, and never closes the shared Vert.x other deployments rely on.
        vertx.undeploy(id).onFailure { cause ->
            logger.log(Level.WARNING, "Failed to undeploy Pano verticle $id during dispose()", cause)
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
        i18nManager = applicationContext.getBean(I18nManager::class.java)
        platformManager = applicationContext.getBean(PlatformManager::class.java)
    }

    private suspend fun initConfigManager() {
        logger.info("Initializing config manager")

        configManager = applicationContext.getBean(ConfigManager::class.java)

        try {
            configManager.init()
        } catch (e: Exception) {
            // ConfigManager.init() deliberately lets a migration failure propagate so config.conf
            // is left untouched, but by this point updateConfig(configValues) already ran against
            // the pre-migration values and listenConfigFile() never got reached (live reloads dead
            // for the session). Continuing silently would run the whole plugin on that half-migrated
            // config with no operator-visible error, so fail this verticle's deployment loudly
            // instead: the platform's onEnable then disables just this plugin, and
            // BanIntegration's "WebSocket not connected" fast path keeps logins working (fail-open)
            // rather than bricking the server.
            logger.log(Level.SEVERE, "Failed to initialize config manager, aborting startup.", e)

            throw e
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