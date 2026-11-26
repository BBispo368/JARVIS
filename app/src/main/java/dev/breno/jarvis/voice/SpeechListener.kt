package dev.breno.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Envolve o [SpeechRecognizer] do Android (pt-BR, com resultados parciais).
 *
 * Tudo aqui DEVE rodar na main thread: o SpeechRecognizer exige isso, e os
 * callbacks do [RecognitionListener] também chegam na main.
 */
class SpeechListener(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onListeningStarted()
        fun onMicLevel(level: Float)
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        /** Usuário não falou nada (timeout / sem correspondência): não é um erro "de verdade". */
        fun onNoSpeech()
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null

    /** Retorna false se o aparelho não tem serviço de reconhecimento de voz. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        checkMainThread()
        // Recria a cada sessão: evita o recognizer ficar "preso" em ERROR_RECOGNIZER_BUSY.
        destroyRecognizer()
        val sr = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        sr.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        sr.startListening(intent)
    }

    /** Para de ouvir e descarta o que estava sendo captado. */
    fun cancel() {
        checkMainThread()
        recognizer?.cancel()
    }

    fun destroy() {
        checkMainThread()
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            it.setRecognitionListener(null)
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = callbacks.onListeningStarted()
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // O RMS costuma variar de ~-2 dB (silêncio) até ~10 dB (fala alta).
            callbacks.onMicLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { callbacks.onPartialResult(it) }
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            if (text.isNullOrBlank()) callbacks.onNoSpeech() else callbacks.onFinalResult(text)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                // ERROR_CLIENT aparece quando a escuta é cancelada; tratar como silêncio.
                SpeechRecognizer.ERROR_CLIENT -> callbacks.onNoSpeech()
                else -> callbacks.onError(errorMessage(error))
            }
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SpeechListener deve ser usado na main thread" }
    }

    companion object {
        private const val LANGUAGE = "pt-BR"

        fun errorMessage(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sem conexão para o reconhecimento de voz."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sem permissão para usar o microfone."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O reconhecimento de voz está ocupado. Tente de novo."
            SpeechRecognizer.ERROR_AUDIO -> "Erro ao gravar o áudio do microfone."
            SpeechRecognizer.ERROR_SERVER -> "O serviço de reconhecimento de voz falhou."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Português (Brasil) não está disponível no reconhecimento de voz."
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Muitas solicitações de reconhecimento. Aguarde um pouco."
            else -> "Erro no reconhecimento de voz (código $error)."
        }
    }
}
