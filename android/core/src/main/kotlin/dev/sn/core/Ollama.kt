package dev.sn.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class OllamaConfig(
    /** Usually a Tailscale MagicDNS name, e.g. http://laptop.tail1234.ts.net:11434 */
    val host: String = "http://127.0.0.1:11434",
    val model: String = "qwen3:8b",
    val keepAlive: String = "30m",
    val temperature: Double = 0.4,
    val contextTokens: Int = 8192,
    val timeoutSeconds: Long = 180,
) {
    val baseUrl: String get() = host.trimEnd('/')

    internal fun options(): JsonObject = buildJsonObject {
        put("temperature", temperature)
        put("num_ctx", contextTokens)
    }
}

/** Something went wrong talking to Ollama, phrased for a human to act on. */
class OllamaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One streamed step of an assistant turn. */
sealed interface ChatStream {
    /** A fragment of the answer, as it is generated. */
    data class Delta(val text: String) : ChatStream

    /** The complete assistant turn, once the stream ends. */
    data class Complete(val message: ChatMessage) : ChatStream
}

/**
 * Somewhere an assistant turn can be generated.
 *
 * The agent depends on this rather than on [OllamaClient] directly, so a test
 * can script model turns without a server, and a different backend could be
 * dropped in later.
 */
interface ChatBackend {
    fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatStream>
}

class OllamaClient(
    @Volatile var config: OllamaConfig,
    private val http: OkHttpClient = defaultHttpClient(config.timeoutSeconds),
) : ChatBackend {
    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        fun defaultHttpClient(timeoutSeconds: Long): OkHttpClient = OkHttpClient.Builder()
            // Generation can be slow, especially the first request after the
            // laptop wakes and has to load the model into VRAM. Only the read
            // timeout needs to be generous; a connect that hangs means the
            // tailnet is down and should fail fast.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Stream one assistant turn.
     *
     * Text arrives as [ChatStream.Delta]; tool calls are accumulated across
     * chunks and delivered only on [ChatStream.Complete], since a half-parsed
     * tool call is of no use to anyone.
     */
    override fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatStream> = flow {
        val request = ChatRequest(
            model = config.model,
            messages = messages,
            tools = tools.ifEmpty { null },
            stream = true,
            keepAlive = config.keepAlive,
            options = config.options(),
        )
        val body = SnJson.encodeToString(ChatRequest.serializer(), request)
            .toRequestBody(JSON_MEDIA)
        val call = http.newCall(
            Request.Builder().url("${config.baseUrl}/api/chat").post(body).build(),
        )

        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw OllamaException(networkAdvice(e), e)
        }

        response.use {
            if (!response.isSuccessful) {
                throw OllamaException(httpAdvice(response.code, response.body?.string().orEmpty()))
            }
            val source = response.body?.source()
                ?: throw OllamaException("Ollama returned an empty response body.")

            var content = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()
            var role = Role.ASSISTANT

            while (true) {
                currentCoroutineContextEnsureActive()
                val line = try {
                    source.readUtf8Line()
                } catch (e: IOException) {
                    throw OllamaException(networkAdvice(e), e)
                } ?: break

                if (line.isBlank()) continue
                val chunk = try {
                    SnJson.decodeFromString(ChatChunk.serializer(), line)
                } catch (e: Exception) {
                    throw OllamaException("Malformed response from Ollama: ${line.take(200)}", e)
                }
                chunk.error?.let { throw OllamaException("Ollama error: $it") }

                chunk.message?.let { part ->
                    part.role?.let { role = it }
                    part.content?.takeIf { it.isNotEmpty() }?.let {
                        content.append(it)
                        emit(ChatStream.Delta(it))
                    }
                    part.toolCalls?.let { toolCalls += it }
                }
            }

            emit(
                ChatStream.Complete(
                    ChatMessage(
                        role = role,
                        content = content.toString(),
                        toolCalls = toolCalls.ifEmpty { null },
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    /** Model names available on the server. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val response = get("/api/tags")
        SnJson.decodeFromString(TagsResponse.serializer(), response).models.map { it.name }.sorted()
    }

    /**
     * What the model can do. An empty list means "unknown" — older Ollama
     * builds omit the field — not "no capabilities".
     */
    suspend fun capabilities(model: String = config.model): List<String> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("model", model) }.toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder().url("${config.baseUrl}/api/show").post(payload).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw OllamaException(httpAdvice(response.code, response.body?.string().orEmpty()))
                }
                SnJson.decodeFromString(
                    ShowResponse.serializer(),
                    response.body?.string().orEmpty(),
                ).capabilities
            }
        } catch (e: IOException) {
            throw OllamaException(networkAdvice(e), e)
        }
    }

    /** True when the server answers at all. Used by the setup screen. */
    suspend fun reachable(): Boolean = runCatching { listModels() }.isSuccess

    private fun get(path: String): String {
        val request = Request.Builder().url("${config.baseUrl}$path").get().build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw OllamaException(httpAdvice(response.code, response.body?.string().orEmpty()))
                }
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw OllamaException(networkAdvice(e), e)
        }
    }

    private fun httpAdvice(code: Int, body: String): String {
        val detail = runCatching {
            SnJson.parseToJsonElement(body).let { element ->
                (element as? JsonObject)?.get("error")?.toString()?.trim('"')
            }
        }.getOrNull() ?: body.take(300)

        return when {
            code == 404 && detail.contains("model", ignoreCase = true) ->
                "Ollama has no model named '${config.model}'. On the laptop: ollama pull ${config.model}"
            else -> "Ollama returned HTTP $code: $detail"
        }
    }

    private fun networkAdvice(e: IOException): String = buildString {
        append("Cannot reach Ollama at ${config.baseUrl} (${e.message}).\n")
        append("Check, in order:\n")
        append("  1. Tailscale is connected on both the phone and the laptop\n")
        append("  2. the laptop is awake and `ollama serve` is running\n")
        append("  3. Ollama listens on the tailnet, not just localhost:\n")
        append("     OLLAMA_HOST=0.0.0.0:11434 ollama serve")
    }
}

/**
 * Cancellation check inside the blocking read loop.
 *
 * Kept as a named helper so the intent is obvious: without it, cancelling a
 * generation would keep draining the socket until the model finished.
 */
private suspend fun currentCoroutineContextEnsureActive() {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
}
