package dev.breno.jarvis.agent

import dev.breno.jarvis.api.MessagesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** Resultado de um turno: o texto a falar e as mensagens a guardar no histórico. */
data class TurnResult(
    val text: String,
    val messages: List<JSONObject>,
)

/**
 * Loop de tool calling: chama o Claude, executa os tool_use pedidos, devolve
 * os tool_result e repete até a resposta final em texto.
 *
 * Dentro de um turno o `system`, as `tools` e as mensagens anteriores não
 * mudam: só há anexos ao final. É isso que mantém válidos os blocos de
 * thinking reenviados entre uma chamada e outra.
 */
class JarvisAgent(
    private val api: MessagesApi,
    private val maxSteps: Int = 8,
) {

    suspend fun runTurn(
        system: String,
        history: List<JSONObject>,
        userText: String,
        tools: List<JarvisTool>,
        onToolStart: (JarvisTool) -> Unit = {},
    ): TurnResult {
        val toolsByName = tools.associateBy { it.name }
        val toolsJson = if (tools.isEmpty()) null else JSONArray(tools.map { it.toApiJson() })

        val turn = mutableListOf(JSONObject().put("role", "user").put("content", userText))
        val messages = JSONArray(history).also { arr -> turn.forEach { arr.put(it) } }

        repeat(maxSteps) {
            val response = api.createMessage(system, messages, toolsJson)
            val toolUses = (0 until response.content.length())
                .mapNotNull { response.content.optJSONObject(it) }
                .filter { it.optString("type") == "tool_use" }

            if (response.stopReason != "tool_use" || toolUses.isEmpty()) {
                val text = response.text.ifBlank { fallbackText(response.stopReason) }
                // A resposta final guardada no histórico só precisa do texto.
                turn += assistantText(text)
                return TurnResult(text, turn)
            }

            val assistant = JSONObject().put("role", "assistant").put("content", response.content)
            turn += assistant
            messages.put(assistant)

            // Todas as ferramentas pedidas rodam em paralelo e voltam numa única mensagem.
            val results = coroutineScope {
                toolUses.map { use -> async { runTool(use, toolsByName, onToolStart) } }.awaitAll()
            }
            val toolResults = JSONObject().put("role", "user").put("content", JSONArray(results))
            turn += toolResults
            messages.put(toolResults)
        }

        val text = "Desisti depois de etapas demais, senhor. Tente pedir de outro jeito."
        turn += assistantText(text)
        return TurnResult(text, turn)
    }

    private suspend fun runTool(
        use: JSONObject,
        toolsByName: Map<String, JarvisTool>,
        onToolStart: (JarvisTool) -> Unit,
    ): JSONObject {
        val tool = toolsByName[use.optString("name")]
        val result = if (tool == null) {
            ToolResult.error("Ferramenta desconhecida: ${use.optString("name")}")
        } else {
            onToolStart(tool)
            try {
                tool.execute(use.optJSONObject("input") ?: JSONObject())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.error("Falha ao executar ${tool.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return JSONObject()
            .put("type", "tool_result")
            .put("tool_use_id", use.optString("id"))
            .put("content", result.content.ifBlank { if (result.isError) "erro" else "ok" })
            .apply { if (result.isError) put("is_error", true) }
    }

    private fun assistantText(text: String) = JSONObject()
        .put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    private fun fallbackText(stopReason: String?): String = when (stopReason) {
        "refusal" -> "Receio que isso eu não possa responder, senhor."
        "max_tokens" -> "Minha resposta ficou longa demais e foi cortada, senhor."
        else -> "Fiquei sem palavras, senhor."
    }
}
