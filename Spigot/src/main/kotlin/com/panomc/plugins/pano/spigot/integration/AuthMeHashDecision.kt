package com.panomc.plugins.pano.spigot.integration

// Decides what a password AuthMe hands to PanoAuthMeEncryptionMethod.computeHash means for the
// platform. Kept free of Bukkit/platform types so it can be unit-tested.
//
// AuthMe computes a hash both when a player registers (no AuthMe row yet) and when an existing
// account changes its password (/changepassword verifies the old password through the platform,
// admin /authme changepassword, email recovery). Only the second case may become a platform
// password change: a name that Pano already knows but AuthMe does not is a registration attempt
// for someone else's account (e.g. the pre-login reconcile was skipped during a platform outage),
// and must be refused instead of overwriting that account's website password.
internal object AuthMeHashDecision {

    enum class Action {
        // New account: remember the password and register it on the platform after AuthMe's RegisterEvent.
        PENDING_REGISTER,

        // Existing AuthMe account whose password AuthMe has already verified or an admin set.
        CHANGE_PASSWORD,

        // Registration for a name that already exists in Pano: abort it.
        REFUSE_REGISTER
    }

    fun decide(registeredInPano: Boolean, registeredInAuthMe: Boolean): Action = when {
        !registeredInPano -> Action.PENDING_REGISTER
        registeredInAuthMe -> Action.CHANGE_PASSWORD
        else -> Action.REFUSE_REGISTER
    }
}
