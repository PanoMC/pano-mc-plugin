package com.panomc.plugins.pano.core.platform.message.response

import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import com.panomc.plugins.pano.core.platform.entity.PermissionGroup
import com.panomc.plugins.pano.core.platform.entity.PermissionNode
import com.panomc.plugins.pano.core.platform.entity.PermissionTrack

data class GetPermissionsMessage(
    val groups: List<PermissionGroup>,
    val tracks: List<PermissionTrack>,
    val nodes: List<PermissionNode>,
    val usernameMap: Map<Long, String>
) : PlatformMessageResponse