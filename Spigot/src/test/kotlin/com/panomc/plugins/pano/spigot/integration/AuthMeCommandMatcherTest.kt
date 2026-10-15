package com.panomc.plugins.pano.spigot.integration

import com.panomc.plugins.pano.spigot.integration.AuthMeCommandMatcher.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthMeCommandMatcherTest {

    // Mimics Bukkit's getPluginCommand(label)?.name for the AuthMe commands the guards care about.
    private val bukkitAliases = mapOf(
        "unreg" to "unregister",
        "unregister" to "unregister",
        "authme" to "authme",
        "reg" to "register",
        "register" to "register",
    )

    private fun classify(commandLine: String) =
        AuthMeCommandMatcher.classify(AuthMeCommandMatcher.resolve(commandLine) { bukkitAliases[it.lowercase()] })

    @Test
    fun `self unregister in every spelling is detected`() {
        listOf("/unregister pw", "/unreg pw", "/UNREG pw", "/authme:unregister pw", "  /unreg   pw ", "unreg pw")
            .forEach { assertEquals(Action.SELF_UNREGISTER, classify(it), it) }
    }

    @Test
    fun `admin unregister including the unr alias is detected`() {
        listOf("/authme unregister Steve", "/authme unreg Steve", "/authme unr Steve", "/authme:authme UNR Steve", "authme unr Steve")
            .forEach { assertEquals(Action.ADMIN_UNREGISTER, classify(it), it) }
    }

    @Test
    fun `admin register including the r alias is detected`() {
        listOf("/authme register Steve pw", "/authme reg Steve pw", "/authme r Steve pw", "authme:authme reg Steve pw")
            .forEach { assertEquals(Action.ADMIN_REGISTER, classify(it), it) }
    }

    @Test
    fun `reload including the rld alias is detected`() {
        listOf("/authme reload", "/authme rld", "authme reload", "/authme:authme RELOAD")
            .forEach { assertEquals(Action.RELOAD, classify(it), it) }
    }

    @Test
    fun `other commands are left alone`() {
        listOf(
            "/register pw pw", "/reg pw pw", "/login pw", "/authme", "/authme version", "/authme premium Steve",
            "/authmeregister", "/authme registerx Steve pw", "/unregisterx", "/", "",
        ).forEach { assertEquals(Action.NONE, classify(it), it) }
    }

    @Test
    fun `register arguments are split on any whitespace`() {
        val command = AuthMeCommandMatcher.resolve("/authme   reg  Steve   s3cret ")

        assertEquals(listOf("reg", "Steve", "s3cret"), command.args)
        assertEquals("authme", command.commandName)
        assertEquals("reg", command.subLabel)
    }
}
