package com.panomc.plugins.pano.core.platform.request

import com.panomc.plugins.pano.core.platform.PlatformRequest

/**
 * A password change for a website account.
 *
 * [currentPassword] is the player's current password when they changed it themselves; [reset] is
 * true only for a staff or console reset. Pano checks them when `password-check` was agreed; an
 * older Pano ignores both fields.
 */
class ChangePasswordRequest(
    val username: String,
    val password: String,
    val currentPassword: String? = null,
    val reset: Boolean? = null
) : PlatformRequest()
