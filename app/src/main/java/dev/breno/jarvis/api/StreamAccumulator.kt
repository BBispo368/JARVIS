package dev.breno.jarvis.api

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Remonta a mensagem completa a partir dos eventos SSE da Messages API
 * (message_start, content_block_start/delta/stop, message_delta, error...).
 *
 * Separado do ClaudeClient para poder ser testado na JVM, sem rede.
 */
class StreamAccumulator {
    private val blocks = sortedMapOf<Int, JSONObject>()
    private val partialJson = mutableMapOf<Int, StringBuilder>()
    var stopReason: String? = null
        private set

    /** Processa um evento; devolve o texto novo (text_delta), se houver. */
    fun onEvent(type: String, data: JSONObject): String? {
        when (type) {
            "content_block_start" -> {
                val index = data.getInt("index")
                val block = JSONObject(data.getJSONObject("content_block").toString())
                if (block.optString("type") == "tool_use") partialJson[index] = StringBuilder()
                blocks[index] = block
                // Alguns blocos já chegam com texto inicial.
                if (block.optString("type") == "text") return block.optString("text").ifEmpty { null }
            }
            "content_block_delta" -> {
                val index = data.getInt("index")
                val block = blocks[index] ?: return null
                val delta = data.getJSONObject("delta")
                when (delta.optString("type")) {
                    "text_delta" -> {
                        val t = delta.optString("text")
                        block.put("text", block.optString("text") + t)
                        return t.ifEmpty { null }
                    }
                    "thinking_delta" -> block.put("thinking", block.optString("thinking") + delta.optString("thinking"))
                    "signature_delta" -> block.put("signature", block.optString("signature") + delta.optString("signature"))
                    "input_json_delta" -> partialJson[index]?.append(delta.optString("partial_json"))
                }
            }
            "content_block_stop" -> {
                val index = data.getInt("index")
                val json = partialJson.remove(index) ?: return null
                val input = try {
                    if (json.isBlank()) JSONObject() else JSONObject(json.toString())
                } catch (e: JSONException) {
                    throw ClaudeException("Entrada de ferramenta inválida no streaming.", e)
                }
                blocks[index]?.put("input", input)
            }
            "message_delta" -> {
                data.optJSONObject("delta")?.optString("stop_reason")
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { stopReason = it }
            }
            "error" -> {
                val msg = data.optJSONObject("error")?.optString("message").orEmpty()
                throw ClaudeException(if (msg.isNotBlank()) "Erro da API no streaming: $msg" else "Erro da API no streaming.")
            }
            // message_start, message_stop, ping: nada a fazer.
        }
        return null
    }

    fun result(): ClaudeResponse {
        val content = JSONArray(blocks.values.toList())
        return ClaudeResponse(ClaudeClient.extractText(content), stopReason, content)
    }
}
