package com.panomc.plugins.pano.fabric

import com.panomc.plugins.pano.core.event.listeners.OnPlayerPreLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.minecraft.network.Connection
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.level.ServerPlayer
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles pre-login checks (ban integration) before the player enters the game world.
 * Called from PlayerListMixin before placeNewPlayer proceeds.
 *
 * `PlayerList.placeNewPlayer` always runs on the Minecraft server main thread by the time this
 * fires, so the check cannot simply block waiting on the platform round trip (platform-modules-2).
 * Instead this cancels the synchronous pass, runs the listener chain on a coroutine bounded by a
 * 5s timeout, and either kicks the connection (denied) or re-drives placeNewPlayer from the main
 * thread (allowed, or the platform timed out — fail open) once the check resolves. The server
 * keeps ticking throughout; only this individual connection is held.
 */
object FabricPreLoginHandler {

    internal var fabricMain: FabricMain? = null

    private const val CHECK_TIMEOUT_MS = 5_000L

    // Connections that have already cleared the async check and are being re-driven through
    // placeNewPlayer a second time — lets that second pass fall straight through instead of
    // checking (and hopping off-thread) again.
    private val recheckedConnections: MutableSet<Connection> =
        Collections.newSetFromMap(ConcurrentHashMap())

    fun handlePreLogin(
        connection: Connection,
        player: ServerPlayer,
        clientData: CommonListenerCookie,
        ci: CallbackInfo
    ) {
        if (recheckedConnections.remove(connection)) {
            return
        }

        val main = fabricMain ?: return
        val server = main.getServerOrNull() ?: return
        val eventListener = main.getFabricEventListenerOrNull() ?: return
        // Same lifecycle as `server` (set on SERVER_STARTED, cleared on SERVER_STOPPING); a null
        // scope here means the server isn't up to run the check against, same as a null server.
        val scope = main.getPluginScopeOrNull() ?: return
        val preLoginListeners = eventListener.listeners.filterIsInstance<OnPlayerPreLogin>()

        if (preLoginListeners.isEmpty()) {
            return
        }

        val username = try {
            val profile = player.getGameProfile()
            try {
                profile.javaClass.getMethod("name").invoke(profile) as String
            } catch (_: NoSuchMethodException) {
                profile.javaClass.getMethod("getName").invoke(profile) as String
            }
        } catch (_: Exception) {
            player.getName().string
        }

        val preLoginHelper = FabricPreLoginEventHelper(eventListener)

        // Hold this connection's login, not the server thread: cancel the synchronous placement
        // and finish it asynchronously once the platform answers (or the timeout below fires).
        ci.cancel()

        // CoroutineStart.ATOMIC, not the default: SERVER_STOPPING can cancel pluginScope from a
        // different thread than this one (e.g. the JVM shutdown hook Minecraft installs to halt()
        // the server on SIGTERM/Ctrl+C runs SERVER_STOPPING off the server thread), so pluginScope
        // can already be cancelled by the time this launch() call runs, even though the `scope`
        // lookup above just found it non-null. ci.cancel() just above already unconditionally
        // aborted the synchronous placeNewPlayer, so with the default start — which skips the body
        // entirely when launched on an already-cancelled Job — this connection would end up
        // neither disconnected nor re-placed: a hang, not a kick. ATOMIC guarantees the body below,
        // and therefore its `finally`, always runs. Matches BungeeEventListener's identical fix for
        // the identical scope-cancelled-mid-launch race.
        scope.launch(start = CoroutineStart.ATOMIC) {
            // A `var` with a fail-closed default, not the previous try-as-expression `val`:
            // Kotlin's try/finally-as-expression can't let a `finally` block observe the value the
            // try/catch produced, and the `finally` below is what has to always resolve the login.
            var denied = true

            try {
                withTimeout(CHECK_TIMEOUT_MS) {
                    preLoginListeners.forEach { it.handle(preLoginHelper, player, username) }
                }
                denied = preLoginHelper.wasDisallowed
            } catch (_: TimeoutCancellationException) {
                // Policy: a platform that doesn't answer within budget is treated as unavailable,
                // same as a disconnected socket — fail open. But an already-recorded disallow
                // (e.g. BanIntegration flagged the player before a later listener or round trip
                // pushed the chain past the deadline) must survive the timeout, not be discarded.
                denied = preLoginHelper.wasDisallowed
            } catch (_: CancellationException) {
                // pluginScope's Job was cancelled out from under this coroutine (SERVER_STOPPING,
                // e.g. a server restart/shutdown mid-check). Per the owner's pre-login policy,
                // "plugin shutting down mid-check" must ALLOW like a disconnected platform, not
                // DENY — but a disallow an earlier listener already recorded still wins.
                // TimeoutCancellationException is caught above first since it's a
                // CancellationException subtype and needed its own comment, but behaves identically
                // here: do nothing, so the player isn't disallowed by the cancellation itself.
                denied = preLoginHelper.wasDisallowed
            } catch (e: Exception) {
                // Any other failure (a throwing listener, etc.) must fail closed, unlike the
                // log-and-continue policy used on the other platforms for this same listener chain.
                org.slf4j.LoggerFactory.getLogger("Pano").error("Error handling player pre-login", e)
                denied = true
            } finally {
                // Always resolves this connection's login — re-drive placeNewPlayer or disconnect
                // it — no matter which branch above ran, including a shutdown-triggered
                // CancellationException. server.execute() only posts a Runnable (not a suspend
                // call), so it runs fine even though this coroutine's Job is already cancelled.
                server.execute {
                    if (!connection.isConnected) {
                        // Player already left while the check was in flight; nothing left to do.
                        return@execute
                    }

                    if (denied) {
                        val reason = preLoginHelper.disallowReason
                            ?: FabricTextHelper.parseColoredText("&cLogin verification failed. Please try again.")
                        connection.send(ClientboundDisconnectPacket(reason))
                        connection.disconnect(reason)
                        return@execute
                    }

                    try {
                        recheckedConnections.add(connection)
                        server.getPlayerList().placeNewPlayer(connection, player, clientData)
                    } catch (e: Exception) {
                        recheckedConnections.remove(connection)
                        org.slf4j.LoggerFactory.getLogger("Pano")
                            .error("Error re-placing player after pre-login check", e)
                        connection.disconnect(FabricTextHelper.parseColoredText("&cAn internal error occurred. Please try again."))
                    }
                }
            }
        }
    }
}
