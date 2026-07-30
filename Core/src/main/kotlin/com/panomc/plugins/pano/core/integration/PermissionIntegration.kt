package com.panomc.plugins.pano.core.integration

import com.panomc.plugins.pano.core.event.Listener
import com.panomc.plugins.pano.core.helper.EventHelper
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.message.response.GetPermissionsMessage
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage
import com.panomc.plugins.pano.core.platform.request.GetPermissionsRequest
import com.panomc.plugins.pano.core.platform.request.SavePermissionsSnapshotRequest
import io.vertx.core.http.WebSocket
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.event.EventSubscription
import net.luckperms.api.event.group.GroupCreateEvent
import net.luckperms.api.event.group.GroupDeleteEvent
import net.luckperms.api.event.node.NodeMutateEvent
import net.luckperms.api.event.track.mutate.TrackMutateEvent
import net.luckperms.api.model.group.Group
import net.luckperms.api.model.user.User
import net.luckperms.api.node.Node
import net.luckperms.api.node.NodeBuilder
import net.luckperms.api.node.NodeEqualityPredicate
import net.luckperms.api.node.ScopedNode
import net.luckperms.api.node.types.*
import net.luckperms.api.track.Track
import java.io.File
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import com.panomc.plugins.pano.core.platform.entity.PermissionNode as PanoPermissionNode


class PermissionIntegration(override val panoPluginMain: PanoPluginMain) : Integration {
    // `by lazy` (Kotlin's default SYNCHRONIZED mode) holds an implicit per-property monitor for the
    // whole initializer below, including the call into panoPluginMain.getPano() -- which, if mPano
    // hasn't resolved yet, blocks synchronously inside the platform main's lifecycleLock section on
    // the blocking Pano.init(). That IS a lock held across a blocking Pano.init() call; it's just
    // not one of this plugin's two *named* locks. lifecycleLock/registrationLock on each platform
    // main are still correctly documented as never held across Pano.init()/Pano.disable() -- that
    // guarantee is real, it simply doesn't cover this monitor, which belongs to the `lazy` delegate
    // itself. No path from Pano.init()'s own call graph back into this property has been found, so
    // this isn't a proven deadlock today, but any future caller that reaches `pano` here from a
    // Vert.x event-loop callback nested inside init()/disable() would create one.
    private val pano by lazy {
        panoPluginMain.getPano()
    }

    private val platformManager by lazy {
        pano.platformManager
    }

    private val logger by lazy {
        panoPluginMain.getPanoLogger()
    }

    @Volatile private var initialized: Boolean = false
    @Volatile private var lpOutboundRegistered: Boolean = false
    private val lpSubscriptions: MutableList<EventSubscription<*>> = CopyOnWriteArrayList()
    private var pushSnapshotJob: Job? = null
    private var lpRetryJob: Job? = null
    // Tracks the coroutine started by start() so stop() can interrupt an in-flight sync
    // instead of leaving it to finish and set initialized back to true after shutdown.
    private var startJob: Job? = null
    // Set by stop(), cleared at the top of start(); checked after suspension points that a
    // cancelled Job's cooperative cancellation can't reach (e.g. right after a blocking
    // LuckPerms .join() call resumes).
    @Volatile private var stopped: Boolean = false
    // A LuckPerms mutation that arrived while a push was suppressed (mid-sync or during the
    // trailing quiet window) is not lost: it re-arms a push once the window closes.
    @Volatile private var pendingPush: Boolean = false
    private val lpRetryScheduled = AtomicBoolean(false)
    @Volatile private var lpRetryDelayMillis: Long = 5_000L
    @Volatile private var suppressPushUntilMillis: Long = 0L
    @Volatile
    private var syncInProgress: Boolean = false
    private val syncMutex = Mutex()
    private val currentPlayerUuidByUsername = ConcurrentHashMap<String, UUID>()
    private val playerJoinListener = object : Listener {
        override suspend fun handle(eventHelper: EventHelper, vararg args: Any) {
            val playerData = eventHelper.convertToPlayerData(args[0])
            rememberJoinedPlayer(playerData)
        }
    }

    /**
     * While LuckPerms is unavailable, [initialized] stays false, so we track these separately
     * to avoid duplicate startup / LP-missing logs when [start] is triggered from both
     * connection and server-settings updates (and from the LP retry timer).
     */
    private var loggedAwaitingLuckPermsStartup: Boolean = false
    private var loggedLuckPermsClassMissing: Boolean = false
    private var loggedLuckPermsProviderNotReady: Boolean = false
    private var loggedLuckPermsOtherMessage: String? = null

    override fun isInitialized() = initialized

    private suspend fun start() {
        stopped = false

        if (!platformManager.serverSettings.permissionIntegration) {
            return
        }

        synchronized(this) {
            if (!initialized && !loggedAwaitingLuckPermsStartup) {
                logger.info("Permission integration is enabled, loading...")
                loggedAwaitingLuckPermsStartup = true
            }
        }

        // Don't set initialized or hook unless LuckPerms is actually available.
        // Sync must never run unless we have successfully hooked into LuckPerms.
        val api = getLuckPermsOrNull() ?: run {
            scheduleLuckPermsRetry()
            return
        }

        hookLuckPerms(api)

        // Always run a sync, even when we were already initialized (e.g. reconnect),
        // because Pano is the source of truth and LuckPerms must be brought back in line.
        try {
            syncLuckPermsFromPlatform(api)
        } catch (e: CancellationException) {
            // stop()/onDisconnect() cancel startJob to interrupt an in-flight sync; that must
            // propagate as a plain cancellation, not be treated as a sync failure worth retrying
            // (which would re-arm the retry loop after teardown already cleared it).
            throw e
        } catch (e: Exception) {
            logger.warning(panoPluginMain.translateColor("&eLuckPerms sync from Pano failed, will retry: ${e.message}"))
            scheduleLuckPermsRetry()
            return
        }

        // stop() may have cancelled/raced this coroutine while the sync above was suspended;
        // don't resurrect a disabled integration as "initialized".
        if (stopped) {
            return
        }

        initialized = true
        lpRetryDelayMillis = 5_000L
    }

    private fun hookLuckPerms(api: net.luckperms.api.LuckPerms) {
        if (!lpOutboundRegistered) {
            logger.info("&eHooking into LuckPerms...".colorize())
        }
        registerLuckPermsOutboundSync(api)
    }

    private suspend fun syncLuckPermsFromPlatform(api: net.luckperms.api.LuckPerms) {
        // LuckPerms .join() calls are blocking. Never run them on the Vert.x event loop or the
        // server can appear to freeze while WebSocket / ticks stall behind a long critical section.
        withContext(Dispatchers.IO) {
        // Fetch outside the lock and bounded by a timeout: an unresponsive platform must not
        // wedge syncMutex (and syncInProgress) forever (platform-core-2/platform-core-6).
        val permissions = withTimeout(15_000L) {
            platformManager.sendMessageAwaitResponse<GetPermissionsMessage>(
                GetPermissionsRequest(),
                GetPermissionsMessage::class.java
            )
        }

        // stop() may have run while we were awaiting the platform; don't apply a sync the
        // integration was told to abandon.
        if (stopped) return@withContext

        syncMutex.withLock {
            syncInProgress = true
            // Avoid push-back storm while we are applying our own sync changes.
            // Use a generous window so that asynchronous LuckPerms save callbacks
            // don't trigger a push-back mid-sync.
            suppressPushUntilMillis = System.currentTimeMillis() + 15_000L

            try {
                logger.info("&eSyncing permissions from Pano...".colorize())

                val groupNameById = permissions.groups.associate { it.id to it.name }
                val backendGroupNames = permissions.groups.map { it.name }.toSet()
                val backendTrackNames = permissions.tracks.map { it.name }.toSet()
                val backendUserNames = permissions.nodes
                    .asSequence()
                    .filter { it.holderType == PanoPermissionNode.Companion.HolderType.USER }
                    .mapNotNull { permissions.usernameMap[it.holderId] }
                    .toSet()

                val state = loadLuckPermsSyncState()

                syncLuckPermsGroups(
                    api = api,
                    permissions = permissions,
                    groupNameById = groupNameById,
                    state = state
                )

                syncLuckPermsTracks(
                    api = api,
                    permissions = permissions,
                    groupNameById = groupNameById,
                    state = state
                )

                // Pano users -> LuckPerms users (Pano is the source of truth for these).
                syncLuckPermsUsers(
                    api = api,
                    permissions = permissions,
                    groupNameById = groupNameById,
                    state = state
                )

                // Delete only what we have managed before
                deleteMissingManagedGroups(api, state, backendGroupNames)
                deleteMissingManagedTracks(api, state, backendTrackNames)
                deleteMissingManagedUsers(api, state, backendUserNames)

                saveLuckPermsSyncState(state)

                logger.info("&2LuckPerms sync complete.".colorize())
            } finally {
                syncInProgress = false
                // Short trailing quiet period so delayed LP events from our writes are also ignored.
                suppressPushUntilMillis = System.currentTimeMillis() + 3_000L

                // A LuckPerms edit that arrived during the sync/suppression window was dropped
                // rather than lost: re-arm a push once the trailing quiet period elapses.
                if (pendingPush) {
                    pendingPush = false
                    CoroutineScope(pano.vertx.dispatcher()).launch {
                        delay(3_000L)
                        scheduleSnapshotPushToPlatform()
                    }
                }
            }
        }

        }
    }

    private fun resetLuckPermsWarningLogDedupe() {
        loggedLuckPermsClassMissing = false
        loggedLuckPermsProviderNotReady = false
        loggedLuckPermsOtherMessage = null
    }

    private fun resetLuckPermsAvailabilityLogDedupe() {
        loggedAwaitingLuckPermsStartup = false
        resetLuckPermsWarningLogDedupe()
    }

    override fun onEnable() {
        panoPluginMain.registerEventListeners(setOf(playerJoinListener))
    }

    override fun onDisable() {
        panoPluginMain.unregisterEventListeners(setOf(playerJoinListener))
        stop()
    }

    private fun stop() {
        val wasInitialized = initialized
        if (wasInitialized) {
            logger.info("&ePermission integration is disabled.".colorize())
        }

        // Tell any in-flight start()/sync coroutine to abandon itself at its next checkpoint,
        // on top of the Job cancellations below (cancellation alone can't interrupt a coroutine
        // that is currently parked in a blocking LuckPerms .join() call).
        stopped = true
        pendingPush = false

        synchronized(this) {
            startJob?.cancel()
            startJob = null
            pushSnapshotJob?.cancel()
            pushSnapshotJob = null
            lpRetryJob?.cancel()
            lpRetryJob = null
            lpRetryScheduled.set(false)
        }
        lpSubscriptions.forEach { sub ->
            try { sub.close() } catch (_: Exception) {}
        }
        lpSubscriptions.clear()
        lpOutboundRegistered = false

        initialized = false
        synchronized(this) {
            resetLuckPermsAvailabilityLogDedupe()
        }
    }

    /**
     * Launch [start] on the tracked coroutine, cancelling any previous in-flight attempt.
     * Tracking the Job lets [stop] interrupt a sync that is still running when the integration
     * gets disabled mid-flight (integrations-20).
     */
    private fun launchStart() {
        synchronized(this) {
            startJob?.cancel()
            startJob = CoroutineScope(pano.vertx.dispatcher()).launch {
                start()
            }
        }
    }

    private fun rememberJoinedPlayer(playerData: EventHelper.Companion.PlayerData) {
        val username = playerData.username.trim()
        if (username.isBlank()) return

        currentPlayerUuidByUsername[usernameKey(username)] = playerData.uuid

        if (!isPermissionIntegrationEnabled()) return

        CoroutineScope(pano.vertx.dispatcher()).launch {
            reconcileLuckPermsPlayerIdentity(username, playerData.uuid)
        }
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        if (platformManager.serverSettings.permissionIntegration) {
            launchStart()
            return
        }

        stop()
    }

    override fun onDisconnect() {
        // Cancel any pending outbound pushes/retries/in-flight sync; they would fail on a dead socket.
        synchronized(this) {
            // Must be set before cancelling startJob: an in-flight start()/scheduleLuckPermsRetry()
            // racing this teardown checks `stopped` at its next checkpoint, and without this it can
            // pass the guard and re-arm lpRetryJob right after we clear lpRetryScheduled below,
            // resurrecting the retry loop against a dead socket. start() resets it on the next
            // reconnect, so this doesn't block recovery.
            stopped = true
            startJob?.cancel()
            startJob = null
            pushSnapshotJob?.cancel()
            pushSnapshotJob = null
            lpRetryJob?.cancel()
            lpRetryJob = null
            lpRetryScheduled.set(false)
        }
        // A wedged sync (platform-core-2) must not leave isInitialized() reporting a live
        // integration after the socket that sync depended on is already gone.
        initialized = false
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        if (serverSettings.permissionIntegration) {
            launchStart()
            return
        }

        stop()
    }

    override fun onPermissionsSnapshotUpdated(message: PermissionsSnapshotUpdatedMessage) {
        if (!platformManager.serverSettings.permissionIntegration) return

        // Don't push back after a remote snapshot update: the update came from Pano
        // (panel or another server) and Pano already has the authoritative state.
        launchStart()
    }

    private fun usernameKey(username: String): String {
        return username.lowercase(Locale.ROOT)
    }

    private fun isPermissionIntegrationEnabled(): Boolean {
        return try {
            platformManager.serverSettings.permissionIntegration
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
    }

    private data class LuckPermsSyncState(
        val managedGroups: MutableSet<String> = mutableSetOf(),
        val managedTracks: MutableSet<String> = mutableSetOf(),
        val managedUsers: MutableMap<String, String> = mutableMapOf()
    )

    private val syncStateFile: File by lazy {
        File(panoPluginMain.getDataFolder(), "luckperms-sync.json")
    }

    private fun loadLuckPermsSyncState(): LuckPermsSyncState {
        return try {
            if (!syncStateFile.exists()) return LuckPermsSyncState()
            val text = syncStateFile.readText(Charsets.UTF_8).trim()
            if (text.isBlank()) return LuckPermsSyncState()
            val obj = JsonObject(text)
            LuckPermsSyncState(
                managedGroups = obj.getJsonArray("managedGroups").toStringSet(),
                managedTracks = obj.getJsonArray("managedTracks").toStringSet(),
                managedUsers = obj.getJsonObject("managedUsers").toStringMap()
            )
        } catch (e: Exception) {
            logger.warning(panoPluginMain.translateColor("&eLuckPerms sync state could not be read: ${e.message}"))
            LuckPermsSyncState()
        }
    }

    private fun JsonArray?.toStringSet(): MutableSet<String> {
        return this?.mapNotNull { it as? String }?.toMutableSet() ?: mutableSetOf()
    }

    private fun JsonObject?.toStringMap(): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        this?.map?.forEach { (key, value) ->
            if (key.isNotBlank() && value != null) {
                result[key] = value.toString()
            }
        }
        return result
    }

    private fun saveLuckPermsSyncState(state: LuckPermsSyncState) {
        try {
            if (!syncStateFile.parentFile.exists()) {
                syncStateFile.parentFile.mkdirs()
            }
            val obj = JsonObject()
                .put("managedGroups", JsonArray(state.managedGroups.toList()))
                .put("managedTracks", JsonArray(state.managedTracks.toList()))
                .put("managedUsers", JsonObject(state.managedUsers.mapValues { it.value }))
            syncStateFile.writeText(obj.encode(), Charsets.UTF_8)
        } catch (e: Exception) {
            logger.warning(panoPluginMain.translateColor("&eLuckPerms sync state could not be saved: ${e.message}"))
        }
    }

    private fun syncLuckPermsGroups(
        api: net.luckperms.api.LuckPerms,
        permissions: GetPermissionsMessage,
        groupNameById: Map<Long, String>,
        state: LuckPermsSyncState
    ) {
        val groupNodes = permissions.nodes.filter { it.holderType == PanoPermissionNode.Companion.HolderType.GROUP }
        val nodesByGroupId = groupNodes.groupBy { it.holderId }

        permissions.groups.forEach { pg ->
            if (!isValidLuckPermsHolderName(pg.name)) {
                logger.warning(
                    panoPluginMain.translateColor(
                        "&eSkipping Pano group '${pg.name}': not a valid LuckPerms group name."
                    )
                )
                return@forEach
            }

            // A single rejected/throwing group must not abort the sync for every other holder.
            try {
                val group = api.groupManager.loadGroup(pg.name).join().orElse(null) ?: api.groupManager.createAndLoadGroup(pg.name).join()

                // Full overwrite for groups that exist in Pano (treat as managed)
                group.data().clear()

                // Marker (used for safe deletion)
                group.data().add(MetaNode.builder("pano-managed", "true").build())

                // Display name
                if (pg.displayName.isNotBlank() && pg.displayName != pg.name) {
                    group.data().add(DisplayNameNode.builder(pg.displayName).build())
                }

                nodesByGroupId[pg.id].orEmpty()
                    .mapNotNull { panoNodeToLuckPermsNode(it, groupNameById) }
                    .forEach { lpNode -> group.data().add(lpNode) }

                api.groupManager.saveGroup(group).join()
                state.managedGroups.add(pg.name)
            } catch (e: Exception) {
                logger.warning(
                    panoPluginMain.translateColor("&eFailed to sync Pano group '${pg.name}' to LuckPerms: ${e.message}")
                )
            }
        }
    }

    private fun syncLuckPermsTracks(
        api: net.luckperms.api.LuckPerms,
        permissions: GetPermissionsMessage,
        groupNameById: Map<Long, String>,
        state: LuckPermsSyncState
    ) {
        permissions.tracks.forEach { pt ->
            if (!isValidLuckPermsHolderName(pt.name)) {
                logger.warning(
                    panoPluginMain.translateColor(
                        "&eSkipping Pano track '${pt.name}': not a valid LuckPerms track name."
                    )
                )
                return@forEach
            }

            // A single rejected/throwing track must not abort the sync for every other holder.
            try {
                val track: Track =
                    api.trackManager.loadTrack(pt.name).join().orElse(null) ?: api.trackManager.createAndLoadTrack(pt.name).join()

                val groupsInOrder = pt.groupIds.mapNotNull { groupNameById[it] }
                track.clearGroups()
                groupsInOrder.forEach { groupName ->
                    val group = api.groupManager.getGroup(groupName)
                        ?: api.groupManager.loadGroup(groupName).join().orElse(null)
                        ?: return@forEach
                    track.appendGroup(group)
                }

                api.trackManager.saveTrack(track).join()
                state.managedTracks.add(pt.name)
            } catch (e: Exception) {
                logger.warning(
                    panoPluginMain.translateColor("&eFailed to sync Pano track '${pt.name}' to LuckPerms: ${e.message}")
                )
            }
        }
    }

    private fun syncLuckPermsUsers(
        api: net.luckperms.api.LuckPerms,
        permissions: GetPermissionsMessage,
        groupNameById: Map<Long, String>,
        state: LuckPermsSyncState
    ) {
        val userNodes = permissions.nodes.filter { it.holderType == PanoPermissionNode.Companion.HolderType.USER }
        val nodesByUserId = userNodes.groupBy { it.holderId }

        permissions.usernameMap.forEach { (userId, username) ->
            val nodes = nodesByUserId[userId].orEmpty()
            if (nodes.isEmpty()) return@forEach

            val uuid = resolveLuckPermsStorageUuid(api, username)
            if (uuid == null) {
                logger.warning(
                    panoPluginMain.translateColor(
                        "&eCould not sync permissions for Pano user with empty username (holder id $userId). " +
                                "Fix the player name on the platform."
                    )
                )
                return@forEach
            }

            // A single rejected/throwing user must not abort the sync for every other holder.
            try {
                // LuckPerms stores users by UUID, but Pano permission sync is username-authoritative.
                // The UUID here is only the LP storage key resolved from that username.
                val user = api.userManager.loadUser(uuid, username).join()

                overwriteUserData(
                    api = api,
                    user = user,
                    panoNodes = nodes,
                    groupNameById = groupNameById
                )

                // Persist the username on LuckPerms' side so it shows up in LP tooling/editor.
                try {
                    api.userManager.savePlayerData(uuid, username).join()
                } catch (_: Exception) {
                    // Not fatal: some storage backends don't implement this.
                }

                state.managedUsers[usernameKey(username)] = uuid.toString()
            } catch (e: Exception) {
                logger.warning(
                    panoPluginMain.translateColor("&eFailed to sync Pano user '$username' to LuckPerms: ${e.message}")
                )
            }
        }
    }

    /**
     * Whether [name] is safe to hand to LuckPerms as a group/track name. LuckPerms rejects blank,
     * over-length, or whitespace-containing names synchronously; validating first means one badly
     * named Pano holder is skipped-and-logged instead of aborting the rest of the sync
     * (integrations-10).
     */
    private fun isValidLuckPermsHolderName(name: String): Boolean {
        return name.isNotBlank() && name.length <= 36 && name.none { it.isWhitespace() }
    }

    /**
     * Resolve the LuckPerms storage UUID for a username.
     *
     * Permission ownership is username-based: Pano's stored Minecraft UUID is deliberately not
     * used here. LuckPerms still requires a UUID key for user storage, so we first ask LP for the
     * username it already knows, then fall back to the server's own never-joined/offline mapping.
     */
    private fun resolveLuckPermsStorageUuid(
        api: net.luckperms.api.LuckPerms,
        username: String
    ): UUID? {
        if (username.isBlank()) return null
        currentPlayerUuidByUsername[usernameKey(username)]?.let { return it }

        return try {
            val fromLp = api.userManager.lookupUniqueId(username).join()
            fromLp ?: panoPluginMain.getNeverJoinedPlayerUniqueId(username)
        } catch (_: Exception) {
            panoPluginMain.getNeverJoinedPlayerUniqueId(username)
        }
    }

    private suspend fun reconcileLuckPermsPlayerIdentity(username: String, currentUuid: UUID) {
        withContext(Dispatchers.IO) {
            val api = getLuckPermsOrNull() ?: return@withContext

            val previousUuid = try {
                api.userManager.lookupUniqueId(username).join()
            } catch (_: Exception) {
                null
            }

            try {
                api.userManager.savePlayerData(currentUuid, username).join()
            } catch (_: Exception) {
                // Not fatal: sync can still write the user entry through loadUser/saveUser.
            }

            if (previousUuid == currentUuid) return@withContext

            if (previousUuid != null) {
                logger.info(
                    panoPluginMain.translateColor(
                        "&7Permission sync: detected UUID change for &f'$username'&7 " +
                                "($previousUuid -> $currentUuid), rewriting LuckPerms user data from Pano."
                    )
                )
            }

            if (initialized && isPermissionIntegrationEnabled()) {
                start()
            }

            if (previousUuid != null && previousUuid != currentUuid) {
                deleteManagedLuckPermsUser(api, previousUuid, username)
            }
        }
    }

    private fun deleteManagedLuckPermsUser(api: net.luckperms.api.LuckPerms, uuid: UUID, username: String) {
        try {
            val staleUser = api.userManager.loadUser(uuid).join() ?: return
            if (!userIsMarkedManaged(staleUser)) return

            staleUser.data().clear()
            api.userManager.saveUser(staleUser).join()
            logger.info(
                panoPluginMain.translateColor(
                    "&7Permission sync: cleared stale Pano-managed LuckPerms user for &f'$username'&7 ($uuid)."
                )
            )
        } catch (e: Exception) {
            logger.warning(
                panoPluginMain.translateColor(
                    "&eCould not remove stale LuckPerms user for '$username' ($uuid): ${e.message}"
                )
            )
        }
    }

    private fun overwriteUserData(
        api: net.luckperms.api.LuckPerms,
        user: User,
        panoNodes: List<PanoPermissionNode>,
        groupNameById: Map<Long, String>
    ) {
        // Full overwrite for users that have Pano nodes (treat as managed)
        user.data().clear()
        user.data().add(MetaNode.builder("pano-managed", "true").build())

        panoNodes
            .mapNotNull { panoNodeToLuckPermsNode(it, groupNameById) }
            .forEach { lpNode -> user.data().add(lpNode) }

        api.userManager.saveUser(user).join()
    }

    private fun userIsMarkedManaged(user: User): Boolean {
        return user.distinctNodes.any { isPanoManagedMarker(it) }
    }

    private fun deleteMissingManagedUsers(
        api: net.luckperms.api.LuckPerms,
        state: LuckPermsSyncState,
        backendUserNames: Set<String>
    ) {
        val backendKeys = backendUserNames.map { usernameKey(it) }.toSet()
        val iterator = state.managedUsers.iterator()
        while (iterator.hasNext()) {
            val (username, uuidText) = iterator.next()
            if (backendKeys.contains(username)) continue

            val uuid = try {
                UUID.fromString(uuidText)
            } catch (_: Exception) {
                iterator.remove()
                continue
            }

            deleteManagedLuckPermsUser(api, uuid, username)
            iterator.remove()
        }
    }

    private fun deleteMissingManagedGroups(
        api: net.luckperms.api.LuckPerms,
        state: LuckPermsSyncState,
        backendGroupNames: Set<String>
    ) {
        val iterator = state.managedGroups.iterator()
        while (iterator.hasNext()) {
            val groupName = iterator.next()
            if (backendGroupNames.contains(groupName)) continue

            val group = api.groupManager.loadGroup(groupName).join().orElse(null)
            if (group != null && groupIsMarkedManaged(group)) {
                api.groupManager.deleteGroup(group).join()
            }
            iterator.remove()
        }
    }

    private fun groupIsMarkedManaged(group: Group): Boolean {
        val marker = MetaNode.builder("pano-managed", "true").build()
        return group.data().contains(marker, NodeEqualityPredicate.IGNORE_EXPIRY_TIME_AND_VALUE) == net.luckperms.api.util.Tristate.TRUE
    }

    private fun deleteMissingManagedTracks(
        api: net.luckperms.api.LuckPerms,
        state: LuckPermsSyncState,
        backendTrackNames: Set<String>
    ) {
        val iterator = state.managedTracks.iterator()
        while (iterator.hasNext()) {
            val trackName = iterator.next()
            if (backendTrackNames.contains(trackName)) continue

            val track = api.trackManager.loadTrack(trackName).join().orElse(null)
            if (track != null) {
                api.trackManager.deleteTrack(track).join()
            }
            iterator.remove()
        }
    }

    private fun panoNodeToLuckPermsNode(
        panoNode: PanoPermissionNode,
        groupNameById: Map<Long, String>
    ): Node? {
        val nodeKey = panoNode.node.trim()
        if (nodeKey.isBlank()) return null

        return when {
            nodeKey.startsWith("group.") -> {
                val groupName = nodeKey.removePrefix("group.")
                if (groupName.isBlank()) return null
                buildNode(InheritanceNode.builder(groupName), panoNode)
            }

            nodeKey.startsWith("weight.") -> {
                val weight = nodeKey.removePrefix("weight.").toIntOrNull() ?: return null
                buildNode(WeightNode.builder(weight), panoNode)
            }

            nodeKey.startsWith("prefix.") -> {
                val (priority, value) = parsePriorityValue(nodeKey.removePrefix("prefix."))
                buildNode(PrefixNode.builder(value, priority), panoNode)
            }

            nodeKey.startsWith("suffix.") -> {
                val (priority, value) = parsePriorityValue(nodeKey.removePrefix("suffix."))
                buildNode(SuffixNode.builder(value, priority), panoNode)
            }

            nodeKey.startsWith("meta.") -> {
                val raw = nodeKey.removePrefix("meta.")
                val idx = raw.indexOf('.')
                if (idx <= 0 || idx >= raw.length - 1) {
                    buildNode(PermissionNode.builder(nodeKey), panoNode)
                } else {
                    val k = raw.substring(0, idx)
                    val v = raw.substring(idx + 1)
                    buildNode(MetaNode.builder(k, v), panoNode)
                }
            }

            nodeKey.startsWith("displayname.") -> {
                val value = nodeKey.removePrefix("displayname.")
                buildNode(DisplayNameNode.builder(value), panoNode)
            }

            else -> buildNode(PermissionNode.builder(nodeKey), panoNode)
        }
    }

    private fun parsePriorityValue(raw: String): Pair<Int, String> {
        val idx = raw.indexOf('.')
        if (idx <= 0 || idx >= raw.length - 1) return 0 to raw
        val maybePriority = raw.substring(0, idx).toIntOrNull()
        if (maybePriority == null) return 0 to raw
        return maybePriority to raw.substring(idx + 1)
    }

    private fun <N : ScopedNode<N, B>, B : NodeBuilder<N, B>> buildNode(builder: B, panoNode: PanoPermissionNode): N? {
        val expiresAt = panoNode.expiresAt

        // An already-expired node must never be written back as permanent: skip it entirely
        // rather than silently dropping just the expiry (integrations-12).
        if (expiresAt != null && expiresAt <= System.currentTimeMillis()) {
            return null
        }

        builder.value(panoNode.active)
        addContexts(builder, panoNode.context)

        if (expiresAt != null) {
            builder.expiry(Instant.ofEpochMilli(expiresAt))
        }

        return builder.build()
    }

    private fun <N : ScopedNode<N, B>, B : NodeBuilder<N, B>> addContexts(builder: B, ctx: JsonObject) {
        try {
            ctx.map.forEach { (k, v) ->
                if (k.isNullOrBlank() || v == null) return@forEach
                builder.withContext(k, v.toString())
            }
        } catch (_: Exception) {
            // ignore invalid context
        }
    }

    private fun registerLuckPermsOutboundSync(api: net.luckperms.api.LuckPerms) {
        if (lpOutboundRegistered) return
        lpOutboundRegistered = true

        val bus = api.eventBus

        // NOTE: We debounce snapshot pushes to avoid flooding the platform when many nodes change at once.
        lpSubscriptions += bus.subscribe(NodeMutateEvent::class.java) { _ ->
            scheduleSnapshotPushToPlatform()
        }
        lpSubscriptions += bus.subscribe(TrackMutateEvent::class.java) { _ ->
            scheduleSnapshotPushToPlatform()
        }
        lpSubscriptions += bus.subscribe(GroupCreateEvent::class.java) { _ ->
            scheduleSnapshotPushToPlatform()
        }
        lpSubscriptions += bus.subscribe(GroupDeleteEvent::class.java) { _ ->
            scheduleSnapshotPushToPlatform()
        }
        // Deliberately not subscribed: UserLoadEvent. Every loaded LuckPerms user carries at
        // least an implicit `group.default` node (and every Pano-managed user carries its full
        // Pano node set), so the "does this user have any non-marker node" guard was true for
        // essentially every join, turning every join into a full snapshot truncate-and-replace
        // (integrations-8). NodeMutateEvent already fires for real permission changes; the
        // offline-UUID -> real-UUID migration on join is handled by
        // reconcileLuckPermsPlayerIdentity via the join listener instead (integrations-15).
    }

    private fun scheduleSnapshotPushToPlatform() {
        // Only push when integration is enabled and connection is alive
        if (!initialized || !platformManager.serverSettings.permissionIntegration) return
        if (syncInProgress) {
            pendingPush = true
            return
        }

        val now = System.currentTimeMillis()
        if (now < suppressPushUntilMillis) {
            pendingPush = true
            return
        }

        synchronized(this) {
            pushSnapshotJob?.cancel()
            pushSnapshotJob = CoroutineScope(pano.vertx.dispatcher()).launch {
                delay(1500L)
                if (!platformManager.serverSettings.permissionIntegration) return@launch
                if (syncInProgress) {
                    pendingPush = true
                    return@launch
                }
                if (System.currentTimeMillis() < suppressPushUntilMillis) {
                    pendingPush = true
                    return@launch
                }
                try {
                    pushLuckPermsSnapshotToPlatform()
                } catch (e: Exception) {
                    logger.warning(panoPluginMain.translateColor("&eFailed to push LuckPerms snapshot to platform: ${e.message}"))
                }
            }
        }
    }

    /**
     * Push a *full* snapshot to the platform DB.
     *
     * Behaviour:
     * - Groups, tracks, and group nodes are taken from LuckPerms after LP runtime changes.
     * - User nodes are taken from LuckPerms for currently loaded users and mapped by username.
     * - Pano-only user nodes are preserved from the backend snapshot so they are not lost to the
     *   platform's truncate-and-replace — UNLESS this push carries LuckPerms' node set for the
     *   exact same identity (the storage UUID the Pano -> LuckPerms sync wrote to, from
     *   [usernameKey]) that owns the backend row, in which case LuckPerms' current data is
     *   trusted instead. Comparing by identity (not just username) matters because a player can
     *   be synced onto an offline/never-joined UUID and then join under a different real UUID:
     *   without this check, the backend row would be dropped as "replaced" even though no LP node
     *   under the new UUID actually replaces it (integrations-7/integrations-18).
     *
     * Runs inside [syncMutex] (re-checking [syncInProgress] / [suppressPushUntilMillis] after
     * every suspension point) so a concurrent Pano -> LuckPerms sync can never be caught
     * mid-rewrite and have that half-applied state shipped back to the platform as authoritative
     * (integrations-11). The initial platform round trip is timeout-bounded so an unresponsive
     * platform can't wedge the mutex forever (platform-core-6).
     */
    private suspend fun pushLuckPermsSnapshotToPlatform() {
        withContext(Dispatchers.IO) {
            val api = getLuckPermsOrNull() ?: return@withContext

            syncMutex.withLock {
                if (syncInProgress || System.currentTimeMillis() < suppressPushUntilMillis) {
                    pendingPush = true
                    return@withLock
                }

                val backendSnapshot = withTimeout(15_000L) {
                    platformManager.sendMessageAwaitResponse<GetPermissionsMessage>(
                        GetPermissionsRequest(),
                        GetPermissionsMessage::class.java
                    )
                }

                if (syncInProgress || System.currentTimeMillis() < suppressPushUntilMillis) {
                    pendingPush = true
                    return@withLock
                }

                api.groupManager.loadAllGroups().join()
                api.trackManager.loadAllTracks().join()

                if (syncInProgress || System.currentTimeMillis() < suppressPushUntilMillis) {
                    pendingPush = true
                    return@withLock
                }

                val now = System.currentTimeMillis()

                val groupsJson = api.groupManager.loadedGroups
                    .sortedBy { (it.name ?: "").lowercase() }
                    .mapNotNull { grp ->
                        val name = grp.name ?: return@mapNotNull null
                        val displayName = (grp.displayName ?: "").ifBlank { name }
                        JsonObject()
                            .put("id", -1)
                            .put("name", name)
                            .put("displayName", displayName)
                            .put("createdAt", now)
                            .put("updatedAt", now)
                    }

                val tracksJson = api.trackManager.loadedTracks
                    .sortedBy { (it.name ?: "").lowercase() }
                    .mapNotNull { trk ->
                        val name = trk.name ?: return@mapNotNull null
                        JsonObject()
                            .put("id", -1)
                            .put("name", name)
                            .put("description", "")
                            .put("groupNames", trk.groups)
                            .put("createdAt", now)
                            .put("updatedAt", now)
                    }

                // GROUP nodes from LuckPerms
                val groupNodesJson = api.groupManager.loadedGroups
                    .flatMap { grp ->
                        grp.distinctNodes
                            .filter { !isPanoManagedMarker(it) }
                            .map { node ->
                                val ctx = JsonObject().also { obj ->
                                    node.contexts.toFlattenedMap().forEach { (k, v) -> obj.put(k, v) }
                                }

                                JsonObject()
                                    .put("id", -1)
                                    .put("holderType", "GROUP")
                                    .put("holderId", -1)
                                    .put("holderName", grp.name ?: "")
                                    .put("node", node.key)
                                    .put("active", node.value)
                                    .put("context", ctx.map)
                                    .put("expiresAt", if (node.hasExpiry()) node.expiry?.toEpochMilli() else null)
                                    .put("createdAt", now)
                                    .put("updatedAt", now)
                            }
                    }

                // Runtime LP changes are captured from users LuckPerms has loaded for the event/command.
                val lpUsers = collectLuckPermsUsersForSnapshot(api)

                // The identity (storage UUID) the Pano -> LuckPerms sync last wrote to for each
                // username, and the identity actually carried by this push, both keyed by
                // usernameKey() so casing differences can't split one player across two rows
                // (integrations-18).
                val syncState = loadLuckPermsSyncState()
                val pushedUuidByUsernameKey = lpUsers
                    .mapNotNull { user -> user.username?.let { usernameKey(it) to user.uniqueId } }
                    .toMap()

                // Preserve Pano-only user nodes so the truncate-and-replace on the backend doesn't
                // wipe them (e.g. admin-created users who don't yet have an LP entry on this
                // server, or an LP user loaded under a different identity than the one Pano's
                // sync wrote to). Only drop a backend row when we can prove this push actually
                // carries LuckPerms' current node set for that same identity.
                val preservedUserRows = backendSnapshot.nodes
                    .filter { it.holderType == PanoPermissionNode.Companion.HolderType.USER }
                    .filter {
                        // usernameMap is a 60s-TTL cache of backend user ids, so a holderId with no
                        // entry yet (e.g. a just-created user) can't be proven safe *or* unsafe to
                        // drop here. Drop it from this push rather than preserving+serializing it
                        // with a null holderName: a null holderName risks the backend rejecting the
                        // whole SavePermissionsSnapshot (losing every LP change in this push), whereas
                        // simply omitting the row only risks the backend truncate-and-replacing away
                        // this one row -- and it self-heals on the next push once the cache catches up.
                        val name = backendSnapshot.usernameMap[it.holderId] ?: return@filter false
                        val key = usernameKey(name)
                        val pushedUuid = pushedUuidByUsernameKey[key]
                        val managedUuidText = syncState.managedUsers[key]
                        pushedUuid == null || managedUuidText == null || managedUuidText != pushedUuid.toString()
                    }

                // Usernames whose backend row is being preserved above. This push must not also
                // carry that same username's LuckPerms node set below -- that would duplicate the
                // holder in this snapshot and permanently block an LP-side deletion from ever
                // reaching Pano, since the preserved backend row would keep re-justifying itself
                // next sync too (integrations-25).
                val preservedUsernameKeys = preservedUserRows
                    .mapNotNullTo(mutableSetOf()) { n -> backendSnapshot.usernameMap[n.holderId]?.let { usernameKey(it) } }

                val lpUserNodesJson = lpUsers.flatMap { user ->
                    val username = user.username ?: return@flatMap emptyList<JsonObject>()
                    if (usernameKey(username) in preservedUsernameKeys) return@flatMap emptyList<JsonObject>()
                    user.distinctNodes
                        .filter { !isPanoManagedMarker(it) }
                        .map { node ->
                            val ctx = JsonObject().also { obj ->
                                node.contexts.toFlattenedMap().forEach { (k, v) -> obj.put(k, v) }
                            }
                            JsonObject()
                                .put("id", -1)
                                .put("holderType", "USER")
                                .put("holderId", -1)
                                .put("holderName", username)
                                .put("node", node.key)
                                .put("active", node.value)
                                .put("context", ctx.map)
                                .put("expiresAt", if (node.hasExpiry()) node.expiry?.toEpochMilli() else null)
                                .put("createdAt", now)
                                .put("updatedAt", now)
                        }
                }

                val preservedUserNodesJson = preservedUserRows
                    .map { n ->
                        JsonObject()
                            .put("id", n.id)
                            .put("holderType", "USER")
                            .put("holderId", n.holderId)
                            .put("holderName", backendSnapshot.usernameMap[n.holderId])
                            .put("node", n.node)
                            .put("active", n.active)
                            .put("context", n.context)
                            .put("expiresAt", n.expiresAt)
                            .put("createdAt", n.createdAt)
                            .put("updatedAt", n.updatedAt)
                    }

                val snapshotMap: Map<String, Any?> = mapOf(
                    "groups" to groupsJson.map { it.map },
                    "tracks" to tracksJson.map { it.map },
                    "nodes" to (groupNodesJson + lpUserNodesJson + preservedUserNodesJson).map { it.map }
                )

                platformManager.sendMessage(SavePermissionsSnapshotRequest(snapshotMap))
            }
        }
    }

    /**
     * Return LuckPerms users to include in a full-snapshot push.
     *
     * Startup and reconnect syncs are Pano -> LuckPerms only. LuckPerms -> Pano runs in response
     * to LP runtime events, where the changed user is expected to be loaded by LuckPerms already.
     */
    private suspend fun collectLuckPermsUsersForSnapshot(api: net.luckperms.api.LuckPerms): List<User> {
        return api.userManager.loadedUsers.toList()
    }

    private fun isPanoManagedMarker(node: Node): Boolean {
        return node is MetaNode && node.metaKey == "pano-managed"
    }

    private fun getLuckPermsOrNull(): net.luckperms.api.LuckPerms? {
        return try {
            // Check if LuckPermsProvider class is available at runtime
            Class.forName("net.luckperms.api.LuckPermsProvider")
            val api = LuckPermsProvider.get()
            synchronized(this) {
                resetLuckPermsWarningLogDedupe()
            }
            api
        } catch (_: ClassNotFoundException) {
            synchronized(this) {
                if (!loggedLuckPermsClassMissing) {
                    loggedLuckPermsClassMissing = true
                    logger.warning(panoPluginMain.translateColor("&eLuckPerms is not installed. Permission integration will not work."))
                }
            }
            null
        } catch (_: IllegalStateException) {
            synchronized(this) {
                if (!loggedLuckPermsProviderNotReady) {
                    loggedLuckPermsProviderNotReady = true
                    logger.warning(panoPluginMain.translateColor("&eLuckPerms API isn't loaded yet. Is LuckPerms installed and enabled?"))
                }
            }
            null
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            synchronized(this) {
                if (loggedLuckPermsOtherMessage != msg) {
                    loggedLuckPermsOtherMessage = msg
                    logger.warning(panoPluginMain.translateColor("&eFailed to access LuckPerms API: $msg"))
                }
            }
            null
        }
    }

    private fun scheduleLuckPermsRetry() {
        // stop()/onDisconnect() flip this before cancelling startJob; without this check a retry
        // requested by an in-flight start() that raced teardown can still arm itself right after
        // stop() cleared lpRetryScheduled, resurrecting the loop post-teardown (integrations-24).
        if (stopped) return
        if (!platformManager.serverSettings.permissionIntegration) return
        // A Job is isActive for its whole body, so checking lpRetryJob?.isActive from inside that
        // same body (when start() fails again) always sees itself as still running and never
        // re-arms. Use a dedicated flag, cleared before start() runs (integrations-13).
        if (!lpRetryScheduled.compareAndSet(false, true)) return

        val delayMillis = lpRetryDelayMillis
        synchronized(this) {
            lpRetryJob = CoroutineScope(pano.vertx.dispatcher()).launch {
                delay(delayMillis)
                lpRetryScheduled.set(false)
                // Bounded exponential backoff; start() resets this back to 5s on success.
                lpRetryDelayMillis = (lpRetryDelayMillis * 2).coerceAtMost(60_000L)

                if (stopped) return@launch
                if (!platformManager.serverSettings.permissionIntegration) return@launch
                try {
                    start()
                } catch (e: Exception) {
                    logger.warning(panoPluginMain.translateColor("&eLuckPerms retry failed: ${e.message}"))
                }
            }
        }
    }
}