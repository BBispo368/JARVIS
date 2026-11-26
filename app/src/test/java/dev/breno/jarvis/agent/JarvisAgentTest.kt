package dev.breno.jarvis.agent

import dev.breno.jarvis.api.ClaudeClient
import dev.breno.jarvis.api.ClaudeResponse
import dev.breno.jarvis.api.MessagesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisAgentTest {

    /** Devolve respostas roteirizadas e guarda cópias das requisições. */
    private class FakeApi(private val responses: List<JSONArray>, private val stops: List<String>) : MessagesApi {
        val requests = mutableListOf<Triple<String, String, String?>>() // system, messages, tools
        override suspend fun createMessage(system: String, messages: JSONArray, tools: JSONArray?): ClaudeResponse {
            val i = requests.size
            requests += Triple(system, messages.toString(), tools?.toString())
            val content = responses[i]
            return ClaudeResponse(ClaudeClient.extractText(content), stops[i], content)
        }
    }

    private class FakeTool(override val name: String, private val result: ToolResult) : JarvisTool {
        override val description = "teste"
        override val inputSchema = Schemas.obj("x" to Schemas.string("x"))
        override val label = "Testando"
        val inputs = mutableListOf<JSONObject>()
        override suspend fun execute(input: JSONObject): ToolResult { inputs += input; return result }
    }

    private fun thinking() = JSONObject().put("type", "thinking").put("thinking", "").put("signature", "sig")
    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)
    private fun toolUse(id: String, name: String, input: JSONObject = JSONObject().put("x", "1")) =
        JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", input)

    @Test
    fun `responde direto sem ferramentas`() = runTest {
        val api = FakeApi(listOf(JSONArray().put(text("Olá, senhor."))), listOf("end_turn"))
        val result = JarvisAgent(api).runTurn("sys", emptyList(), "oi", emptyList())

        assertEquals("Olá, senhor.", result.text)
        assertEquals(2, result.messages.size)
        assertEquals("user", result.messages[0].getString("role"))
        assertEquals(null, api.requests[0].third) // sem tools, o campo não vai
    }

    @Test
    fun `executa ferramentas em paralelo e reenvia o historico apenas anexando`() = runTest {
        val a = FakeTool("pc_a", ToolResult.ok("feito A"))
        val b = FakeTool("pc_b", ToolResult.error("falhou B"))
        val first = JSONArray().put(thinking()).put(toolUse("t1", "pc_a")).put(toolUse("t2", "pc_b"))
        val api = FakeApi(listOf(first, JSONArray().put(text("Pronto."))), listOf("tool_use", "end_turn"))

        val result = JarvisAgent(api).runTurn("sys", emptyList(), "faz", listOf(a, b))

        assertEquals("Pronto.", result.text)
        assertEquals(1, a.inputs.size)
        assertEquals(1, b.inputs.size)

        // Segunda chamada = primeira + anexos; system e tools idênticos.
        val (sys1, msgs1, tools1) = api.requests[0]
        val (sys2, msgs2, tools2) = api.requests[1]
        assertEquals(sys1, sys2)
        assertEquals(tools1, tools2)
        val m1 = JSONArray(msgs1)
        val m2 = JSONArray(msgs2)
        assertEquals(m1.length() + 2, m2.length())
        assertEquals(m1.get(0).toString(), m2.get(0).toString())

        // O bloco de thinking volta intacto dentro do turno.
        val assistant = m2.getJSONObject(1).getJSONArray("content")
        assertEquals("thinking", assistant.getJSONObject(0).getString("type"))

        // Os dois tool_result vão numa única mensagem do usuário.
        val results = m2.getJSONObject(2).getJSONArray("content")
        assertEquals(2, results.length())
        assertEquals("t1", results.getJSONObject(0).getString("tool_use_id"))
        assertFalse(results.getJSONObject(0).has("is_error"))
        assertTrue(results.getJSONObject(1).getBoolean("is_error"))
    }

    @Test
    fun `ferramenta desconhecida vira erro para o modelo`() = runTest {
        val api = FakeApi(
            listOf(JSONArray().put(toolUse("t1", "nao_existe")), JSONArray().put(text("Não deu."))),
            listOf("tool_use", "end_turn"),
        )
        JarvisAgent(api).runTurn("sys", emptyList(), "faz", emptyList())
        val results = JSONArray(api.requests[1].second).getJSONObject(2).getJSONArray("content")
        assertTrue(results.getJSONObject(0).getBoolean("is_error"))
    }

    @Test
    fun `recusa sem texto gera frase e historico valido`() = runTest {
        val api = FakeApi(listOf(JSONArray().put(thinking())), listOf("refusal"))
        val result = JarvisAgent(api).runTurn("sys", emptyList(), "x", emptyList())
        assertTrue(result.text.isNotBlank())
        val last = result.messages.last().getJSONArray("content").getJSONObject(0)
        assertEquals("text", last.getString("type"))
    }

    @Test
    fun `limite de etapas termina com resposta do assistente`() = runTest {
        val tool = FakeTool("pc_a", ToolResult.ok("ok"))
        val loop = (1..3).map { JSONArray().put(toolUse("t$it", "pc_a")) }
        val api = FakeApi(loop, List(3) { "tool_use" })
        val result = JarvisAgent(api, maxSteps = 3).runTurn("sys", emptyList(), "x", listOf(tool))
        assertEquals("assistant", result.messages.last().getString("role"))
        assertEquals(3, api.requests.size)
    }
}
