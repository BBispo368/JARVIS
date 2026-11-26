package dev.breno.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitterTest {
    @Test
    fun `solta frases completas conforme o texto chega`() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.push("São três da tar"))
        assertEquals(emptyList<String>(), s.push("de, senhor."))  // ainda sem espaço depois do ponto
        assertEquals(listOf("São três da tarde, senhor."), s.push(" Ainda dá tempo"))
        assertEquals(listOf("Ainda dá tempo de ser útil!"), s.push(" de ser útil! Ou n"))
        assertEquals("Ou não.", s.push("ão.").let { s.flush() })
    }

    @Test
    fun `nao corta numeros nem frases curtas demais`() {
        val s = SentenceSplitter()
        // O ponto de "1.500" não tem espaço depois: não é fim de frase.
        assertEquals(emptyList<String>(), s.push("Custa R$ 1.500 hoje"))
        assertEquals(listOf("Custa R$ 1.500 hoje."), s.push(". Sim. "))
        // "Sim." sozinho é curto demais: espera juntar com a próxima.
        assertEquals(listOf("Sim. Pode confiar, senhor."), s.push("Pode confiar, senhor. "))
        assertEquals("", s.flush())
    }
}
