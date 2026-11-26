package dev.breno.jarvis.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Histórico no formato da API, guardado por turno.
 *
 * Um turno = a mensagem de texto do usuário + tudo que veio depois até a
 * resposta final (tool_use, tool_result...). Cortar por turnos inteiros
 * garante que a janela sempre começa com role "user" e nunca separa um
 * tool_use do seu tool_result.
 *
 * Os blocos de thinking são removidos quando o turno é salvo: eles ficam
 * presos ao histórico exato que os gerou, e a janela deslizante mudaria
 * esse histórico. Dentro do turno (no loop de ferramentas), o agente
 * reenvia os blocos intactos.
 */
class ConversationHistory(private val maxMessages: Int = 20) {

    private val turns = ArrayDeque<List<JSONObject>>()

    val turnCount: Int get() = turns.size

    fun addTurn(messages: List<JSONObject>) {
        require(messages.isNotEmpty() && messages.first().optString("role") == "user") {
            "Um turno precisa começar com uma mensagem do usuário"
        }
        turns.addLast(messages.map(::withoutThinking))
        // Não precisa guardar mais do que a janela usa.
        while (turns.size > 1 && turns.sumOf { it.size } > maxMessages * 2) turns.removeFirst()
    }

    fun clear() = turns.clear()

    /** Últimos turnos completos que cabem em [maxMessages] (sempre pelo menos o mais recente). */
    fun window(): List<JSONObject> {
        val selected = ArrayDeque<List<JSONObject>>()
        var count = 0
        for (turn in turns.reversed()) {
            if (selected.isNotEmpty() && count + turn.size > maxMessages) break
            selected.addFirst(turn)
            count += turn.size
        }
        return selected.flatten()
    }

    companion object {
        private val THINKING_TYPES = setOf("thinking", "redacted_thinking")

        internal fun withoutThinking(message: JSONObject): JSONObject {
            val content = message.opt("content") as? JSONArray ?: return message
            val kept = JSONArray()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i)
                if (block == null || block.optString("type") !in THINKING_TYPES) kept.put(content.get(i))
            }
            return JSONObject(message.toString()).put("content", kept)
        }
    }
}
