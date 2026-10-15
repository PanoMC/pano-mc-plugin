package com.panomc.plugins.pano.spigot.integration

// Classifies an AuthMe command line for AuthMeIntegration's command guards. The labels mirror
// AuthMe's own CommandInitializer, which is the same in 5.6.x, 5.7.x and 6.0.x (6.0 only adds
// premium/freemium); AuthMeCompatibilityTest checks them against every supported release, so an
// alias AuthMe adds later fails the build instead of silently bypassing a guard.
object AuthMeCommandMatcher {

    enum class Action {
        // "/unregister" or "/unreg" - a player deleting their own AuthMe row.
        SELF_UNREGISTER,

        // "/authme unregister <player>" - the admin form.
        ADMIN_UNREGISTER,

        // "/authme register <player> <password>".
        ADMIN_REGISTER,

        // "/authme reload".
        RELOAD,

        NONE
    }

    val SELF_UNREGISTER_LABELS = setOf("unregister", "unreg")
    val ADMIN_UNREGISTER_LABELS = setOf("unregister", "unreg", "unr")
    val ADMIN_REGISTER_LABELS = setOf("register", "reg", "r")
    val RELOAD_LABELS = setOf("reload", "rld")

    private const val AUTHME_LABEL = "authme"

    data class ResolvedCommand(val commandName: String, val subLabel: String?, val args: List<String>)

    // Splits a typed command line (with or without the leading "/") into the canonical command name,
    // its first argument and every argument. [canonicalName] maps a typed label to the registered
    // command's real name (Bukkit's getPluginCommand(label)?.name), so aliases like "/unreg" and the
    // "authme:" fallback prefix resolve to the same command instead of slipping past a literal
    // prefix match.
    fun resolve(commandLine: String, canonicalName: (String) -> String? = { null }): ResolvedCommand {
        val tokens = commandLine.trim().removePrefix("/").split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (tokens.isEmpty()) {
            return ResolvedCommand("", null, emptyList())
        }

        val rawLabel = tokens[0].substringAfter(':')
        val commandName = (canonicalName(rawLabel) ?: rawLabel).lowercase()

        return ResolvedCommand(commandName, tokens.getOrNull(1)?.lowercase(), tokens.drop(1))
    }

    fun classify(command: ResolvedCommand): Action = when {
        command.commandName in SELF_UNREGISTER_LABELS -> Action.SELF_UNREGISTER
        command.commandName != AUTHME_LABEL -> Action.NONE
        command.subLabel in ADMIN_UNREGISTER_LABELS -> Action.ADMIN_UNREGISTER
        command.subLabel in ADMIN_REGISTER_LABELS -> Action.ADMIN_REGISTER
        command.subLabel in RELOAD_LABELS -> Action.RELOAD
        else -> Action.NONE
    }
}
