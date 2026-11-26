package dev.breno.jarvis.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamAccumulatorTest {

    private fun feed(acc: StreamAccumulator, vararg events: String): List<String> =
        events.mapNotNull { e -> val j = JSONObject(e); acc.onEvent(j.getString("type"), j) }

    @Test
    fun `remonta texto, thinking e tool_use`() {
        val acc = StreamAccumulator()
        val deltas = feed(
            acc,
            """{"type":"message_start","message":{"id":"m","content":[],"stop_reason":null}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Abrindo "}}""",
            """{"type":"ping"}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"agora."}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"t1","name":"pc_open_app","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"name\": \"spo"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"tify\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":10}}""",
            """{"type":"message_stop"}""",
        )
        assertEquals(listOf("Abrindo ", "agora."), deltas)

        val r = acc.result()
        assertEquals("tool_use", r.stopReason)
        assertEquals("Abrindo agora.", r.text)
        assertEquals("hmm", r.content.getJSONObject(0).getString("thinking"))
        assertEquals("SIG", r.content.getJSONObject(0).getString("signature"))
        assertEquals("spotify", r.content.getJSONObject(2).getJSONObject("input").getString("name"))
    }

    @Test
    fun `tool_use sem argumentos vira objeto vazio`() {
        val acc = StreamAccumulator()
        feed(
            acc,
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"pc_lock_screen","input":{}}}""",
            """{"type":"content_block_stop","index":0}""",
        )
        assertEquals(0, acc.result().content.getJSONObject(0).getJSONObject("input").length())
    }

    @Test
    fun `evento de erro vira excecao com a mensagem`() {
        val acc = StreamAccumulator()
        val e = assertThrows(ClaudeException::class.java) {
            feed(acc, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        }
        assertEquals(true, e.message!!.contains("Overloaded"))
    }
}
