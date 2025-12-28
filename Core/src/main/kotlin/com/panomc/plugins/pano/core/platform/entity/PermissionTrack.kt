package com.panomc.plugins.pano.core.platform.entity

data class PermissionTrack(
    val id: Long = -1,
    val name: String,
    val description: String,
    val groupIds: List<Long>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)