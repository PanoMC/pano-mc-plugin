package com.panomc.plugins.pano.core.platform.entity

data class PermissionGroup(
    val id: Long = -1,
    val name: String,
    val displayName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)