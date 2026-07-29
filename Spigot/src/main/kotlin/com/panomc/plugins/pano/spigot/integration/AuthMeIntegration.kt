package com.panomc.plugins.pano.spigot.integration

import com.panomc.plugins.pano.core.event.listeners.OnPlayerDisconnect
import com.panomc.plugins.pano.core.event.listeners.OnPlayerJoin
import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.platform.message.response.*
import com.panomc.plugins.pano.core.platform.request.*
import com.panomc.plugins.pano.core.util.EmailUtil.maskEmail
import com.panomc.plugins.pano.spigot.SpigotMain
import com.panomc.plugins.pano.spigot.SpigotServerUtil
import com.panomc.plugins.pano.spigot.SpigotServerUtil.getPlayerIp
import fr.xephi.authme.api.v3.AuthMeApi
import fr.xephi.authme.events.LoginEvent
import fr.xephi.authme.events.LogoutEvent
import fr.xephi.authme.events.PasswordEncryptionEvent
import fr.xephi.authme.events.RegisterEvent
import fr.xephi.authme.security.crypts.EncryptionMethod
import fr.xephi.authme.security.crypts.HashedPassword
import io.vertx.core.http.WebSocket
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerCommandEvent
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class AuthMeIntegration(override val panoPluginMain: SpigotMain) : Integration, Listener {

    private companion object {
        // AuthMe's comparePassword always delegates to the platform (see onPasswordEncryptionEvent
        // below) and never reads this value back, so it only needs to be non-null/non-empty and
        // non-reversible — never the player's real password.
        const val MANAGED_PASSWORD_PLACEHOLDER = "pano-managed"
    }

    private data class ConfigSetting(
        val path: String,
        val expectedValue: Any,
        val getValue: (FileConfiguration) -> Any?,
        val logMessage: String
    )

    private val requiredConfigSettings = listOf(
        ConfigSetting(
            path = "settings.security.passwordHash",
            expectedValue = "CUSTOM",
            getValue = { it.getString("settings.security.passwordHash") },
            logMessage = "Set AuthMe password hash to CUSTOM"
        ),
        ConfigSetting(
            path = "settings.registration.type",
            expectedValue = "PASSWORD",
            getValue = { it.getString("settings.registration.type") },
            logMessage = "Set AuthMe register type to PASSWORD"
        ),
        ConfigSetting(
            path = "settings.security.minPasswordLength",
            expectedValue = 6,
            getValue = { it.getInt("settings.security.minPasswordLength") },
            logMessage = "Set AuthMe min password length to 6"
        ),
        ConfigSetting(
            path = "settings.security.passwordMaxLength",
            expectedValue = 128,
            getValue = { it.getInt("settings.security.passwordMaxLength") },
            logMessage = "Set AuthMe password max length to 128"
        ),
        ConfigSetting(
            path = "settings.restrictions.allowedNicknameCharacters",
            expectedValue = "[a-zA-Z0-9_]*",
            getValue = { it.getString("settings.restrictions.allowedNicknameCharacters") },
            logMessage = "Set AuthMe allowed nickname characters to [a-zA-Z0-9_]*"
        )
    )
    private val logger by lazy {
        panoPluginMain.getPanoLogger()
    }

    private val authMePlugin by lazy {
        Bukkit.getPluginManager().getPlugin("AuthMe")!!
    }

    private lateinit var authMeApi: AuthMeApi

    private val pano by lazy {
        panoPluginMain.getPano()
    }

    private val platformManager by lazy {
        pano.platformManager
    }

    private val eventManager by lazy {
        pano.eventManager
    }

    private val i18nManager by lazy {
        pano.i18nManager
    }

    // when register command is called, saved here
    private val pendingRegisterPasswords = ConcurrentHashMap<String, String>()

    // playerName.lowercase() entries currently being provisioned with a synthetic AuthMe password
    // (first-join reconcile); handleComputeHash must not treat these as a real password change.
    private val provisioningPlaceholder = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var initialized: Boolean = false

    // Guards registerEvents() specifically (has the Bukkit listener been installed / not yet
    // unregistered), kept separate from `initialized` so start() can always re-run its
    // isConfigCompatible()/forceConfig() verification without that also re-registering an
    // already-registered listener. Not volatile and not lock-guarded: every read/write of this
    // field happens inside performStart()/performStop() (always executed on the Bukkit main thread
    // via runOnMainThread(), see below) or onEnable() (Bukkit guarantees onEnable() itself runs on
    // the main thread) - so Bukkit's own single-threaded task execution serializes all of it. It is
    // never read from anywhere else.
    private var eventsRegistered: Boolean = false

    // Contributed into the shared SpigotEventListener (see registerEvents()/performStop()) instead
    // of a second, independent AsyncPlayerPreLoginEvent Bukkit handler, so this reconcile shares
    // SpigotEventListener's single withTimeout(5_000) budget with BanIntegration's own pre-login
    // check instead of each spending its own 5s on the same login (worst case was 10s before this).
    // Still runs at the same effective Bukkit priority as before this change:
    // SpigotEventListener.onPlayerPreLogin carries no explicit @EventHandler priority (i.e.
    // EventPriority.NORMAL), exactly what this reconcile ran at directly beforehand - so this only
    // changes *which* Pano-owned Bukkit listener object the reconcile runs from, not where in
    // AuthMe's own AsyncPlayerPreLoginEvent priority chain it runs relative to AuthMe's own
    // listeners (that ordering guarantee - NORMAL tier - is unchanged; the exact order of listeners
    // *within* the same tier was never a guarantee Bukkit provides in the first place, so nothing
    // that depended on it could have been relied upon before this change either).
    private val preLoginListeners by lazy {
        setOf(
            object : OnPlayerPreLogin() {
                override suspend fun handle(eventHelper: EventHelper, vararg args: Any) {
                    // Owner's agreed pre-login policy (mirrors BanIntegration / VelocityEventListener):
                    // socket down -> allow; a throwing check -> deny (fail closed); the check exceeding
                    // its budget -> allow, but an already-recorded disallow (e.g. the not-verified kick
                    // below) must survive.
                    if (platformManager.getWebSocket() == null) {
                        return
                    }

                    val event = args[0] as AsyncPlayerPreLoginEvent
                    val playerName = args[1] as String

                    try {
                        val playerInfo =
                            platformManager.sendMessageAwaitResponse<GetPlayerInfoMessage>(
                                GetPlayerInfoRequest(playerName),
                                GetPlayerInfoMessage::class.java
                            )

                        // serverSettings is lateinit and only assigned at the very end of
                        // onConnectionEstablished(), after `webSocket` (the field the allow-gate above
                        // reads) is already published - a pre-login landing in that window would
                        // otherwise throw UninitializedPropertyAccessException here and get denied by
                        // the generic catch below. Treat "not yet negotiated" as "platform unavailable"
                        // -> allow instead, per the owner's policy. Mirrors
                        // BanPlayerHandler/PermissionIntegration.isPermissionIntegrationEnabled().
                        val authRequireVerified = try {
                            platformManager.serverSettings.authRequireVerified
                        } catch (_: UninitializedPropertyAccessException) {
                            false
                        }

                        if (authRequireVerified && playerInfo.registered && !playerInfo.verified) { // registered but not verified, kick
                            val key = if (playerInfo.email.isNullOrBlank()) "auth.register-kick" else "auth.not-verified"
                            val message = i18nManager.translate(playerInfo, key, mapOf("email" to maskEmail(playerInfo.email)))

                            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message.colorize())
                            return
                        }

                        val registeredInAuthMe = authMeApi.isRegistered(playerName)

                        if (!registeredInAuthMe && playerInfo.registered) {
                            // registered in Pano but not in AuthMe; provision a placeholder AuthMe row without
                            // letting the synthetic password round-trip through handleComputeHash as a real
                            // password change (see provisioningPlaceholder / handleComputeHash).
                            val provisioningKey = playerName.lowercase()

                            provisioningPlaceholder.add(provisioningKey)

                            try {
                                authMeApi.registerPlayer(playerName, UUID.randomUUID().toString())
                            } finally {
                                provisioningPlaceholder.remove(provisioningKey)
                            }
                        } else if (registeredInAuthMe && !playerInfo.registered) {
                            // registered in AuthMe but not in Pano
                            authMeApi.forceUnregister(playerName)
                        }
                    } catch (e: TimeoutCancellationException) {
                        // The shared budget (SpigotEventListener's withTimeout(5_000)) ran out - rethrow
                        // instead of swallowing it here, so its own catch handles the timeout centrally
                        // (allow, unless a disallow() is already recorded above) and cooperative
                        // cancellation of the shared coroutine completes correctly. Swallowing a
                        // CancellationException instead of rethrowing it would only have been safe when
                        // this owned its own private withTimeout, which it no longer does.
                        throw e
                    } catch (e: Exception) {
                        // Fail closed: the default AsyncPlayerPreLoginEvent result is ALLOWED, so a
                        // throwing check must not leave the login unresolved. Handled locally (not left
                        // to SpigotEventListener's own catch) specifically so a failure here can never
                        // skip a later listener sharing this same forEach loop (e.g. BanIntegration's own
                        // OnPlayerPreLogin) - the two checks must stay isolated from each other, exactly
                        // as they were when each ran from its own independent Bukkit handler.
                        logger.severe("&cPre-login check failed for \"$playerName\": ${e.message}".colorize())

                        if (event.loginResult == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                            event.disallow(
                                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                                "Unable to verify your account right now, please try again shortly.".colorize()
                            )
                        }
                    }
                }
            }
        )
    }

    // Runs [block] on the Bukkit main thread - synchronously if we're already on it, otherwise
    // scheduled for the next tick via the Bukkit scheduler. start()/stop() are called from
    // onConnectionEstablished()/onServerSettingsChanged()/onDisconnect(), which run on the Vert.x
    // event loop, never the main thread - so the common case for those is the scheduled path. This
    // is also why performStart()/performStop() (and everything they touch: AuthMe's config object,
    // authme-backup.yml, HandlerList) never need a lock of their own: they only ever run here, from
    // onEnable(), or from the Bukkit command-preprocess handlers below - all three guaranteed
    // main-thread by Bukkit's own plugin/event lifecycle - so Bukkit's single-threaded task
    // execution is what actually serializes all of it.
    //
    // If the plugin is already disabled and we're not on the main thread, the scheduler refuses new
    // tasks (IllegalPluginAccessException) and there is no future tick to hop to - [onSchedulingRefused]
    // runs instead, synchronously, so it must be safe to call off-thread (e.g. just a log line).
    private fun runOnMainThread(onSchedulingRefused: () -> Unit, block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) {
            block()
            return
        }

        if (!panoPluginMain.isEnabled) {
            onSchedulingRefused()
            return
        }

        try {
            Bukkit.getScheduler().runTask(panoPluginMain, Runnable(block))
        } catch (e: Exception) {
            // e.g. IllegalPluginAccessException if the plugin was disabled in the gap between the
            // isEnabled check above and this call.
            onSchedulingRefused()
        }
    }

    private fun start() {
        // Cheap pre-check on the calling thread (usually the Vert.x event loop) so a false
        // authIntegration/disabled-AuthMe doesn't even bother scheduling a main-thread hop -
        // performStart() re-checks both anyway (see below) since either can change in the gap
        // between this call and the scheduled tick actually running.
        if (!platformManager.serverSettings.authIntegration) {
            return
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("AuthMe")) {
            return
        }

        runOnMainThread(
            onSchedulingRefused = {
                logger.warning("&cCould not hook into AuthMe: Pano is disabled and this is not the main thread.".colorize())
            }
        ) {
            performStart()
        }
    }

    private fun performStart() {
        // Re-verify: this may be running a tick or more after start() was called from the event
        // loop, and authIntegration/AuthMe's enabled state can change in that gap (e.g. a
        // near-simultaneous onServerSettingsChanged() already flipped authIntegration back off, or
        // an admin disabled AuthMe in between).
        if (!platformManager.serverSettings.authIntegration) {
            return
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("AuthMe")) {
            return
        }

        authMeApi = AuthMeApi.getInstance()

        initialized = true

        if (!eventsRegistered) {
            logger.info("&eAuthMe is enabled, hooking into AuthMe...".colorize())

            registerEvents()
            eventsRegistered = true
        }

        // Reload from disk before checking compatibility, so the check reflects the real current
        // state (an operator/another plugin may have changed config.yml since AuthMe's own cache
        // was last populated) - and only touch AuthMe again (forceConfig(), which itself only
        // reloads AuthMe when it actually changes a setting) when something is actually wrong.
        // start() runs on every onConnectionEstablished()/onServerSettingsChanged(), including
        // PlatformManager's 3s reconnect retry loop while the platform is flapping - unconditionally
        // reloading AuthMe here used to dispatch "authme reload" every 3 seconds for no reason.
        authMePlugin.reloadConfig()

        if (!isConfigCompatible()) {
            logger.warning("&6AuthMe config is not compatible with Pano. Pano will force and reload AuthMe.".colorize())

            forceConfig()
        }
    }

    // restoreConfig defaults to false. Only a few call sites ever pass true, and all of them are a
    // deliberate "Pano no longer owns AuthMe's auth" signal, never an ordinary restart:
    //   - onDisconnect(), once platformManager.isPlatformConfigured() has already gone false (a
    //     deliberate /pano disconnect, not a transient WebSocket blip - see onDisconnect below).
    //   - onConnectionEstablished()/onServerSettingsChanged(), when authIntegration reads false.
    //   - onDisable(), but ONLY when the server itself is not the one stopping (see onDisable() /
    //     isServerStopping()) - an ordinary server restart still passes false, leaving config.yml on
    //     CUSTOM and authme-backup.yml on disk for onEnable()'s startup reconcile to pick back up on
    //     the next boot, because restoring would flip AuthMe back onto its native hash while every
    //     row it holds is still the literal MANAGED_PASSWORD_PLACEHOLDER, breaking every login for
    //     the entire window until the platform reconnects.
    private fun stop(restoreConfig: Boolean = false) {
        runOnMainThread(
            onSchedulingRefused = {
                logger.warning("&ePano is disabled but not on the main thread - please run '/authme reload' manually so AuthMe picks up the restored configuration.".colorize())
            }
        ) {
            performStop(restoreConfig)
        }
    }

    private fun performStop(restoreConfig: Boolean) {
        if (restoreConfig) {
            // Take AuthMe off passwordHash: CUSTOM while our EncryptionMethod is still installed below,
            // otherwise CUSTOM resolves to a null encryptionMethod after unregister/reload and every
            // login/register breaks until an admin hand-edits config.yml.
            // Explicit `this.` because the restoreConfig parameter above shadows this method's name.
            //
            // Must run even when `initialized` is already false (e.g. this instance never forced the
            // config itself but a stale authme-backup.yml / a leftover CUSTOM setting is on disk from
            // before) — otherwise a real teardown silently skips the restore and leaves AuthMe stuck on
            // CUSTOM with no EncryptionMethod. So this runs BEFORE the `!initialized` guard below.
            // restoreConfig() is self-guarding (no-op beyond consuming a leftover backup file / reverting
            // a stray CUSTOM to SHA256), so calling it when nothing was actually forced is harmless.
            this.restoreConfig()
        }

        if (!initialized) {
            return
        }

        HandlerList.unregisterAll(this)
        panoPluginMain.unregisterEventListeners(preLoginListeners)
        panoPluginMain.registerEventListeners(eventManager.eventListeners)

        // Pano's custom password / event path is gone; AuthMe may still hold in-memory state that
        // matched that integration. Reloading makes it re-read config and re-initialize cleanly.
        // performStop() is only ever entered via runOnMainThread(), so we're always on the main
        // thread here already - dispatch directly instead of hopping through reloadAuthMe()'s own
        // scheduler round trip.
        try {
            // Bukkit disables plugins in reverse load order during a real shutdown, so AuthMe may
            // already be disabled (and its commands unregistered) by the time this runs.
            // dispatchCommand() returns false for an unrecognized command instead of throwing, so
            // without checking both of these first the log below would unconditionally claim success
            // even when AuthMe was never actually told about the restored config.
            val reloaded = Bukkit.getPluginManager().isPluginEnabled("AuthMe") &&
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "authme reload")

            if (reloaded) {
                logger.info("AuthMe configuration reloaded".colorize())
            } else {
                logger.warning("Could not reload AuthMe automatically: AuthMe is already disabled.".colorize())
                logger.warning("Please run '/authme reload' manually".colorize())
            }
        } catch (e: Exception) {
            logger.warning("Could not reload AuthMe automatically: ${e.message}".colorize())
            logger.warning("Please run '/authme reload' manually".colorize())
        }

        logger.info("&eAuthMe integration is disabled.".colorize())

        initialized = false
        eventsRegistered = false
    }

    override fun onEnable() {
        // Startup reconcile: the invariant "config is CUSTOM (forced by Pano) iff the
        // EncryptionMethod listener is installed" can be broken by a hard kill - config.yml left on
        // CUSTOM and authme-backup.yml left on disk, with nothing to reconcile them, because
        // start() (and therefore registerEvents()) only ever runs later, from
        // onConnectionEstablished()/onServerSettingsChanged() once authIntegration == true is
        // known. If the platform is unreachable/unconfigured on this boot, that may be seconds away
        // or never - and until then AuthMe resolves CUSTOM to a null EncryptionMethod and every
        // login/register throws. Reconcile eagerly here, before AuthMe can serve its first login.
        //
        // Only authme-backup.yml's presence is used as evidence that PANO forced the config (see
        // below) - passwordHash merely reading CUSTOM with no backup file is just as consistent with
        // a genuine third-party EncryptionMethod provider that has nothing to do with Pano, and must
        // not be silently taken over.
        if (!Bukkit.getPluginManager().isPluginEnabled("AuthMe")) {
            return
        }

        val backupFile = File(panoPluginMain.dataFolder, "authme-backup.yml")
        val leftOnCustom = authMePlugin.config.getString("settings.security.passwordHash") == "CUSTOM"

        if (!backupFile.exists()) {
            if (leftOnCustom) {
                // passwordHash is CUSTOM but there is no authme-backup.yml, so nothing here proves
                // Pano is the one that set it - it could be a genuine third-party EncryptionMethod
                // provider with Pano's authIntegration off. Only an actual backup file is positive
                // proof Pano forced this config; anything less must not silently take over a setup
                // Pano may not own. Warn instead so the operator knows what's on disk and leave it
                // alone - if this really is a leftover Pano state (e.g. authme-backup.yml was
                // deleted by hand), the fix is to reconnect/re-toggle authIntegration so start()
                // forces and backs it up properly again.
                logger.warning(
                    ("&6AuthMe's passwordHash is set to CUSTOM but Pano has no record of setting it " +
                        "(authme-backup.yml is missing) - leaving AuthMe's configuration alone. If a " +
                        "third-party plugin provides this, this is expected. If Pano is supposed to " +
                        "manage AuthMe, verify authIntegration is enabled and the platform is connected.").colorize()
                )
            }

            // Clean state - either a fresh install, or the previous session shut down gracefully
            // and already restored everything itself.
            return
        }

        if (!platformManager.isPlatformConfigured()) {
            // start() can never run this session - only onConnectionEstablished()/
            // onServerSettingsChanged() call it, and neither fires without a configured platform -
            // so nothing will ever come along to fix this on its own. Restore right now instead of
            // leaving AuthMe stuck on CUSTOM with no EncryptionMethod for the entire session.
            restoreConfig()
            return
        }

        // A connection attempt is about to happen (see SpigotMain.onStart(), called right after
        // integrations' onEnable()), but authIntegration's real value won't be known until
        // GetServerSettingsMessage arrives - which could be seconds into boot, or never, if the
        // platform turns out to be unreachable. The leftover CUSTOM/backup already proves Pano
        // intended to manage AuthMe as of the last time it definitely knew its state, so register
        // the listener eagerly to close that gap. `eventsRegistered = true` makes the eventual
        // start() (if authIntegration comes back true) skip re-registering the listener, but it
        // still runs isConfigCompatible()/forceConfig() - re-verifying (and repairing, if needed)
        // the other four settings instead of trusting this leftover state blindly for the rest of
        // the session. If authIntegration comes back false instead, onServerSettingsChanged() ->
        // stop(restoreConfig = true) unregisters and restores exactly like any other teardown.
        authMeApi = AuthMeApi.getInstance()
        initialized = true
        registerEvents()
        eventsRegistered = true

        logger.info("&eReconciled AuthMe integration at startup (leftover CUSTOM from a previous session).".colorize())
    }

    override fun onDisable() {
        // Two very different situations reach onDisable() and they must be told apart, the same way
        // onDisconnect() below already tells apart a transient blip from a deliberate unlink:
        //   1. A full server shutdown/restart (isServerStopping() true). onEnable()'s startup
        //      reconcile above runs again on the next boot and picks leftover CUSTOM +
        //      authme-backup.yml back up, so restoreConfig() must NOT run here - every AuthMe
        //      password row written while this integration was live is the literal
        //      MANAGED_PASSWORD_PLACEHOLDER, not a real hash, so restoring passwordHash now would
        //      break every login with "wrong password" for the entire window until the platform
        //      reconnects (and a player could /register locally in that window, permanently
        //      desyncing that account from Pano). Leave config.yml on CUSTOM and
        //      authme-backup.yml on disk instead.
        //   2. Only this plugin is being disabled and the server keeps running (e.g.
        //      /plugman disable Pano). Nothing will ever call onEnable() again to reconcile -
        //      stop() below is about to unregister the ONLY provider of the CUSTOM
        //      EncryptionMethod (HandlerList.unregisterAll in performStop()), and leaving
        //      config.yml on CUSTOM with no provider bricks every login/register until an admin
        //      re-enables Pano or hand-edits config.yml. Restore the config in that case instead,
        //      same as any other deliberate teardown.
        stop(restoreConfig = !isServerStopping())
    }

    // Bukkit.isStopping() (Server#isStopping()) exists only on Paper and modern Spigot (added by
    // SPIGOT-5479, ~2020) - specifically to distinguish a real server shutdown from a plugin being
    // disabled independently (e.g. PlugMan). Call it reflectively so this still compiles/runs
    // against the older Bukkit API this module targets.
    //
    // When BOTH reflective probes fail (NoSuchMethodException on a server old enough to predate
    // that API), we used to treat that as "stopping" - but that conflates "the API is missing"
    // with "the server is stopping", and API absence is unconditionally true for every such old
    // server, every time, including an ordinary PlugMan disable. The two wrong verdicts are not
    // symmetric: reporting "stopping" when it's really just this plugin being disabled unregisters
    // the sole CUSTOM EncryptionMethod provider without restoring passwordHash first (see
    // onDisable()), bricking every login on the server until an admin intervenes by hand; reporting
    // "not stopping" when the server really is stopping only costs a redundant restoreConfig() that
    // self-heals the moment forceConfig() re-runs on the platform's next reconnect (see
    // performStart()). So the legacy fallback must be biased toward "not stopping", not "stopping".
    //
    // probeJvmShutdownInProgress() below is the best cross-version positive signal available for
    // "not stopping" without blocking (waiting to observe a future server tick is not an option
    // from inside onDisable()) or relying on version-specific NMS/CraftBukkit internals: plugin
    // isEnabled()-based tricks (e.g. trying to schedule a Bukkit task for this plugin) do NOT work
    // here, because JavaPlugin.setEnabled(false) flips isEnabled() to false *before* invoking
    // onDisable() (confirmed by disassembling spigot-api's JavaPlugin.class) - CraftScheduler's
    // task-validation throws off that same flag (confirmed against spigot-1.8.8's CraftScheduler) -
    // so by the time this code runs, both isEnabled() and "can I schedule a task for myself" are
    // already false/reject regardless of whether this is a real shutdown or a PlugMan disable; they
    // cannot tell the two cases apart.
    private fun isServerStopping(): Boolean {
        return try {
            Bukkit::class.java.getMethod("isStopping").invoke(null) as? Boolean ?: probeJvmShutdownInProgress()
        } catch (e: Exception) {
            try {
                val server = Bukkit.getServer()
                server.javaClass.getMethod("isStopping").invoke(server) as? Boolean ?: probeJvmShutdownInProgress()
            } catch (e2: Exception) {
                probeJvmShutdownInProgress()
            }
        }
    }

    // Positive signal for "the server is NOT stopping", used only once both isStopping() reflective
    // probes above have failed. Runtime#addShutdownHook() throws IllegalStateException once the JVM
    // has actually begun running its registered shutdown hooks - and vanilla/CraftBukkit/Spigot has
    // registered exactly such a hook since at least 1.8 (a "Server shutdown thread" that runs the
    // same stop path onDisable() is called from) specifically to catch an abrupt termination
    // (Ctrl+C / SIGTERM / `kill`) that never goes through the ordinary "/stop" console command. So
    // when that hook is the reason we're on this thread, this reliably observes "shutdown already
    // in progress" and correctly reports isServerStopping() == true.
    //
    // This probe cannot see an ordinary in-console "/stop": that path reaches onDisable() via the
    // main thread's own graceful-exit code, before the JVM's shutdown-hook machinery itself starts,
    // so addShutdownHook() still succeeds there too - indistinguishable from a PlugMan disable by
    // this signal alone. That residual case falls through to "not stopping", which is exactly the
    // accepted, bias-justified default explained above (a harmless, self-healing redundant restore)
    // rather than the old blanket "assume stopping" that guaranteed a broken PlugMan disable.
    private fun probeJvmShutdownInProgress(): Boolean {
        val probe = Thread {}

        return try {
            Runtime.getRuntime().addShutdownHook(probe)
            Runtime.getRuntime().removeShutdownHook(probe)
            false
        } catch (e: IllegalStateException) {
            // "Shutdown in progress" - the JVM's own shutdown-hook sequence has already started.
            true
        } catch (e: Exception) {
            // Any other failure (e.g. a SecurityManager denying hook registration) is not evidence
            // of a shutdown - bias toward the cheaper mistake, same as every other branch here.
            false
        }
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        if (platformManager.serverSettings.authIntegration) {
            start()
            return
        }

        stop(restoreConfig = true)
    }

    override fun onDisconnect() {
        // This hook fires for two very different situations and they must be told apart:
        //   1. A transient WebSocket drop (PlatformManager.onWebSocketClosed()) - the platform
        //      config on disk/in-memory is untouched, we're about to auto-retry, and this must be
        //      a no-op: unregistering the CUSTOM EncryptionMethod listener here (the ONLY provider
        //      of it) would make AuthMe resolve CUSTOM to a null EncryptionMethod and break every
        //      login/register for the whole outage, not just requests made while the socket
        //      happens to be down.
        //   2. A deliberate /pano disconnect (PlatformManager.disconnectPlatform()) - the token and
        //      encryption key are gone for good, so leaving `initialized` true and the listener
        //      registered means every comparePassword()/computeHash() throws
        //      "Not connected to Pano Platform" FOREVER: config.yml stays on passwordHash: CUSTOM,
        //      restoreConfig() is never reached, and no player can log in or register until an
        //      admin re-runs /pano connect or hand-edits AuthMe's config.
        //
        // disconnectPlatform() calls pluginMain.onDisconnect() AFTER it has already cleared the
        // platform config (removePlatform()), specifically so the two cases are distinguishable
        // here: isPlatformConfigured() reading false means case 2, a real, deliberate unlink -
        // restore the config and unregister cleanly, same as any other teardown.
        if (!platformManager.isPlatformConfigured()) {
            stop(restoreConfig = true)
            return
        }

        // Case 1: leave the listener registered and `initialized` untouched, so CUSTOM keeps
        // resolving. comparePassword()/computeHash() already have to round-trip through
        // platformManager.sendMessageAwaitResponse(), which throws immediately when the socket is
        // null — so individual login/register attempts fail loudly (as they must, there's no
        // platform to answer them) without the whole server being locked out until reconnect.
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        if (serverSettings.authIntegration) {
            start()
            return
        }

        stop(restoreConfig = true)
    }

    override fun isInitialized(): Boolean = initialized

    private fun registerEvents() {
        panoPluginMain.unregisterEventListeners(eventManager.eventListeners)
        panoPluginMain.registerEventListeners(preLoginListeners)
        panoPluginMain.server.pluginManager.registerEvents(this, panoPluginMain)

        logger.info("&2Registered events for AuthMe.".colorize())
    }

    private fun forceConfig() {
        // Merge onto the current on-disk state, not whatever authMePlugin.config's cache last
        // held - AuthMe (a ConfigMe plugin) can rewrite config.yml on its own between whenever
        // that cache was populated and now (see restoreConfig()'s reloadConfig() for the same
        // reasoning).
        authMePlugin.reloadConfig()

        val config = authMePlugin.config
        val backupValues = mutableMapOf<String, Any?>()

        // Whether a pre-Pano passwordHash value is already on record from an earlier forceConfig()
        // call this same management epoch (i.e. since the last restoreConfig() consumed the backup).
        // Read once so the loop below only ever records it the FIRST time: on a later call, seeing
        // passwordHash already at CUSTOM is Pano's own doing (this same code forced it), not the
        // operator's original value, and must not overwrite the real one already saved.
        val passwordHashAlreadyBackedUp = File(panoPluginMain.dataFolder, "authme-backup.yml")
            .takeIf { it.exists() }
            ?.let {
                try {
                    YamlConfiguration.loadConfiguration(it).contains("settings.security.passwordHash")
                } catch (_: Exception) {
                    false
                }
            } ?: false

        requiredConfigSettings.forEach { setting ->
            val currentValue = setting.getValue(config)

            if (currentValue != setting.expectedValue) {
                backupValues[setting.path] = currentValue
                config.set(setting.path, setting.expectedValue)
                logger.info(setting.logMessage.colorize())
            } else if (setting.path == "settings.security.passwordHash" && !passwordHashAlreadyBackedUp) {
                // Record passwordHash's pre-Pano value even when it already equals CUSTOM (e.g. a
                // third-party EncryptionMethod was already installed) - restoreConfig() must be
                // able to tell "Pano set this to CUSTOM" apart from "a third party already had it
                // on CUSTOM" by reading this backup, instead of inferring ownership from whether
                // SOME backup file happens to exist (see restoreConfig()).
                backupValues[setting.path] = currentValue
            }
        }

        if (backupValues.isNotEmpty()) {
            // Save backup before changing config
            saveBackup(backupValues)
            
            authMePlugin.saveConfig()
            logger.info("AuthMe configuration has been updated for Pano integration".colorize())

            reloadAuthMe()
        }
    }

    private fun saveBackup(backupValues: Map<String, Any?>) {
        try {
            val dataFolder = panoPluginMain.dataFolder
            val backupFile = File(dataFolder, "authme-backup.yml")

            val backupConfig = if (backupFile.exists()) {
                YamlConfiguration.loadConfiguration(backupFile)
            } else {
                YamlConfiguration()
            }

            // Update backup values (overwrite existing ones)
            backupValues.forEach { (key, value) ->
                backupConfig.set(key, value)
            }

            backupFile.parentFile?.mkdirs()
            backupConfig.save(backupFile)

            logger.info("AuthMe backup saved to authme-backup.yml".colorize())
        } catch (e: Exception) {
            logger.warning("Failed to save AuthMe backup: ${e.message}".colorize())
        }
    }

    // Inverse of forceConfig()/saveBackup(): writes the pre-Pano values back into AuthMe's config
    // and consumes the backup file. Must run before HandlerList.unregisterAll/reloadAuthMe in stop(),
    // otherwise AuthMe is left on passwordHash: CUSTOM with no encryptionMethod and every login fails.
    private fun restoreConfig() {
        // performStop() calls this before checking `initialized`, so this can run even on a server
        // that never had AuthMe installed this session (e.g. every onDisable() where AuthMe is
        // absent entirely). Look the plugin up directly instead of via the force-unwrapped
        // `authMePlugin` lazy so that case is a clean no-op instead of an NPE.
        val authMe = Bukkit.getPluginManager().getPlugin("AuthMe") ?: return

        // Merge onto whatever is on disk right now, not a stale in-memory snapshot from whenever
        // this instance first touched authMe.config - AuthMe (a ConfigMe plugin) rewrites
        // config.yml on its own, so anything an operator changed since our cache was populated
        // must not be reverted by the save below.
        authMe.reloadConfig()

        val dataFolder = panoPluginMain.dataFolder
        val backupFile = File(dataFolder, "authme-backup.yml")
        val config = authMe.config
        var changed = false

        if (backupFile.exists()) {
            try {
                val backupConfig = YamlConfiguration.loadConfiguration(backupFile)

                // forceConfig() always records settings.security.passwordHash's pre-Pano value
                // here, even when it already equalled CUSTOM (see forceConfig()) - so restoring
                // strictly from what the backup actually contains is enough on its own to tell
                // "Pano set this to CUSTOM" (restores to whatever Pano overwrote) apart from "a
                // third-party EncryptionMethod already had it on CUSTOM" (restores CUSTOM right
                // back to itself). No separate ownership guard is needed here - inferring
                // ownership from "some backup file exists" used to clobber a third-party CUSTOM
                // setup that forceConfig() itself never touched.
                requiredConfigSettings.forEach { setting ->
                    if (backupConfig.contains(setting.path)) {
                        config.set(setting.path, backupConfig.get(setting.path))
                        changed = true
                    }
                }
            } catch (e: Exception) {
                logger.warning("Failed to read AuthMe backup: ${e.message}".colorize())
            }
        }

        if (!changed) {
            return
        }

        try {
            // Save explicitly against the real config file instead of authMe.saveConfig() -
            // JavaPlugin.saveConfig() swallows IOException internally (just logs it), which on a
            // read-only/full data dir would silently leave config.yml on CUSTOM while we'd already
            // deleted the backup below, permanently losing the operator's original settings.
            config.save(File(authMe.dataFolder, "config.yml"))
        } catch (e: Exception) {
            logger.severe(
                "&cFailed to restore AuthMe configuration: ${e.message}. config.yml may still be on passwordHash: CUSTOM with no EncryptionMethod - logins may fail until this is fixed. The backup was kept so a retry (e.g. /pano connect then /pano disconnect) can try again.".colorize()
            )
            return
        }

        // Only consume the backup once the write above is confirmed to have succeeded - never
        // claim "Restored" (below) unless it actually was.
        backupFile.delete()

        // The config is restored, but the AuthMe rows themselves are not: every password AuthMe
        // stored while this integration was active is the literal placeholder
        // MANAGED_PASSWORD_PLACEHOLDER, not a real hash, and the original hashes no longer exist
        // anywhere. Say so plainly instead of letting the "Restored" log line imply AuthMe is
        // fully working again - every account will otherwise fail with a silent "wrong password".
        logger.warning(
            ("&6Restored AuthMe configuration. AuthMe passwords were managed by Pano while the " +
                "integration was active and cannot be recovered - the original hashes no longer " +
                "exist. Every account will fail to log in with \"wrong password\"; players must " +
                "re-register, or an admin must run '/authme register <player> <password>' for them.").colorize()
        )
    }

    private fun reloadAuthMe() {
        if (!panoPluginMain.isEnabled) {
            return
        }
        try {
            Bukkit.getScheduler().runTask(panoPluginMain, Runnable {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "authme reload")
                logger.info("AuthMe configuration reloaded".colorize())
            })
        } catch (e: Exception) {
            logger.warning("Could not reload AuthMe automatically: ${e.message}".colorize())
            logger.warning("Please run '/authme reload' manually".colorize())
        }
    }

    private fun isConfigCompatible(): Boolean {
        val config = authMePlugin.config

        return requiredConfigSettings.all { setting ->
            setting.getValue(config) == setting.expectedValue
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerLogin(event: LoginEvent) {
        runBlocking {
            eventManager.eventListeners.filterIsInstance<OnPlayerJoin>().forEach { it.handle(panoPluginMain.eventHelper, event.player) }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerRegister(event: RegisterEvent) {
        val player = event.player
        val playerName = player.name

        val pendingPassword = pendingRegisterPasswords.remove(playerName.lowercase()) ?: return

        runBlocking {
            val request = RegisterPlayerRequest(playerName, pendingPassword, getPlayerIp(playerName) ?: "unknown")

            val registerResponse = platformManager.sendMessageAwaitResponse<RegisterPlayerMessage>(
                request,
                RegisterPlayerMessage::class.java
            )

            if (registerResponse.error != null) {
                SpigotServerUtil.kickPlayer(panoPluginMain, player, "")
                logger.severe("&cAn error occurred during the registration of \"$playerName\": ${registerResponse.error}".colorize())
                return@runBlocking
            }

            logger.info("&2Successfully registered player \"$playerName\".".colorize())

            if (platformManager.serverSettings.authKickAfterRegister) {
                val message = i18nManager.translate(i18nManager.platformLocale, "auth.register-kick")

                SpigotServerUtil.kickPlayer(panoPluginMain, player, message.colorize())
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerLogout(event: LogoutEvent) {
        runBlocking {
            eventManager.eventListeners.filterIsInstance<OnPlayerDisconnect>().forEach { it.handle(panoPluginMain.eventHelper, event.player) }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerDisconnect(event: PlayerQuitEvent) {
        if (authMeApi.isAuthenticated(event.player)) {
            runBlocking {
                eventManager.eventListeners.filterIsInstance<OnPlayerDisconnect>().forEach { it.handle(panoPluginMain.eventHelper, event.player) }
            }
        }

        pendingRegisterPasswords.remove(event.player.name.lowercase())
    }

    // Resolves a typed command line to Bukkit's canonical command name (following aliases and the
    // plugin:label fallback-prefix form, and collapsing repeated whitespace) plus its first argument,
    // so the unsupported-command guard below can't be bypassed by an alias like "/unreg" or
    // "/authme:unregister" that a literal prefix match on the raw text would miss.
    private fun resolveAuthMeCommand(message: String): Pair<String, String?> {
        val tokens = message.removePrefix("/").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (tokens.isEmpty()) {
            return "" to null
        }

        val rawLabel = tokens[0].substringAfter(':')
        val commandName = Bukkit.getPluginCommand(rawLabel)?.name?.lowercase() ?: rawLabel.lowercase()
        val subLabel = tokens.getOrNull(1)?.lowercase()

        return commandName to subLabel
    }

    private fun isUnregisterCommand(commandName: String, subLabel: String?): Boolean =
        commandName == "unregister" || commandName == "unreg" ||
            (commandName == "authme" && (subLabel == "unregister" || subLabel == "unreg"))

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        if (event.isCancelled) {
            return
        }

        val msg = event.message.lowercase()

        val (commandName, subLabel) = resolveAuthMeCommand(event.message)

        if (isUnregisterCommand(commandName, subLabel)) {
            // Only the "/authme unregister" admin sub-command is exempt, and only with the admin
            // node — the bare "/unregister" / "/unreg" form (self-unregister) is always blocked,
            // otherwise a player could delete their own AuthMe row while Pano still believes the
            // account is registered, desyncing the two.
            if (commandName == "authme" && event.player.hasPermission("authme.admin.unregister")) {
                return
            }

            event.isCancelled = true
            event.player.sendMessage("&cThis command is unsupported by Pano!".colorize())
            event.player.sendMessage("&cCheckout docs: https://panomc.com/docs".colorize())
            return
        }

        if (msg.startsWith("/authme reg") || msg.startsWith("/authme register")) {
            // If player doesn't have permission, let AuthMe handle the permission check
            if (!event.player.hasPermission("authme.admin.register")) {
                return
            }

            val args = event.message.split("\\s+".toRegex()) // split spaces

            if (args.size != 4) {
                return
            }

            val playerName = args[2]
            val password = args[3]

            runBlocking {
                val response =
                    platformManager.sendMessageAwaitResponse<IsPlayerRegisteredMessage>(
                        IsPlayerRegisteredRequest(
                            playerName
                        ),
                        IsPlayerRegisteredMessage::class.java
                    )

                if (response.registered) {
                    event.player.sendMessage("&c${playerName} is already registered.".colorize())
                    event.isCancelled = true
                    return@runBlocking
                }

                val request = RegisterPlayerRequest(playerName, password, getPlayerIp(playerName) ?: "unknown")
                val registerResponse = platformManager.sendMessageAwaitResponse<RegisterPlayerMessage>(
                    request,
                    RegisterPlayerMessage::class.java
                )

                if (registerResponse.error != null) {
                    event.isCancelled = true
                    event.player.sendMessage("&cAn error occurred during the registration of \"$playerName\": ${registerResponse.error}".colorize())
                    return@runBlocking
                }

                event.player.sendMessage("&2Successfully registered player \"$playerName\".".colorize())
                logger.info("&2Successfully registered player \"$playerName\".".colorize())

                val player = Bukkit.getPlayer(playerName)

                if ((player?.isOnline ?: false) && platformManager.serverSettings.authKickAfterRegister) {
                    val message = i18nManager.translate(i18nManager.platformLocale, "auth.register-kick")

                    SpigotServerUtil.kickPlayer(panoPluginMain, player, message.colorize())
                }
            }
        }

        if (msg.startsWith("/authme reload")) {
            // If player doesn't have permission, let AuthMe handle the permission check
            if (!event.player.hasPermission("authme.admin.reload")) {
                return
            }

            authMePlugin.reloadConfig()

            if (!isConfigCompatible()) {
                event.player.sendMessage("&6AuthMe config is not compatible with Pano. Pano will force and reload AuthMe.".colorize())

                forceConfig()

                event.player.sendMessage("&2AuthMe reloaded successfully!".colorize())
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onServerCommandProcess(event: ServerCommandEvent) {
        val msg = event.command.lowercase()

        val (commandName, subLabel) = resolveAuthMeCommand(event.command)

        if (isUnregisterCommand(commandName, subLabel)) {
            event.isCancelled = true
            event.sender.sendMessage("&cThis command is unsupported by Pano!".colorize())
            event.sender.sendMessage("&cCheckout docs: https://panomc.com/docs".colorize())
            return
        }

        if (msg.startsWith("authme reg") || msg.startsWith("authme register")) {
            val args = event.command.split("\\s+".toRegex()) // split spaces

            if (args.size != 4) {
                return
            }

            val playerName = args[2]
            val password = args[3]

            runBlocking {
                val response =
                    platformManager.sendMessageAwaitResponse<IsPlayerRegisteredMessage>(
                        IsPlayerRegisteredRequest(
                            playerName
                        ),
                        IsPlayerRegisteredMessage::class.java
                    )

                if (response.registered) {
                    logger.severe("&c${playerName} is already registered.".colorize())
                    event.isCancelled = true
                    return@runBlocking
                }

                val request = RegisterPlayerRequest(playerName, password, getPlayerIp(playerName) ?: "unknown")
                val registerResponse = platformManager.sendMessageAwaitResponse<RegisterPlayerMessage>(
                    request,
                    RegisterPlayerMessage::class.java
                )

                if (registerResponse.error != null) {
                    event.isCancelled = true
                    logger.warning("&cAn error occurred: ${registerResponse.error}".colorize())
                    return@runBlocking
                }

                logger.info("&2Successfully registered player \"$playerName\".".colorize())

                val player = Bukkit.getPlayer(playerName)

                if ((player?.isOnline ?: false) && platformManager.serverSettings.authKickAfterRegister) {
                    val message = i18nManager.translate(i18nManager.platformLocale, "auth.register-kick")

                    SpigotServerUtil.kickPlayer(panoPluginMain, player, message.colorize())
                }
            }
        }

        if (msg.startsWith("authme reload")) {
            authMePlugin.reloadConfig()

            if (!isConfigCompatible()) {
                logger.severe("&6AuthMe config is not compatible with Pano. Pano will force and reload AuthMe.".colorize())

                forceConfig()
                event.isCancelled = true
            }
        }
    }

    private fun handleComputeHash(playerName: String, password: String) {
        if (provisioningPlaceholder.contains(playerName.lowercase())) {
            // Pano's own first-join provisioning triggered this hash computation with a synthetic
            // password; it must never be forwarded to the platform as a real registration/password
            // change (see preLoginListeners).
            return
        }

        runBlocking {
            val response =
                platformManager.sendMessageAwaitResponse<IsPlayerRegisteredMessage>(
                    IsPlayerRegisteredRequest(
                        playerName
                    ),
                    IsPlayerRegisteredMessage::class.java
                )

            if (!response.registered) {
                pendingRegisterPasswords[playerName.lowercase()] = password

                return@runBlocking
            }

            val request = ChangePasswordRequest(playerName, password)

            val changePasswordResponse = platformManager.sendMessageAwaitResponse<ChangePasswordMessage>(
                request,
                ChangePasswordMessage::class.java
            )

            if (changePasswordResponse.error == null) {
                return@runBlocking
            }

            logger.severe("&cAn error occurred during the changing password of \"$playerName\": ${changePasswordResponse.error}".colorize())
            return@runBlocking
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPasswordEncryptionEvent(event: PasswordEncryptionEvent) {
        event.method = object : EncryptionMethod {
            override fun computeHash(
                password: String,
                name: String
            ): HashedPassword {
                handleComputeHash(name, password)

                // Never persist the real password: AuthMe writes this verbatim to its datasource,
                // and comparePassword() below ignores it entirely, delegating to the platform.
                return HashedPassword(MANAGED_PASSWORD_PLACEHOLDER)
            }

            override fun computeHash(
                password: String,
                salt: String?,
                name: String
            ): String {
                handleComputeHash(name, password)

                return MANAGED_PASSWORD_PLACEHOLDER
            }

            override fun comparePassword(
                password: String,
                hashedPassword: HashedPassword?,
                name: String
            ): Boolean {
                val success: Boolean

                runBlocking {
                    val response = platformManager.sendMessageAwaitResponse<PlayerAuthenticateMessage>(
                        PlayerAuthenticateRequest(
                            name,
                            password
                        ),
                        PlayerAuthenticateMessage::class.java
                    )

                    success = response.success
                }

                return success
            }

            override fun generateSalt(): String? = null

            override fun hasSeparateSalt(): Boolean = false

        }
    }
}