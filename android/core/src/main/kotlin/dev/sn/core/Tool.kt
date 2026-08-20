package dev.sn.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A failure the model should see and reason about — a missing permission, no
 * such contact, a path outside the sandbox.
 *
 * The message is returned to the model as the tool result, so write it for that
 * reader: what went wrong, and what to do instead.
 */
class ToolException(message: String) : Exception(message)

/** One capability the agent can use. Implementations live in the app module. */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    val category: String

    /**
     * True for anything that touches the world outside the phone, or that the
     * user cannot undo. These sit behind a confirmation prompt.
     */
    val consequential: Boolean get() = false

    /** Runs the tool. Throw [ToolException] for expected failures. */
    suspend fun call(arguments: JsonObject): JsonElement

    fun spec(): ToolSpec = ToolSpec(
        function = FunctionSpec(name = name, description = description, parameters = parameters),
    )
}

/** Convenience base class so implementations stay short. */
abstract class BaseTool(
    override val name: String,
    override val description: String,
    override val parameters: JsonObject = schema {},
    override val category: String = "misc",
    override val consequential: Boolean = false,
) : Tool

// -- schema helpers -------------------------------------------------------

class SchemaBuilder {
    private val properties = mutableMapOf<String, JsonElement>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) {
        properties[name] = buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
        }
        if (required) this.required += name
    }

    fun integer(name: String, description: String, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "integer")
            put("description", description)
        }
        if (required) this.required += name
    }

    fun boolean(name: String, description: String, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }
        if (required) this.required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

// -- argument handling ----------------------------------------------------

/**
 * Reconcile what the model sent with what the tool declared.
 *
 * Small models get this wrong in predictable ways: they pass `"limit": "10"`
 * as a string, `"true"` for a boolean, or invent an argument that was never in
 * the schema. Coercing where the intent is unambiguous, and dropping the rest,
 * turns a whole class of tool failures into successful calls.
 */
fun coerceArguments(schema: JsonObject, arguments: JsonObject): JsonObject {
    val properties = (schema["properties"] as? JsonObject) ?: return JsonObject(emptyMap())
    val cleaned = mutableMapOf<String, JsonElement>()

    for ((key, value) in arguments) {
        val definition = properties[key] as? JsonObject ?: continue // not in the schema — drop it
        val type = (definition["type"] as? JsonPrimitive)?.contentOrNullSafe()
        cleaned[key] = coerceValue(value, type) ?: continue
    }
    return JsonObject(cleaned)
}

private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

private fun coerceValue(value: JsonElement, type: String?): JsonElement? {
    if (value is JsonPrimitive && !value.isString && value.contentOrNullSafe() == "null") return null
    return when (type) {
        "integer" -> asLong(value)?.let { JsonPrimitive(it) }
        "number" -> asDouble(value)?.let { JsonPrimitive(it) }
        "boolean" -> asBoolean(value)?.let { JsonPrimitive(it) }
        "string" -> asString(value)?.let { JsonPrimitive(it) }
        else -> value
    }
}

private fun asLong(value: JsonElement): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    primitive.longOrNull?.let { return it }
    primitive.doubleOrNull?.let { return it.toLong() }
    return primitive.contentOrNullSafe()?.trim()?.toLongOrNull()
}

private fun asDouble(value: JsonElement): Double? {
    val primitive = value as? JsonPrimitive ?: return null
    primitive.doubleOrNull?.let { return it }
    return primitive.contentOrNullSafe()?.trim()?.toDoubleOrNull()
}

private fun asBoolean(value: JsonElement): Boolean? {
    val primitive = value as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNullSafe()?.trim()?.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
}

private fun asString(value: JsonElement): String? = when (value) {
    is JsonPrimitive -> value.contentOrNullSafe()
    else -> value.toString()
}

// -- reading arguments inside a tool --------------------------------------

fun JsonObject.stringOr(key: String, fallback: String = ""): String =
    (this[key] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() } ?: fallback

fun JsonObject.requireString(key: String): String =
    stringOr(key).ifBlank { throw ToolException("Missing required argument '$key'.") }

fun JsonObject.intOr(key: String, fallback: Int): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: fallback

fun JsonObject.longOr(key: String, fallback: Long): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: fallback

fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } ?: fallback

// -- registry -------------------------------------------------------------

class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    init {
        require(tools.size == byName.size) {
            val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
            "duplicate tool names: $duplicates"
        }
    }

    val all: List<Tool> get() = byName.values.sortedBy { it.name }

    fun enabled(disabled: Set<String>): List<Tool> = all.filter { it.name !in disabled }

    fun specs(disabled: Set<String> = emptySet()): List<ToolSpec> = enabled(disabled).map { it.spec() }

    fun find(name: String, disabled: Set<String> = emptySet()): Tool? =
        byName[name]?.takeIf { it.name !in disabled }

    fun byCategory(disabled: Set<String> = emptySet()): Map<String, List<Tool>> =
        enabled(disabled).groupBy { it.category }.toSortedMap()
}
