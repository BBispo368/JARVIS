package dev.breno.jarvis.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tradução entre o formato interno do agente (o mesmo da Messages API da
 * Anthropic: blocos text / tool_use / tool_result) e o formato nativo do
 * Gemini (generateContent: contents → parts → text / functionCall / functionResponse).
 *
 * Campos extras guardados nos blocos internos (nunca enviados ao Claude, já
 * que o histórico é limpo ao trocar de provedor):
 *  - "gemini_signature": thoughtSignature que veio junto da parte. O Gemini
 *    exige que ela volte nas chamadas de função do turno atual (senão 400).
 *  - "gemini_call_id": id original do functionCall (pode não vir).
 */
object GeminiFormat {

    private val SCHEMA_KEYS = setOf("type", "description", "properties", "required", "enum", "items", "nullable", "format")

    fun buildRequest(system: String, messages: JSONArray, tools: JSONArray?, thinkingLevel: String?): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", toContents(messages))

        if (tools != null && tools.length() > 0) {
            val decls = JSONArray()
            for (i in 0 until tools.length()) {
                val t = tools.getJSONObject(i)
                val decl = JSONObject().put("name", t.getString("name")).put("description", t.optString("description"))
                val schema = t.optJSONObject("input_schema")
                // Ferramenta sem argumentos: o Gemini recusa "properties" vazio, então omite "parameters".
                if (schema != null && (schema.optJSONObject("properties")?.length() ?: 0) > 0) {
                    decl.put("parameters", sanitizeSchema(schema))
                }
                decls.put(decl)
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }

        val gen = JSONObject().put("maxOutputTokens", 4096)
        if (thinkingLevel != null) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        body.put("generationConfig", gen)
        return body
    }

    /** O "parameters" do Gemini aceita só um subconjunto de schema (ex.: sem additionalProperties). */
    internal fun sanitizeSchema(schema: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in schema.keys()) {
            if (key !in SCHEMA_KEYS) continue
            when (key) {
                "properties" -> {
                    val props = schema.getJSONObject(key)
                    val clean = JSONObject()
                    for (p in props.keys()) clean.put(p, sanitizeSchema(props.getJSONObject(p)))
                    out.put(key, clean)
                }
                "items" -> out.put(key, sanitizeSchema(schema.getJSONObject(key)))
                else -> out.put(key, schema.get(key))
            }
        }
        return out
    }

    internal fun toContents(messages: JSONArray): JSONArray {
        val contents = JSONArray()
        val callNames = mutableMapOf<String, String>() // tool_use_id -> nome (o functionResponse precisa do nome)
        val callIds = mutableMapOf<String, String?>()  // tool_use_id -> id original do Gemini

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val isUser = msg.optString("role") == "user"
            val parts = JSONArray()
            when (val content = msg.opt("content")) {
                is String -> parts.put(JSONObject().put("text", content))
                is JSONArray -> for (j in 0 until content.length()) {
                    val block = content.optJSONObject(j) ?: continue
                    when (block.optString("type")) {
                        "text" -> parts.put(
                            JSONObject().put("text", block.optString("text")).withSignature(block)
                        )
                        "tool_use" -> {
                            val id = block.optString("id")
                            val geminiId = block.optString("gemini_call_id").takeIf { it.isNotEmpty() }
                            callNames[id] = block.optString("name")
                            callIds[id] = geminiId
                            val call = JSONObject()
                                .put("name", block.optString("name"))
                                .put("args", block.optJSONObject("input") ?: JSONObject())
                            if (geminiId != null) call.put("id", geminiId)
                            parts.put(JSONObject().put("functionCall", call).withSignature(block))
                        }
                        "tool_result" -> {
                            val useId = block.optString("tool_use_id")
                            val text = block.opt("content")?.let(::flattenContent).orEmpty()
                            val response = if (block.optBoolean("is_error")) JSONObject().put("error", text)
                            else JSONObject().put("result", text)
                            val fr = JSONObject()
                                .put("name", callNames[useId] ?: "desconhecida")
                                .put("response", response)
                            callIds[useId]?.let { fr.put("id", it) }
                            parts.put(JSONObject().put("functionResponse", fr))
                        }
                        // thinking/redacted_thinking/fallback são do Claude: não existem no Gemini.
                    }
                }
            }
            if (parts.length() > 0) {
                contents.put(JSONObject().put("role", if (isUser) "user" else "model").put("parts", parts))
            }
        }
        return contents
    }

    private fun JSONObject.withSignature(block: JSONObject): JSONObject {
        block.optString("gemini_signature").takeIf { it.isNotEmpty() }?.let { put("thoughtSignature", it) }
        return this
    }

    private fun flattenContent(content: Any): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length())
            .mapNotNull { content.optJSONObject(it)?.optString("text") }
            .joinToString("\n")
        else -> content.toString()
    }
}

/**
 * Junta os pedaços do streamGenerateContent (SSE) numa resposta no formato
 * interno: blocos text e tool_use, mais um stop_reason equivalente.
 */
class GeminiStreamAccumulator {
    private val blocks = mutableListOf<JSONObject>()
    private var finishReason: String? = null
    private var blocked = false
    private var callCounter = 0

    /** Processa um chunk; devolve o texto novo, se houver. */
    fun onChunk(chunk: JSONObject): String? {
        chunk.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { blocked = true }
        chunk.optJSONObject("error")?.let { err ->
            throw ClaudeException("Erro do Gemini: ${err.optString("message").ifBlank { err.toString() }}")
        }
        val candidate = chunk.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        candidate.optString("finishReason").takeIf { it.isNotEmpty() }?.let { finishReason = it }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null

        val newText = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optBoolean("thought")) continue // resumo de raciocínio: não vai pra fala
            val signature = part.optString("thoughtSignature").takeIf { it.isNotEmpty() }
            val call = part.optJSONObject("functionCall")
            when {
                call != null -> {
                    val geminiId = call.optString("id").takeIf { it.isNotEmpty() }
                    val block = JSONObject()
                        .put("type", "tool_use")
                        .put("id", geminiId ?: "gemini_call_${++callCounter}")
                        .put("name", call.optString("name"))
                        .put("input", call.optJSONObject("args") ?: JSONObject())
                    if (geminiId != null) block.put("gemini_call_id", geminiId)
                    if (signature != null) block.put("gemini_signature", signature)
                    blocks += block
                }
                part.has("text") -> {
                    val text = part.optString("text")
                    val last = blocks.lastOrNull()
                    val target = if (last != null && last.optString("type") == "text") last
                    else JSONObject().put("type", "text").put("text", "").also { blocks += it }
                    target.put("text", target.optString("text") + text)
                    if (signature != null) target.put("gemini_signature", signature)
                    newText.append(text)
                }
                signature != null -> {
                    // Assinatura chegando sozinha num chunk vazio: pendura no último bloco.
                    blocks.lastOrNull()?.put("gemini_signature", signature)
                }
            }
        }
        return newText.toString().ifEmpty { null }
    }

    fun result(): ClaudeResponse {
        val content = JSONArray(blocks.filter { it.optString("type") != "text" || it.optString("text").isNotBlank() })
        val hasCalls = blocks.any { it.optString("type") == "tool_use" }
        val stop = when {
            hasCalls -> "tool_use"
            blocked -> "refusal"
            finishReason == "MAX_TOKENS" -> "max_tokens"
            finishReason in REFUSAL_REASONS -> "refusal"
            else -> "end_turn"
        }
        return ClaudeResponse(ClaudeClient.extractText(content), stop, content)
    }

    private companion object {
        val REFUSAL_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "RECITATION", "BLOCKLIST", "SPII", "IMAGE_SAFETY")
    }
}
