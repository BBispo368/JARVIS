package dev.breno.jarvis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.breno.jarvis.api.ClaudeClient
import dev.breno.jarvis.model.ChatMessage
import dev.breno.jarvis.model.JarvisStatus
import dev.breno.jarvis.model.JarvisUiState
import dev.breno.jarvis.model.Role
import dev.breno.jarvis.voice.SpeechListener
import dev.breno.jarvis.voice.VoiceSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Orquestra o ciclo ouvir → pensar → falar.
 *
 * Todos os métodos públicos são chamados na main thread (UI ou callbacks
 * já repassados pra main), então o SpeechRecognizer é sempre usado na main.
 */
class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(JarvisUiState())
    val state: StateFlow<JarvisUiState> = _state.asStateFlow()

    private val claude = ClaudeClient(apiKey = BuildConfig.CLAUDE_API_KEY, model = BuildConfig.CLAUDE_MODEL)
    private var nextId = 0L
    private var requestJob: Job? = null

    private val listener = SpeechListener(application, object : SpeechListener.Callbacks {
        override fun onListeningStarted() {
            _state.update { it.copy(error = null) }
        }

        override fun onMicLevel(level: Float) {
            if (_state.value.status == JarvisStatus.LISTENING) _state.update { it.copy(micLevel = level) }
        }

        override fun onPartialResult(text: String) {
            if (_state.value.status == JarvisStatus.LISTENING) _state.update { it.copy(partialTranscript = text) }
        }

        override fun onFinalResult(text: String) {
            if (_state.value.status == JarvisStatus.LISTENING) sendToClaude(text)
        }

        override fun onNoSpeech() {
            // Silêncio: volta pro ocioso sem mensagem de erro.
            if (_state.value.status == JarvisStatus.LISTENING) goIdle()
        }

        override fun onError(message: String) {
            if (_state.value.status == JarvisStatus.LISTENING) goIdle(error = message)
        }
    })

    private val speaker = VoiceSpeaker(
        context = application,
        onFinished = ::onSpeechFinished,
        onError = { message ->
            if (_state.value.status == JarvisStatus.SPEAKING) goIdle(error = message)
            else _state.update { it.copy(error = message) }
        },
    )

    init {
        if (BuildConfig.CLAUDE_API_KEY.isBlank()) {
            _state.update { it.copy(error = MISSING_KEY_ERROR) }
        }
    }

    /** Toque no orb (a Activity só chama isso com a permissão de microfone concedida). */
    fun onOrbTapped() {
        when (_state.value.status) {
            JarvisStatus.IDLE -> startListening()
            JarvisStatus.LISTENING -> {
                listener.cancel()
                goIdle()
            }
            JarvisStatus.SPEAKING -> {
                speaker.stop()
                startListening()
            }
            JarvisStatus.THINKING -> Unit // aguardando a API
        }
    }

    fun onPermissionDenied() {
        goIdle(error = "Preciso da permissão do microfone para ouvir você.")
    }

    fun setConversationMode(enabled: Boolean) {
        _state.update { it.copy(conversationMode = enabled) }
    }

    private fun startListening() {
        if (BuildConfig.CLAUDE_API_KEY.isBlank()) {
            goIdle(error = MISSING_KEY_ERROR)
            return
        }
        if (!listener.isAvailable()) {
            goIdle(error = "Este aparelho não tem reconhecimento de voz disponível (instale/ative o app do Google).")
            return
        }
        _state.update {
            it.copy(status = JarvisStatus.LISTENING, partialTranscript = "", micLevel = 0f, error = null)
        }
        listener.start()
    }

    private fun sendToClaude(text: String) {
        val userMessage = ChatMessage(nextId++, Role.USER, text)
        _state.update {
            it.copy(
                status = JarvisStatus.THINKING,
                messages = it.messages + userMessage,
                partialTranscript = "",
                micLevel = 0f,
            )
        }

        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            try {
                val reply = claude.reply(systemPrompt(), historyWindow(_state.value.messages))
                val assistantMessage = ChatMessage(nextId++, Role.ASSISTANT, reply)
                _state.update { it.copy(status = JarvisStatus.SPEAKING, messages = it.messages + assistantMessage) }
                speaker.speak(reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Remove a pergunta que falhou pra manter a alternância user/assistant.
                _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == userMessage.id }) }
                goIdle(error = e.message ?: "Falha ao falar com o Claude.")
            }
        }
    }

    private fun onSpeechFinished() {
        if (_state.value.status != JarvisStatus.SPEAKING) return
        if (_state.value.conversationMode) startListening() else goIdle()
    }

    private fun goIdle(error: String? = null) {
        _state.update {
            it.copy(
                status = JarvisStatus.IDLE,
                partialTranscript = "",
                micLevel = 0f,
                error = error ?: it.error.takeIf { e -> e == MISSING_KEY_ERROR },
            )
        }
    }

    override fun onCleared() {
        requestJob?.cancel()
        listener.destroy()
        speaker.shutdown()
        super.onCleared()
    }

    companion object {
        private const val MAX_HISTORY = 20
        private const val MISSING_KEY_ERROR =
            "CLAUDE_API_KEY vazia. Coloque sua chave no local.properties e recompile o app."

        private const val SYSTEM_PROMPT =
            "Você é o Jarvis, assistente pessoal do Breno. Responda sempre em português do Brasil, " +
                "curto e direto (1 a 3 frases), pois a resposta será falada em voz alta. Nunca use markdown, " +
                "listas, emojis ou código. Tom inteligente, leal, com humor seco e leve ironia britânica; " +
                "chame-o de 'senhor' de vez em quando. Se não puder fazer algo, diga em uma frase."

        private val DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("pt-BR"))

        private fun systemPrompt(): String =
            "$SYSTEM_PROMPT\n\nData e hora atuais: ${LocalDateTime.now().format(DATE_FORMAT)}."

        /** Últimas ~20 mensagens, garantindo que a primeira seja do usuário. */
        internal fun historyWindow(messages: List<ChatMessage>): List<ChatMessage> =
            messages.takeLast(MAX_HISTORY).dropWhile { it.role != Role.USER }
    }
}
