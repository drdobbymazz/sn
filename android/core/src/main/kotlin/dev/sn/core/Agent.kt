package dev.sn.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the agent is doing, as it does it. The UI renders these. */
sealed interface AgentEvent {
    /** A fragment of the answer. */
    data class Delta(val text: String) : AgentEvent

    data class ToolStarted(val tool: String, val arguments: JsonObject) : AgentEvent

    data class ToolFinished(
        val tool: String,
        val arguments: JsonObject,
        val summary: String,
        val failed: Boolean,
    ) : AgentEvent

    /** The user declined a confirmation prompt. */
    data class ToolDenied(val tool: String, val arguments: JsonObject) : AgentEvent

    /**
     * The turn finished. [newMessages] is everything generated this turn — the
     * user message, assistant turns and tool results — ready to be persisted.
     */
    data class Final(val text: String, val newMessages: List<ChatMessage>) : AgentEvent

    /** The turn could not complete. Already phrased for the user. */
    data class Failed(val message: String, val newMessages: List<ChatMessage>) : AgentEvent
}

data class AgentConfig(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** How many tool round trips before giving up on a question. */
    val maxSteps: Int = 8,
    /** Tool names that must be confirmed, on top of those marked consequential. */
    val confirmTools: Set<String> = emptySet(),
    /** Tool names switched off entirely. The model is not told they exist. */
    val disabledTools: Set<String> = emptySet(),
)

/** Asked before a consequential tool runs. Returning false declines it. */
fun interface Confirmer {
    suspend fun confirm(tool: Tool, arguments: JsonObject): Boolean
}

/** Records what a tool did, for the audit log. */
fun interface ToolAuditor {
    suspend fun record(
        tool: String,
        arguments: JsonObject,
        decision: String,
        summary: String,
        failed: Boolean,
    )
}

/**
 * The loop: ask the model, run the tools it asks for, repeat until it answers.
 *
 * Emits [AgentEvent]s rather than touching any UI, which is what keeps this
 * module free of Android and testable on a plain JVM.
 */
class Agent(
    private val client: ChatBackend,
    private val registry: ToolRegistry,
    private val config: AgentConfig,
    private val confirmer: Confirmer = Confirmer { _, _ -> true },
    private val auditor: ToolAuditor = ToolAuditor { _, _, _, _, _ -> },
) {

    fun run(prompt: String, history: List<ChatMessage> = emptyList()): Flow<AgentEvent> = flow {
        val userMessage = ChatMessage.user(prompt)
        val newMessages = mutableListOf(userMessage)

        val conversation = mutableListOf<ChatMessage>().apply {
            add(ChatMessage.system(config.systemPrompt))
            addAll(history)
            add(userMessage)
        }
        val specs = registry.specs(config.disabledTools)

        repeat(config.maxSteps) {
            var complete: ChatMessage? = null
            try {
                client.chat(conversation, specs).collect { event ->
                    when (event) {
                        is ChatStream.Delta -> emit(AgentEvent.Delta(event.text))
                        is ChatStream.Complete -> complete = event.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OllamaException) {
                emit(AgentEvent.Failed(e.message ?: "Could not reach the model.", newMessages))
                return@flow
            }

            val reply = complete ?: ChatMessage(Role.ASSISTANT, "")
            conversation += reply
            newMessages += reply

            val calls = reply.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                emit(AgentEvent.Final(reply.content, newMessages))
                return@flow
            }

            for (call in calls) {
                val name = call.function.name
                val requested = call.function.arguments
                val tool = registry.find(name, config.disabledTools)

                if (tool != null && needsConfirmation(tool)) {
                    if (!confirmer.confirm(tool, requested)) {
                        emit(AgentEvent.ToolDenied(name, requested))
                        auditor.record(name, requested, "denied", "declined by user", false)
                        val note = ChatMessage.tool(
                            name,
                            "The user declined to run this tool. Do not try it again. " +
                                "Acknowledge briefly and ask what they would prefer.",
                        )
                        conversation += note
                        newMessages += note
                        continue
                    }
                }

                val arguments = tool?.let { coerceArguments(it.parameters, requested) } ?: requested
                emit(AgentEvent.ToolStarted(name, arguments))

                val outcome = execute(tool, name, arguments)
                auditor.record(name, arguments, "ran", outcome.summary, outcome.failed)

                val result = ChatMessage.tool(name, outcome.payload)
                conversation += result
                newMessages += result
                emit(AgentEvent.ToolFinished(name, arguments, outcome.summary, outcome.failed))
            }
        }

        emit(
            AgentEvent.Failed(
                "Stopped after ${config.maxSteps} tool steps without an answer. " +
                    "Raise the step limit in settings, or ask something narrower.",
                newMessages,
            ),
        )
    }

    private fun needsConfirmation(tool: Tool): Boolean =
        tool.consequential || tool.name in config.confirmTools

    private data class Outcome(val payload: String, val summary: String, val failed: Boolean)

    private suspend fun execute(tool: Tool?, name: String, arguments: JsonObject): Outcome {
        if (tool == null) {
            val known = registry.enabled(config.disabledTools).joinToString(", ") { it.name }
            return Outcome(
                "Error: No tool named '$name'. Available tools: $known",
                "no such tool",
                failed = true,
            )
        }
        return try {
            val text = tool.call(arguments).toString()
            Outcome(text, summarize(text), failed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            Outcome("Error: ${e.message}", e.message ?: "failed", failed = true)
        } catch (e: Exception) {
            // A crashing tool must never lose the conversation. Hand the model
            // the failure and let it decide what to tell the user.
            val detail = "${e::class.simpleName}: ${e.message}"
            Outcome("Error: $name failed unexpectedly: $detail", detail, failed = true)
        }
    }

    private fun summarize(text: String, width: Int = 140): String =
        if (text.length <= width) text else text.take(width) + "…"

    companion object {
        /** Formats tool arguments for a confirmation dialog. */
        fun describeArguments(arguments: JsonObject): List<Pair<String, String>> =
            arguments.map { (key, value) ->
                key to when (value) {
                    is JsonPrimitive -> if (value.isString) value.content else value.toString()
                    else -> value.toString()
                }
            }
    }
}

val DEFAULT_SYSTEM_PROMPT = """
You are sn, a personal assistant running directly on the user's Android phone (a
Samsung Galaxy S23 Ultra). You reach the phone's messages, contacts, calendar,
notifications, files, camera, location and device state through tools.

How to behave:
- Use tools to find things out. Do not guess at the contents of the phone, and
  never invent a phone number, contact, message, event or file path.
- Prefer one targeted tool call over several broad ones. Read before you write.
- Tools that send messages, place calls or take photos are confirmed by the user
  before they run. Call them normally; do not ask for permission in prose first,
  and do not claim something was sent until a tool result says it was.
- If a tool returns an error, tell the user what actually failed rather than
  retrying the same call unchanged.
- The user is on a phone screen. Answer in a few short sentences. No preamble, no
  restating the question, no markdown tables.
""".trimIndent()
