package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse

/**
 * Broadcast from platform to tell servers to re-sync permissions from the platform.
 */
data class PermissionsSnapshotUpdatedMessage(
    val updatedAt: Long
) : PlatformMessageResponse



