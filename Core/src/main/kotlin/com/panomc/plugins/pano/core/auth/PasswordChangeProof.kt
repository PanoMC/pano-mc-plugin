package com.panomc.plugins.pano.core.auth

import com.panomc.plugins.pano.core.platform.request.ChangePasswordRequest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What a `CHANGE_PASSWORD` can prove to Pano (capability `password-check`, MC-PROTOCOL-SPEC.md 3).
 *
 * The auth plugins (AuthMe, LimboAuth) only tell Pano the new password. They do check the current
 * one first when a player changes it themselves - through Pano, by the same `PLAYER_AUTHENTICATE`
 * that logs them in - so that check is remembered here for a moment and sent along as
 * `currentPassword`. A reset by staff or the console is marked here by the command hook that saw
 * it, and sent as `reset`. A change with neither is sent with neither, and Pano refuses it.
 *
 * Entries live [TTL_MILLIS] and are used once.
 */
class PasswordChangeProof(private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(val password: String?, val at: Long)

    private val verified = ConcurrentHashMap<String, Entry>()
    private val resets = ConcurrentHashMap<String, Entry>()

    private fun key(username: String) = username.lowercase(Locale.ROOT)

    /** [username] just proved [password] to Pano. */
    fun rememberVerified(username: String, password: String) {
        verified[key(username)] = Entry(password, clock())
    }

    /** Staff or the console is about to set [username]'s password without knowing the current one. */
    fun markReset(username: String) {
        resets[key(username)] = Entry(null, clock())
    }

    fun forget(username: String) {
        verified.remove(key(username))
        resets.remove(key(username))
    }

    /** The request for a change of [username]'s password to [newPassword], with whatever it can prove. */
    fun request(username: String, newPassword: String): ChangePasswordRequest {
        val now = clock()
        val reset = resets.remove(key(username))?.takeIf { now - it.at <= TTL_MILLIS } != null
        val current = verified.remove(key(username))?.takeIf { now - it.at <= TTL_MILLIS }?.password

        return if (reset) {
            ChangePasswordRequest(username, newPassword, currentPassword = null, reset = true)
        } else {
            ChangePasswordRequest(username, newPassword, currentPassword = current, reset = null)
        }
    }

    companion object {
        const val TTL_MILLIS = 60_000L
    }
}
