package dev.breno.jarvis.model

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

data class JarvisUiState(
    val status: JarvisStatus = JarvisStatus.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    /** Transcrição parcial enquanto o usuário fala. */
    val partialTranscript: String = "",
    /** Nível do microfone normalizado em 0..1 (vem do onRmsChanged). */
    val micLevel: Float = 0f,
    val conversationMode: Boolean = true,
    val error: String? = null,
)
