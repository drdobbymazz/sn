package dev.sn.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Shared JSON codec.
 *
 * `encodeDefaults` must stay on: `ToolSpec.type` defaults to "function", and
 * that field is how Ollama recognises a tool at all. Dropping it silently
 * disables tool calling. `explicitNulls` stays off so absent fields — a message
 * with no tool calls, a request with no tools — are omitted rather than sent as
 * nulls, which Ollama rejects.
 *
 * Lenient and unknown-key-tolerant because neither model output nor Ollama's
 * response shape is entirely stable across versions.
 */
val SnJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

/**
 * One message in a conversation, in the shape Ollama's /api/chat expects.
 *
 * `toolName` is set on tool results so newer Ollama builds can match a result
 * to its call; older ones ignore the field.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_name") val toolName: String? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)
        fun user(text: String) = ChatMessage(Role.USER, text)
        fun tool(name: String, content: String) =
            ChatMessage(Role.TOOL, content, toolName = name)
    }
}

object Role {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
}

@Serializable
data class ToolCall(val function: FunctionCall)

@Serializable
data class FunctionCall(
    val name: String,
    /**
     * Held as a raw element because Ollama sends an object but some models
     * emit a JSON *string* containing the object. [arguments] normalises both.
     */
    @SerialName("arguments") val rawArguments: JsonElement? = null,
) {
    val arguments: JsonObject
        get() = when (val raw = rawArguments) {
            null -> JsonObject(emptyMap())
            is JsonObject -> raw
            is JsonPrimitive -> if (raw.isString) parseObject(raw.content) else JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }

    private fun parseObject(text: String): JsonObject = runCatching {
        SnJson.parseToJsonElement(text).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }
}

/** A tool as advertised to the model. */
@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    val stream: Boolean = true,
    @SerialName("keep_alive") val keepAlive: String? = null,
    val options: JsonObject? = null,
)

@Serializable
internal data class ChatChunk(
    val message: StreamedMessage? = null,
    val done: Boolean = false,
    val error: String? = null,
)

@Serializable
internal data class StreamedMessage(
    val role: String? = null,
    val content: String? = null,
    val thinking: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
)

@Serializable
internal data class TagsResponse(val models: List<ModelEntry> = emptyList())

@Serializable
internal data class ModelEntry(val name: String)

@Serializable
internal data class ShowResponse(val capabilities: List<String> = emptyList())
