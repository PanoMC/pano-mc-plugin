package com.panomc.plugins.pano.core.files

/**
 * Hides the network-wide secrets that ordinary config files carry, when the file manager shows
 * them in its editor.
 *
 * `forwarding.secret` is denied outright on the proxy, but every backend behind it keeps the same
 * secret in its own config (Paper's `proxies.velocity.secret`, FabricProxy-Lite's `secret`), and
 * `server.properties` carries the RCON password. Those files have to stay editable, so instead of
 * hiding them the secret value is replaced by [PLACEHOLDER] on read, and a save that still carries
 * [PLACEHOLDER] keeps the value that is on disk. Typing a new value replaces it as before.
 *
 * Line-based on purpose: the files are YAML, TOML and properties, and a parser that rewrote them
 * would reformat the operator's file on every save.
 */
object SecretRedaction {
    const val PLACEHOLDER = "<hidden by Pano>"

    private class Rule(val path: String, val line: Regex)

    // Group 1 is everything up to the value, group 2 the value itself.
    private val RULES = listOf(
        Rule("server.properties", Regex("""^(\s*rcon\.password\s*[=:]\s*)(.*)$""")),
        Rule("config/paper-global.yml", Regex("""^(\s*secret\s*:\s*)(.*)$""")),
        Rule("config/fabricproxy-lite.toml", Regex("""^(\s*secret\s*=\s*)(.*)$""")),
        Rule("velocity.toml", Regex("""^(\s*forwarding-secret\s*=\s*)(.*)$"""))
    )

    private fun ruleFor(path: String?): Rule? {
        val lower = ServerFileDenylist.normalise(path).lowercase()

        return RULES.firstOrNull { it.path == lower }
    }

    /** Whether [path] is a file whose secrets are hidden. */
    fun applies(path: String?): Boolean = ruleFor(path) != null

    /** [content] of [path] with every secret value replaced by [PLACEHOLDER]. */
    fun redact(path: String?, content: String): String {
        val rule = ruleFor(path) ?: return content

        return mapLines(content) { line ->
            val match = rule.line.find(line) ?: return@mapLines line
            val value = match.groupValues[2]

            if (value.isBlank()) line else match.groupValues[1] + PLACEHOLDER
        }
    }

    /**
     * [incoming] (what the editor saves) with every [PLACEHOLDER] put back to the value [current]
     * (what is on disk) has for the same key, in order of appearance.
     */
    fun restore(path: String?, incoming: String, current: String?): String {
        val rule = ruleFor(path) ?: return incoming

        if (!incoming.contains(PLACEHOLDER)) {
            return incoming
        }

        val values = ArrayDeque(
            current.orEmpty().split('\n').mapNotNull { rule.line.find(it.removeSuffix("\r"))?.groupValues?.get(2) }
        )

        return mapLines(incoming) { line ->
            val match = rule.line.find(line) ?: return@mapLines line
            val original = values.removeFirstOrNull()

            if (match.groupValues[2] == PLACEHOLDER) {
                // No value to put back means there never was one; an empty secret is still safer
                // than writing the placeholder itself as the secret.
                match.groupValues[1] + (original ?: "")
            } else {
                line
            }
        }
    }

    private fun mapLines(content: String, transform: (String) -> String): String =
        content.split('\n').joinToString("\n") { raw ->
            val carriageReturn = raw.endsWith("\r")
            val line = if (carriageReturn) raw.dropLast(1) else raw

            transform(line) + if (carriageReturn) "\r" else ""
        }
}
