package dev.breno.jarvis.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationHistoryTest {

    private fun user(t: String) = JSONObject().put("role", "user").put("content", t)
    private fun assistant(vararg blocks: JSONObject) = JSONObject().put("role", "assistant").put("content", JSONArray(blocks.toList()))
    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)
    private fun thinking() = JSONObject().put("type", "thinking").put("thinking", "").put("signature", "s")

    @Test
    fun `janela corta por turnos inteiros e sempre comeca com usuario`() {
        val h = ConversationHistory(maxMessages = 5)
        h.addTurn(listOf(user("1"), assistant(text("a"))))
        // turno com ferramenta: 4 mensagens
        h.addTurn(listOf(user("2"), assistant(text("t")), user("res"), assistant(text("b"))))
        h.addTurn(listOf(user("3"), assistant(text("c"))))

        val w = h.window()
        assertEquals(2, w.size) // o turno de 4 não cabe junto com o último (2+4 > 5)
        assertEquals("3", w[0].getString("content"))

        val h2 = ConversationHistory(maxMessages = 6)
        listOf("1", "2", "3").forEach { h2.addTurn(listOf(user(it), assistant(text(it)))) }
        assertEquals(6, h2.window().size)
        assertEquals("user", h2.window()[0].getString("role"))
    }

    @Test
    fun `turno maior que a janela ainda entra sozinho`() {
        val h = ConversationHistory(maxMessages = 2)
        h.addTurn(listOf(user("1"), assistant(text("t")), user("r"), assistant(text("b"))))
        assertEquals(4, h.window().size)
    }

    @Test
    fun `remove blocos de thinking ao guardar`() {
        val h = ConversationHistory()
        h.addTurn(listOf(user("1"), assistant(thinking(), text("a"))))
        val content = h.window()[1].getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
    }
}
