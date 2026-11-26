package dev.breno.jarvis.voice

/**
 * Junta os pedaços de texto do streaming e solta frases completas, para o
 * TTS começar a falar a primeira frase enquanto o resto ainda chega.
 */
class SentenceSplitter(private val minLength: Int = 12) {
    private val buffer = StringBuilder()

    /** Adiciona texto e devolve as frases que ficaram completas. */
    fun push(text: String): List<String> {
        buffer.append(text)
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            val isEnd = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'
            // Só corta se vier espaço depois (evita "3.5", "R$ 1.000" e reticências pela metade).
            if (isEnd && i + 1 < buffer.length && buffer[i + 1].isWhitespace() && i + 1 - start >= minLength) {
                buffer.substring(start, i + 1).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                start = i + 1
            }
            i++
        }
        if (start > 0) buffer.delete(0, start)
        return out
    }

    /** Fim do streaming: devolve o que sobrou. */
    fun flush(): String {
        val rest = buffer.toString().trim()
        buffer.clear()
        return rest
    }
}
