package dev.breno.jarvis.api

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
 * [content] são os blocos crus (text, tool_use, thinking...). No loop de
 * ferramentas eles voltam para a API exatamente como vieram.
 */
data class ClaudeResponse(
    val text: String,
    val stopReason: String?,
    val content: JSONArray,
)

/** Abstração da chamada, para o agente poder ser testado com um cliente falso. */
fun interface MessagesApi {
    suspend fun createMessage(system: String, messages: JSONArray, tools: JSONArray?): ClaudeResponse
}

/** Cliente mínimo da Messages API (POST /v1/messages) com OkHttp + org.json. */
class ClaudeClient(
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = defaultHttpClient(),
) : MessagesApi {

    override suspend fun createMessage(
        system: String,
        messages: JSONArray,
        tools: JSONArray?,
    ): ClaudeResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw ClaudeException("CLAUDE_API_KEY não configurada no local.properties.")
        }

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
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
            .url(API_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("anthropic-beta", BETAS)
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
        ClaudeResponse(
            text = extractText(content),
            stopReason = json.optString("stop_reason").takeIf { it.isNotEmpty() && it != "null" },
            content = content,
        )
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
