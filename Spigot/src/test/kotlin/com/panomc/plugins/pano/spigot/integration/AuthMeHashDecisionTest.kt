package com.panomc.plugins.pano.spigot.integration

import com.panomc.plugins.pano.spigot.integration.AuthMeHashDecision.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthMeHashDecisionTest {

    @Test
    fun `a new name is registered on the platform after AuthMe's register`() {
        assertEquals(Action.PENDING_REGISTER, AuthMeHashDecision.decide(registeredInPano = false, registeredInAuthMe = false))
        assertEquals(Action.PENDING_REGISTER, AuthMeHashDecision.decide(registeredInPano = false, registeredInAuthMe = true))
    }

    @Test
    fun `an existing AuthMe account changes its platform password`() {
        assertEquals(Action.CHANGE_PASSWORD, AuthMeHashDecision.decide(registeredInPano = true, registeredInAuthMe = true))
    }

    @Test
    fun `registering a name that only exists in Pano never changes that account's password`() {
        assertEquals(Action.REFUSE_REGISTER, AuthMeHashDecision.decide(registeredInPano = true, registeredInAuthMe = false))
    }
}
