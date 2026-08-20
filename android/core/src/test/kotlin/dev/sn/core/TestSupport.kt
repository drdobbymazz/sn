package dev.sn.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A tool that records what it was called with and returns a canned result. */
class RecordingTool(
    name: String,
    parameters: JsonObject = schema { string("text", "some text") },
    consequential: Boolean = false,
    private val result: JsonElement = JsonPrimitive("ok"),
    private val onCall: (suspend (JsonObject) -> Unit)? = null,
) : BaseTool(name, "Test tool $name", parameters, "test", consequential) {

    val calls = mutableListOf<JsonObject>()

    override suspend fun call(arguments: JsonObject): JsonElement {
        calls += arguments
        onCall?.invoke(arguments)
        return result
    }
}

/** A tool that always fails the given way. */
class FailingTool(
    name: String,
    private val error: Throwable,
) : BaseTool(name, "Always fails", schema {}, "test") {
    override suspend fun call(arguments: JsonObject): JsonElement = throw error
}

fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) ->
        when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            is Boolean -> put(key, value)
            is JsonElement -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}

/** Builds one NDJSON chunk as Ollama would send it. */
fun contentChunk(text: String): String =
    """{"model":"test","message":{"role":"assistant","content":${JsonPrimitive(text)}},"done":false}"""

fun toolCallChunk(name: String, argumentsJson: String): String =
    """{"model":"test","message":{"role":"assistant","content":"","tool_calls":""" +
        """[{"function":{"name":"$name","arguments":$argumentsJson}}]},"done":false}"""

fun doneChunk(): String = """{"model":"test","message":{"role":"assistant","content":""},"done":true}"""
