package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest

/**
 * Push a full permissions snapshot to the platform (server websocket event: SAVE_PERMISSIONS_SNAPSHOT).
 *
 * Backend expects an object (map) containing { groups: [], tracks: [], nodes: [] } compatible with panel snapshot.
 */
data class SavePermissionsSnapshotRequest(
    val snapshot: Map<String, Any?>
) : PlatformRequest()


