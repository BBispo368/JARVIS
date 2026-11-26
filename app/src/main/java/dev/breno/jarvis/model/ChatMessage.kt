package dev.breno.jarvis.model

import dev.breno.jarvis.settings.JarvisSettings

/** Papel da mensagem, com o nome exato usado pela Messages API. */
enum class Role(val apiName: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
)

/** Estados do assistente — cada um tem uma cor de orb e um texto de status. */
enum class JarvisStatus {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
}

/** Conexão com o agente do PC. */
enum class PcStatus {
    NOT_CONFIGURED,
    CHECKING,
    ONLINE,
    OFFLINE,
}

/** Resultado da busca pelo PC na tela de Ajustes. */
data class DiscoveryState(
    val searching: Boolean = false,
    val message: String? = null,
    /** URL encontrada, para preencher o campo na tela de Ajustes. */
    val foundUrl: String? = null,
)

data class JarvisUiState(
    val status: JarvisStatus = JarvisStatus.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    /** Transcrição parcial enquanto o usuário fala. */
    val partialTranscript: String = "",
    /** Nível do microfone normalizado em 0..1 (vem do onRmsChanged). */
    val micLevel: Float = 0f,
    val conversationMode: Boolean = true,
    val error: String? = null,
    /** O que o agente está fazendo agora ("Criando timer"...), durante o estado THINKING. */
    val activity: String? = null,
    val pcStatus: PcStatus = PcStatus.NOT_CONFIGURED,
    val pcToolCount: Int = 0,
    val settings: JarvisSettings = JarvisSettings(),
    val showSettings: Boolean = false,
    val discovery: DiscoveryState = DiscoveryState(),
)
