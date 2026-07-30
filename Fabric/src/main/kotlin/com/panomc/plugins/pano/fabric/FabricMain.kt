package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.command.Command
import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.integration.BanIntegration
import com.panomc.plugins.pano.core.integration.PermissionIntegration
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage
import io.vertx.core.http.WebSocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import java.io.File
import java.util.logging.Logger

class FabricMain : DedicatedServerModInitializer, PanoPluginMain {
    // Three distinct monitors, never `this`. lifecycleLock guards "swap my fields" state (mPano,
    // integrations, disabled). registrationLock guards "run Pano's lifecycle" state (commands,
    // fabricEventListener). panoInitLock is new this round (R1) and serializes construction: it is
    // acquired by getPano() and BY NOTHING ELSE — not onEnable, onDisable, registerCommands,
    // unregisterCommands, registerEventListeners, unregisterEventListeners, or any Integration
    // callback — and is held across the ENTIRE Pano.init() call below, unlike lifecycleLock/
    // registrationLock, which are always released before Pano.init()/Pano.disable() (both blocking
    // calls that re-enter this class from the Vert.x event loop — see getPano() and SERVER_STOPPING
    // below). With panoInitLock serializing every construction attempt, at most one Pano is ever
    // mid-construction at a time, so there is no second, losing construction left to dispose of —
    // see getPano() for the full R2 deadlock-freedom enumeration and R5's abort-after-disable
    // orphan case, the one scenario a serialized getPano() still has to clean up after. panoInitLock
    // nests only lifecycleLock inside it (never the reverse — nothing outside getPano() ever takes
    // panoInitLock, so the reverse ordering cannot occur here), and registrationLock is never held
    // simultaneously with panoInitLock at all, so no cycle can form between these three locks or
    // with the event loop from this file's own code.
    //
    // That is NOT the whole lock picture, though: PermissionIntegration/BanIntegration (Core, not
    // this class) memoize `pano`/`platformManager`/etc. via `by lazy`, and Kotlin's default
    // SYNCHRONIZED lazy holds its own per-property monitor for the whole initializer, which for
    // `pano by lazy { panoPluginMain.getPano() }` includes the call into getPano() and — the first
    // time that property resolves — getPano()'s call into Pano.init(), so a `by lazy` monitor
    // genuinely can be held across Pano.init()'s blocking wait. getPano()'s own doc comment
    // enumerates, for Fabric specifically, why that first resolution can never itself be racing (or
    // nested inside) another in-flight Pano.init() call. This class's own locks are then acquired
    // (briefly, non-blocking) *inside* that held `by lazy` monitor via getPano()'s own synchronized
    // blocks. Every place this class itself touches an integration
    // (onEnable/onDisable/onConnectionEstablished/onDisconnect/onServerSettingsChanged/
    // onPermissionsSnapshotUpdated, all below) does so with none of this class's locks held, so the
    // reverse ordering (this class's locks held, then a `by lazy` monitor acquired) does not occur
    // from this file — but that is as far as this file's own code can vouch for; it does not amount
    // to a full deadlock proof covering Core's lazy properties.
    private val lifecycleLock = Any()
    private val registrationLock = Any()
    private val panoInitLock = Any()

    // Not a `by lazy`: SERVER_STOPPING fires unconditionally even when the server never finished
    // starting (port bind failure, world load failure, ...), and forcing Pano/Vert.x init during
    // shutdown NPEs deep inside DI (serverData needs `server`, which is still null) and aborts
    // MinecraftServer.stopServer() entirely (platform-modules-11).
    // Guarded by lifecycleLock.
    @Volatile
    private var mPano: Pano? = null

    // Guarded by lifecycleLock (matches Bungee/VelocityMain's `disabled`). Flipped to true inside
    // SERVER_STOPPING's lifecycleLock capture-and-null block below, cleared at the start of the
    // next SERVER_STARTED. Closes the gap the plain checkNotNull(server) fast-path check in
    // getPano() can't: `server` is nulled later and without any lock during SERVER_STOPPING (well
    // after mPano is captured-and-nulled), so a getPano() call arriving mid-shutdown but before
    // that null lands could otherwise sail past checkNotNull(server) and, worse, a getPano() call
    // that was already inside Pano.init() when SERVER_STOPPING ran (panoInitLock does not block
    // SERVER_STOPPING — see panoInitLock's own comment above) would, without this check, publish a
    // brand new Pano into `mPano` after teardown, with nothing left to ever dispose of it. This is
    // R5's abort-after-disable orphan case; getPano() checks `disabled` again after Pano.init()
    // returns and, if it is now true, disposes the orphan itself instead of publishing it.
    private var disabled = false

    // Bumped (under lifecycleLock, alongside `disabled`) on every SERVER_STARTED and every
    // SERVER_STOPPING, so any completed enable-then-disable-then-re-enable cycle changes this value
    // even though `disabled` itself gets reset to false at the start of the next SERVER_STARTED.
    // getPano() captures this before calling Pano.init() and compares again after it returns: a
    // plain `disabled` check alone can't distinguish "still mid the original disable" from "a whole
    // disable-then-re-enable cycle finished while this thread was parked in Pano.init()" — the latter
    // would otherwise let this thread publish an instance belonging to the PREVIOUS enable cycle, one
    // whose event listener SERVER_STOPPING already unregistered (R5, generation-counter variant).
    // Guarded by lifecycleLock.
    @Volatile
    private var enableGeneration = 0

    // registeredCommands: guarded by registrationLock.
    private val registeredCommands = mutableListOf<Command>()

    // Volatile: written under registrationLock from the Vert.x event loop
    // (registerEventListeners/unregisterEventListeners, reached via GetServerSettingsHandler on a
    // connection/settings change) and read WITHOUT any lock from the server thread and Netty I/O
    // threads (join/disconnect/pre-login dispatch) — without @Volatile a freshly registered listener
    // is not guaranteed visible to the reading thread (platform-modules-9). The field doubles as its
    // own "is a listener registered" flag (see registerEventListeners()).
    @Volatile
    internal var fabricEventListener: FabricEventListener? = null

    internal fun getFabricEventListenerOrNull(): FabricEventListener? = fabricEventListener

    // Volatile: written on the server thread (SERVER_STARTED/SERVER_STOPPING) and read from Netty
    // I/O threads via getServerOrNull() (FabricPreLoginHandler) — without this a pre-login arriving
    // right after startup can observe a stale null and skip the ban check entirely (fail open).
    @Volatile
    private var server: MinecraftServer? = null

    // Not `by lazy`: `by lazy` memoizes against the FIRST MinecraftServer forever, so after a
    // Fabric integrated-server restart (SERVER_STOPPING -> a fresh SERVER_STARTED without the mod
    // itself reloading) getServerData() would keep reporting the torn-down server's motd/port/
    // player counts. Rebuilt every SERVER_STARTED from that lambda's own `server` parameter,
    // nulled on SERVER_STOPPING (R7, matches fabricEventListener/pluginScope). Volatile
    // for the same cross-thread-visibility reason as `server` above: SpringConfig reads
    // getServerData() while building the PlatformManager bean, which runs on the Vert.x event
    // loop (Pano.init()'s deployVerticle -> start()), not the server thread that sets this.
    @Volatile
    private var serverData: FabricServerData? = null

    internal fun getServerOrNull(): MinecraftServer? = server

    // Long-lived replacement for the per-invocation `CoroutineScope(Dispatchers.IO)` FabricCommand
    // and FabricPreLoginHandler used to create: those were never cancelled on disable and had no
    // exception handler. Created alongside `server` on SERVER_STARTED, cancelled alongside it on
    // SERVER_STOPPING. Volatile for the same cross-thread-visibility reason as `server` above.
    @Volatile
    private var pluginScope: CoroutineScope? = null

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.warning("Unhandled coroutine exception: ${throwable.message}")
    }

    internal fun getPluginScopeOrNull(): CoroutineScope? = pluginScope

    // Bridge JUL → SLF4J so logs use Fabric's native format
    private val logger: Logger = FabricLogger("Pano")

    // Not `by lazy`: Fabric can restart the integrated server (SERVER_STOPPING then a fresh
    // SERVER_STARTED) without the mod itself reloading, so reusing the first-built integrations
    // would leave them wired to a torn-down Pano (their `pano by lazy { main.getPano() }` would
    // keep resolving to the very first Pano instance forever). Rebuilt every SERVER_STARTED (R7).
    // Volatile: written on the server thread (SERVER_STARTED/SERVER_STOPPING), read WITHOUT any
    // lock from the Vert.x event loop (onConnectionEstablished/onDisconnect/onServerSettingsChanged/
    // onPermissionsSnapshotUpdated below) — visibility comes from @Volatile alone, matching
    // fabricEventListener/server/pluginScope above.
    @Volatile
    private var integrations: List<Integration> = emptyList()

    override fun onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            this.server = server
            serverData = FabricServerData(server)
            pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)

            // Mirrors Bungee/VelocityMain's onEnable(): a Fabric integrated-server restart runs
            // SERVER_STOPPING (which sets disabled=true) then a fresh SERVER_STARTED without the
            // mod itself reloading, so this has to clear the flag again or every getPano() call
            // after the first restart would trip the disabled guard forever.
            synchronized(lifecycleLock) {
                disabled = false
                enableGeneration++
            }

            FabricPreLoginHandler.fabricMain = this

            integrations = listOf(
                PermissionIntegration(this),
                BanIntegration(this),
            )

            integrations.forEach { it.onEnable() }

            try {
                getPano().onServerStart()
            } catch (e: IllegalStateException) {
                // getPano() can throw here if SERVER_STOPPING (which can run concurrently via
                // Minecraft's JVM shutdown hook on SIGTERM/Ctrl+C — see getPano()'s R5 comment and
                // SERVER_STOPPING below) tears this same enable cycle down before or while this call
                // runs. Propagating would escape a fabric-api event handler; a server that is already
                // stopping does not need Pano to start anyway.
                logger.warning("Skipping Pano startup: ${e.message}")
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            // Integrations before Pano (R5): PermissionIntegration.onDisable() -> stop() closes its
            // LuckPerms EventSubscriptions and calls unregisterEventListeners() while Vert.x/Pano is
            // still up. Running this after mPano?.disable() (as this used to) tears LuckPerms down
            // against an already-closed platform and unregisters listeners after EventManager.disable()
            // already ran. Each step keeps its own try/catch so one failure can't abort the rest of
            // MinecraftServer.stopServer().
            integrations.forEach {
                try {
                    it.onDisable()
                } catch (e: Exception) {
                    logger.warning("Error while disabling a Pano integration: ${e.message}")
                }
            }
            integrations = emptyList()

            // mPano is only non-null once SERVER_STARTED actually ran; skip entirely rather than
            // constructing Pano during shutdown (platform-modules-11). Capture-and-null under
            // lifecycleLock, then release it before the blocking pano.disable() call: disable() runs
            // `runBlocking { vertx.close().coAwait() }` on this thread, and the close re-enters this
            // class from the Vert.x event loop (unregisterCommands/unregisterEventListeners), each of
            // which only ever takes registrationLock — but holding lifecycleLock here regardless
            // would still be wrong, so it's released first. panoInitLock (see its class-level
            // comment) is never taken here at all: this handler can run concurrently with another
            // thread that is mid-Pano.init() inside getPano() (e.g. via Minecraft's own JVM shutdown
            // hook running SERVER_STOPPING off the server thread on SIGTERM/Ctrl+C — see
            // FabricPreLoginHandler's identical note), and must not block on it.
            val pano = synchronized(lifecycleLock) {
                // Flip before releasing mPano (same block, so no other thread can observe one
                // without the other): any getPano() call that is (or later becomes) blocked on
                // lifecycleLock right behind this one -- including one already inside Pano.init() and
                // only now reaching its post-init lifecycleLock check (the abort-after-disable orphan
                // case getPano()'s own comment documents) -- must see the disabled state instead of
                // resurrecting a Pano this handler is about to tear down.
                disabled = true
                enableGeneration++
                val p = mPano
                mPano = null
                p
            }

            try {
                pano?.disable()
            } catch (e: Exception) {
                logger.warning("Error while disabling Pano: ${e.message}")
            }

            // Cancel any in-flight command/pre-login coroutines and drop the references so a
            // straggling pre-login fails safe (getServerOrNull() == null) instead of racing a
            // stopped server.
            pluginScope?.cancel()
            pluginScope = null
            this.server = null
            serverData = null

            // Drop the per-enable listener/main references the same way, so a restarted server
            // (SERVER_STOPPING -> a fresh SERVER_STARTED without the mod reloading) rebuilds them
            // instead of registerEventListeners() reusing a stale FabricEventListener (R7) or
            // FabricPreLoginHandler dispatching against a FabricMain whose server/scope/listener
            // fields it just nulled above (it already treats a null `fabricMain` as "nothing to
            // do", same as its null `server`/`scope`/`eventListener` checks).
            synchronized(registrationLock) {
                fabricEventListener = null
            }
            FabricPreLoginHandler.fabricMain = null
        }

        // Register player join/disconnect events via Fabric API
        // Pre-login checks (ban) are handled via PlayerManagerMixin -> FabricPreLoginHandler

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            // Hop to the server thread before touching game state, matching kickPlayer()
            // (platform-modules-14) — DISCONNECT below can fire from a Netty I/O thread, and JOIN
            // is hopped too for consistency.
            val player = handler.player
            server?.execute {
                fabricEventListener?.onPlayerJoin(player)
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            // fabric-api fires DISCONNECT from both the server thread (handleDisconnection) and a
            // Netty I/O thread (channelInactive) depending on how the client left; the latter must
            // not touch PlayerList/ServerPlayer state directly (platform-modules-14).
            val player = handler.player
            server?.execute {
                fabricEventListener?.onPlayerDisconnect(player)
            }
        }

        // Store dispatcher and register any commands already queued
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            synchronized(registrationLock) {
                registeredCommands.forEach { command ->
                    FabricCommand.register(dispatcher, command, this)
                }
            }
        }
    }

    override fun getDataFolder(): File {
        val dataDir = File("config/pano")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        return dataDir
    }

    override fun getPanoLogger(): Logger = logger

    // R1: panoInitLock (declared with the other locks above) is held across the ENTIRE Pano.init()
    // call below, so at most one thread is ever mid-construction at a time on this FabricMain — the
    // previous "double-init race" (two Panos built concurrently, the loser disposed after the
    // fact) cannot happen any more, and the loser-disposal branch that used to live here is gone
    // (R4). lifecycleLock is still released before Pano.init() runs, same as before — see the
    // class-level comment for why panoInitLock and lifecycleLock never deadlock each other.
    //
    // R2 deadlock-freedom, enumerated for Fabric (not assumed): while this thread holds panoInitLock
    // and is parked in Pano.init()'s `runBlocking { vertx.deployVerticle(pano).coAwait() }`, the
    // verticle's start() -> init() (Pano.kt) runs on the VERT.X EVENT LOOP and calls, in order:
    //   1. initDependencyInjection() — Spring `applicationContext.refresh()`. Every @Bean factory
    //      method in SpringConfig.kt that touches `panoPluginMain` calls only
    //      getPanoLogger()/getDataFolder()/getServerData(), none of which take any lock on this
    //      class (getServerData() just reads the @Volatile `serverData` field).
    //   2. initConfigManager() — ConfigManager.init() is pure config-file I/O; it never calls back
    //      into `panoPluginMain` at all.
    //   3. initCommandManager() — CommandManager.init() -> this.registerCommands(commands), which
    //      takes registrationLock only. PanoCommand/LinkCommand's constructors (invoked building
    //      CommandManager's `commands` list) touch only their injected PlatformManager/I18nManager,
    //      never `panoPluginMain`.
    //   4. initEventManager() — EventManager.init() -> this.registerEventListeners(listeners),
    //      registrationLock only.
    // None of that call graph ever calls back into getPano(), so nothing the event loop can do while
    // this thread holds panoInitLock ever needs panoInitLock itself — verified by reading
    // CommandManager/EventManager/PlatformManager/SpringConfig and every *Command class Spring
    // constructs along the way, not assumed from the shape of the code.
    //
    // The only OTHER caller of getPano() is PermissionIntegration/BanIntegration's
    // `pano by lazy { panoPluginMain.getPano() }` (Core). Both only ever touch `pano` — directly, or
    // transitively via their own `platformManager by lazy { pano.platformManager }` — from
    // onConnectionEstablished()/onDisconnect()/onServerSettingsChanged()/onPermissionsSnapshotUpdated()
    // or (PermissionIntegration only) a join-triggered LuckPerms identity reconcile. Every one of
    // those is reachable ONLY through PlatformManager.start(), which this class calls via
    // `getPano().onServerStart()` in SERVER_STARTED below — strictly AFTER getPano() has already
    // returned once with `mPano` published. PermissionIntegration.onEnable() (called BEFORE that
    // first getPano() call, in SERVER_STARTED below) only calls registerEventListeners() and never
    // touches `pano`; BanIntegration has no onEnable() override at all. A `by lazy` also memoizes
    // once resolved, so it never calls getPano() again even after a later disable. So on Fabric these
    // two integrations can never be the SECOND caller into a still-in-progress Pano.init() — the only
    // thread that ever reaches the "actually construct" branch below is the one that made the very
    // first getPano() call, which is always the SERVER_STARTED handler running on the Fabric server
    // thread. (This replaces an earlier version of this comment that assumed
    // onConnectionEstablished() could race the first Pano.init() call before `mPano` was published —
    // that was never actually reachable on Fabric per the enumeration above, since
    // onConnectionEstablished only fires after PlatformManager.start(), which only runs after
    // getPano() already returned.)
    //
    // R5 abort-after-disable orphan: SERVER_STOPPING does NOT take panoInitLock (R1/R3 — see the
    // class-level comment and SERVER_STOPPING below) and CAN run concurrently with a thread that is
    // still inside Pano.init(): Minecraft installs a JVM shutdown hook that runs SERVER_STOPPING off
    // the server thread on SIGTERM/Ctrl+C (see FabricPreLoginHandler's identical note), so it is not
    // limited to running strictly after the server thread's own work. If that happens, `disabled`
    // comes back true once Pano.init() returns here, and `created` must be torn down instead of
    // published. A plain `disabled` check alone is not enough, though: a COMPLETE SERVER_STOPPING ->
    // SERVER_STARTED cycle can also finish entirely while this thread is parked inside Pano.init(),
    // which resets `disabled` back to false and would let this thread go on to publish `created` as
    // if it belonged to the NEW enable cycle — it doesn't; it's still built from the previous cycle's
    // `this` (same FabricMain instance, but SERVER_STOPPING already unregistered that cycle's event
    // listener). `enableGeneration` (bumped alongside `disabled` on both SERVER_STARTED and
    // SERVER_STOPPING — see its field comment) closes that gap: this thread captures it right before
    // calling Pano.init() and, on return, treats a changed value the same as `disabled` being true.
    // Either way, created.dispose() + Pano.closeVertxForAbortedInit() below does the teardown
    // (dispose() the verticle, and fire-and-forget close a brand-new Vert.x instance
    // getOrCreateVertx() may have had to mint for it, if SERVER_STOPPING's own closeVertx() already
    // ran first). Since the only thread that ever reaches this far is the server thread (see above),
    // this cleanup path runs on the Fabric server thread for this platform, never the Vert.x event
    // loop — but the cleanup is written non-blocking regardless, both because Pano.kt's contract
    // requires it and because nothing here should depend on that always remaining true.
    override fun getPano(): Pano {
        // Only ever constructed once `server` is set (SERVER_STARTED) — constructing it earlier
        // (e.g. from SERVER_STOPPING on a server that never finished starting) NPEs deep inside DI
        // because FabricServerData needs a non-null `server` (platform-modules-11). Checked before
        // checkNotNull(server): `disabled` is the reliable shutdown signal (see its field comment)
        // -- `server` itself is only nulled later, unguarded, well into SERVER_STOPPING -- so a
        // call arriving mid-shutdown must fail on the disabled check, not fall through to a
        // possibly-still-non-null `server`.
        synchronized(lifecycleLock) {
            mPano?.let { return it }
            check(!disabled) { "Pano requested after FabricMain was disabled" }
            checkNotNull(server) { "Pano requested before the server finished starting" }
        }

        synchronized(panoInitLock) {
            // Re-check under panoInitLock: another thread may have already published a Pano (or
            // SERVER_STOPPING may have already run) between the fast-path check above and this
            // thread acquiring panoInitLock. R1 only guarantees no two threads are ever inside
            // Pano.init() concurrently — it does not guarantee only one thread ever reaches this
            // far, so without this re-check a second thread could still redundantly rebuild Pano
            // right after the first one finished.
            val generationAtStart = synchronized(lifecycleLock) {
                mPano?.let { return it }
                check(!disabled) { "Pano requested after FabricMain was disabled" }
                checkNotNull(server) { "Pano requested before the server finished starting" }
                enableGeneration
            }

            val created = Pano.init(this)

            val published = synchronized(lifecycleLock) {
                if (disabled || enableGeneration != generationAtStart) {
                    // R5: either SERVER_STOPPING ran to completion while this thread was inside
                    // Pano.init() above (disabled still true), or a full SERVER_STOPPING ->
                    // SERVER_STARTED cycle ran and finished in that same window (disabled reset to
                    // false again, but enableGeneration moved) — either way `created` belongs to a
                    // cycle that is no longer current and must not be published. mPano is either
                    // already null (SERVER_STOPPING's own capture-and-null found nothing to clear
                    // that time) or already holds the NEW cycle's own Pano, and nothing else will
                    // ever dispose of `created` unless this branch does.
                    false
                } else {
                    mPano = created
                    true
                }
            }

            if (!published) {
                created.disposeAndCloseVertxForAbortedInit()

                error("Pano requested after FabricMain was disabled")
            }

            return created
        }
    }

    override fun registerCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            registeredCommands.addAll(commands)

            logger.info("Pano registerCommands called with ${commands.size} commands")

            // Try to register on stored dispatcher from CommandRegistrationCallback
            val dispatcher = server?.getCommands()?.getDispatcher()
            if (dispatcher != null) {
                commands.forEach { command ->
                    logger.info("Registering command '${command.name}' (as '${command.name.lowercase()}') on live dispatcher")
                    FabricCommand.register(dispatcher, command, this)
                }

                server?.getPlayerList()?.getPlayers()?.forEach { player ->
                    server?.getCommands()?.sendCommands(player)
                }
            } else {
                logger.warning("Server not available yet, commands will be registered when CommandRegistrationCallback fires")
            }
        }
    }

    // Removes only its argument from the tracking list (set-based diff, idempotent: an
    // already-absent command is silently skipped, matching unregisterEventListeners() below), but
    // that is the most this method can honestly do on Fabric. Brigadier's registration API is
    // one-directional: CommandNode/RootCommandNode (see FabricCommand.register(), which is the only
    // place this class calls dispatcher.register()) exposes addChild() but no public removal of a
    // single node, and its backing `children`/`literals`/`arguments` maps are private with no
    // accessor -- there is no supported way to pull one command out of an already-built, live
    // CommandDispatcher. So a command already registered on the live dispatcher keeps dispatching
    // (and stays in players' client-side tab-complete) until the dispatcher itself is next rebuilt
    // from `registeredCommands` -- CommandRegistrationCallback.EVENT above, which fires on a fresh
    // SERVER_STARTED or a vanilla /reload. This method only keeps that command out of the *next*
    // rebuild; it cannot evict it from the one currently running. Forcibly clearing/rebuilding the
    // shared dispatcher here to force eviction would also tear down every other mod's commands --
    // Fabric's Knot classloader (and the command dispatcher it produces) is shared across all mods,
    // unlike Bukkit/Bungee/Velocity's per-plugin command registries -- which would be strictly worse
    // than the do-nothing-to-the-live-tree behavior this method actually has.
    override fun unregisterCommands(commands: List<Command>) {
        synchronized(registrationLock) {
            registeredCommands.removeAll(commands.toSet())
        }
    }

    override fun getServerData(): ServerData =
        serverData ?: error("Pano requested server data before the server finished starting")

    override fun getPluginClassLoader(): java.net.URLClassLoader {
        // Fabric's KnotClassLoader is not a URLClassLoader, so wrap it.
        // We scan all MANIFEST.MF files and find the one with VERSION + BUILD_TYPE (Pano's manifest).
        val fabricClassLoader = FabricMain::class.java.classLoader

        var panoManifestUrl: java.net.URL? = null
        try {
            val manifestUrls = fabricClassLoader.getResources("META-INF/MANIFEST.MF")
            while (manifestUrls.hasMoreElements()) {
                val url = manifestUrls.nextElement()
                try {
                    val manifest = java.util.jar.Manifest(url.openStream())
                    if (manifest.mainAttributes.getValue("VERSION") != null &&
                        manifest.mainAttributes.getValue("BUILD_TYPE") != null
                    ) {
                        panoManifestUrl = url
                        break
                    }
                } catch (_: Exception) { /* skip unreadable manifests */
                }
            }
        } catch (_: Exception) { /* ignore */
        }

        return object : java.net.URLClassLoader(emptyArray(), fabricClassLoader) {
            override fun findResource(name: String?): java.net.URL? {
                if (name == "META-INF/MANIFEST.MF" && panoManifestUrl != null) {
                    return panoManifestUrl
                }
                return fabricClassLoader.getResource(name)
            }

            override fun findResources(name: String?) = fabricClassLoader.getResources(name)
        }
    }

    override fun translateColor(text: String): String = text.replace('&', '§')

    override fun registerEventListeners(listeners: Set<Listener>) {
        synchronized(registrationLock) {
            val current = fabricEventListener
            if (current == null) {
                // CopyOnWriteArraySet: this set is read on the server thread and Netty I/O threads far
                // more often than it is mutated, and mutation only happens from the Vert.x event loop
                // (platform-modules-9) — a plain LinkedHashSet here is not thread-safe.
                fabricEventListener = FabricEventListener(this, java.util.concurrent.CopyOnWriteArraySet(listeners))
                return
            }

            current.listeners.addAll(listeners)
        }
    }

    // Already scoped to its argument (partial removal from the CopyOnWriteArraySet built in
    // registerEventListeners() above, not a teardown of the whole FabricEventListener) and already
    // idempotent (`?.` no-ops if no listener was ever registered; removeAll() no-ops on entries
    // that are already gone) -- unlike unregisterCommands() above, Fabric's own listener dispatch
    // (ServerPlayConnectionEvents.JOIN/DISCONNECT above, FabricPreLoginHandler) is this class's own
    // code reading `fabricEventListener?.listeners`, not a third-party API with no removal
    // primitive, so there is no Brigadier-style structural limitation here to document.
    override fun unregisterEventListeners(listeners: Set<Listener>) {
        synchronized(registrationLock) {
            fabricEventListener?.listeners?.removeAll(listeners)
        }
    }

    // Not synchronized (R4): runs on the Vert.x event loop and only reads the @Volatile
    // `integrations` field, taking no lock at all. Each integration gets its own try/catch so one
    // throwing (e.g. from PlatformManager.onWebSocketClosed() -> pluginMain.onDisconnect(), called
    // directly from the WebSocket close handler on the Vert.x event loop) can't take out the rest of
    // the fan-out or surface as an unhandled event-loop exception.
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
            } catch (e: Exception) {
                logger.warning("Integration ${it.javaClass.simpleName} failed to handle connection established: ${e.message}")

                if (firstFailure == null) {
                    firstFailure = e
                }
            }
        }

        firstFailure?.let { throw it }
    }

    override fun onDisconnect() {
        integrations.forEach {
            try {
                it.onDisconnect()
            } catch (e: Exception) {
                logger.warning("Integration ${it.javaClass.simpleName} failed to handle disconnect: ${e.message}")
            }
        }
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        integrations.forEach {
            try {
                it.onServerSettingsChanged(serverSettings)
            } catch (e: Exception) {
                logger.warning("Integration ${it.javaClass.simpleName} failed to handle server settings change: ${e.message}")
            }
        }
    }

    override fun onPermissionsSnapshotUpdated(message: PermissionsSnapshotUpdatedMessage) {
        integrations.forEach {
            try {
                it.onPermissionsSnapshotUpdated(message)
            } catch (e: Exception) {
                logger.warning("Integration ${it.javaClass.simpleName} failed to handle permissions snapshot update: ${e.message}")
            }
        }
    }

    override fun kickPlayer(player: String, message: String) {
        server?.execute {
            try {
                server?.getPlayerList()?.getPlayerByName(player)?.connection?.disconnect(
                    FabricTextHelper.parseColoredText(message)
                )
            } catch (_: Exception) {
                // Silently handle if method signatures changed between MC versions
            }
        }
    }
}
