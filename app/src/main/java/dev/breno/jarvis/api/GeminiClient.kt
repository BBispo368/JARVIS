package dev.breno.jarvis.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    model: String,
    private val http: OkHttpClient = ClaudeClient.defaultHttpClient(),
    private val baseUrl: String = BASE_URL,
    fallbackModels: List<String> = DEFAULT_FALLBACKS,
    private val retryDelayMs: Long = 800,
    private val clock: () -> Long = System::currentTimeMillis,
) : MessagesApi {

    /** Modelo escolhido em Ajustes primeiro; os outros só entram se ele estiver sobrecarregado/sem cota. */
    private val models = (listOf(model) + fallbackModels).distinct()

    /** Modelo em uso agora (índice em [models]) e desde quando, para voltar ao principal depois. */
    @Volatile private var activeIndex = 0
    @Volatile private var activeSince = 0L

    /** Se o modelo recusar o thinkingConfig, as próximas chamadas vão sem ele. */
    @Volatile private var sendThinking = true

    /** Modelo que respondeu a última chamada (para testes/diagnóstico). */
    val currentModel: String get() = models[activeIndex]

    override suspend fun createMessage(
        system: String,
        messages: JSONArray,
        tools: JSONArray?,
        onTextDelta: suspend (String) -> Unit,
    ): ClaudeResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw ClaudeException("Chave do Gemini não configurada. Abra Ajustes e cole sua chave do Google AI Studio.")
        }
        // Depois de um tempo num modelo reserva, tenta o principal de novo.
        if (activeIndex != 0 && clock() - activeSince > RETURN_TO_PRIMARY_MS) activeIndex = 0

        var lastError: Unavailable? = null
        var index = activeIndex
        while (index < models.size) {
            val model = models[index]
            var attempt = 0
            while (true) {
                try {
                    val result = withThinkingFallback(model, system, messages, tools, onTextDelta)
                    if (index != activeIndex) {
                        activeIndex = index
                        activeSince = clock()
                    }
                    return@withContext result
                } catch (e: Unavailable) {
                    lastError = e
                    // Sobrecarga costuma passar em segundos: uma nova tentativa no mesmo modelo antes de trocar.
                    if (e.retrySameModel && attempt == 0) {
                        attempt++
                        delay(retryDelayMs)
                        continue
                    }
                    break
                }
            }
            index++
        }
        throw ClaudeException(lastError?.userMessage ?: "O Gemini não respondeu. Tente de novo.")
    }

    private suspend fun withThinkingFallback(
        model: String,
        system: String,
        messages: JSONArray,
        tools: JSONArray?,
        onTextDelta: suspend (String) -> Unit,
    ): ClaudeResponse = try {
        stream(model, system, messages, tools, onTextDelta, thinking = sendThinking)
    } catch (e: ThinkingNotSupported) {
        sendThinking = false
        stream(model, system, messages, tools, onTextDelta, thinking = false)
    }

    private class ThinkingNotSupported : Exception()

    /** Falha que vale a pena contornar trocando de modelo (sobrecarga, cota, modelo reserva inexistente). */
    private class Unavailable(val userMessage: String, val retrySameModel: Boolean) : Exception(userMessage)

    private suspend fun stream(
        model: String,
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
                    val status = err?.optString("status").orEmpty()
                    if (resp.code == 400 && thinking && message.contains("thinking", ignoreCase = true)) {
                        throw ThinkingNotSupported()
                    }
                    val friendly = errorMessage(resp.code, status, message)
                    when {
                        resp.code == 503 || resp.code == 500 || status == "UNAVAILABLE" || status == "INTERNAL" ->
                            throw Unavailable(friendly, retrySameModel = true)
                        // Cota gratuita é por modelo: outro modelo ainda pode ter.
                        resp.code == 429 || status == "RESOURCE_EXHAUSTED" -> throw Unavailable(friendly, retrySameModel = false)
                        // Um modelo reserva que não existe mais não pode travar o app.
                        resp.code == 404 && model != models.first() -> throw Unavailable(friendly, retrySameModel = false)
                    }
                    throw ClaudeException(friendly)
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
        private const val RETURN_TO_PRIMARY_MS = 10 * 60 * 1000L

        /** Modelos Flash com plano gratuito, do mais capaz ao mais leve. */
        val DEFAULT_FALLBACKS = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite")
        private val JSON = "application/json".toMediaType()

        internal fun errorMessage(code: Int, status: String, message: String): String = when {
            code == 429 || status == "RESOURCE_EXHAUSTED" ->
                "Limite gratuito do Gemini atingido por agora. Espere um pouco e tente de novo."
            code == 503 || code == 500 || status == "UNAVAILABLE" || status == "INTERNAL" ->
                "Os servidores do Gemini estão sobrecarregados agora. Tente de novo em instantes."
            code == 400 && message.contains("API key", ignoreCase = true) ->
                "Chave do Gemini inválida. Confira em Ajustes."
            code == 403 -> "O Gemini recusou a chave (HTTP 403)${if (message.isNotBlank()) ": $message" else "."}"
            code == 404 -> "Modelo do Gemini não encontrado. Confira o nome do modelo em Ajustes."
            message.isNotBlank() -> "Erro do Gemini ($code): $message"
            else -> "Erro do Gemini (HTTP $code)."
        }
    }
}
