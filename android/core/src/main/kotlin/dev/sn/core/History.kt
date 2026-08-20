package dev.sn.core

/**
 * Prepares stored messages for replay into a new turn.
 *
 * A tool exchange spans several messages, and replaying half of one confuses
 * the model. Two ways a window can split one:
 *
 * * it opens on tool results whose call fell outside the window;
 * * the app was killed after the model asked for a tool but before the result
 *   was stored, leaving an unanswered call at the end.
 *
 * Both ends are trimmed to a clean boundary.
 */
object History {
    fun trimForReplay(messages: List<ChatMessage>, maxMessages: Int = 40): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        var window = messages.takeLast(maxMessages).toMutableList()

        while (window.isNotEmpty() && window.first().role == Role.TOOL) {
            window.removeAt(0)
        }
        while (window.isNotEmpty() && !window.last().toolCalls.isNullOrEmpty()) {
            window.removeAt(window.size - 1)
        }
        // A system prompt is supplied fresh each turn; a stored one would
        // double up and drift from whatever settings now say.
        return window.filter { it.role != Role.SYSTEM }
    }
}
