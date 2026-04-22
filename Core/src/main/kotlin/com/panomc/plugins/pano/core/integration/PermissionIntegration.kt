package com.panomc.plugins.pano.core.integration

import com.panomc.plugins.pano.core.Pano
import com.panomc.plugins.pano.core.helper.Integration
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.message.response.GetPermissionsMessage
import com.panomc.plugins.pano.core.platform.message.response.GetServerSettingsMessage
import com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage
import com.panomc.plugins.pano.core.platform.request.GetPermissionsRequest
import com.panomc.plugins.pano.core.platform.request.SavePermissionsSnapshotRequest
import io.vertx.core.http.WebSocket
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.event.EventSubscription
import net.luckperms.api.event.group.GroupCreateEvent
import net.luckperms.api.event.group.GroupDeleteEvent
import net.luckperms.api.event.node.NodeMutateEvent
import net.luckperms.api.event.track.mutate.TrackMutateEvent
import net.luckperms.api.event.user.UserLoadEvent
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
import com.panomc.plugins.pano.core.platform.entity.PermissionNode as PanoPermissionNode


class PermissionIntegration(override val panoPluginMain: PanoPluginMain) : Integration {
    private val pano by lazy {
        panoPluginMain.getPano()
    }

    private val platformManager by lazy {
        pano.platformManager
    }

    private val logger by lazy {
        panoPluginMain.getPanoLogger()
    }

    private var initialized: Boolean = false
    private var lpOutboundRegistered: Boolean = false
    private val lpSubscriptions: MutableList<EventSubscription<*>> = mutableListOf()
    private var pushSnapshotJob: Job? = null
    private var lpRetryJob: Job? = null
    private var postSyncPushJob: Job? = null
    @Volatile private var suppressPushUntilMillis: Long = 0L
    @Volatile
    private var syncInProgress: Boolean = false
    private var lpUsersBootstrapped: Boolean = false
    private val syncMutex = Mutex()

    override fun isInitialized() = initialized

    private suspend fun start(pushAfterSync: Boolean) {
        if (!platformManager.serverSettings.permissionIntegration) {
            return
        }

        if (!initialized) {
            logger.info("&ePermission integration is enabled, loading...".colorize())
        }

        // Don't set initialized or hook unless LuckPerms is actually available.
        // Sync must never run unless we have successfully hooked into LuckPerms.
        val api = getLuckPermsOrNull() ?: run {
            scheduleLuckPermsRetry(pushAfterSync)
            return
        }

        hookLuckPerms(api)

        // Always run a sync, even when we were already initialized (e.g. reconnect),
        // because Pano is the source of truth and LuckPerms must be brought back in line.
        syncLuckPermsFromPlatform(api, pushAfterSync)

        initialized = true
    }

    private fun hookLuckPerms(api: net.luckperms.api.LuckPerms) {
        if (!lpOutboundRegistered) {
            logger.info("&eHooking into LuckPerms...".colorize())
        }
        registerLuckPermsOutboundSync(api)
    }

    private suspend fun syncLuckPermsFromPlatform(
        api: net.luckperms.api.LuckPerms,
        pushAfterSync: Boolean
    ) {
        syncMutex.withLock {
            syncInProgress = true
            // Avoid push-back storm while we are applying our own sync changes.
            // Use a generous window so that asynchronous LuckPerms save callbacks
            // don't trigger a push-back mid-sync.
            suppressPushUntilMillis = System.currentTimeMillis() + 15_000L

            try {
                val permissions = platformManager.sendMessageAwaitResponse<GetPermissionsMessage>(
                    GetPermissionsRequest(),
                    GetPermissionsMessage::class.java
                )

                logger.info("&eSyncing permissions from Pano...".colorize())

                val groupNameById = permissions.groups.associate { it.id to it.name }
                val backendGroupNames = permissions.groups.map { it.name }.toSet()
                val backendTrackNames = permissions.tracks.map { it.name }.toSet()

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
                    groupNameById = groupNameById
                )

                // Delete only what we have managed before
                deleteMissingManagedGroups(api, state, backendGroupNames)
                deleteMissingManagedTracks(api, state, backendTrackNames)

                saveLuckPermsSyncState(state)

                logger.info("&2LuckPerms sync complete.".colorize())
            } finally {
                syncInProgress = false
                // Short trailing quiet period so delayed LP events from our writes are also ignored.
                suppressPushUntilMillis = System.currentTimeMillis() + 3_000L
            }
        }

        // After applying Pano -> LuckPerms, push LuckPerms -> Pano so that any LP-only
        // groups/users/permissions get registered on the Pano side as well.
        if (pushAfterSync) {
            schedulePostSyncPushback()
        }
    }

    private fun stop() {
        if (!initialized) {
            return
        }

        logger.info("&ePermission integration is disabled.".colorize())

        pushSnapshotJob?.cancel()
        pushSnapshotJob = null
        postSyncPushJob?.cancel()
        postSyncPushJob = null
        lpRetryJob?.cancel()
        lpRetryJob = null
        lpSubscriptions.forEach { sub ->
            try { sub.close() } catch (_: Exception) {}
        }
        lpSubscriptions.clear()
        lpOutboundRegistered = false
        lpUsersBootstrapped = false

        initialized = false
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        if (platformManager.serverSettings.permissionIntegration) {
            CoroutineScope(pano.vertx.dispatcher()).launch {
                start(pushAfterSync = true)
            }
            return
        }

        stop()
    }

    override fun onDisconnect() {
        // Cancel any pending outbound pushes; they would fail on a dead socket.
        pushSnapshotJob?.cancel()
        pushSnapshotJob = null
        postSyncPushJob?.cancel()
        postSyncPushJob = null
        lpRetryJob?.cancel()
        lpRetryJob = null
        // Force full LP user enumeration on next reconnect so any offline LP users
        // pick up again on the push side.
        lpUsersBootstrapped = false
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        if (serverSettings.permissionIntegration) {
            CoroutineScope(pano.vertx.dispatcher()).launch {
                start(pushAfterSync = true)
            }
            return
        }

        stop()
    }

    override fun onPermissionsSnapshotUpdated(message: PermissionsSnapshotUpdatedMessage) {
        if (!platformManager.serverSettings.permissionIntegration) return

        // Don't push back after a remote snapshot update: the update came from Pano
        // (panel or another server) and Pano already has the authoritative state.
        CoroutineScope(pano.vertx.dispatcher()).launch {
            start(pushAfterSync = false)
        }
    }

    private data class LuckPermsSyncState(
        val managedGroups: MutableSet<String> = mutableSetOf(),
        val managedTracks: MutableSet<String> = mutableSetOf()
    )

    private val syncStateFile: File by lazy {
        File(panoPluginMain.getDataFolder(), "luckperms-sync.json")
    }

    private fun loadLuckPermsSyncState(): LuckPermsSyncState {
        return try {
            if (!syncStateFile.exists()) return LuckPermsSyncState()
            val text = syncStateFile.readText(Charsets.UTF_8).trim()
            if (text.isBlank()) return LuckPermsSyncState()
            Pano.gson.fromJson(text, LuckPermsSyncState::class.java) ?: LuckPermsSyncState()
        } catch (e: Exception) {
            logger.warning(panoPluginMain.translateColor("&eLuckPerms sync state could not be read: ${e.message}"))
            LuckPermsSyncState()
        }
    }

    private fun saveLuckPermsSyncState(state: LuckPermsSyncState) {
        try {
            if (!syncStateFile.parentFile.exists()) {
                syncStateFile.parentFile.mkdirs()
            }
            syncStateFile.writeText(Pano.gson.toJson(state), Charsets.UTF_8)
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
        }
    }

    private fun syncLuckPermsTracks(
        api: net.luckperms.api.LuckPerms,
        permissions: GetPermissionsMessage,
        groupNameById: Map<Long, String>,
        state: LuckPermsSyncState
    ) {
        permissions.tracks.forEach { pt ->
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
        }
    }

    private fun syncLuckPermsUsers(
        api: net.luckperms.api.LuckPerms,
        permissions: GetPermissionsMessage,
        groupNameById: Map<Long, String>
    ) {
        val userNodes = permissions.nodes.filter { it.holderType == PanoPermissionNode.Companion.HolderType.USER }
        val nodesByUserId = userNodes.groupBy { it.holderId }

        permissions.usernameMap.forEach { (userId, username) ->
            val nodes = nodesByUserId[userId].orEmpty()
            if (nodes.isEmpty()) return@forEach

            val uuid = resolveLuckPermsUuid(api, username)
            if (uuid == null) {
                logger.warning(
                    panoPluginMain.translateColor(
                        "&eCould not resolve Minecraft UUID for Pano user '$username'. " +
                                "Permissions will be synced next time this user is seen by LuckPerms."
                    )
                )
                return@forEach
            }

            // loadUser creates a storage entry if one doesn't already exist, which is exactly
            // what we want when Pano has a user that LuckPerms has never seen.
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
        }
    }

    /**
     * Resolve a Minecraft UUID from a username using LuckPerms.
     *
     * Tries LuckPerms' cached username table first, then falls back to the Mojang profile lookup
     * that LuckPerms configures when running in online mode.
     */
    private fun resolveLuckPermsUuid(
        api: net.luckperms.api.LuckPerms,
        username: String
    ): UUID? {
        return try {
            api.userManager.lookupUniqueId(username).join()
        } catch (_: Exception) {
            null
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

    private fun <N : ScopedNode<N, B>, B : NodeBuilder<N, B>> buildNode(builder: B, panoNode: PanoPermissionNode): N {
        builder.value(panoNode.active)
        addContexts(builder, panoNode.context)

        panoNode.expiresAt?.let { expiresAt ->
            val now = System.currentTimeMillis()
            if (expiresAt > now) {
                builder.expiry(Instant.ofEpochMilli(expiresAt))
            }
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
        // When a user is loaded into memory (e.g. a player joins) any pre-existing permissions
        // they have on the LuckPerms side should also reach Pano.
        lpSubscriptions += bus.subscribe(UserLoadEvent::class.java) { event ->
            if (event.user.distinctNodes.any { !isPanoManagedMarker(it) }) {
                scheduleSnapshotPushToPlatform()
            }
        }
    }

    private fun scheduleSnapshotPushToPlatform() {
        // Only push when integration is enabled and connection is alive
        if (!initialized || !platformManager.serverSettings.permissionIntegration) return
        if (syncInProgress) return

        val now = System.currentTimeMillis()
        if (now < suppressPushUntilMillis) return

        pushSnapshotJob?.cancel()
        pushSnapshotJob = CoroutineScope(pano.vertx.dispatcher()).launch {
            delay(1500L)
            if (syncInProgress) return@launch
            if (System.currentTimeMillis() < suppressPushUntilMillis) return@launch
            try {
                pushLuckPermsSnapshotToPlatform()
            } catch (e: Exception) {
                logger.warning(panoPluginMain.translateColor("&eFailed to push LuckPerms snapshot to platform: ${e.message}"))
            }
        }
    }

    private fun schedulePostSyncPushback() {
        postSyncPushJob?.cancel()
        postSyncPushJob = CoroutineScope(pano.vertx.dispatcher()).launch {
            // Wait past the trailing suppression window + give LuckPerms a moment to persist saves.
            delay(4_000L)
            if (syncInProgress) return@launch
            try {
                pushLuckPermsSnapshotToPlatform()
                logger.info("&2LuckPerms state synchronized back to Pano.".colorize())
            } catch (e: Exception) {
                logger.warning(panoPluginMain.translateColor("&eFailed to push LuckPerms state to Pano: ${e.message}"))
            }
        }
    }

    /**
     * Push a *full* snapshot to the platform DB.
     *
     * Behaviour:
     * - Groups, tracks, and group nodes are taken from LuckPerms (authoritative for groups).
     * - User nodes are taken from LuckPerms for every user LuckPerms knows about, so that any
     *   user added directly in LuckPerms (editor/command) is mirrored to Pano.
     * - User nodes whose username is *not* present in LuckPerms (typically Pano-only users whose
     *   Minecraft UUID could not be resolved yet) are preserved from the backend snapshot so
     *   they are not lost to the platform's truncate-and-replace.
     */
    private suspend fun pushLuckPermsSnapshotToPlatform() {
        val api = getLuckPermsOrNull() ?: return

        val backendSnapshot = platformManager.sendMessageAwaitResponse<GetPermissionsMessage>(
            GetPermissionsRequest(),
            GetPermissionsMessage::class.java
        )

        api.groupManager.loadAllGroups().join()
        api.trackManager.loadAllTracks().join()

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

        // All LP users we know about on this server (online + offline when bootstrapping).
        val lpUsers = collectLuckPermsUsersForSnapshot(api)
        val usernamesInLp = lpUsers.mapNotNull { it.username }.toSet()

        val lpUserNodesJson = lpUsers.flatMap { user ->
            val username = user.username ?: return@flatMap emptyList<JsonObject>()
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
                        .put("holderUniqueId", user.uniqueId.toString())
                        .put("node", node.key)
                        .put("active", node.value)
                        .put("context", ctx.map)
                        .put("expiresAt", if (node.hasExpiry()) node.expiry?.toEpochMilli() else null)
                        .put("createdAt", now)
                        .put("updatedAt", now)
                }
        }

        // Preserve Pano-only user nodes so the truncate-and-replace on the backend doesn't wipe them
        // (e.g. admin-created users who don't yet have an LP entry on this server).
        val preservedUserNodesJson = backendSnapshot.nodes
            .filter { it.holderType == PanoPermissionNode.Companion.HolderType.USER }
            .filter {
                val name = backendSnapshot.usernameMap[it.holderId]
                name != null && name !in usernamesInLp
            }
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

    /**
     * Return LuckPerms users to include in a full-snapshot push.
     *
     * On the first push after a (re)connect we enumerate every stored LuckPerms user so that
     * offline LP-only users (who have never been loaded on this server) are registered in Pano.
     * Subsequent pushes only include currently-loaded users - offline ones are preserved from the
     * backend snapshot in [pushLuckPermsSnapshotToPlatform].
     */
    private fun collectLuckPermsUsersForSnapshot(api: net.luckperms.api.LuckPerms): List<User> {
        if (lpUsersBootstrapped) {
            return api.userManager.loadedUsers.toList()
        }

        val users = try {
            val uniqueUsers = api.userManager.uniqueUsers.join()
            val accumulator = mutableListOf<User>()
            uniqueUsers.forEach { uuid ->
                try {
                    val user = api.userManager.loadUser(uuid).join() ?: return@forEach
                    accumulator.add(user)
                } catch (_: Exception) {
                    // Skip users we can't load; they'll be picked up on next mutation/load.
                }
            }
            accumulator
        } catch (e: Exception) {
            logger.warning(
                panoPluginMain.translateColor(
                    "&eFailed to enumerate LuckPerms users (${e.message}). " +
                            "Falling back to currently-loaded users for this push."
                )
            )
            api.userManager.loadedUsers.toList()
        }

        lpUsersBootstrapped = true
        return users
    }

    private fun isPanoManagedMarker(node: Node): Boolean {
        return node is MetaNode && node.metaKey == "pano-managed"
    }

    private fun getLuckPermsOrNull(): net.luckperms.api.LuckPerms? {
        return try {
            // Check if LuckPermsProvider class is available at runtime
            Class.forName("net.luckperms.api.LuckPermsProvider")
            LuckPermsProvider.get()
        } catch (_: ClassNotFoundException) {
            // LuckPermsProvider class is not available (LuckPerms not installed)
            logger.warning(panoPluginMain.translateColor("&eLuckPerms is not installed. Permission integration will not work."))
            null
        } catch (_: IllegalStateException) {
            // LuckPermsProvider throws an IllegalStateException when the API isn't loaded yet.
            logger.warning(panoPluginMain.translateColor("&eLuckPerms API isn't loaded yet. Is LuckPerms installed and enabled?"))
            null
        } catch (e: Exception) {
            logger.warning(panoPluginMain.translateColor("&eFailed to access LuckPerms API: ${e.message}"))
            null
        }
    }

    private fun scheduleLuckPermsRetry(pushAfterSync: Boolean) {
        if (!platformManager.serverSettings.permissionIntegration) return
        if (lpRetryJob?.isActive == true) return

        lpRetryJob = CoroutineScope(pano.vertx.dispatcher()).launch {
            // small backoff
            delay(5000L)
            if (!platformManager.serverSettings.permissionIntegration) return@launch
            try {
                start(pushAfterSync)
            } catch (_: Exception) {
                // start() already logs and will reschedule if needed
            }
        }
    }
}