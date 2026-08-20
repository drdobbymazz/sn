package dev.sn.app.data

import dev.sn.core.ChatMessage
import dev.sn.core.History
import dev.sn.core.SnJson
import dev.sn.core.ToolAuditor
import kotlinx.serialization.json.JsonObject

/** Conversation storage, in the terms the agent thinks in. */
class ConversationRepository(private val db: SnDatabase) {

    suspend fun currentConversation(): Long =
        db.conversations().latest()?.id ?: newConversation()

    suspend fun newConversation(): Long =
        db.conversations().insert(ConversationEntity(startedAt = System.currentTimeMillis()))

    suspend fun setTitle(conversationId: Long, title: String) {
        db.conversations().setTitleIfUnset(conversationId, title.take(80))
    }

    fun messages(conversationId: Long) = db.messages().stream(conversationId)

    fun conversations() = db.conversations().recent()

    suspend fun delete(conversationId: Long) {
        db.messages().clear(conversationId)
        db.conversations().delete(conversationId)
    }

    /**
     * The window of past messages replayed into the next turn, already trimmed
     * so it never opens or closes mid tool exchange.
     */
    suspend fun replayWindow(conversationId: Long, maxMessages: Int): List<ChatMessage> {
        val stored = db.messages().recent(conversationId, maxMessages).reversed()
        val decoded = stored.mapNotNull { row ->
            runCatching { SnJson.decodeFromString(ChatMessage.serializer(), row.payload) }.getOrNull()
        }
        return History.trimForReplay(decoded, maxMessages)
    }

    suspend fun append(conversationId: Long, messages: List<ChatMessage>) {
        val now = System.currentTimeMillis()
        db.messages().insertAll(
            messages.map { message ->
                MessageEntity(
                    conversationId = conversationId,
                    timestamp = now,
                    role = message.role,
                    text = message.content,
                    payload = SnJson.encodeToString(ChatMessage.serializer(), message),
                )
            },
        )
    }
}

/**
 * Writes every tool call to the audit table.
 *
 * An agent that can text people from your number should leave a trail, and it
 * should be one you can read without trusting the agent to tell you the truth
 * about what it did.
 */
class DatabaseAuditor(
    private val db: SnDatabase,
    private val conversationId: () -> Long,
) : ToolAuditor {
    override suspend fun record(
        tool: String,
        arguments: JsonObject,
        decision: String,
        summary: String,
        failed: Boolean,
    ) {
        db.audit().insert(
            AuditEntity(
                timestamp = System.currentTimeMillis(),
                conversationId = conversationId(),
                tool = tool,
                arguments = arguments.toString().take(2000),
                decision = decision,
                summary = summary.take(500),
                failed = failed,
            ),
        )
    }
}
