package dev.breno.jarvis.settings

import android.content.Context
import dev.breno.jarvis.BuildConfig

/** Configurações editáveis pela tela de Ajustes (antes ficavam só no local.properties). */
data class JarvisSettings(
    val apiKey: String,
    val model: String,
    val pcUrl: String,
    val pcToken: String,
)

/**
 * Guarda as configurações no SharedPreferences privado do app
 * (o backup do Android está desligado no manifest, então a chave não sai do aparelho).
 *
 * O local.properties continua valendo como valor inicial: o que vier dele é
 * usado até você salvar algo diferente em Ajustes.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    fun load() = JarvisSettings(
        apiKey = prefs.getString(KEY_API, null) ?: BuildConfig.CLAUDE_API_KEY,
        model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.CLAUDE_MODEL,
        pcUrl = prefs.getString(KEY_PC_URL, null) ?: BuildConfig.PC_AGENT_URL,
        pcToken = prefs.getString(KEY_PC_TOKEN, null) ?: BuildConfig.PC_AGENT_TOKEN,
    )

    fun save(s: JarvisSettings) {
        prefs.edit()
            .putString(KEY_API, s.apiKey.trim())
            .putString(KEY_MODEL, s.model.trim())
            .putString(KEY_PC_URL, s.pcUrl.trim())
            .putString(KEY_PC_TOKEN, s.pcToken.trim())
            .apply()
    }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_PC_URL = "pc_url"
        const val KEY_PC_TOKEN = "pc_token"
    }
}
