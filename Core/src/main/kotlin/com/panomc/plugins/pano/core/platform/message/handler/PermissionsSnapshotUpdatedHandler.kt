package com.panomc.plugins.pano.core.platform.message.handler

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformMessageHandler
import com.panomc.plugins.pano.core.platform.message.response.PermissionsSnapshotUpdatedMessage

class PermissionsSnapshotUpdatedHandler(
    private val pluginMain: PanoPluginMain
) : PlatformMessageHandler<PermissionsSnapshotUpdatedMessage>() {
    override suspend fun handle(response: PermissionsSnapshotUpdatedMessage) {
        pluginMain.onPermissionsSnapshotUpdated(response)
    }
}



