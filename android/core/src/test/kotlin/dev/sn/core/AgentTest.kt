package dev.sn.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

/** Replays scripted assistant turns instead of talking to Ollama. */
private class ScriptedClient(
    private val turns: List<ChatMessage>,
    private val failWith: OllamaException? = null,
) : ChatBackend {

    val sentConversations = mutableListOf<List<ChatMessage>>()
    val sentTools = mutableListOf<List<ToolSpec>>()
    private var index = 0

    override fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatStream> = flow {
        sentConversations += messages.toList()
        sentTools += tools.toList()
        failWith?.let { throw it }

        val turn = turns.getOrElse(index++) { ChatMessage(Role.ASSISTANT, "(out of script)") }
        if (turn.content.isNotEmpty()) emit(ChatStream.Delta(turn.content))
        emit(ChatStream.Complete(turn))
    }
}

private fun assistantSaying(text: String) = ChatMessage(Role.ASSISTANT, text)

private fun assistantCalling(name: String, arguments: JsonObject) = ChatMessage(
    role = Role.ASSISTANT,
    content = "",
    toolCalls = listOf(ToolCall(FunctionCall(name, arguments))),
)

class AgentTest {

    private fun agent(
        client: ChatBackend,
        tools: List<Tool>,
        config: AgentConfig = AgentConfig(maxSteps = 4),
        confirmer: Confirmer = Confirmer { _, _ -> true },
        auditor: ToolAuditor = ToolAuditor { _, _, _, _, _ -> },
    ) = Agent(client, ToolRegistry(tools), config, confirmer, auditor)

    @Test
    fun `a plain answer needs no tools`() = runTest {
        val tool = RecordingTool("echo")
        val events = agent(ScriptedClient(listOf(assistantSaying("the answer"))), listOf(tool))
            .run("hello").toList()

        assertEquals(listOf("Delta", "Final"), events.map { it::class.simpleName })
        assertEquals("the answer", (events.last() as AgentEvent.Final).text)
        assertTrue(tool.calls.isEmpty())
    }

    @Test
    fun `tool result is fed back to the model`() = runTest {
        val tool = RecordingTool("echo")
        val client = ScriptedClient(
            listOf(
                assistantCalling("echo", args("text" to "hi")),
                assistantSaying("it said hi"),
            ),
        )
        val events = agent(client, listOf(tool)).run("go").toList()

        assertEquals(
            listOf("ToolStarted", "ToolFinished", "Delta", "Final"),
            events.map { it::class.simpleName },
        )
        assertEquals(1, tool.calls.size)
        assertEquals("hi", tool.calls.single().stringOr("text"))

        // The second request must carry the assistant's call and its result.
        val second = client.sentConversations[1]
        assertEquals(Role.TOOL, second.last().role)
        assertEquals("echo", second.last().toolName)
        assertFalse(second[second.size - 2].toolCalls.isNullOrEmpty())
    }

    @Test
    fun `the system prompt leads every request`() = runTest {
        val client = ScriptedClient(listOf(assistantSaying("hi")))
        agent(client, listOf(RecordingTool("echo"))).run("hello").toList()

        val first = client.sentConversations.first()
        assertEquals(Role.SYSTEM, first.first().role)
        assertContains(first.first().content, "sn")
    }

    @Test
    fun `tool specs are advertised to the model`() = runTest {
        val client = ScriptedClient(listOf(assistantSaying("hi")))
        agent(client, listOf(RecordingTool("echo"), RecordingTool("other"))).run("x").toList()

        assertEquals(listOf("echo", "other"), client.sentTools.first().map { it.function.name })
    }

    @Test
    fun `a consequential tool asks before running`() = runTest {
        val tool = RecordingTool("fire", consequential = true)
        val asked = mutableListOf<String>()
        val client = ScriptedClient(
            listOf(assistantCalling("fire", args("text" to "now")), assistantSaying("done")),
        )
        agent(client, listOf(tool), confirmer = { t, _ -> asked += t.name; true })
            .run("go").toList()

        assertEquals(listOf("fire"), asked)
        assertEquals(1, tool.calls.size)
    }

    @Test
    fun `declining stops the tool but not the turn`() = runTest {
        val tool = RecordingTool("fire", consequential = true)
        val client = ScriptedClient(
            listOf(assistantCalling("fire", args("text" to "now")), assistantSaying("ok, skipped")),
        )
        val events = agent(client, listOf(tool), confirmer = { _, _ -> false }).run("go").toList()

        assertEquals(
            listOf("ToolDenied", "Delta", "Final"),
            events.map { it::class.simpleName },
        )
        assertTrue(tool.calls.isEmpty(), "declined tool must not run")
        assertContains(client.sentConversations[1].last().content, "declined")
        assertEquals("ok, skipped", (events.last() as AgentEvent.Final).text)
    }

    @Test
    fun `the confirm list gates an otherwise harmless tool`() = runTest {
        val tool = RecordingTool("echo")
        val client = ScriptedClient(
            listOf(assistantCalling("echo", args("text" to "hi")), assistantSaying("x")),
        )
        val config = AgentConfig(maxSteps = 4, confirmTools = setOf("echo"))
        val events = agent(client, listOf(tool), config, confirmer = { _, _ -> false })
            .run("go").toList()

        assertIs<AgentEvent.ToolDenied>(events.first())
        assertTrue(tool.calls.isEmpty())
    }

    @Test
    fun `a disabled tool is neither advertised nor callable`() = runTest {
        val tool = RecordingTool("secret")
        val client = ScriptedClient(
            listOf(assistantCalling("secret", args()), assistantSaying("cannot")),
        )
        val config = AgentConfig(maxSteps = 4, disabledTools = setOf("secret"))
        val events = agent(client, listOf(tool), config).run("go").toList()

        assertTrue(client.sentTools.first().isEmpty(), "disabled tool must not be advertised")
        assertTrue(tool.calls.isEmpty(), "disabled tool must not run even if called")
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.failed)
    }

    @Test
    fun `an unknown tool is reported to the model rather than crashing`() = runTest {
        val client = ScriptedClient(
            listOf(assistantCalling("nope", args()), assistantSaying("I cannot do that")),
        )
        val events = agent(client, listOf(RecordingTool("echo"))).run("go").toList()

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.failed)
        assertContains(client.sentConversations[1].last().content, "No tool named")
    }

    @Test
    fun `a tool error becomes a tool result the model can read`() = runTest {
        val tool = FailingTool("broken", ToolException("no contact matching 'Ada'"))
        val client = ScriptedClient(
            listOf(assistantCalling("broken", args()), assistantSaying("recovered")),
        )
        val events = agent(client, listOf(tool)).run("go").toList()

        assertContains(client.sentConversations[1].last().content, "no contact matching")
        assertEquals("recovered", (events.last() as AgentEvent.Final).text)
    }

    @Test
    fun `an unexpected crash does not lose the conversation`() = runTest {
        val tool = FailingTool("broken", IllegalStateException("kaboom"))
        val client = ScriptedClient(
            listOf(assistantCalling("broken", args()), assistantSaying("recovered")),
        )
        val events = agent(client, listOf(tool)).run("go").toList()

        assertContains(client.sentConversations[1].last().content, "kaboom")
        assertEquals("recovered", (events.last() as AgentEvent.Final).text)
    }

    @Test
    fun `runaway tool use stops at the step limit`() = runTest {
        val tool = RecordingTool("echo")
        val turns = List(10) { assistantCalling("echo", args("text" to "again")) }
        val config = AgentConfig(maxSteps = 3)
        val events = agent(ScriptedClient(turns), listOf(tool), config).run("go").toList()

        assertEquals(3, tool.calls.size)
        val failure = events.last()
        assertIs<AgentEvent.Failed>(failure)
        assertContains(failure.message, "3 tool steps")
    }

    @Test
    fun `an unreachable model fails with advice, not an exception`() = runTest {
        val client = ScriptedClient(emptyList(), failWith = OllamaException("Cannot reach Ollama"))
        val events = agent(client, listOf(RecordingTool("echo"))).run("go").toList()

        val failure = events.single()
        assertIs<AgentEvent.Failed>(failure)
        assertContains(failure.message, "Cannot reach Ollama")
    }

    @Test
    fun `the turn hands back every message for persistence`() = runTest {
        val client = ScriptedClient(
            listOf(assistantCalling("echo", args("text" to "hi")), assistantSaying("done")),
        )
        val events = agent(client, listOf(RecordingTool("echo"))).run("go").toList()

        val final = events.last() as AgentEvent.Final
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT),
            final.newMessages.map { it.role },
        )
        assertEquals("go", final.newMessages.first().content)
    }

    @Test
    fun `history is replayed ahead of the new question`() = runTest {
        val client = ScriptedClient(listOf(assistantSaying("second")))
        val history = listOf(ChatMessage.user("remember this"), assistantSaying("first"))
        agent(client, listOf(RecordingTool("echo"))).run("and this", history).toList()

        val sent = client.sentConversations.single()
        assertEquals(
            listOf("remember this", "and this"),
            sent.filter { it.role == Role.USER }.map { it.content },
        )
    }

    @Test
    fun `every tool call is audited, including denials`() = runTest {
        val records = mutableListOf<Pair<String, String>>()
        val auditor = ToolAuditor { tool, _, decision, _, _ -> records += tool to decision }
        val client = ScriptedClient(
            listOf(
                assistantCalling("fire", args("text" to "x")),
                assistantCalling("echo", args("text" to "y")),
                assistantSaying("done"),
            ),
        )
        val tools = listOf(RecordingTool("fire", consequential = true), RecordingTool("echo"))
        var first = true
        val confirmer = Confirmer { _, _ -> if (first) { first = false; false } else true }

        agent(client, tools, confirmer = confirmer, auditor = auditor).run("go").toList()

        assertEquals(listOf("fire" to "denied", "echo" to "ran"), records)
    }

    @Test
    fun `several tool calls in one turn all run`() = runTest {
        val a = RecordingTool("a")
        val b = RecordingTool("b")
        val both = ChatMessage(
            role = Role.ASSISTANT,
            toolCalls = listOf(
                ToolCall(FunctionCall("a", args("text" to "1"))),
                ToolCall(FunctionCall("b", args("text" to "2"))),
            ),
        )
        val client = ScriptedClient(listOf(both, assistantSaying("done")))
        agent(client, listOf(a, b)).run("go").toList()

        assertEquals(1, a.calls.size)
        assertEquals(1, b.calls.size)
    }
}
