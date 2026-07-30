package com.panomc.plugins.pano.spigot

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.integration.BanIntegration
import com.panomc.plugins.pano.core.integration.PermissionIntegration
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.spigot.integration.AuthMeIntegration
import io.vertx.core.http.WebSocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandMap
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.CopyOnWriteArraySet
import java.util.function.Consumer
import java.util.jar.Manifest
import java.util.logging.Logger

class SpigotMain : JavaPlugin(), PanoPluginMain {
    // Three distinct monitors, never nested in more than one direction (panoInitLock -> lifecycleLock
    // only) and never held across the two blocking calls that re-enter this class from the Vert.x
    // event loop (Pano.init() re-enters via registerCommands/registerEventListeners; Pano.disable()
    // re-enters via unregisterCommands/unregisterEventListeners and the four Integration fan-outs
    // below — see CommandManager/EventManager and R3).
    //
    // panoInitLock is acquired by getPano() ONLY, and by nothing else in this file — see getPano()
    // for the full contract (R1/R2 in the cross-cutting round notes). Holding it across Pano.init()
    // is what makes at most one Pano ever get constructed, so there is never a "loser" instance for
    // anything downstream to clean up.
    //
    // lifecycleLock guards mPano, disabled and integrations. registrationLock guards
    // commands/spigotEventListener. Never `this` as a monitor: getPano() used to be `@Synchronized`
    // (i.e. `this`), which was safe only because nothing else happened to synchronize on `this` —
    // named locks make each boundary explicit instead of implicit.
    //
    // CAVEAT: the "never held across a blocking call" guarantee covers only these three named locks.
    // It does NOT extend to the `pano by lazy` property inside PermissionIntegration/BanIntegration/
    // AuthMeIntegration (all three declare `private val pano by lazy { panoPluginMain.getPano() }`
    // with the default SYNCHRONIZED mode): the first read of `pano` — reachable from the Vert.x event
    // loop via the onConnectionEstablished/onDisconnect/onServerSettingsChanged/
    // onPermissionsSnapshotUpdated fan-out below — takes that lazy delegate's own internal monitor
    // and holds it for the whole initializer, i.e. across the getPano() call (and, on a first-ever
    // resolution, the blocking Pano.init() underneath it).
    private val panoInitLock = Any()
    private val lifecycleLock = Any()
    private val registrationLock = Any()

    // Not a `by lazy`: onDisable() calls mPano.disable(), which closes the shared static Vert.x.
    // A runtime re-enable of this same plugin instance (e.g. PlugMan disabling and re-enabling
    // without recreating the classloader) must build a fresh Pano instead of reusing the dead
    // one — a `by lazy` memoizes permanently, so onStart() would call onServerStart() on a
    // verticle whose Vert.x is already closed and PlatformManager would never reconnect. Mirrors
    // VelocityMain/FabricMain. Guarded by lifecycleLock; that lock is always released before the
    // blocking Pano.init()/Pano.disable() call itself (see getPano()/onDisable()) — panoInitLock is
    // the one held across Pano.init(), not lifecycleLock.
    private var mPano: Pano? = null

    // Guards getPano() against resurrecting a Pano after onDisable() has torn one down: flipped to
    // true inside onDisable()'s lifecycleLock block together with the mPano capture, and cleared at
    // the top of the following onEnable(). Checked in getPano()'s fast-path critical section and
    // again after Pano.init() returns (R5's abort-after-disable orphan check), both under
    // lifecycleLock. Mirrors VelocityMain.disabled.
    @Volatile
    private var disabled = false

    // Bumped on every completed onEnable() AND every completed onDisable() (see both methods), so
    // any full disable-then-re-enable cycle changes this value even though `disabled` itself gets
    // reset to false at the top of the next onEnable(). getPano() captures this before calling
    // Pano.init() and compares after it returns (R5): `disabled` alone can't detect a cycle that
    // completes entirely while a thread is parked inside Pano.init(), which would otherwise publish
    // an instance belonging to the previous enable cycle — one whose event listener was already
    // unregistered by that cycle's onDisable(). @Volatile: read/written across the same threads as
    // `disabled`, so it needs the same visibility guarantee; not folded under lifecycleLock because
    // getPano()'s pre-Pano.init() read must happen outside that lock (see getPano()).
    @Volatile
    private var enableGeneration = 0

    // Guarded by registrationLock. Keyed by the original Command instance (identity, not name): a
    // disable/re-enable cycle builds a brand new CommandManager whose "pano"/"link" Command objects
    // are new instances with the same names as the previous cycle's, and the R5 orphan path means a
    // torn-down instance's dispose() -> unregisterCommands() call can run asynchronously (Pano.dispose()
    // only kicks off vertx.undeploy() and returns) well after a following onEnable() has already
    // published a fresh Pano and registered its own same-named commands. Keying by identity lets
    // unregisterCommands(commands) remove only the exact wrappers it was passed, never a
    // subsequently-registered instance's commands of the same name — see unregisterCommands below.
    private val commands = mutableMapOf<Command, SpigotCommand>()

    private val serverData by lazy { SpigotServerData(this) }
    private val mPanoLogger by lazy { getPanoLogger() }
    internal val eventHelper by lazy { SpigotEventHelper(this) }
    // Nullable var, not lateinit: onDisable() clears this to null so a following onEnable() ->
    // registerEventListeners() builds and registers a fresh SpigotEventListener instead of
    // silently reusing the old one. Bukkit's plugin disable already auto-unregisters the old
    // listener from HandlerList (SimplePluginManager.disablePlugin() calls
    // HandlerList.unregisterAll(plugin)), so without this reset a re-enable would keep mutating an
    // object no longer wired into Bukkit's event dispatch — pre-login ban checks would silently
    // stop firing after a runtime disable/re-enable of this same plugin instance (e.g. PlugMan).
    // Guarded by registrationLock — every read and write (registerEventListeners,
    // unregisterEventListeners, onDisable) now goes through registrationLock, so the lock itself
    // supplies the happens-before edge between the Vert.x event loop (registerEventListeners) and
    // the Bukkit main thread (onDisable() -> PermissionIntegration.onDisable() ->
    // unregisterEventListeners()'s null guard). @Volatile kept as a belt-and-suspenders guard on
    // top of that. Mirrors FabricMain.fabricEventListener/VelocityMain.velocityEventListener.
    @Volatile
    internal var spigotEventListener: SpigotEventListener? = null

    // Long-lived scope for command handlers (see SpigotCommand): created in onEnable, cancelled
    // in onDisable, so a /pano coroutine can't outlive the plugin and touch a torn-down Pano.
    // Mirrors BungeeMain/VelocityMain/FabricMain. @Volatile: reassigned per enable/re-enable on the
    // Bukkit main thread but read from arbitrary command-dispatch threads via SpigotCommand, which
    // is not otherwise synchronized against this field — without @Volatile a command thread could
    // observe a stale, already-cancelled scope from a previous enable cycle.
    @Volatile
    internal lateinit var coroutineScope: CoroutineScope
        private set

    // Not a `by lazy`: a `by lazy` memoizes permanently, so after a runtime re-enable of this same
    // plugin instance these would keep the PermissionIntegration/BanIntegration/AuthMeIntegration
    // built for the torn-down enable cycle — their `pano`/`platformManager` lazies still point at
    // the old, disabled Pano (closed Vert.x). Rebuilt in onEnable() instead, same lifecycle as
    // mPano. Both writes (onEnable()/onDisable()) go under lifecycleLock, matching mPano/disabled —
    // see the class comment. Also @Volatile: read lock-free from the Vert.x event loop via
    // onConnectionEstablished/onDisconnect/onServerSettingsChanged/onPermissionsSnapshotUpdated
    // below, so a write under the lock must still be visible there without the reader taking it.
    // Mirrors VelocityMain.integrations.
    @Volatile
    private var integrations: List<Integration> = emptyList()

    override fun onEnable() {
        // Cleared before anything else so a getPano() call racing this onEnable() (e.g. a lingering
        // scheduler task from a previous enable cycle) sees the plugin as enabled again rather than
        // tripping the check(!disabled) fast-path guard below.
        disabled = false
        // Bumped on every enable (see the field comment) so a getPano() call whose Pano.init() spans
        // a full disable+re-enable cycle can tell it no longer belongs to the current one, even
        // though `disabled` was just reset to false above.
        enableGeneration++

        coroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
                mPanoLogger.warning("Unhandled coroutine exception: ${throwable.message}")
            }
        )

        synchronized(lifecycleLock) {
            integrations = listOf(
                PermissionIntegration(this),
                BanIntegration(this),
                AuthMeIntegration(this),
            )
        }

        if (SpigotServerUtil.isFolia()) {
            try {
                val scheduler = server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
                val runDelayed = scheduler.javaClass.getMethod(
                    "runDelayed",
                    Plugin::class.java,
                    Consumer::class.java,
                    Long::class.javaPrimitiveType
                )
                val task = Consumer<Any> {
                    onStart()
                }
                runDelayed.invoke(scheduler, this, task, 1L)
            } catch (exception: Exception) {
                mPanoLogger.severe("Failed to schedule start task: ${exception.message}")
            }
        } else {
            server.scheduler.scheduleSyncDelayedTask(this) {
                onStart()
            }
        }
    }

    private fun onStart() {
        // Each integration gets its own try/catch, mirroring the onDisable() loop below: without
        // this, AuthMeIntegration.onEnable() reaching platformManager -> getPano() -> Pano.init()
        // and Pano.init() propagating a config-migration failure would escape onStart() entirely,
        // skipping getPano().onServerStart() below and surfacing as a raw scheduler stack trace
        // instead of the "Failed to start Pano" message that try/catch is meant to produce.
        integrations.forEach {
            try {
                it.onEnable()
            } catch (exception: Exception) {
                mPanoLogger.severe("Failed to enable integration ${it.javaClass.simpleName}: ${exception.message}")
            }
        }

        // getPano() constructs Pano as soon as it's called below, before onServerStart() runs, so
        // a thrown exception (PlatformManager.start() does retrying network work) still leaves
        // mPano non-null for onDisable() to clean up.
        try {
            getPano().onServerStart()
        } catch (exception: Exception) {
            mPanoLogger.severe("Failed to start Pano: ${exception.message}")
        }
    }

    override fun onDisable() {
        // Integrations first: they may need to schedule Bukkit tasks while the plugin is still
        // enabled. Calling mPano.disable() first closes Vert.x asynchronously; the WebSocket
        // onDisconnect can fire after the plugin is already disabled, which would break
        // AuthMeIntegration's reload (runTask on a disabled plugin).
        // Each integration gets its own try/catch: onStart() may never have run (aborted startup),
        // so PermissionIntegration.onDisable() can hit unregisterEventListeners's null guard or
        // similar not-yet-initialized state — that must not skip BanIntegration/AuthMeIntegration.
        integrations.forEach {
            try {
                it.onDisable()
            } catch (exception: Exception) {
                mPanoLogger.severe("Failed to disable integration ${it.javaClass.simpleName}: ${exception.message}")
            }
        }
        // Release so a following onEnable() builds fresh integrations instead of reusing ones
        // wired to this (now torn-down) Pano — see the field's declaration above. lifecycleLock
        // only: pure field assignment, no blocking call inside.
        synchronized(lifecycleLock) {
            integrations = emptyList()
        }

        // Cleared (not left for the next registerEventListeners() call to reuse) so a following
        // onEnable() always builds and registers a fresh SpigotEventListener — see the field's
        // declaration above for why reusing the old one would silently break ban checks after a
        // re-enable. Must run after the integrations.onDisable() loop above, which still needs to
        // read this to unregister PermissionIntegration's listener. registrationLock only — never
        // nested inside lifecycleLock, and released well before the blocking pano?.disable() call
        // below.
        synchronized(registrationLock) {
            spigotEventListener = null
        }

        // mPano is only non-null once onStart() actually reached getPano(); null-safe so a
        // disable-before-start never constructs Pano just to tear it down. The reference is
        // captured and cleared under lifecycleLock, then the lock is released *before* the
        // blocking disable() call — see R2/getPano(). Clearing mPano here (rather than in a
        // finally after disable()) still guarantees a following onEnable()/onStart() builds a
        // fresh Pano even if disable() throws, since mPano is already null by the time disable()
        // runs. disabled is flipped true in the same block so any getPano() call that reaches the
        // fast-path check afterwards (this thread has already released registrationLock and the
        // integrations/spigotEventListener teardown above) sees the plugin as disabled instead of
        // constructing a Pano/Vert.x that nothing will ever dispose of.
        val pano = synchronized(lifecycleLock) {
            val p = mPano
            mPano = null
            disabled = true
            // Bumped on every disable (see the field comment) so any thread that captured the
            // generation before this cycle's onEnable() and is still inside Pano.init() gets
            // detected as an orphan even if a following onEnable() resets `disabled` back to false
            // before that thread wakes up.
            enableGeneration++
            p
        }
        pano?.disable()

        // Guard isInitialized: onDisable() can be reached even if onEnable() never finished
        // assigning it (e.g. it threw before this line).
        if (::coroutineScope.isInitialized) {
            coroutineScope.cancel()
        }
    }

    override fun registerCommands(commands: List<Command>) {
        // registrationLock only: pure field mutation plus Bukkit's own CommandMap/PluginCommand
        // API, no Vert.x/coroutine/lifecycleLock call inside. syncRegisteredCommands() stays
        // outside the lock — it only reflects on the server/online players, it never touches
        // `this.commands`.
        synchronized(registrationLock) {
            val commandMap = getCommandMap()

            commands.forEach { command ->
                val spigotCommand = SpigotCommand(command, this)
                this.commands[command] = spigotCommand

                val pluginCommand = getCommand(spigotCommand.name.lowercase())
                if (pluginCommand != null) {
                    pluginCommand.description = spigotCommand.description
                    pluginCommand.usage = spigotCommand.usage
                    pluginCommand.permission = spigotCommand.permission
                    pluginCommand.permissionMessage = spigotCommand.permissionMessage
                    pluginCommand.setExecutor(spigotCommand)
                } else {
                    commandMap.register(name, spigotCommand)
                }
            }
        }

        syncRegisteredCommands()
    }

    override fun unregisterCommands(commands: List<Command>) {
        // registrationLock only, matching registerCommands above. Only the wrappers built from
        // the Command instances passed in are removed — this.commands.remove(command) is a no-op
        // for a key that was already removed (or never registered), which keeps this idempotent —
        // never the whole `this.commands` map, so an R5 orphan's async dispose() -> stop() ->
        // this call can no longer tear down a subsequently-published Pano's same-named commands.
        synchronized(registrationLock) {
            val commandMap = getCommandMap()

            commands.forEach { command ->
                val removed = this.commands.remove(command) ?: return@forEach

                val pluginCommand = getCommand(removed.name.lowercase())
                if (pluginCommand != null) {
                    // Pre-declared in plugin.yml ("pano"/"link"): registerCommands above never
                    // puts this wrapper into commandMap for this branch, it only ever supplies the
                    // executor on the one shared PluginCommand instance — so there is nothing to
                    // reflectively unregister here. What must stay consistent instead is that
                    // executor: only touch it if `removed` is still the active one (an R5 orphan's
                    // asynchronous dispose() can run after a following onEnable() already
                    // registered a fresh instance's commands and overwrote the executor, in which
                    // case leave that fresh instance's executor alone). If it is still active, hand
                    // off to another still-registered wrapper for the same name if one exists,
                    // otherwise fall back to Bukkit's own default (setExecutor(null) resets
                    // PluginCommand to its owning-plugin fallback) rather than leaving "pano"/"link"
                    // wired to a wrapper whose Pano instance is being disposed.
                    if (pluginCommand.executor === removed) {
                        val replacement = this.commands.values
                            .firstOrNull { it.name.equals(removed.name, ignoreCase = true) }
                        pluginCommand.setExecutor(replacement)
                    }
                } else {
                    removed.unregister(commandMap)
                }
            }
        }

        syncRegisteredCommands()
    }

    private fun getCommandMap(): CommandMap {
        val commandMapField = Bukkit.getServer().javaClass.getDeclaredField("commandMap")

        commandMapField.isAccessible = true

        return commandMapField.get(Bukkit.getServer()) as CommandMap
    }

    private fun syncRegisteredCommands() {
        try {
            server.javaClass.getMethod("syncCommands").invoke(server)
        } catch (_: NoSuchMethodException) {
            // 1.8 has no client command tree to sync.
        } catch (exception: Exception) {
            mPanoLogger.warning("Failed to sync server commands: ${exception.message}")
        }

        server.onlinePlayers.forEach { player ->
            try {
                player.javaClass.getMethod("updateCommands").invoke(player)
            } catch (_: NoSuchMethodException) {
                // Older Bukkit players do not expose updateCommands.
            } catch (exception: Exception) {
                mPanoLogger.warning("Failed to sync commands for ${player.name}: ${exception.message}")
            }
        }
    }

    override fun getServerData(): ServerData = serverData

    override fun getPluginClassLoader(): URLClassLoader {
        val pluginClassLoader = javaClass.classLoader
        if (pluginClassLoader is URLClassLoader) {
            return pluginClassLoader
        }

        val panoManifestUrl = findPanoManifestUrl(pluginClassLoader)
        return object : URLClassLoader(emptyArray(), pluginClassLoader) {
            override fun findResource(name: String?): URL? {
                if (name == "META-INF/MANIFEST.MF" && panoManifestUrl != null) {
                    return panoManifestUrl
                }
                return pluginClassLoader.getResource(name)
            }

            override fun findResources(name: String?): Enumeration<URL> = pluginClassLoader.getResources(name)
        }
    }

    private fun findPanoManifestUrl(pluginClassLoader: ClassLoader): URL? {
        return try {
            val manifestUrls = pluginClassLoader.getResources("META-INF/MANIFEST.MF")
            while (manifestUrls.hasMoreElements()) {
                val url = manifestUrls.nextElement()
                try {
                    url.openStream().use { stream ->
                        val manifest = Manifest(stream)
                        if (manifest.mainAttributes.getValue("VERSION") != null &&
                            manifest.mainAttributes.getValue("BUILD_TYPE") != null
                        ) {
                            return url
                        }
                    }
                } catch (_: Exception) {
                    // Skip unreadable manifests from shaded dependencies.
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun translateColor(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    override fun registerEventListeners(listeners: Set<Listener>) {
        // registrationLock only: field mutation plus Bukkit's own HandlerList registration, no
        // blocking call inside.
        synchronized(registrationLock) {
            val existingListener = spigotEventListener
            if (existingListener != null) {
                existingListener.listeners.addAll(listeners)
                return
            }

            // CopyOnWriteArraySet: registerEventListeners/unregisterEventListeners mutate this set
            // from the Vert.x event loop while Bukkit reads it from the main thread and the async
            // login thread (platform-modules-9) — a plain LinkedHashSet is neither thread-safe nor
            // visible across those threads.
            val newListener = SpigotEventListener(this, eventHelper, CopyOnWriteArraySet(listeners))
            spigotEventListener = newListener

            server.pluginManager.registerEvents(newListener, this)
        }
    }

    override fun unregisterEventListeners(listeners: Set<Listener>) {
        // spigotEventListener is only assigned once onStart() runs (or null again after
        // onDisable()); onDisable() can reach here (via PermissionIntegration.onDisable()) even
        // when onStart() never fired. registrationLock only, matching registerEventListeners above.
        synchronized(registrationLock) {
            val existingListener = spigotEventListener ?: return

            existingListener.listeners.removeAll(listeners)
        }
    }

    override fun getPanoLogger(): Logger = ColoredLogger("[Pano] ")

    // R1/R2: panoInitLock is held across the whole method, including the fast path and the
    // check-then-construct-then-publish sequence below, so at most one thread is ever inside
    // Pano.init() at a time and there is never a second, "losing" Pano to dispose of. Nothing else
    // in this file acquires panoInitLock — see the class comment.
    //
    // R2 caller enumeration for Spigot (verified against CommandManager.kt/EventManager.kt/
    // ConfigManager.kt/PlatformManager.kt/SpringConfig.kt in Core, not taken on faith): while a
    // thread is parked inside Pano.init()'s `runBlocking { vertx.deployVerticle(pano).coAwait() }`,
    // Pano.init()'s own suspend init() body runs, in order:
    //   1. initDependencyInjection() — builds the Spring context. Two beans are eagerly created
    //      (non-`@Lazy`) purely by `refresh()`: vertx(), which just returns the companion's
    //      already-built `vertx` field and calls nothing back into this class at all, and logger(),
    //      which calls panoPluginMain.getPanoLogger() — SpigotMain's impl just `return`s
    //      `ColoredLogger(...)`, no lock. Every other bean here is `@Lazy`, but
    //      initDependencyInjection() forces two of them via explicit getBean() calls regardless:
    //      applicationContext.getBean(I18nManager::class.java) (constructor only takes the
    //      already-built logger bean, no panoPluginMain calls) and
    //      applicationContext.getBean(PlatformManager::class.java), which pulls in its own
    //      constructor dependencies — configManager() (-> getPanoLogger(), getDataFolder(), neither
    //      locked; JavaPlugin's built-in getDataFolder() touches no state of this class),
    //      provideWebClient()/provideWebsocketClient()/provideMinecraftStatusClient() (each just
    //      wraps the shared `vertx` bean, no panoPluginMain calls), i18nManager() (already built
    //      above), and platformManager() itself (-> getPanoLogger(), getServerData() — the latter
    //      returns the `serverData by lazy` field, no lock). No @PostConstruct/init{} anywhere in
    //      these Core classes, so nothing calls PlatformManager.start() (and therefore no
    //      onConnectionEstablished/onDisconnect/onServerSettingsChanged/
    //      onPermissionsSnapshotUpdated fan-out, see below) during this phase.
    //   2. initConfigManager() — configManager.init() only touches Vert.x's ConfigRetriever and the
    //      local config file; it does not hold a `PanoPluginMain` reference at all (ConfigManager's
    //      constructor takes an already-resolved `dataFolder: File`), so it cannot call back into
    //      this class.
    //   3. initCommandManager() — applicationContext.getBean(CommandManager::class.java) builds the
    //      commandManager() bean (constructor only stores its arguments, no calls), then
    //      commandManager.init() calls panoPluginMain.registerCommands(commands) — this class's
    //      registerCommands() takes registrationLock only (field mutation + Bukkit CommandMap API),
    //      never lifecycleLock or panoInitLock, and never calls getPano().
    //   4. initEventManager() — applicationContext.getBean(EventManager::class.java) (constructor
    //      only stores its arguments, no calls), then eventManager.init() calls
    //      panoPluginMain.registerEventListeners(listeners) — registrationLock only (field mutation
    //      + Bukkit HandlerList registration), never lifecycleLock/panoInitLock, never getPano().
    // So the full set of callbacks reachable from inside a panoInitLock-held Pano.init() call is
    // {getPanoLogger, getDataFolder, getServerData, registerCommands, registerEventListeners} — the
    // first three take no lock at all (confirmed per call site above), and the last two take
    // registrationLock only, never panoInitLock or lifecycleLock and never call getPano(), so
    // holding panoInitLock across Pano.init() is deadlock-free on Spigot.
    //
    // The four Integration fan-outs below (onConnectionEstablished/onDisconnect/
    // onServerSettingsChanged/onPermissionsSnapshotUpdated), which resolve each Integration's
    // `pano by lazy { getPano() }` on first touch, run on the Vert.x event loop — but only after
    // PlatformManager.start() has run, which onStart() only calls with `getPano().onServerStart()`
    // strictly after some getPano() call has already returned. That means these fan-outs CAN still
    // call getPano() from an event-loop thread (they are ordinary callers, not excluded by any
    // thread-affinity check) — just never while another thread is still inside the very Pano.init()
    // call that will eventually let them run at all, since nothing in that call chain reaches them.
    // A LATER getPano() call from one of these fan-outs (e.g. after a disable/re-enable cycle
    // rebuilds `integrations` and a fresh Integration's `pano` lazy resolves for the first time) can
    // therefore be the thread that hits R5's abort-after-disable orphan branch below — so that
    // branch's cleanup must be non-blocking, which is exactly why it never calls the blocking
    // disable()/closeVertx() path.
    override fun getPano(): Pano {
        synchronized(panoInitLock) {
            // Fast path plus the disabled check, both under lifecycleLock: closes the gap where
            // onDisable() has already captured-and-nulled mPano (and called pano.disable()) but
            // something still reachable after that — an integration's `pano by lazy`, an in-flight
            // command coroutine — calls getPano() again. Without this it would fall through to
            // Pano.init() below and build a brand-new Pano/Vert.x that nothing would ever publish
            // or dispose of. Matches VelocityMain. `enableGeneration` is captured here too, still
            // under lifecycleLock, so it reflects the exact enable cycle this call's Pano.init() is
            // about to run under (see the field comment and the R5 recheck below).
            val generationBeforeInit: Int
            synchronized(lifecycleLock) {
                mPano?.let { return it }
                check(!disabled) { "Pano requested after SpigotMain was disabled" }
                generationBeforeInit = enableGeneration
            }

            // Pano.init() is a blocking call (runBlocking { vertx.deployVerticle(...).coAwait() })
            // that re-enters this class from the Vert.x event loop (registerCommands,
            // registerEventListeners — see the enumeration above), both of which take
            // registrationLock only. lifecycleLock must therefore be released before this call, and
            // panoInitLock must stay held across it — R1/R2.
            val created = Pano.init(this)

            // No double-init race survives to this point (R4): panoInitLock has excluded every
            // other getPano() caller from reaching Pano.init() while this thread was inside it, and
            // onDisable() is the only other writer of mPano/disabled, and it only ever clears mPano
            // to null — never assigns a competing instance. So the only way `created` must not be
            // published is either `disabled` being set now, or `enableGeneration` having moved on
            // from `generationBeforeInit` while this thread was blocked above (R5's abort-after-
            // disable orphan). The generation check subsumes the disabled check — a completed
            // disable+re-enable cycle resets `disabled` back to false but still bumps the
            // generation twice, so `created` would otherwise belong to a since-torn-down enable
            // cycle whose event listener is already gone — but the explicit disabled check is kept
            // as the cheap, obviously-correct fast path.
            val published = synchronized(lifecycleLock) {
                if (disabled || enableGeneration != generationBeforeInit) {
                    false
                } else {
                    mPano = created
                    true
                }
            }

            if (published) {
                return created
            }

            // R5: onDisable() ran to completion while this thread was inside Pano.init() above.
            // Don't resurrect a Pano onto a SpigotMain instance that has since been torn down —
            // clean up `created` instead of publishing it. dispose() undeploys `created`'s own
            // verticle; since Pano.stop() no longer skips shared teardown for anyone (the `disposing`
            // flag is gone), this correctly unregisters exactly the commands/listeners `created`
            // itself registered above via the identity-keyed maps/sets in
            // registerCommands()/registerEventListeners() (see those methods and the `commands`
            // field comment). Pano.closeVertxForAbortedInit() then closes the companion Vert.x
            // fire-and-forget, but only if it is still the exact instance `created` was deployed
            // onto and nothing has since claimed it — needed because getOrCreateVertx() inside
            // Pano.init() may have had to build a brand-new Vert.x here (onDisable() already closed
            // the previous one), and that instance's non-daemon event-loop/worker threads would
            // otherwise hang JVM shutdown with nothing left referencing it. Both calls are
            // non-blocking and safe to call from a Vert.x event-loop thread (see the class-level
            // enumeration above for why this branch can be reached from one).
            created.disposeAndCloseVertxForAbortedInit()

            error("Pano requested after SpigotMain was disabled")
        }
    }

    // All four fan-outs below run on the Vert.x event loop and reach each Integration's
    // `pano by lazy { panoPluginMain.getPano() }` (see the class comment). getPano()'s fast path
    // throws IllegalStateException once `disabled` is set, and onDisable() flips `disabled` and
    // clears `integrations` on the Bukkit main thread with no coordination against in-flight
    // event-loop callbacks — so a message that was already in-flight when shutdown started can
    // still call one of these after `disabled` is true. Each integration therefore gets its own
    // try/catch, mirroring onStart()/onDisable() above: a late callback becomes a logged warning
    // instead of an unhandled exception on the event loop, while a genuine bug in an integration's
    // handler still surfaces (via the log) rather than being silently swallowed.
    override fun onConnectionEstablished(webSocket: WebSocket?) {
        // Unlike the other fan-outs, this one must not swallow. PlatformManager wraps its call to this
        // method in a try/catch whose whole purpose is to notice a half-wired connection and abort into
        // the reconnect path; eating the exception here would leave the socket up with integrations only
        // partially hooked and nothing retrying. So keep the per-integration boundary -- every
        // integration still gets its callback even if an earlier one throws -- but rethrow the first
        // failure once the loop is done.
        var firstFailure: Exception? = null

        integrations.forEach {
            try {
                it.onConnectionEstablished(webSocket)
            } catch (exception: Exception) {
                mPanoLogger.warning("Integration ${it.javaClass.simpleName} failed to handle connection established: ${exception.message}")

                if (firstFailure == null) {
                    firstFailure = exception
                }
            }
        }

        firstFailure?.let { throw it }
    }

    override fun onDisconnect() {
        integrations.forEach {
            try {
                it.onDisconnect()
            } catch (exception: Exception) {
                mPanoLogger.warning("Integration ${it.javaClass.simpleName} failed to handle disconnect: ${exception.message}")
            }
        }
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        integrations.forEach {
            try {
                it.onServerSettingsChanged(serverSettings)
            } catch (exception: Exception) {
                mPanoLogger.warning("Integration ${it.javaClass.simpleName} failed to handle server settings change: ${exception.message}")
            }
        }
    }

    override fun onPermissionsSnapshotUpdated(message: com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage) {
        integrations.forEach {
            try {
                it.onPermissionsSnapshotUpdated(message)
            } catch (exception: Exception) {
                mPanoLogger.warning("Integration ${it.javaClass.simpleName} failed to handle permissions snapshot update: ${exception.message}")
            }
        }
    }

    override fun kickPlayer(player: String, message: String) {
        // PanoPluginMain.kickPlayer's contract is raw '&' in, rendered out — player.kickPlayer()
        // (via SpigotServerUtil.kickPlayer) does no conversion of its own, unlike the other
        // platforms' kick calls.
        SpigotServerUtil.kickPlayer(this, server.getPlayer(player) ?: return, translateColor(message))
    }

    /**
     * Matches the server’s own never-joined / offline name→UUID mapping (version-accurate).
     */
    override fun getNeverJoinedPlayerUniqueId(minecraftName: String): UUID {
        return Bukkit.getOfflinePlayer(minecraftName).uniqueId
    }
}