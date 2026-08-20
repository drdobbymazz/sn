package dev.sn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ArgumentCoercionTest {

    private val sample = schema {
        string("text", "some text")
        integer("limit", "how many")
        boolean("recent", "only recent")
    }

    @Test
    fun `well formed arguments pass through unchanged`() {
        val result = coerceArguments(sample, args("text" to "hi", "limit" to 5, "recent" to true))
        assertEquals("hi", result.stringOr("text"))
        assertEquals(5, result.intOr("limit", 0))
        assertTrue(result.boolOr("recent", false))
    }

    @Test
    fun `a number sent as a string is coerced`() {
        // Small models do this constantly: {"limit": "10"}
        val result = coerceArguments(sample, args("limit" to "10"))
        assertEquals(10, result.intOr("limit", 0))
    }

    @Test
    fun `a boolean sent as a word is coerced`() {
        assertTrue(coerceArguments(sample, args("recent" to "true")).boolOr("recent", false))
        assertTrue(coerceArguments(sample, args("recent" to "yes")).boolOr("recent", false))
        assertEquals(false, coerceArguments(sample, args("recent" to "no")).boolOr("recent", true))
    }

    @Test
    fun `a float sent for an integer is truncated rather than dropped`() {
        assertEquals(3, coerceArguments(sample, args("limit" to 3.7)).intOr("limit", 0))
    }

    @Test
    fun `a number sent for a string field becomes a string`() {
        val result = coerceArguments(sample, args("text" to 42))
        assertEquals("42", result.stringOr("text"))
    }

    @Test
    fun `arguments not in the schema are dropped`() {
        val result = coerceArguments(sample, args("text" to "hi", "invented" to "nonsense"))
        assertEquals(setOf("text"), result.keys)
    }

    @Test
    fun `a value that cannot be coerced is dropped rather than passed on`() {
        val result = coerceArguments(sample, args("limit" to "quite a lot"))
        assertNull(result["limit"])
    }

    @Test
    fun `an empty argument object stays empty`() {
        assertTrue(coerceArguments(sample, JsonObject(emptyMap())).isEmpty())
    }
}

class ToolCallParsingTest {

    @Test
    fun `arguments arriving as an object are read directly`() {
        val call = SnJson.decodeFromString(
            FunctionCall.serializer(),
            """{"name":"sms_send","arguments":{"to":"Ada","message":"hi"}}""",
        )
        assertEquals("Ada", call.arguments.stringOr("to"))
    }

    @Test
    fun `arguments arriving as a JSON string are parsed`() {
        // Some models emit the arguments object as an escaped string.
        val call = SnJson.decodeFromString(
            FunctionCall.serializer(),
            """{"name":"sms_send","arguments":"{\"to\":\"Ada\",\"message\":\"hi\"}"}""",
        )
        assertEquals("Ada", call.arguments.stringOr("to"))
        assertEquals("hi", call.arguments.stringOr("message"))
    }

    @Test
    fun `missing arguments become an empty object, not a crash`() {
        val call = SnJson.decodeFromString(FunctionCall.serializer(), """{"name":"battery_status"}""")
        assertTrue(call.arguments.isEmpty())
    }

    @Test
    fun `unparseable argument text degrades to empty rather than throwing`() {
        val call = SnJson.decodeFromString(
            FunctionCall.serializer(),
            """{"name":"x","arguments":"not json at all"}""",
        )
        assertTrue(call.arguments.isEmpty())
    }
}

class ToolRegistryTest {

    @Test
    fun `duplicate tool names are rejected at construction`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ToolRegistry(listOf(RecordingTool("same"), RecordingTool("same")))
        }
        assertTrue(error.message!!.contains("duplicate"))
    }

    @Test
    fun `disabled tools are hidden from specs and lookup`() {
        val registry = ToolRegistry(listOf(RecordingTool("a"), RecordingTool("b")))
        assertEquals(listOf("a"), registry.specs(setOf("b")).map { it.function.name })
        assertNull(registry.find("b", setOf("b")))
    }

    @Test
    fun `a spec carries a description and an object schema`() {
        val registry = ToolRegistry(listOf(RecordingTool("a")))
        val function = registry.specs().single().function

        assertEquals("a", function.name)
        assertTrue(function.description.isNotBlank())
        assertEquals("object", function.parameters["type"]?.jsonPrimitive?.content)
        assertTrue(function.parameters.containsKey("properties"))
        assertTrue(function.parameters.containsKey("required"))
    }

    @Test
    fun `every declared property carries a description for the model`() {
        val built = schema {
            string("to", "who to send to", required = true)
            integer("limit", "how many")
        }
        val properties = built["properties"]!!.jsonObject
        properties.values.forEach { definition ->
            assertTrue(
                definition.jsonObject["description"]?.jsonPrimitive?.content?.isNotBlank() == true,
                "every property needs a description: $definition",
            )
        }
        assertEquals(listOf("to"), built["required"]!!.let { required ->
            (required as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
        })
    }
}

class HistoryTest {

    private fun user(text: String) = ChatMessage.user(text)
    private fun assistant(text: String) = ChatMessage(Role.ASSISTANT, text)
    private fun calling(name: String) = ChatMessage(
        role = Role.ASSISTANT,
        toolCalls = listOf(ToolCall(FunctionCall(name, JsonObject(emptyMap())))),
    )

    @Test
    fun `an ordinary conversation is returned in order`() {
        val messages = listOf(user("one"), assistant("two"), user("three"))
        assertEquals(messages, History.trimForReplay(messages))
    }

    @Test
    fun `orphaned tool results at the start are dropped`() {
        val messages = listOf(
            ChatMessage.tool("x", "{}"),
            assistant("answer"),
        )
        assertEquals(listOf("assistant"), History.trimForReplay(messages).map { it.role })
    }

    @Test
    fun `an unanswered tool call at the end is dropped`() {
        // The app was killed between the model asking for a tool and the
        // result being stored.
        val messages = listOf(user("do the thing"), calling("x"))
        assertEquals(listOf("user"), History.trimForReplay(messages).map { it.role })
    }

    @Test
    fun `a stored system prompt is not replayed`() {
        val messages = listOf(ChatMessage.system("old prompt"), user("hi"))
        assertEquals(listOf("user"), History.trimForReplay(messages).map { it.role })
    }

    @Test
    fun `the window keeps the most recent messages`() {
        val messages = (1..10).map { user("message $it") }
        val window = History.trimForReplay(messages, maxMessages = 3)
        assertEquals(listOf("message 8", "message 9", "message 10"), window.map { it.content })
    }

    @Test
    fun `an empty history stays empty`() {
        assertTrue(History.trimForReplay(emptyList()).isEmpty())
    }
}

class DescribeArgumentsTest {

    @Test
    fun `strings are shown unquoted for the confirmation dialog`() {
        val described = Agent.describeArguments(args("to" to "+44 7700 900111", "message" to "hi"))
        assertEquals(listOf("to" to "+44 7700 900111", "message" to "hi"), described)
    }

    @Test
    fun `numbers are shown as written`() {
        assertEquals(listOf("limit" to "5"), Agent.describeArguments(args("limit" to 5)))
    }
}

class ProtocolTest {

    @Test
    fun `a tool result message carries its tool name`() {
        val encoded = SnJson.encodeToString(
            ChatMessage.serializer(),
            ChatMessage.tool("sms_list", "{\"count\":2}"),
        )
        assertTrue(encoded.contains("\"tool_name\":\"sms_list\""), encoded)
        assertTrue(encoded.contains("\"role\":\"tool\""), encoded)
    }

    @Test
    fun `an assistant message without tool calls omits the field`() {
        val encoded = SnJson.encodeToString(
            ChatMessage.serializer(),
            ChatMessage(Role.ASSISTANT, "hello"),
        )
        assertTrue(!encoded.contains("tool_calls"), encoded)
    }

    @Test
    fun `unknown fields from a newer Ollama do not break decoding`() {
        val message = SnJson.decodeFromString(
            ChatMessage.serializer(),
            """{"role":"assistant","content":"hi","images":null,"something_new":42}""",
        )
        assertEquals("hi", message.content)
    }

    @Test
    fun `config trims a trailing slash from the host`() {
        assertEquals(
            "http://laptop.tail1234.ts.net:11434",
            OllamaConfig(host = "http://laptop.tail1234.ts.net:11434/").baseUrl,
        )
    }

    @Test
    fun `options carry the temperature and context size`() {
        val options = OllamaConfig(temperature = 0.2, contextTokens = 4096).options()
        assertEquals(0.2, options["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals("4096", options["num_ctx"]!!.jsonPrimitive.content)
    }
}

class HostAddressTest {

    @Test
    fun `a bare tailscale name gets a scheme and the default port`() {
        assertEquals(
            "http://laptop.tail1234.ts.net:11434",
            HostAddress.normalize("laptop.tail1234.ts.net"),
        )
    }

    @Test
    fun `an explicit port is respected`() {
        assertEquals(
            "http://laptop.tail1234.ts.net:8080",
            HostAddress.normalize("laptop.tail1234.ts.net:8080"),
        )
    }

    @Test
    fun `a full URL is left alone apart from a trailing slash`() {
        assertEquals(
            "http://laptop.tail1234.ts.net:11434",
            HostAddress.normalize("http://laptop.tail1234.ts.net:11434/"),
        )
    }

    @Test
    fun `https is not downgraded`() {
        assertEquals(
            "https://laptop.tail1234.ts.net:11434",
            HostAddress.normalize("https://laptop.tail1234.ts.net"),
        )
    }

    @Test
    fun `a tailscale IP works too`() {
        assertEquals("http://100.101.102.103:11434", HostAddress.normalize("100.101.102.103"))
    }

    @Test
    fun `an IPv6 literal is not mistaken for a host and port`() {
        assertEquals("http://[fd7a::1]:11434", HostAddress.normalize("[fd7a::1]"))
        assertEquals("http://[fd7a::1]:9999", HostAddress.normalize("http://[fd7a::1]:9999"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("http://laptop:11434", HostAddress.normalize("  laptop  "))
    }

    @Test
    fun `blank input stays blank rather than becoming a bogus URL`() {
        assertEquals("", HostAddress.normalize("   "))
    }
}
