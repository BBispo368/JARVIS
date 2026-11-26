package dev.breno.jarvis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.breno.jarvis.agent.ConversationHistory
import dev.breno.jarvis.agent.JarvisAgent
import dev.breno.jarvis.agent.JarvisTool
import dev.breno.jarvis.api.ClaudeClient
import dev.breno.jarvis.model.ChatMessage
import dev.breno.jarvis.model.JarvisStatus
import dev.breno.jarvis.model.JarvisUiState
import dev.breno.jarvis.model.PcStatus
import dev.breno.jarvis.model.Role
import dev.breno.jarvis.pc.PcAgentClient
import dev.breno.jarvis.tools.PhoneTools
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
 * Orquestra o ciclo ouvir → pensar (agente + ferramentas) → falar.
 *
 * Todos os métodos públicos são chamados na main thread (UI ou callbacks
 * já repassados pra main), então o SpeechRecognizer é sempre usado na main.
 */
class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(JarvisUiState())
    val state: StateFlow<JarvisUiState> = _state.asStateFlow()

    private val agent = JarvisAgent(ClaudeClient(apiKey = BuildConfig.CLAUDE_API_KEY, model = BuildConfig.CLAUDE_MODEL))
    private val history = ConversationHistory(maxMessages = MAX_HISTORY)
    private val phoneTools: List<JarvisTool> = PhoneTools.all(application)

    private val pc = PcAgentClient(BuildConfig.PC_AGENT_URL, BuildConfig.PC_AGENT_TOKEN)
    private var pcTools: List<JarvisTool> = emptyList()
    private var pcJob: Job? = null

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
        refreshPcTools()
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
            JarvisStatus.THINKING -> Unit // aguardando a API / ferramentas
        }
    }

    fun onPermissionDenied() {
        goIdle(error = "Preciso da permissão do microfone para ouvir você.")
    }

    fun setConversationMode(enabled: Boolean) {
        _state.update { it.copy(conversationMode = enabled) }
    }

    /** Busca (de novo) as ferramentas do PC. Chamado ao abrir, após cada turno e ao tocar no indicador. */
    fun refreshPcTools() {
        if (!pc.isConfigured) {
            _state.update { it.copy(pcStatus = PcStatus.NOT_CONFIGURED, pcToolCount = 0) }
            return
        }
        if (pcJob?.isActive == true) return
        if (pcTools.isEmpty()) _state.update { it.copy(pcStatus = PcStatus.CHECKING) }
        pcJob = viewModelScope.launch {
            pcTools = try {
                pc.fetchTools()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val online = pcTools.isNotEmpty()
            _state.update {
                it.copy(pcStatus = if (online) PcStatus.ONLINE else PcStatus.OFFLINE, pcToolCount = pcTools.size)
            }
        }
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
                activity = null,
            )
        }

        // Fotografia das ferramentas: o conjunto não muda durante o turno.
        val tools = phoneTools + pcTools
        val pcOnline = pcTools.isNotEmpty()

        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            try {
                val result = agent.runTurn(
                    system = systemPrompt(pcOnline),
                    history = history.window(),
                    userText = text,
                    tools = tools,
                    onToolStart = { tool -> _state.update { it.copy(activity = tool.label) } },
                )
                history.addTurn(result.messages)
                val assistantMessage = ChatMessage(nextId++, Role.ASSISTANT, result.text)
                _state.update {
                    it.copy(status = JarvisStatus.SPEAKING, messages = it.messages + assistantMessage, activity = null)
                }
                speaker.speak(result.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // O turno não entra no histórico; some da tela também, pra manter tudo coerente.
                _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == userMessage.id }) }
                goIdle(error = e.message ?: "Falha ao falar com o Claude.")
            } finally {
                refreshPcTools()
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
                activity = null,
                error = error ?: it.error.takeIf { e -> e == MISSING_KEY_ERROR },
            )
        }
    }

    override fun onCleared() {
        requestJob?.cancel()
        pcJob?.cancel()
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

        private const val TOOLS_PROMPT =
            "Você tem ferramentas para agir no celular (prefixo phone_) e no computador do Breno (prefixo pc_). " +
                "Quando ele pedir uma ação, use a ferramenta certa sem pedir permissão e depois confirme em uma " +
                "frase curta o que fez. Se uma ferramenta falhar, diga o motivo em uma frase. Antes de algo " +
                "irreversível, como desligar ou reiniciar o computador, pergunte se ele tem certeza."

        private val DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("pt-BR"))

        private fun systemPrompt(pcOnline: Boolean): String {
            val pcLine = if (pcOnline) "O computador do Breno está conectado."
            else "O computador do Breno está desconectado agora; as ferramentas pc_ não estão disponíveis."
            return "$SYSTEM_PROMPT\n\n$TOOLS_PROMPT $pcLine\n\n" +
                "Data e hora atuais: ${LocalDateTime.now().format(DATE_FORMAT)}."
        }
    }
}
