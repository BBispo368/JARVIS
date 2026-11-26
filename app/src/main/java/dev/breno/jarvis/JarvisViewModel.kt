package dev.breno.jarvis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.breno.jarvis.agent.ConversationHistory
import dev.breno.jarvis.agent.JarvisAgent
import dev.breno.jarvis.agent.JarvisTool
import dev.breno.jarvis.api.ClaudeClient
import dev.breno.jarvis.model.ChatMessage
import dev.breno.jarvis.model.DiscoveryState
import dev.breno.jarvis.model.JarvisStatus
import dev.breno.jarvis.model.JarvisUiState
import dev.breno.jarvis.model.PcStatus
import dev.breno.jarvis.model.Role
import dev.breno.jarvis.pc.PcAgentClient
import dev.breno.jarvis.pc.PcDiscovery
import dev.breno.jarvis.settings.JarvisSettings
import dev.breno.jarvis.settings.SettingsStore
import dev.breno.jarvis.tools.PhoneTools
import dev.breno.jarvis.voice.SentenceSplitter
import dev.breno.jarvis.voice.SpeechListener
import dev.breno.jarvis.voice.VoiceSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val settingsStore = SettingsStore(application)
    private val _state = MutableStateFlow(JarvisUiState(settings = settingsStore.load()))
    val state: StateFlow<JarvisUiState> = _state.asStateFlow()
    private val settings: JarvisSettings get() = _state.value.settings

    private var agent = newAgent(settings)
    private var pc = PcAgentClient(settings.pcUrl, settings.pcToken)
    private val history = ConversationHistory(maxMessages = MAX_HISTORY)
    private val phoneTools: List<JarvisTool> = PhoneTools.all(application)

    private var pcTools: List<JarvisTool> = emptyList()
    private var pcJob: Job? = null
    private var discoveryJob: Job? = null

    private var nextId = 0L
    private var requestJob: Job? = null
    /** Mensagens (usuário + resposta parcial) do turno em andamento, para limpar se ele for abortado. */
    private var turnMessageIds = emptySet<Long>()

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
        onError = { message -> _state.update { it.copy(error = message) } },
    )

    init {
        if (settings.apiKey.isBlank()) {
            _state.update { it.copy(error = MISSING_KEY_ERROR, showSettings = true) }
        }
        refreshPcTools()
    }

    // ---------------------------------------------------------------- orb

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
                abortTurnIfRunning()
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

    // ----------------------------------------------------------- ajustes

    fun openSettings() {
        if (_state.value.status == JarvisStatus.LISTENING) listener.cancel()
        if (_state.value.status == JarvisStatus.SPEAKING) speaker.stop()
        abortTurnIfRunning()
        goIdle()
        _state.update { it.copy(showSettings = true, discovery = DiscoveryState()) }
    }

    fun closeSettings() {
        discoveryJob?.cancel()
        _state.update { it.copy(showSettings = false, discovery = DiscoveryState()) }
    }

    fun saveSettings(new: JarvisSettings) {
        val clean = new.copy(
            apiKey = new.apiKey.trim(),
            model = new.model.trim().ifBlank { BuildConfig.CLAUDE_MODEL },
            pcUrl = new.pcUrl.trim().trimEnd('/'),
            pcToken = new.pcToken.trim(),
        )
        settingsStore.save(clean)
        agent = newAgent(clean)
        pc = PcAgentClient(clean.pcUrl, clean.pcToken)
        pcTools = emptyList()
        pcJob?.cancel()
        _state.update {
            it.copy(
                settings = clean,
                error = if (clean.apiKey.isBlank()) MISSING_KEY_ERROR else null,
            )
        }
        closeSettings()
        refreshPcTools()
    }

    /** Botão "Procurar PC na rede" da tela de Ajustes. */
    fun discoverPc() {
        if (discoveryJob?.isActive == true) return
        _state.update { it.copy(discovery = DiscoveryState(searching = true, message = "Procurando…")) }
        discoveryJob = viewModelScope.launch {
            val found = try {
                PcDiscovery.discover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update {
                it.copy(
                    discovery = if (found.isEmpty()) {
                        DiscoveryState(message = "Nenhum PC encontrado. O jarvis_agent.py está rodando na mesma rede?")
                    } else {
                        val pc = found.first()
                        val extra = if (found.size > 1) " (e mais ${found.size - 1})" else ""
                        DiscoveryState(message = "Encontrei ${pc.name} em ${pc.url}$extra", foundUrl = pc.url)
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------ PC

    /**
     * Busca (de novo) as ferramentas do PC. Se o endereço salvo não responder,
     * procura o PC na rede e, achando, salva o endereço novo sozinho.
     */
    fun refreshPcTools() {
        if (settings.pcToken.isBlank()) {
            pcTools = emptyList()
            _state.update { it.copy(pcStatus = PcStatus.NOT_CONFIGURED, pcToolCount = 0) }
            return
        }
        if (pcJob?.isActive == true) return
        if (pcTools.isEmpty()) _state.update { it.copy(pcStatus = PcStatus.CHECKING) }
        pcJob = viewModelScope.launch {
            var tools = if (pc.isConfigured) tryFetch(pc) else null
            if (tools == null) {
                for (found in runCatching { PcDiscovery.discover() }.getOrDefault(emptyList())) {
                    if (found.url == settings.pcUrl) continue
                    val candidate = PcAgentClient(found.url, settings.pcToken)
                    tools = tryFetch(candidate)
                    if (tools != null) {
                        pc = candidate
                        val updated = settings.copy(pcUrl = found.url)
                        settingsStore.save(updated)
                        _state.update { it.copy(settings = updated) }
                        break
                    }
                }
            }
            pcTools = tools.orEmpty()
            _state.update {
                it.copy(pcStatus = if (tools != null) PcStatus.ONLINE else PcStatus.OFFLINE, pcToolCount = pcTools.size)
            }
        }
    }

    private suspend fun tryFetch(client: PcAgentClient): List<JarvisTool>? = try {
        client.fetchTools()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // --------------------------------------------------------------- turno

    private fun startListening() {
        if (settings.apiKey.isBlank()) {
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
        val replyId = nextId++
        turnMessageIds = setOf(userMessage.id, replyId)
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
        val currentAgent = agent
        val splitter = SentenceSplitter()
        val streamed = StringBuilder()
        speaker.begin()

        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            try {
                val result = currentAgent.runTurn(
                    system = systemPrompt(pcOnline),
                    history = history.window(),
                    userText = text,
                    tools = tools,
                    onTextDelta = { delta ->
                        withContext(Dispatchers.Main) {
                            streamed.append(delta)
                            showReply(replyId, streamed.toString())
                            splitter.push(delta).forEach(::speakSentence)
                        }
                    },
                    onToolStart = { tool -> _state.update { it.copy(activity = tool.label) } },
                )
                history.addTurn(result.messages)
                showReply(replyId, result.text)
                splitter.flush().takeIf { it.isNotBlank() }?.let(::speakSentence)
                if (result.unstreamedTail.isNotBlank()) speakSentence(result.unstreamedTail)
                turnMessageIds = emptySet()
                // Se nada foi falado (texto vazio), o end() já dispara onFinished.
                if (_state.value.status == JarvisStatus.THINKING) {
                    _state.update { it.copy(status = JarvisStatus.SPEAKING) }
                }
                speaker.end()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speaker.stop()
                removeTurnMessages()
                goIdle(error = e.message ?: "Falha ao falar com o Claude.")
            } finally {
                refreshPcTools()
            }
        }
    }

    /** Fala uma frase e, na primeira, passa o estado para SPEAKING. */
    private fun speakSentence(sentence: String) {
        speaker.enqueue(sentence)
        _state.update { it.copy(status = JarvisStatus.SPEAKING, activity = null) }
    }

    /** Cria ou atualiza a bolha de resposta do turno atual. */
    private fun showReply(id: Long, text: String) {
        if (text.isBlank()) return
        _state.update { s ->
            val exists = s.messages.any { it.id == id }
            val messages = if (exists) s.messages.map { if (it.id == id) it.copy(text = text) else it }
            else s.messages + ChatMessage(id, Role.ASSISTANT, text)
            s.copy(messages = messages)
        }
    }

    /** Toque no meio da resposta: cancela a chamada e descarta o turno (não entra no histórico). */
    private fun abortTurnIfRunning() {
        if (requestJob?.isActive == true) {
            requestJob?.cancel()
            removeTurnMessages()
        }
    }

    private fun removeTurnMessages() {
        val ids = turnMessageIds
        turnMessageIds = emptySet()
        if (ids.isNotEmpty()) _state.update { s -> s.copy(messages = s.messages.filterNot { it.id in ids }) }
    }

    private fun onSpeechFinished() {
        if (_state.value.status != JarvisStatus.SPEAKING) return
        if (_state.value.conversationMode && !_state.value.showSettings) startListening() else goIdle()
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
        discoveryJob?.cancel()
        listener.destroy()
        speaker.shutdown()
        super.onCleared()
    }

    companion object {
        private const val MAX_HISTORY = 20
        private const val MISSING_KEY_ERROR = "Chave da API vazia. Toque em Ajustes e cole sua chave."

        private fun newAgent(s: JarvisSettings) = JarvisAgent(ClaudeClient(apiKey = s.apiKey, model = s.model))

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
