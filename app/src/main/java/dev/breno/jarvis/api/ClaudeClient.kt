package dev.breno.jarvis.api

import dev.breno.jarvis.model.ChatMessage
import kotlinx.coroutines.Dispatchers
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

class ClaudeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Resposta já decodificada da Messages API.
 *
 * [content] guarda os blocos crus pra Fase 2: com tool calling, o histórico
 * precisa reenviar exatamente esses blocos (text, tool_use, thinking...) como
 * conteúdo da mensagem "assistant".
 */
data class ClaudeResponse(
    val text: String,
    val stopReason: String?,
    val content: JSONArray,
)

/**
 * Cliente mínimo da Messages API (POST /v1/messages) com OkHttp + org.json.
 *
 * Fase 2: o parâmetro [tools] de [createMessage] já existe; o loop de
 * tool_use → tool_result vai morar numa camada acima (agente), usando
 * [ClaudeResponse.content] e [ClaudeResponse.stopReason].
 */
class ClaudeClient(
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = defaultHttpClient(),
) {

    /** Conveniência da Fase 1: histórico simples de texto → texto da resposta. */
    suspend fun reply(system: String, history: List<ChatMessage>): String {
        val messages = JSONArray()
        history.forEach { msg ->
            messages.put(JSONObject().put("role", msg.role.apiName).put("content", msg.text))
        }
        val response = createMessage(system = system, messages = messages)
        if (response.text.isNotBlank()) return response.text
        return when (response.stopReason) {
            "refusal" -> "Receio que isso eu não possa responder, senhor."
            "max_tokens" -> "Minha resposta ficou longa demais e foi cortada, senhor."
            else -> throw ClaudeException("A API respondeu sem texto (stop_reason=${response.stopReason}).")
        }
    }

    /**
     * Chamada genérica. [messages] segue o formato da API (role + content,
     * onde content pode ser string ou array de blocos).
     */
    suspend fun createMessage(
        system: String,
        messages: JSONArray,
        tools: JSONArray? = null,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): ClaudeResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw ClaudeException("CLAUDE_API_KEY não configurada no local.properties.")
        }

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", messages)
            // Respostas curtas e faladas: pouco raciocínio = menos latência.
            put("output_config", JSONObject().put("effort", "low"))
            // Se o classificador de segurança recusar, o servidor tenta outro modelo sozinho.
            put("fallbacks", "default")
            if (tools != null && tools.length() > 0) put("tools", tools)
        }

        val request = Request.Builder()
            .url(API_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("anthropic-beta", FALLBACK_BETA)
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (code, raw) = try {
            http.newCall(request).execute().use { resp -> resp.code to resp.body.string() }
        } catch (e: IOException) {
            throw ClaudeException("Falha de rede ao falar com a API: ${e.message ?: e.javaClass.simpleName}", e)
        }

        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw ClaudeException("Resposta inválida da API (HTTP $code).", e)
        }

        if (code !in 200..299) {
            val apiMessage = json.optJSONObject("error")?.optString("message").orEmpty()
            throw ClaudeException(
                if (apiMessage.isNotBlank()) "Erro da API ($code): $apiMessage" else "Erro da API (HTTP $code)."
            )
        }

        val content = json.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                // Ignora blocos thinking/tool_use: só o texto vai pra tela e pro TTS.
                if (block.optString("type") == "text") {
                    if (isNotEmpty()) append(' ')
                    append(block.optString("text"))
                }
            }
        }.trim()

        ClaudeResponse(
            text = text,
            stopReason = json.optString("stop_reason").takeIf { it.isNotEmpty() && it != "null" },
            content = content,
        )
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private const val DEFAULT_MAX_TOKENS = 4096
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
