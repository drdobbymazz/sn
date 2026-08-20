package dev.sn.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Exercises the wire protocol against a real HTTP server, so the NDJSON
 * streaming, the request shape and the error advice are all genuinely tested
 * rather than mocked away.
 */
class OllamaClientTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun client(model: String = "test-model") = OllamaClient(
        OllamaConfig(host = server.url("/").toString(), model = model, timeoutSeconds = 10),
    )

    private fun ndjson(vararg lines: String) = MockResponse()
        .setHeader("Content-Type", "application/x-ndjson")
        .setBody(lines.joinToString("\n") + "\n")

    @Test
    fun `streamed text arrives as deltas and is assembled into one message`() = runTest {
        server.enqueue(
            ndjson(
                contentChunk("Your battery "),
                contentChunk("is at 82%."),
                doneChunk(),
            ),
        )

        val events = client().chat(listOf(ChatMessage.user("battery?")), emptyList()).toList()

        val deltas = events.filterIsInstance<ChatStream.Delta>().map { it.text }
        assertEquals(listOf("Your battery ", "is at 82%."), deltas)

        val complete = events.filterIsInstance<ChatStream.Complete>().single()
        assertEquals("Your battery is at 82%.", complete.message.content)
        assertEquals(Role.ASSISTANT, complete.message.role)
        assertTrue(complete.message.toolCalls.isNullOrEmpty())
    }

    @Test
    fun `a tool call is delivered on completion, not streamed as text`() = runTest {
        server.enqueue(
            ndjson(
                toolCallChunk("sms_list", """{"limit":5,"box":"inbox"}"""),
                doneChunk(),
            ),
        )

        val events = client().chat(listOf(ChatMessage.user("messages?")), emptyList()).toList()

        assertTrue(events.filterIsInstance<ChatStream.Delta>().isEmpty())
        val call = events.filterIsInstance<ChatStream.Complete>()
            .single().message.toolCalls!!.single()
        assertEquals("sms_list", call.function.name)
        assertEquals(5, call.function.arguments.intOr("limit", 0))
        assertEquals("inbox", call.function.arguments.stringOr("box"))
    }

    @Test
    fun `the request carries the model, messages and tool schemas`() = runTest {
        server.enqueue(ndjson(contentChunk("ok"), doneChunk()))
        val tools = listOf(RecordingTool("battery_status").spec())

        client("qwen3:8b").chat(listOf(ChatMessage.user("hi")), tools).toList()

        val body = SnJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("qwen3:8b", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("hi", body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)

        val tool = body["tools"]!!.jsonArray.single().jsonObject
        // Ollama identifies a tool by this field. Without it, tool calling
        // silently never fires.
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)

        val advertised = tool["function"]!!.jsonObject
        assertEquals("battery_status", advertised["name"]!!.jsonPrimitive.content)
        assertEquals("object", advertised["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no tools means the field is omitted entirely`() = runTest {
        server.enqueue(ndjson(contentChunk("ok"), doneChunk()))
        client().chat(listOf(ChatMessage.user("hi")), emptyList()).toList()

        val body = SnJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue("tools" !in body, "an empty tool list must not be sent as []")
    }

    @Test
    fun `an error inside the stream surfaces as an exception`() = runTest {
        server.enqueue(ndjson("""{"error":"model requires more system memory"}"""))

        val error = assertFailsWith<OllamaException> {
            client().chat(listOf(ChatMessage.user("hi")), emptyList()).toList()
        }
        assertContains(error.message!!, "more system memory")
    }

    @Test
    fun `a missing model is reported with the pull command`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":"model 'qwen3:8b' not found"}"""),
        )

        val error = assertFailsWith<OllamaException> {
            client("qwen3:8b").chat(listOf(ChatMessage.user("hi")), emptyList()).toList()
        }
        assertContains(error.message!!, "ollama pull qwen3:8b")
    }

    @Test
    fun `an unreachable server explains what to check`() = runTest {
        server.shutdown() // nothing listening any more

        val error = assertFailsWith<OllamaException> {
            client().chat(listOf(ChatMessage.user("hi")), emptyList()).toList()
        }
        assertContains(error.message!!, "Tailscale")
        assertContains(error.message!!, "OLLAMA_HOST=0.0.0.0:11434")
    }

    @Test
    fun `malformed NDJSON is reported rather than silently skipped`() = runTest {
        server.enqueue(ndjson("this is not json"))

        val error = assertFailsWith<OllamaException> {
            client().chat(listOf(ChatMessage.user("hi")), emptyList()).toList()
        }
        assertContains(error.message!!, "Malformed response")
    }

    @Test
    fun `blank lines in the stream are ignored`() = runTest {
        server.enqueue(ndjson(contentChunk("hello"), "", "   ", doneChunk()))

        val events = client().chat(listOf(ChatMessage.user("hi")), emptyList()).toList()
        assertEquals("hello", events.filterIsInstance<ChatStream.Complete>().single().message.content)
    }

    @Test
    fun `listModels returns the server's models sorted`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"models":[{"name":"qwen3:8b"},{"name":"llama3.1:8b"}]}""",
            ),
        )
        assertEquals(listOf("llama3.1:8b", "qwen3:8b"), client().listModels())
    }

    @Test
    fun `capabilities reports tool support`() = runTest {
        server.enqueue(MockResponse().setBody("""{"capabilities":["completion","tools"]}"""))
        assertContains(client().capabilities(), "tools")
    }

    @Test
    fun `capabilities is empty rather than failing on an older Ollama`() = runTest {
        server.enqueue(MockResponse().setBody("""{"license":"MIT"}"""))
        assertTrue(client().capabilities().isEmpty())
    }

    @Test
    fun `the whole loop runs against a real server`() = runTest {
        // First turn asks for a tool, second turn answers using its result.
        server.enqueue(ndjson(toolCallChunk("battery_status", "{}"), doneChunk()))
        server.enqueue(ndjson(contentChunk("You are at 82%."), doneChunk()))

        val battery = RecordingTool(
            "battery_status",
            parameters = schema {},
            result = SnJson.parseToJsonElement("""{"percentage":82}""") as JsonObject,
        )
        val agent = Agent(
            client = client(),
            registry = ToolRegistry(listOf(battery)),
            config = AgentConfig(maxSteps = 4),
        )

        val events = agent.run("what's my battery?").toList()

        assertEquals(1, battery.calls.size)
        assertEquals("You are at 82%.", (events.last() as AgentEvent.Final).text)

        // The tool result must have gone back over the wire on the second call.
        server.takeRequest()
        val second = SnJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val toolMessage = second["messages"]!!.jsonArray.map { it.jsonObject }
            .single { it["role"]?.jsonPrimitive?.content == "tool" }
        assertContains(toolMessage["content"]!!.jsonPrimitive.content, "82")
    }
}
