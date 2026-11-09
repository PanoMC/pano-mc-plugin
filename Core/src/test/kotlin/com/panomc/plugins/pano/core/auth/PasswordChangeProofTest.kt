package com.panomc.plugins.pano.core.auth

import io.vertx.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordChangeProofTest {
    private var now = 1_000_000L
    private val proof = PasswordChangeProof { now }

    @Test
    fun `a change right after the current password was checked carries it`() {
        proof.rememberVerified("Steve", "old-pass")

        val request = proof.request("steve", "new-pass")

        assertEquals("old-pass", request.currentPassword)
        assertNull(request.reset)

        // Used once.
        assertNull(proof.request("Steve", "again").currentPassword)
    }

    @Test
    fun `a staff reset is sent as a reset without a current password`() {
        proof.rememberVerified("Steve", "old-pass")
        proof.markReset("Steve")

        val request = proof.request("Steve", "new-pass")

        assertEquals(true, request.reset)
        assertNull(request.currentPassword)
    }

    @Test
    fun `a change with nothing to prove claims nothing`() {
        val request = proof.request("Steve", "new-pass")

        assertNull(request.currentPassword)
        assertNull(request.reset)
    }

    @Test
    fun `proof older than its lifetime is not used`() {
        proof.rememberVerified("Steve", "old-pass")
        proof.markReset("Alex")

        now += PasswordChangeProof.TTL_MILLIS + 1

        assertNull(proof.request("Steve", "new").currentPassword)
        assertNull(proof.request("Alex", "new").reset)
    }

    @Test
    fun `the request carries the new fields on the wire`() {
        proof.rememberVerified("Steve", "old-pass")

        val json = JsonObject(proof.request("Steve", "new-pass").encode())

        assertEquals("CHANGE_PASSWORD", json.getString("event"))
        assertEquals("Steve", json.getString("username"))
        assertEquals("new-pass", json.getString("password"))
        assertEquals("old-pass", json.getString("currentPassword"))
        assertTrue(json.containsKey("eventId"))
    }
}
