package dev.breno.jarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Envolve o [TextToSpeech] em pt-BR.
 *
 * Os callbacks do [UtteranceProgressListener] chegam numa thread interna do
 * TTS; aqui eles são repassados pra main via [Handler] — assim quem recebe
 * [onFinished] pode reiniciar o SpeechRecognizer com segurança.
 */
class VoiceSpeaker(
    context: Context,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false
    private var pendingText: String? = null
    private var utteranceCounter = 0
    /** Só o enunciado atual dispara callbacks; um stop() invalida o anterior. */
    private var currentUtteranceId: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        mainHandler.post { onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onError("Não foi possível iniciar a voz (TextToSpeech).")
            return
        }
        val result = tts.setLanguage(Locale.forLanguageTag("pt-BR"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            onError("Voz em português (Brasil) não instalada no motor de TTS.")
        }
        tts.setOnUtteranceProgressListener(progressListener)
        ready = true
        pendingText?.let { pendingText = null; speak(it) }
    }

    fun speak(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) {
            onFinished()
            return
        }
        if (!ready) {
            pendingText = clean
            return
        }
        val id = "jarvis-${++utteranceCounter}"
        currentUtteranceId = id
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Interrompe a fala sem disparar [onFinished]. */
    fun stop() {
        currentUtteranceId = null
        pendingText = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        tts.shutdown()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                if (utteranceId != null && utteranceId == currentUtteranceId) {
                    currentUtteranceId = null
                    onFinished()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = handleError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String?, errorCode: Int) = handleError(utteranceId, errorCode)

        private fun handleError(utteranceId: String?, errorCode: Int) {
            mainHandler.post {
                if (utteranceId != null && utteranceId == currentUtteranceId) {
                    currentUtteranceId = null
                    this@VoiceSpeaker.onError("Erro ao falar a resposta (código $errorCode).")
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
