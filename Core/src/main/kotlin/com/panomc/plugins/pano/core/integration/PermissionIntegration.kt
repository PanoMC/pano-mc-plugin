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
    @Volatile private var suppressPushUntilMillis: Long = 0L

    override fun isInitialized() = initialized

    private suspend fun start() {
        if (initialized) {
            return
        }

        if (!platformManager.serverSettings.permissionIntegration) {
            return
        }

        initialized = true

        logger.info("&ePermission integration is enabled, loading...".colorize())

        // Don't start unless LuckPerms is actually available.
        // Sync must never run unless we have successfully hooked into LuckPerms.
        val api = getLuckPermsOrNull() ?: run {
            scheduleLuckPermsRetry()
            return
        }

        hookLuckPerms(api)
        syncLuckPermsFromPlatform(api)
    }

    private fun hookLuckPerms(api: net.luckperms.api.LuckPerms) {
        logger.info("&eHooking into LuckPerms...".colorize())
        registerLuckPermsOutboundSync(api)
    }

    private suspend fun syncLuckPermsFromPlatform(api: net.luckperms.api.LuckPerms) {
        val permissions = platformManager.sendMessageAwaitResponse<GetPermissionsMessage>(
            GetPermissionsRequest(),
            GetPermissionsMessage::class.java
        )

        logger.info("&eSyncing permissions from Pano...".colorize())

        // Avoid push-back storm while we are applying our own sync changes
        suppressPushUntilMillis = System.currentTimeMillis() + 5_000L
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

        // Optional: user nodes (panel users) -> LuckPerms users (minecraft usernames)
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
    }

    private fun stop() {
        if (!initialized) {
            return
        }

        logger.info("&ePermission integration is disabled.".colorize())

        pushSnapshotJob?.cancel()
        pushSnapshotJob = null
        lpRetryJob?.cancel()
        lpRetryJob = null
        lpSubscriptions.forEach { sub ->
            try { sub.close() } catch (_: Exception) {}
        }
        lpSubscriptions.clear()
        lpOutboundRegistered = false

        initialized = false
    }

    override fun onConnectionEstablished(webSocket: WebSocket?) {
        if (platformManager.serverSettings.permissionIntegration) {
            CoroutineScope(pano.vertx.dispatcher()).launch {
                start()
            }
            return
        }

        stop()
    }

    override fun onServerSettingsChanged(serverSettings: GetServerSettingsMessage) {
        if (serverSettings.permissionIntegration) {
            CoroutineScope(pano.vertx.dispatcher()).launch {
                start()
            }
            return
        }

        stop()
    }

    override fun onPermissionsSnapshotUpdated(message: PermissionsSnapshotUpdatedMessage) {
        if (!platformManager.serverSettings.permissionIntegration) return

        CoroutineScope(pano.vertx.dispatcher()).launch {
            val api = getLuckPermsOrNull() ?: run {
                scheduleLuckPermsRetry()
                return@launch
            }

            // If we aren't started yet, start() will hook + sync.
            if (!initialized) {
                start()
                return@launch
            }

            // Ensure hook is in place (idempotent) then sync.
            syncLuckPermsFromPlatform(api)
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

            val uuid = api.userManager.lookupUniqueId(username).join() ?: return@forEach
            val user = api.userManager.loadUser(uuid).join()

            overwriteUserData(
                api = api,
                user = user,
                panoNodes = nodes,
                groupNameById = groupNameById
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
    }

    private fun scheduleSnapshotPushToPlatform() {
        // Only push when integration is enabled and connection is alive
        if (!initialized || !platformManager.serverSettings.permissionIntegration) return

        val now = System.currentTimeMillis()
        if (now < suppressPushUntilMillis) return

        pushSnapshotJob?.cancel()
        pushSnapshotJob = CoroutineScope(pano.vertx.dispatcher()).launch {
            delay(1500L)
            if (System.currentTimeMillis() < suppressPushUntilMillis) return@launch
            try {
                pushLuckPermsSnapshotToPlatform()
            } catch (e: Exception) {
                logger.warning(panoPluginMain.translateColor("&eFailed to push LuckPerms snapshot to platform: ${e.message}"))
            }
        }
    }

    /**
     * Push a *full* snapshot to the platform DB.
     *
     * IMPORTANT:
     * - Backend save endpoint truncates permission tables.
     * - To avoid losing panel-side user nodes, we preserve USER nodes from the backend snapshot.
     */
    private suspend fun pushLuckPermsSnapshotToPlatform() {
        val api = getLuckPermsOrNull() ?: return

        // Preserve current backend USER nodes so we don't wipe them on truncate
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
                grp.distinctNodes.map { node ->
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

        // Preserve USER nodes from backend snapshot
        val userNodesJson = backendSnapshot.nodes
            .filter { it.holderType == PanoPermissionNode.Companion.HolderType.USER }
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
            "nodes" to (userNodesJson + groupNodesJson).map { it.map }
        )

        platformManager.sendMessage(SavePermissionsSnapshotRequest(snapshotMap))
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

    private fun scheduleLuckPermsRetry() {
        if (!platformManager.serverSettings.permissionIntegration) return
        if (lpRetryJob?.isActive == true) return

        lpRetryJob = CoroutineScope(pano.vertx.dispatcher()).launch {
            // small backoff
            delay(5000L)
            if (!platformManager.serverSettings.permissionIntegration) return@launch
            try {
                start()
            } catch (_: Exception) {
                // start() already logs and will reschedule if needed
            }
        }
    }
}