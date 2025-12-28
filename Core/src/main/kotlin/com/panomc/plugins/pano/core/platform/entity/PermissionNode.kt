package com.panomc.plugins.pano.core.platform.entity

import io.vertx.core.json.JsonObject

data class PermissionNode(
    val id: Long = -1,
    val holderType: HolderType,
    val holderId: Long,
    val node: String,
    val active: Boolean = false,
    val context: JsonObject = JsonObject(),
    val expiresAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        enum class HolderType {
            GROUP, USER
        }
    }
}