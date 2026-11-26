package dev.breno.jarvis.settings

import android.content.Context
import dev.breno.jarvis.BuildConfig

/** Qual IA responde. */
enum class Provider(val id: String, val label: String) {
    GEMINI("gemini", "Gemini (grátis)"),
    CLAUDE("claude", "Claude"),
    ;

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

/** Configurações editáveis pela tela de Ajustes (antes ficavam só no local.properties). */
data class JarvisSettings(
    val provider: Provider = Provider.GEMINI,
    val geminiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val apiKey: String = "",
    val model: String = "",
    /** Endereço do PC na rede de casa (a busca na rede atualiza sozinha). */
    val pcUrl: String = "",
    /** Endereço do PC fora de casa (ex.: Tailscale). Nunca é sobrescrito pela busca. */
    val pcRemoteUrl: String = "",
    val pcToken: String = "",
) {
    /** Chave do provedor escolhido. */
    val activeKey: String get() = if (provider == Provider.GEMINI) geminiKey else apiKey

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
    }
}

/**
 * Guarda as configurações no SharedPreferences privado do app
 * (o backup do Android está desligado no manifest, então as chaves não saem do aparelho).
 *
 * O local.properties continua valendo como valor inicial: o que vier dele é
 * usado até você salvar algo diferente em Ajustes.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    fun load() = JarvisSettings(
        provider = Provider.from(prefs.getString(KEY_PROVIDER, null) ?: BuildConfig.JARVIS_PROVIDER),
        geminiKey = prefs.getString(KEY_GEMINI, null) ?: BuildConfig.GEMINI_API_KEY,
        geminiModel = prefs.getString(KEY_GEMINI_MODEL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_MODEL,
        apiKey = prefs.getString(KEY_API, null) ?: BuildConfig.CLAUDE_API_KEY,
        model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.CLAUDE_MODEL,
        pcUrl = prefs.getString(KEY_PC_URL, null) ?: BuildConfig.PC_AGENT_URL,
        pcRemoteUrl = prefs.getString(KEY_PC_REMOTE, null) ?: BuildConfig.PC_AGENT_REMOTE_URL,
        pcToken = prefs.getString(KEY_PC_TOKEN, null) ?: BuildConfig.PC_AGENT_TOKEN,
    )

    fun save(s: JarvisSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, s.provider.id)
            .putString(KEY_GEMINI, s.geminiKey.trim())
            .putString(KEY_GEMINI_MODEL, s.geminiModel.trim())
            .putString(KEY_API, s.apiKey.trim())
            .putString(KEY_MODEL, s.model.trim())
            .putString(KEY_PC_URL, s.pcUrl.trim())
            .putString(KEY_PC_REMOTE, s.pcRemoteUrl.trim())
            .putString(KEY_PC_TOKEN, s.pcToken.trim())
            .apply()
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_GEMINI = "gemini_key"
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_PC_URL = "pc_url"
        const val KEY_PC_REMOTE = "pc_remote_url"
        const val KEY_PC_TOKEN = "pc_token"
    }
}
