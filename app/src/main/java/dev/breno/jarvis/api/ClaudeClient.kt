package dev.breno.jarvis.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class ClaudeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Resposta já decodificada da Messages API.
 *
 * [content] são os blocos crus (text, tool_use, thinking...). No loop de
 * ferramentas eles voltam para a API como vieram.
 */
data class ClaudeResponse(
    val text: String,
    val stopReason: String?,
    val content: JSONArray,
)

/** Abstração da chamada, para o agente poder ser testado com um cliente falso. */
fun interface MessagesApi {
    /**
     * Chama a API em streaming. [onTextDelta] recebe cada pedaço de texto assim
     * que chega (para o TTS começar a falar antes da resposta terminar).
     */
    suspend fun createMessage(
        system: String,
        messages: JSONArray,
        tools: JSONArray?,
        onTextDelta: suspend (String) -> Unit,
    ): ClaudeResponse
}

/** Cliente mínimo da Messages API (POST /v1/messages, SSE) com OkHttp + org.json. */
class ClaudeClient(
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = defaultHttpClient(),
    private val apiUrl: String = API_URL,
) : MessagesApi {

    override suspend fun createMessage(
        system: String,
        messages: JSONArray,
        tools: JSONArray?,
        onTextDelta: suspend (String) -> Unit,
    ): ClaudeResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw ClaudeException("Chave da API não configurada. Abra Ajustes e cole sua chave.")
        }

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("stream", true)
            put("system", system)
            put("messages", messages)
            put(
                "thinking",
                JSONObject()
                    .put("type", "adaptive")
                    // Rede de segurança: se algum bloco de thinking não bater com o histórico,
                    // a API descarta o bloco em vez de responder 400.
                    .put("block_binding", JSONObject().put("prefix_mismatch_behavior", "drop_block")),
            )
            // Respostas curtas e faladas: pouco raciocínio = menos latência.
            put("output_config", JSONObject().put("effort", "low"))
            // Se o classificador de segurança recusar, o servidor tenta outro modelo sozinho.
            put("fallbacks", "default")
            if (tools != null && tools.length() > 0) put("tools", tools)
        }

        val request = Request.Builder()
            .url(apiUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("anthropic-beta", BETAS)
            .header("accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = http.newCall(request)
        // A leitura do stream bloqueia a thread: cancelar a corrotina precisa cancelar a chamada HTTP.
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body.string()
                    val apiMessage = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
                        .getOrNull().orEmpty()
                    throw ClaudeException(
                        if (apiMessage.isNotBlank()) "Erro da API (${resp.code}): $apiMessage"
                        else "Erro da API (HTTP ${resp.code})."
                    )
                }

                val acc = StreamAccumulator()
                val source = resp.body.source()
                var event = ""
                val data = StringBuilder()
                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substringAfter(':').trim()
                        line.startsWith("data:") -> data.append(line.substringAfter(':').trimStart())
                        line.isEmpty() && data.isNotEmpty() -> {
                            val json = try {
                                JSONObject(data.toString())
                            } catch (e: JSONException) {
                                throw ClaudeException("Evento inválido no streaming da API.", e)
                            }
                            val type = event.ifEmpty { json.optString("type") }
                            acc.onEvent(type, json)?.let { onTextDelta(it) }
                            event = ""
                            data.clear()
                            if (type == "message_stop") break
                        }
                    }
                }
                acc.result()
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw CancellationException("cancelado")
            throw ClaudeException("Falha de rede ao falar com a API: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            cancelHandle?.dispose()
        }
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val BETAS = "server-side-fallback-2026-07-01,thinking-binding-controls-2026-08-01"
        private const val MAX_TOKENS = 4096
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Junta só os blocos "text" (thinking e tool_use não vão pra tela nem pro TTS). */
        fun extractText(content: JSONArray): String = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    if (isNotEmpty()) append(' ')
                    append(block.optString("text"))
                }
            }
        }.trim()
    }
}
