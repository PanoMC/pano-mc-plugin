package com.panomc.plugins.pano.core

import com.google.gson.GsonBuilder
import com.panomc.plugins.pano.core.annotation.Boot
import com.panomc.plugins.pano.core.command.CommandManager
import com.panomc.plugins.pano.core.config.ConfigManager
import com.panomc.plugins.pano.core.event.EventManager
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.i18n.I18nManager
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.util.LoggerUtil
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
        // R2 (see the getPano() contract on each platform main) serializes Pano.init() itself behind
        // panoInitLock, so there is at most one thread ever building a Pano process-wide -- that alone
        // doesn't need a lock-free field. What still does is R3: disable() (-> closeVertx()) is
        // deliberately NEVER called under panoInitLock, because holding it across a shutdown that can
        // re-enter the main via onDisconnect would reintroduce the exact deadlock class this project
        // already shipped. So a disable() on one thread can run concurrently with a getPano() call
        // still inside init() on another -- the R5 abort-after-disable orphan path exists precisely
        // because of that race -- and the field needs to stay safe under that concurrent access.
        // AtomicReference keeps every touch of the field a single lock-free CAS/getAndSet; it
        // deliberately does NOT wrap Vertx.vertx(...), deployVerticle(), or the
        // runBlocking { vertx.close() } below -- holding anything across those would recreate the
        // exact event-loop-parked-on-its-own-close deadlock class the last three rounds removed.
        private val vertxInstanceRef = AtomicReference<Vertx?>(null)

        private fun getOrCreateVertx(): Vertx {
            while (true) {
                vertxInstanceRef.get()?.let { return it }

                val created = Vertx.vertx(options)

                if (vertxInstanceRef.compareAndSet(null, created)) {
                    return created
                }

                // Not expected to fire in normal operation: R2 serializes every Pano.init() call
                // behind panoInitLock, so at most one thread is ever inside getOrCreateVertx() at a
                // time, and the only other writers of this field (closeVertx(), closeVertxIfCurrent(),
                // closeVertxForAbortedInit()) only ever write null here, never a competing instance --
                // nothing should make this CAS(null, created) fail. Kept as a defensive fallback
                // rather than an assumed guarantee, since getOrCreateVertx() has no way to verify its
                // caller actually holds panoInitLock. If it ever does fire: another thread's
                // getOrCreateVertx() already installed an instance (or closeVertx() cleared it again
                // right after). Ours is surplus -- close it in the background, fire-and-forget, and
                // loop to pick up whatever is now in vertxInstanceRef instead of leaking it.
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

        // Used only by init()'s deploy-failure catch below. Unlike closeVertx() (a real shutdown,
        // which intentionally closes whatever instance is current), a failed deployVerticle() must
        // close exactly the instance THIS init() call obtained from getOrCreateVertx() -- and only
        // while it is still the one installed in vertxInstanceRef. This is NOT protection against a
        // double-init race -- R2 (panoInitLock, held by getPano() across the whole of Pano.init())
        // makes that impossible, since at most one thread is ever inside init() process-wide. The
        // real hazard is R3: disable()/closeVertx() is deliberately NEVER called under panoInitLock,
        // so it can run concurrently on another thread while this init() call is still in flight, and
        // its unconditional getAndSet(null) would happily close and clear whatever is current --
        // including this very instance, or a later one that has already replaced it. The CAS here
        // makes sure we only ever close the exact instance this call obtained, and only if nothing
        // else (a concurrent disable(), or another getOrCreateVertx() race-loser cleanup) has already
        // claimed or cleared it first.
        private fun closeVertxIfCurrent(vertxInstance: Vertx) {
            if (!vertxInstanceRef.compareAndSet(vertxInstance, null)) {
                return
            }

            runBlocking {
                vertxInstance.close().coAwait()
            }
        }

        /**
         * Fire-and-forget cleanup for ONE case only: the getPano() abort-after-disable orphan (R5). A
         * thread can finish Pano.init() (deployVerticle succeeded, so getOrCreateVertx() already ran
         * once for it) only to find its main was disabled while it was still inside init() -- the
         * resulting Pano instance must never be published, and must be [dispose]d instead (see that
         * KDoc). Nothing will ever use the instance that orphan was deployed onto once it is disposed,
         * but it still has to be closed -- its event-loop/worker threads are non-daemon and would
         * otherwise hang JVM shutdown.
         *
         * In practice that instance is almost always the one already installed in vertxInstanceRef, NOT
         * a brand-new one: a main only reaches closeVertx() through `pano?.disable()`, and while an
         * init() is in flight mPano is still null (getPano() assigns it only after init() returns, under
         * panoInitLock), so the concurrent onDisable() has nothing to disable and never closes anything.
         * The CAS below is what makes that safe to rely on rather than assume.
         *
         * Call this ONLY after the orphaned instance's own undeploy (started by [dispose]) has settled --
         * prefer the combined [disposeAndCloseVertxForAbortedInit], which calls this at exactly that
         * point; calling [dispose] and this back-to-back as two separate statements races this close()
         * (which undeploys everything on [vertxInstance] itself) against dispose()'s own explicit
         * `undeploy(id)` over the same deployment -- see [disposeAndCloseVertxForAbortedInit]'s KDoc.
         * Only ever call this with the orphaned instance's own `vertx` (e.g. `pano.vertx`). Never call it
         * for a real platform shutdown -- use disable() there, which closes the shared Vert.x as part of an
         * already-blocking teardown. Closes [vertxInstance] only while it is still the exact instance installed in
         * vertxInstanceRef: if a concurrent disable()/closeVertx() (R3 lets that happen while this
         * runs) or another getOrCreateVertx() race-loser cleanup already claimed or replaced it, this
         * is a no-op. Never blocks and never throws at the caller -- failures are only logged, exactly
         * like the race-loser cleanup in getOrCreateVertx() above.
         */
        fun closeVertxForAbortedInit(vertxInstance: Vertx) {
            if (!vertxInstanceRef.compareAndSet(vertxInstance, null)) {
                return
            }

            vertxInstance.close().onFailure { cause ->
                Logger.getLogger(Pano::class.java.name)
                    .log(Level.WARNING, "Failed to close an orphaned Vert.x instance from a getPano() abort-after-disable race", cause)
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
            // Deliberately strict: EnvironmentType must equal exactly "DEVELOPMENT" (not merely be
            // non-empty/non-null) to select DEVELOPMENT. This is a documented behaviour change from
            // an earlier version that treated ANY non-empty value as an opt-in to DEVELOPMENT --
            // meaning an operator who set EnvironmentType to something else entirely (a stray
            // "false", "0", or an unrelated tool's own env var value that happened to be non-empty)
            // would silently end up running the plugin in dev mode. The strict, exact-match form is
            // kept on purpose: it makes DEVELOPMENT an explicit opt-in instead of "anything set", at
            // the cost of anyone relying on the old lenient behaviour needing to set
            // EnvironmentType=DEVELOPMENT exactly.
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
                val vertxInstance = getOrCreateVertx()

                try {
                    pano.deploymentId.set(vertxInstance.deployVerticle(pano).coAwait())
                } catch (deployException: Exception) {
                    // Deployment failed before the platform ever assigns its `pano` field, so this is
                    // the only chance to release the Vert.x instance THIS call obtained above —
                    // otherwise its event-loop/worker threads leak for the life of the server, and on
                    // Spigot/Bungee (a throwing `lazy { Pano.init(this) }`) every later touch of `pano`
                    // re-enters init() and leaks another instance. Close exactly that instance, via
                    // closeVertxIfCurrent() rather than closeVertx(): R3 keeps disable() from ever
                    // being called under panoInitLock, so a concurrent disable()/closeVertx() on
                    // another thread can still race this failed init() and already be relying on (or
                    // have already replaced/cleared) whatever is actually in vertxInstanceRef --
                    // closeVertx()'s unconditional close-whatever's-current would tear that out from
                    // under it instead of closing the failed instance this call owns. The close itself
                    // must not shadow the real failure, so it's caught and only logged.
                    try {
                        closeVertxIfCurrent(vertxInstance)
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
    lateinit var platformManager: PlatformManager
        private set
    lateinit var i18nManager: I18nManager
        private set
    private val logger = panoPluginMain.getPanoLogger()
    private var stopping = false

    // Set by init() from the deployVerticle result; identifies THIS verticle deployment only.
    // AtomicReference: init() (the writer, via .set()) and dispose() (the reader, via
    // getAndSet(null)) can run on different threads. dispose() is documented (see its own KDoc) to
    // be safe to call more than once on the same instance -- getAndSet() makes a duplicate/
    // concurrent dispose() call see null and return instead of racing another caller to undeploy
    // the same id twice (cross-cutting-4). A plain @Volatile var would make the write visible but
    // not make that read-then-clear atomic.
    private val deploymentId = AtomicReference<String?>(null)

    override suspend fun start() {
        stopping = false

        quietVertxConnectionEvictionWarnings()

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

    // Every rejected WebSocket upgrade to the platform costs one "Connection evicted" WARN from
    // Vert.x internals, so a server that is simply waiting to be approved in the panel drips two
    // lines per reconnect attempt into the console instead of one. The warning carries no
    // information we don't already report ourselves: WebSocketGroup only installs its own eviction
    // handler on a WebSocket it managed to open (see requestConnection2), so a handshake the
    // platform refuses leaves the underlying Http1xClientConnection on
    // HttpClientConnectionInternal.DEFAULT_EVICTION_HANDLER, whose sole job is to log that line
    // when closeInternal() runs. Nothing on the public WebSocketClient API can replace that
    // handler, so the noise is muted at the logger instead. Scoped to exactly that Vert.x class,
    // and only down to ERROR: "Connection evicted" is the one and only message it ever emits
    // (vertx-core 5.0.4), so nothing else is hidden and a future Vert.x that logs a real error
    // there still gets through.
    private fun quietVertxConnectionEvictionWarnings() {
        if (!LoggerUtil.setLoggerLevel("io.vertx.core.http.impl.HttpClientConnectionInternal", "ERROR")) {
            logger.fine("Could not quiet Vert.x connection eviction warnings: no known logging backend accepted the level change.")
        }
    }

    override suspend fun stop() {
        logger.info("Disabling Pano")

        // stop() also runs when Vert.x rolls back a failed deployVerticle (init() throwing, e.g.
        // from initConfigManager()) by undeploying this half-started verticle. At that point these
        // managers are lateinit and may not be assigned yet, so each is guarded with isInitialized
        // and given its own try/catch: an unguarded/uncaught access here would throw
        // UninitializedPropertyAccessException (or let one manager's failure abort the rest),
        // masking the real startup error and skipping the later teardown steps.
        //
        // Always runs its full teardown now -- there is no more "loser" to skip it for. R2
        // (getPano() serializes Pano.init() behind panoInitLock) means at most one Pano is ever
        // constructed, so the double-init race this method used to special-case (a `disposing`
        // flag that skipped commandManager/eventManager teardown for whichever instance lost) can't
        // happen any more. The one surviving case that calls dispose() -- the R5 abort-after-disable
        // orphan -- calls it on an instance that DID fully init(), so it needs the same full
        // teardown to unregister exactly what it registered; skipping it here would leak that
        // instance's commands/listeners onto the shared plugin main forever.
        var anyInitialized = false

        if (::commandManager.isInitialized) {
            anyInitialized = true

            try {
                commandManager.disable()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to disable command manager", e)
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

        if (::configManager.isInitialized) {
            anyInitialized = true

            try {
                // ConfigManager's ConfigRetriever otherwise keeps polling config.conf via the
                // shared Vert.x forever (its listen() callback, registered by listenConfigFile()).
                // configManager() is declared `@Bean(destroyMethod = "")` in SpringConfig, so
                // applicationContext.close() below does NOT also invoke this via Spring's inferred-
                // destroy-method resolution — see that annotation for why, otherwise this would
                // run twice.
                configManager.close()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to close config manager", e)
            }
        }

        if (::platformManager.isInitialized) {
            anyInitialized = true

            try {
                // platformManager and its WebSocket connection are this instance's own state
                // (built by this instance's DI container, never handed to another Pano instance) —
                // but stop() is not fully self-contained: stop() -> closeConnection() ->
                // webSocket.close() -> the closeHandler registered in
                // establishConnectionToPlatform() -> onWebSocketClosed() ->
                // pluginMain.onDisconnect(), and pluginMain is the SHARED platform-main singleton.
                // onDisconnect() fans out to every registered Integration, which live process-wide
                // and are never owned by this Pano instance. So this call is always safe to run
                // (there's no other Pano instance's PlatformManager to step on) — it's just not
                // confined to this instance's own state on the way down.
                platformManager.stop()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to stop platform manager", e)
            }
        }

        if (::applicationContext.isInitialized) {
            anyInitialized = true

            try {
                // Releases this instance's own DI container along with whatever it created:
                // webClient, webSocketClient and minecraftStatusClient (all instance-scoped, never
                // shared with another Pano instance) get closed via Spring's default "(inferred)"
                // destroy-method resolution, since each declares a public no-arg close(). The
                // vertx() bean is the one exception — declared @Bean(destroyMethod = "") in
                // SpringConfig (see that bean's own comment), so this close() can never reach into
                // the SHARED Vert.x other deployments rely on. configManager's destroy
                // method is disabled the same way (see the configManager.close() block above), so
                // this doesn't double-close its ConfigRetriever.
                applicationContext.close()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to close dependency injection context", e)
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
     * calls runBlocking.
     *
     * Use this ONLY for the getPano() abort-after-disable orphan case (R5): a thread finishes Pano.init()
     * (deployVerticle succeeded) after its main's onDisable() already ran, so the resulting instance must never be
     * published as `pano`. Calling this undeploys the verticle for real, which runs stop() -- and since `disposing`
     * is gone, stop() always does its full teardown, so this correctly unregisters exactly what this instance
     * registered. This does NOT close the shared Vert.x; if getOrCreateVertx() had to build a brand-new one for
     * this doomed instance (because onDisable() already closed whatever was current), the caller must separately
     * close it via [closeVertxForAbortedInit] once it has confirmed this instance will never be published --
     * but do NOT just call the two back-to-back (see [disposeAndCloseVertxForAbortedInit], which exists
     * because that sequencing is not safe done by hand).
     *
     * [afterUndeploy] runs once the undeploy has settled -- successfully or not, and after this call's own
     * failure logging -- so a caller can order work after this instance's teardown without polling or
     * blocking. It also runs (synchronously, on the calling thread) when this is a duplicate call that finds
     * nothing left to undeploy, so it always fires exactly once no matter how many times [dispose] is called.
     * Never use this for a real platform shutdown -- use disable() there, which also closes the shared Vert.x.
     */
    fun dispose(afterUndeploy: () -> Unit = {}) {
        val id = deploymentId.getAndSet(null)

        if (id == null) {
            afterUndeploy()
            return
        }

        // vertx here is this verticle's own AbstractVerticle-assigned instance (the one it was actually
        // deployed onto), never the companion's vertxInstanceRef -- so this can never race disable()'s
        // closeVertx() over that field, and never closes the shared Vert.x other deployments rely on.
        // getAndSet() above keeps dispose() idempotent: a duplicate call sees null and returns instead of
        // calling vertx.undeploy() twice.
        vertx.undeploy(id).onComplete { result ->
            if (result.failed()) {
                logger.log(Level.WARNING, "Failed to undeploy Pano verticle $id during dispose()", result.cause())
            }

            afterUndeploy()
        }
    }

    /**
     * The correct single call for the getPano() abort-after-disable orphan (R5). It does two things in a
     * deliberate order: it claims vertxInstanceRef immediately, synchronously, so no later
     * getOrCreateVertx() can adopt this doomed instance; and it closes that instance only once this
     * orphan's own undeploy (and the stop() teardown that undeploy runs) has settled.
     *
     * Both halves of that order matter and they pull in opposite directions.
     *
     * The close must be LATE, because [dispose] only *starts* `vertx.undeploy(id)` and returns, while
     * `vertx.close()` undeploys every deployment on the instance itself -- issuing them back to back races
     * an explicit undeploy(id) against close()'s own implicit undeploy of the same id. Chaining through
     * [dispose]'s `afterUndeploy` removes that race by construction.
     *
     * The CLAIM must be EARLY, because getPano() releases panoInitLock the moment it calls this. If the ref
     * were only claimed once the undeploy settled, the next enable's getPano() could reach
     * getOrCreateVertx() in that window, adopt this very instance, and then have it closed underneath it --
     * the same silent death the R5 handling exists to prevent, just relocated. So the CAS happens up front
     * and only the close() is deferred.
     *
     * Same constraints as calling the two by hand: only for the R5 orphan case, never for a real platform
     * shutdown (use disable() there), and always with this instance's own `vertx`. Never blocks and never
     * calls runBlocking, so it stays safe to call from a Vert.x event-loop thread exactly like both calls it
     * replaces.
     */
    fun disposeAndCloseVertxForAbortedInit() {
        // Claim the ref SYNCHRONOUSLY, here, before the undeploy is even started -- deferring the claim
        // until afterUndeploy is what makes this dangerous rather than merely late. getPano() releases
        // panoInitLock the moment it calls this, so the next enable's getPano() can reach
        // getOrCreateVertx() while our undeploy is still settling. If vertxInstanceRef still pointed at
        // this doomed instance at that moment, that enable would ADOPT it -- and then our deferred
        // close() would tear it down underneath a cycle that is legitimately using it. Clearing the ref
        // first means the next getOrCreateVertx() mints a fresh instance instead.
        val claimed = vertxInstanceRef.compareAndSet(vertx, null)

        dispose {
            // Only close what we actually claimed. A failed CAS means the ref no longer held this
            // instance: either a concurrent disable() already did getAndSet(null) and closed it (so
            // closing again would be wrong), or it was replaced. Either way it is not ours to close.
            if (claimed) {
                vertx.close().onFailure { cause ->
                    logger.log(
                        Level.WARNING,
                        "Failed to close the Vert.x instance of an orphaned Pano from a getPano() abort-after-disable race",
                        cause
                    )
                }
            }
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

        // The vertx() bean in SpringConfig is declared @Bean(destroyMethod = "") -- see that bean's
        // own comment for why (it returns this verticle's own `vertx`, the SHARED Vert.x instance
        // every Pano deployment runs on, per vertxInstanceRef above) -- so no extra wiring is needed
        // here to keep applicationContext.close() below from reaching into it.
        applicationContext = AnnotationConfigApplicationContext(SpringConfig::class.java)

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