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
import kotlin.coroutines.coroutineContext

/**
 * Cliente do Google Gemini (API nativa streamGenerateContent, SSE).
 *
 * Implementa o mesmo [MessagesApi] do ClaudeClient: o agente não sabe qual
 * provedor está do outro lado. A tradução de formato fica em [GeminiFormat].
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = ClaudeClient.defaultHttpClient(),
    private val baseUrl: String = BASE_URL,
) : MessagesApi {

    /** Se o modelo recusar o thinkingConfig, as próximas chamadas vão sem ele. */
    @Volatile private var sendThinking = true

    override suspend fun createMessage(
        system: String,
        messages: JSONArray,
        tools: JSONArray?,
        onTextDelta: suspend (String) -> Unit,
    ): ClaudeResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw ClaudeException("Chave do Gemini não configurada. Abra Ajustes e cole sua chave do Google AI Studio.")
        }
        try {
            stream(system, messages, tools, onTextDelta, thinking = sendThinking)
        } catch (e: ThinkingNotSupported) {
            sendThinking = false
            stream(system, messages, tools, onTextDelta, thinking = false)
        }
    }

    private class ThinkingNotSupported : Exception()

    private suspend fun stream(
        system: String,
        messages: JSONArray,
        tools: JSONArray?,
        onTextDelta: suspend (String) -> Unit,
        thinking: Boolean,
    ): ClaudeResponse {
        // Pouco raciocínio = resposta falada mais rápida ("low" é aceito por todos os Flash atuais).
        val body = GeminiFormat.buildRequest(system, messages, tools, if (thinking) "low" else null)
        val request = Request.Builder()
            .url("$baseUrl/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .header("accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val call = http.newCall(request)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body.string()
                    val err = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
                    val message = err?.optString("message").orEmpty()
                    if (resp.code == 400 && thinking && message.contains("thinking", ignoreCase = true)) {
                        throw ThinkingNotSupported()
                    }
                    throw ClaudeException(errorMessage(resp.code, err?.optString("status").orEmpty(), message))
                }

                val acc = GeminiStreamAccumulator()
                val source = resp.body.source()
                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substringAfter(':').trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val json = try {
                        JSONObject(data)
                    } catch (e: JSONException) {
                        throw ClaudeException("Resposta inválida do Gemini no streaming.", e)
                    }
                    acc.onChunk(json)?.let { onTextDelta(it) }
                }
                return acc.result()
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw CancellationException("cancelado")
            throw ClaudeException("Falha de rede ao falar com o Gemini: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            cancelHandle?.dispose()
        }
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json".toMediaType()

        internal fun errorMessage(code: Int, status: String, message: String): String = when {
            code == 429 || status == "RESOURCE_EXHAUSTED" ->
                "Limite gratuito do Gemini atingido por agora. Espere um pouco e tente de novo."
            code == 400 && message.contains("API key", ignoreCase = true) ->
                "Chave do Gemini inválida. Confira em Ajustes."
            code == 403 -> "O Gemini recusou a chave (HTTP 403)${if (message.isNotBlank()) ": $message" else "."}"
            code == 404 -> "Modelo do Gemini não encontrado. Confira o nome do modelo em Ajustes."
            message.isNotBlank() -> "Erro do Gemini ($code): $message"
            else -> "Erro do Gemini (HTTP $code)."
        }
    }
}
