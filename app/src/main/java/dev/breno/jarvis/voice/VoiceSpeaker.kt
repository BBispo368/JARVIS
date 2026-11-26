package dev.breno.jarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Envolve o [TextToSpeech] em pt-BR, com uma fila de frases por "sessão":
 *
 *     begin() → enqueue(frase)… → end()  ⇒  onFinished() quando a última frase acabar
 *
 * Assim o Jarvis fala a primeira frase enquanto a resposta ainda chega pelo
 * streaming. Os callbacks do [UtteranceProgressListener] chegam numa thread
 * interna do TTS e são repassados pra main via [Handler] — assim quem recebe
 * [onFinished] pode reiniciar o SpeechRecognizer com segurança.
 *
 * Todos os métodos públicos devem ser chamados na main thread.
 */
class VoiceSpeaker(
    context: Context,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false
    /** TTS falhou ao iniciar: as falas são puladas, mas o ciclo continua (sem travar em "Falando"). */
    private var broken = false
    private var utteranceCounter = 0

    private var sessionActive = false
    private var sessionEnded = false
    /** Frases recebidas antes do TTS terminar de iniciar. */
    private val waiting = ArrayDeque<String>()
    /** Frases já entregues ao TTS e ainda não terminadas. */
    private val outstanding = mutableSetOf<String>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        mainHandler.post { onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            broken = true
            waiting.clear()
            onError("Não foi possível iniciar a voz (TextToSpeech).")
            checkDone()
            return
        }
        val result = tts.setLanguage(Locale.forLanguageTag("pt-BR"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            onError("Voz em português (Brasil) não instalada no motor de TTS.")
        }
        tts.setOnUtteranceProgressListener(progressListener)
        ready = true
        while (waiting.isNotEmpty()) submit(waiting.removeFirst())
        checkDone()
    }

    /** Começa uma nova fala (interrompe a anterior sem disparar [onFinished]). */
    fun begin() {
        stop()
        sessionActive = true
        sessionEnded = false
    }

    /** Põe uma frase na fila da sessão atual. */
    fun enqueue(text: String) {
        if (!sessionActive || sessionEnded) return
        val clean = cleanForSpeech(text)
        if (clean.isBlank() || broken) return
        if (ready) submit(clean) else waiting.addLast(clean)
    }

    /** Não virão mais frases: [onFinished] dispara quando a fila esvaziar. */
    fun end() {
        if (!sessionActive) return
        sessionEnded = true
        checkDone()
    }

    /** Interrompe a fala sem disparar [onFinished]. */
    fun stop() {
        sessionActive = false
        sessionEnded = false
        waiting.clear()
        outstanding.clear()
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        tts.shutdown()
    }

    private fun submit(text: String) {
        val id = "jarvis-${++utteranceCounter}"
        outstanding += id
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun checkDone() {
        if (sessionActive && sessionEnded && outstanding.isEmpty() && waiting.isEmpty() && (ready || broken)) {
            sessionActive = false
            onFinished()
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                if (utteranceId != null && outstanding.remove(utteranceId)) checkDone()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = handleError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String?, errorCode: Int) = handleError(utteranceId, errorCode)

        private fun handleError(utteranceId: String?, errorCode: Int) {
            mainHandler.post {
                // Pula a frase com erro e segue a fila; o ciclo não pode travar.
                if (utteranceId != null && outstanding.remove(utteranceId)) {
                    this@VoiceSpeaker.onError("Erro ao falar a resposta (código $errorCode).")
                    checkDone()
                }
            }
        }
    }

    companion object {
        private val MARKDOWN_CHARS = Regex("[*#`_]")
        private val SPACES = Regex("\\s+")

        /** Remove caracteres de markdown que o TTS leria em voz alta. */
        fun cleanForSpeech(text: String): String =
            text.replace(MARKDOWN_CHARS, "").replace(SPACES, " ").trim()
    }
}
