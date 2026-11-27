package dev.breno.jarvis.api

import com.sun.net.httpserver.HttpServer
import dev.breno.jarvis.agent.JarvisAgent
import dev.breno.jarvis.agent.JarvisTool
import dev.breno.jarvis.agent.Schemas
import dev.breno.jarvis.agent.ToolResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class GeminiClientTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<JSONObject>()
    private val paths = mutableListOf<String>()
    private val keys = mutableListOf<String?>()
    /** Respostas roteirizadas: (status, corpo). */
    private val replies = ArrayDeque<Pair<Int, String>>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests += JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            paths += ex.requestURI.toString()
            keys += ex.requestHeaders.getFirst("x-goog-api-key")
            val (status, body) = replies.removeFirst()
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun client(fallbacks: List<String> = emptyList()) = GeminiClient(
        "chave-g", "gemini-3.8-flash",
        baseUrl = "http://127.0.0.1:${server.address.port}/v1beta",
        fallbackModels = fallbacks, retryDelayMs = 1,
    )
    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\r\n\r\n" }

    private class EchoTool : JarvisTool {
        override val name = "pc_open_app"
        override val description = "abre app"
        override val inputSchema = Schemas.obj("name" to Schemas.string("app", listOf("spotify")))
        override val label = "Abrindo"
        val inputs = mutableListOf<JSONObject>()
        override suspend fun execute(input: JSONObject): ToolResult { inputs += input; return ToolResult.ok("aberto") }
    }

    private class LockTool : JarvisTool {
        override val name = "pc_lock_screen"
        override val description = "bloqueia"
        override val inputSchema = Schemas.obj()
        override val label = "Bloqueando"
        override suspend fun execute(input: JSONObject) = ToolResult.ok("ok")
    }

    @Test
    fun `turno com ferramenta reenvia assinatura e functionResponse`() = runBlocking {
        replies += 200 to sse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Um momento."}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"pc_open_app","args":{"name":"spotify"},"id":"fc1"},"thoughtSignature":"SIG-1"}]},"finishReason":"STOP"}]}""",
        )
        replies += 200 to sse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Spotify aberto, "}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"senhor."}]},"finishReason":"STOP"}]}""",
        )
        val tool = EchoTool()
        val deltas = StringBuilder()
        val result = JarvisAgent(client()).runTurn(
            system = "sys", history = emptyList(), userText = "abre o spotify",
            tools = listOf(tool, LockTool()), onTextDelta = { deltas.append(it) },
        )

        assertEquals("Um momento. Spotify aberto, senhor.", result.text)
        assertEquals(deltas.toString(), result.text)
        assertEquals("spotify", tool.inputs.single().getString("name"))

        // Requisição 1: formato nativo correto.
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse", paths[0])
        assertEquals("chave-g", keys[0])
        val r1 = requests[0]
        assertEquals("sys", r1.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val decls = r1.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        val open = decls.getJSONObject(0)
        assertFalse(open.getJSONObject("parameters").has("additionalProperties")) // removido para o Gemini
        assertEquals("spotify", open.getJSONObject("parameters").getJSONObject("properties").getJSONObject("name").getJSONArray("enum").getString(0))
        assertFalse(decls.getJSONObject(1).has("parameters")) // sem argumentos: sem parameters
        assertEquals("low", r1.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))

        // Requisição 2: user, model (texto + functionCall com assinatura), user (functionResponse).
        val contents = requests[1].getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        val modelParts = contents.getJSONObject(1).getJSONArray("parts")
        val fcPart = (0 until modelParts.length()).map { modelParts.getJSONObject(it) }.first { it.has("functionCall") }
        assertEquals("SIG-1", fcPart.getString("thoughtSignature"))
        assertEquals("fc1", fcPart.getJSONObject("functionCall").getString("id"))
        val fr = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("user", contents.getJSONObject(2).getString("role"))
        assertEquals("pc_open_app", fr.getString("name"))
        assertEquals("fc1", fr.getString("id"))
        assertEquals("aberto", fr.getJSONObject("response").getString("result"))
    }

    @Test
    fun `429 vira mensagem de limite gratuito`() {
        replies += 429 to """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
        val e = assertThrows(ClaudeException::class.java) {
            runBlocking { client().createMessage("s", JSONArray(), null) {} }
        }
        assertTrue(e.message!!.contains("Limite gratuito"))
    }

    @Test
    fun `503 tenta de novo e depois troca de modelo`() = runBlocking {
        val overloaded = 503 to """{"error":{"code":503,"message":"This model is currently experiencing high demand.","status":"UNAVAILABLE"}}"""
        replies += overloaded // 3.8, 1ª tentativa
        replies += overloaded // 3.8, 2ª tentativa
        replies += 200 to sse("""{"candidates":[{"content":{"parts":[{"text":"Aqui, senhor."}]},"finishReason":"STOP"}]}""")
        val c = client(fallbacks = listOf("gemini-3.6-flash"))
        val r = c.createMessage("s", JSONArray().put(JSONObject().put("role", "user").put("content", "oi")), null) {}

        assertEquals("Aqui, senhor.", r.text)
        assertEquals(3, paths.size)
        assertTrue(paths[0].contains("gemini-3.8-flash"))
        assertTrue(paths[1].contains("gemini-3.8-flash"))
        assertTrue(paths[2].contains("gemini-3.6-flash"))
        assertEquals("gemini-3.6-flash", c.currentModel)

        // A próxima chamada já começa no modelo que funcionou.
        replies += 200 to sse("""{"candidates":[{"content":{"parts":[{"text":"De novo."}]},"finishReason":"STOP"}]}""")
        c.createMessage("s", JSONArray().put(JSONObject().put("role", "user").put("content", "oi")), null) {}
        assertTrue(paths[3].contains("gemini-3.6-flash"))
    }

    @Test
    fun `429 pula direto para o proximo modelo e sem reserva mostra aviso`() = runBlocking {
        val quota = 429 to """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
        replies += quota
        replies += 200 to sse("""{"candidates":[{"content":{"parts":[{"text":"Ok."}]},"finishReason":"STOP"}]}""")
        client(fallbacks = listOf("gemini-3.5-flash-lite"))
            .createMessage("s", JSONArray().put(JSONObject().put("role", "user").put("content", "oi")), null) {}
        assertTrue(paths[1].contains("gemini-3.5-flash-lite"))

        replies += 503 to """{"error":{"code":503,"message":"high demand","status":"UNAVAILABLE"}}"""
        replies += 503 to """{"error":{"code":503,"message":"high demand","status":"UNAVAILABLE"}}"""
        val e = assertThrows(ClaudeException::class.java) {
            runBlocking { client().createMessage("s", JSONArray(), null) {} }
        }
        assertTrue(e.message!!.contains("sobrecarregados"))
    }

    @Test
    fun `se o modelo recusar thinkingConfig tenta de novo sem ele`() = runBlocking {
        replies += 400 to """{"error":{"code":400,"message":"Thinking level is not supported for this model.","status":"INVALID_ARGUMENT"}}"""
        replies += 200 to sse("""{"candidates":[{"content":{"parts":[{"text":"Oi."}]},"finishReason":"STOP"}]}""")
        val r = client().createMessage("s", JSONArray().put(JSONObject().put("role", "user").put("content", "oi")), null) {}
        assertEquals("Oi.", r.text)
        assertFalse(requests[1].getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test
    fun `bloqueio de seguranca vira refusal`() {
        val acc = GeminiStreamAccumulator()
        acc.onChunk(JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals("refusal", acc.result().stopReason)
        val acc2 = GeminiStreamAccumulator()
        acc2.onChunk(JSONObject("""{"candidates":[{"content":{"parts":[{"text":"abc"}]},"finishReason":"MAX_TOKENS"}]}"""))
        assertEquals("max_tokens", acc2.result().stopReason)
    }

    @Test
    fun `chamadas paralelas viram varios tool_use e uma mensagem de respostas`() {
        val acc = GeminiStreamAccumulator()
        acc.onChunk(JSONObject("""{"candidates":[{"content":{"parts":[
            {"functionCall":{"name":"a","args":{}},"thoughtSignature":"S"},
            {"functionCall":{"name":"b","args":{"x":1}}}]}}]}"""))
        val r = acc.result()
        assertEquals("tool_use", r.stopReason)
        assertEquals(2, r.content.length())
        // Sem id do Gemini: gera um id interno, mas não manda id de volta.
        val contents = GeminiFormat.toContents(
            JSONArray()
                .put(JSONObject().put("role", "assistant").put("content", r.content))
                .put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "tool_result").put("tool_use_id", r.content.getJSONObject(0).getString("id")).put("content", "ok"))
                            .put(JSONObject().put("type", "tool_result").put("tool_use_id", r.content.getJSONObject(1).getString("id")).put("content", "falhou").put("is_error", true))
                    )
                )
        )
        val calls = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals("S", calls.getJSONObject(0).getString("thoughtSignature"))
        assertFalse(calls.getJSONObject(0).getJSONObject("functionCall").has("id"))
        val responses = contents.getJSONObject(1).getJSONArray("parts")
        assertEquals(2, responses.length())
        assertEquals("b", responses.getJSONObject(1).getJSONObject("functionResponse").getString("name"))
        assertEquals("falhou", responses.getJSONObject(1).getJSONObject("functionResponse").getJSONObject("response").getString("error"))
    }
}
