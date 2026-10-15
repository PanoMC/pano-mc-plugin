package com.panomc.plugins.pano.spigot.integration

import fr.xephi.authme.security.crypts.EncryptionMethod
import fr.xephi.authme.security.crypts.HashedPassword

// The EncryptionMethod Pano hands AuthMe for passwordHash: CUSTOM (see
// AuthMeIntegration.onPasswordEncryptionEvent). AuthMe never sees a real hash: every password it
// would hash goes to [onPasswordHashed] (register / change password on the platform) and every
// password it would check goes to [authenticate]. Kept free of Bukkit/platform types so the
// compatibility tests can run it inside each supported AuthMe release's real PasswordSecurity.
//
// The EncryptionMethod / HashedPassword surface used here is identical in AuthMe 5.6.x, 5.7.x and
// 6.0.x; AuthMeCompatibilityTest guards that.
class PanoAuthMeEncryptionMethod(
    private val onPasswordHashed: (playerName: String, password: String) -> Unit,
    private val authenticate: (playerName: String, password: String) -> Boolean
) : EncryptionMethod {

    companion object {
        // AuthMe's comparePassword always delegates to the platform (see comparePassword below) and
        // never reads this value back, so it only needs to be non-null/non-empty and non-reversible
        // — never the player's real password.
        const val MANAGED_PASSWORD_PLACEHOLDER = "pano-managed"
    }

    override fun computeHash(password: String, name: String): HashedPassword {
        onPasswordHashed(name, password)

        // Never persist the real password: AuthMe writes this verbatim to its datasource, and
        // comparePassword() below ignores it entirely, delegating to the platform.
        return HashedPassword(MANAGED_PASSWORD_PLACEHOLDER)
    }

    override fun computeHash(password: String, salt: String?, name: String): String {
        onPasswordHashed(name, password)

        return MANAGED_PASSWORD_PLACEHOLDER
    }

    override fun comparePassword(password: String, hashedPassword: HashedPassword?, name: String): Boolean =
        authenticate(name, password)

    override fun generateSalt(): String? = null

    override fun hasSeparateSalt(): Boolean = false
}
