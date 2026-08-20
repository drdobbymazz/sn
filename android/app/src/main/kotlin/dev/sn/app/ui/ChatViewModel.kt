package dev.sn.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sn.app.SnApplication
import dev.sn.app.data.SnSettings
import dev.sn.app.service.AgentService
import dev.sn.core.Agent
import dev.sn.core.AgentEvent
import dev.sn.core.Confirmer
import dev.sn.core.Role
import dev.sn.core.Tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** One line in the transcript. */
sealed interface Turn {
    val id: Long

    data class User(override val id: Long, val text: String) : Turn
    data class Assistant(override val id: Long, val text: String, val streaming: Boolean = false) : Turn
    data class ToolRun(
        override val id: Long,
        val tool: String,
        val detail: String,
        val failed: Boolean = false,
        val denied: Boolean = false,
        val running: Boolean = false,
    ) : Turn

    data class Problem(override val id: Long, val text: String) : Turn
}

/** A pending confirmation, waiting on the user to tap yes or no. */
data class PendingConfirmation(
    val tool: Tool,
    val arguments: JsonObject,
    private val answer: CompletableDeferred<Boolean>,
) {
    val details: List<Pair<String, String>> get() = Agent.describeArguments(arguments)

    fun allow() = answer.complete(true)
    fun deny() = answer.complete(false)
}

data class ChatUiState(
    val turns: List<Turn> = emptyList(),
    val busy: Boolean = false,
    val pending: PendingConfirmation? = null,
    val conversationId: Long = 0,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = SnApplication.from(application)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val settings: StateFlow<SnSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SnSettings())

    val conversations = container.conversations.conversations()
        .map { list -> list.map { it.id to (it.title.ifBlank { "(untitled)" }) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var turnJob: Job? = null
    private var nextId = 0L

    init {
        viewModelScope.launch {
            val id = container.conversations.currentConversation()
            _state.value = _state.value.copy(conversationId = id)
            loadTranscript(id)
        }
    }

    private suspend fun loadTranscript(conversationId: Long) {
        val stored = container.conversations.replayWindow(conversationId, 200)
        val turns = stored.mapNotNull { message ->
            when {
                message.role == Role.USER -> Turn.User(nextId++, message.content)
                message.role == Role.ASSISTANT && message.content.isNotBlank() ->
                    Turn.Assistant(nextId++, message.content)
                // Tool traffic is shown live while it happens, but replaying it
                // on reopen would bury the actual conversation.
                else -> null
            }
        }
        _state.value = _state.value.copy(turns = turns)
    }

    fun newConversation() {
        turnJob?.cancel()
        viewModelScope.launch {
            val id = container.conversations.newConversation()
            _state.value = ChatUiState(conversationId = id)
        }
    }

    fun openConversation(conversationId: Long) {
        turnJob?.cancel()
        viewModelScope.launch {
            _state.value = ChatUiState(conversationId = conversationId)
            loadTranscript(conversationId)
        }
    }

    fun cancel() {
        turnJob?.cancel()
        _state.value.pending?.deny()
        _state.value = _state.value.copy(busy = false, pending = null)
        AgentService.stop(getApplication())
    }

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || _state.value.busy) return

        val app = getApplication<Application>()
        append(Turn.User(nextId++, text))
        _state.value = _state.value.copy(busy = true)

        turnJob = viewModelScope.launch {
            val settings = container.settings.settings.first()
            if (!settings.isConfigured) {
                append(
                    Turn.Problem(
                        nextId++,
                        "No Ollama host set yet. Open Settings and enter your laptop's " +
                            "Tailscale name, e.g. laptop.tail1234.ts.net",
                    ),
                )
                _state.value = _state.value.copy(busy = false)
                return@launch
            }

            val conversationId = _state.value.conversationId
            container.conversations.setTitle(conversationId, text)
            AgentService.start(app)

            val agent = container.agent(
                current = settings,
                conversationId = { conversationId },
                confirmer = uiConfirmer(),
            )
            val history = container.conversations.replayWindow(conversationId, settings.historyMessages)

            var streamingId: Long? = null
            var streamed = StringBuilder()

            try {
                agent.run(text, history).collect { event ->
                    when (event) {
                        is AgentEvent.Delta -> {
                            if (streamingId == null) {
                                streamingId = nextId++
                                streamed = StringBuilder()
                                append(Turn.Assistant(streamingId!!, "", streaming = true))
                            }
                            streamed.append(event.text)
                            replace(streamingId!!, Turn.Assistant(streamingId!!, streamed.toString(), true))
                        }

                        is AgentEvent.ToolStarted -> {
                            streamingId = null
                            append(
                                Turn.ToolRun(
                                    id = nextId++,
                                    tool = event.tool,
                                    detail = summarizeArguments(event.arguments),
                                    running = true,
                                ),
                            )
                        }

                        is AgentEvent.ToolFinished -> {
                            replaceLastToolRun(event.tool) { existing ->
                                existing.copy(
                                    detail = event.summary,
                                    failed = event.failed,
                                    running = false,
                                )
                            }
                        }

                        is AgentEvent.ToolDenied -> {
                            replaceLastToolRun(event.tool) { existing ->
                                existing.copy(detail = "declined", denied = true, running = false)
                            }
                        }

                        is AgentEvent.Final -> {
                            streamingId?.let {
                                replace(it, Turn.Assistant(it, event.text.ifBlank { streamed.toString() }))
                            } ?: run {
                                if (event.text.isNotBlank()) append(Turn.Assistant(nextId++, event.text))
                            }
                            container.conversations.append(conversationId, event.newMessages)
                        }

                        is AgentEvent.Failed -> {
                            streamingId = null
                            append(Turn.Problem(nextId++, event.message))
                            container.conversations.append(conversationId, event.newMessages)
                        }
                    }
                }
            } finally {
                AgentService.stop(app)
                _state.value = _state.value.copy(busy = false, pending = null)
            }
        }
    }

    /**
     * Bridges the agent's suspend-based confirmation to the UI.
     *
     * The agent coroutine parks on the deferred until a button is tapped, which
     * is what makes "no terminal means no" impossible to get wrong here: there
     * is always a person present to answer.
     */
    private fun uiConfirmer() = Confirmer { tool, arguments ->
        val answer = CompletableDeferred<Boolean>()
        _state.value = _state.value.copy(
            pending = PendingConfirmation(tool, arguments, answer),
        )
        try {
            answer.await()
        } finally {
            _state.value = _state.value.copy(pending = null)
        }
    }

    private fun summarizeArguments(arguments: JsonObject): String =
        Agent.describeArguments(arguments)
            .joinToString(" ") { (key, value) -> "$key=${value.take(40)}" }
            .ifBlank { "…" }

    private fun append(turn: Turn) {
        _state.value = _state.value.copy(turns = _state.value.turns + turn)
    }

    private fun replace(id: Long, turn: Turn) {
        _state.value = _state.value.copy(
            turns = _state.value.turns.map { if (it.id == id) turn else it },
        )
    }

    private fun replaceLastToolRun(tool: String, transform: (Turn.ToolRun) -> Turn.ToolRun) {
        val turns = _state.value.turns.toMutableList()
        val index = turns.indexOfLast { it is Turn.ToolRun && it.tool == tool && it.running }
        if (index < 0) return
        turns[index] = transform(turns[index] as Turn.ToolRun)
        _state.value = _state.value.copy(turns = turns)
    }
}
