package dev.sn.core

/**
 * Turns whatever the user typed into a URL Ollama will answer on.
 *
 * Getting the host wrong is the single most likely setup mistake, and the
 * symptom — "cannot reach Ollama" — looks identical whether the tailnet is
 * down or the port was simply omitted. Accepting the obvious shorthands
 * removes most of that.
 */
object HostAddress {

    const val DEFAULT_PORT = 11434

    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""

        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }

        val scheme = withScheme.substringBefore("://")
        val rest = withScheme.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = rest.removePrefix(authority)

        // An IPv6 literal is bracketed, so a colon inside the brackets is part
        // of the address rather than a port separator.
        val hasPort = if (authority.startsWith("[")) {
            authority.substringAfter(']', "").startsWith(":")
        } else {
            authority.contains(':')
        }

        val withPort = if (hasPort) authority else "$authority:$DEFAULT_PORT"
        return "$scheme://$withPort$path"
    }
}
