package dev.breno.jarvis.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** Testa o laço SSE real do ClaudeClient contra um servidor HTTP local. */
class ClaudeClientStreamTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var body = ""
    private var lastRequest: JSONObject? = null
    private val headers = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { ex ->
            lastRequest = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            ex.requestHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.first() }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun client() = ClaudeClient("chave-teste", "claude-sonnet-5-5", apiUrl = "http://127.0.0.1:${server.address.port}/v1/messages")

    private fun sse(vararg events: String) = events.joinToString("") { e ->
        "event: ${JSONObject(e).getString("type")}\ndata: $e\n\n"
    }

    @Test
    fun `le o stream, entrega deltas e monta a resposta`() = runBlocking {
        body = sse(
            """{"type":"message_start","message":{"id":"m","content":[]}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Bom dia, "}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"senhor."}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        )
        val deltas = mutableListOf<String>()
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "oi"))
        val r = client().createMessage("sys", msgs, null) { deltas += it }

        assertEquals(listOf("Bom dia, ", "senhor."), deltas)
        assertEquals("Bom dia, senhor.", r.text)
        assertEquals("end_turn", r.stopReason)

        val req = lastRequest!!
        assertEquals(true, req.getBoolean("stream"))
        assertEquals("claude-sonnet-5-5", req.getString("model"))
        assertEquals("adaptive", req.getJSONObject("thinking").getString("type"))
        assertEquals("chave-teste", headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        assertTrue(headers["anthropic-beta"]!!.contains("thinking-binding-controls-2026-08-01"))
    }

    @Test
    fun `erro HTTP mostra error message da API`() {
        status = 401
        body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val e = assertThrows(ClaudeException::class.java) {
            runBlocking { client().createMessage("sys", JSONArray(), null) {} }
        }
        assertEquals("Erro da API (401): invalid x-api-key", e.message)
    }
}
