package dev.breno.jarvis.pc

import dev.breno.jarvis.agent.JarvisTool
import dev.breno.jarvis.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fala com o agente do PC (pc-agent/jarvis_agent.py) na rede local.
 *
 * O PC anuncia as próprias ferramentas em GET /tools, então dá pra adicionar
 * apps e atalhos no config.json do PC sem recompilar o app.
 */
class PcAgentClient(
    baseUrl: String,
    private val token: String,
) {
    private val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()

    val isConfigured: Boolean get() = base != null && token.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Busca as ferramentas do PC. Lança [IOException] se o PC estiver offline ou recusar. */
    suspend fun fetchTools(): List<JarvisTool> = withContext(Dispatchers.IO) {
        val json = call(request("tools").get().build())
        val arr = json.optJSONArray("tools") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            RemoteTool(
                name = t.getString("name"),
                description = t.optString("description"),
                inputSchema = t.optJSONObject("input_schema") ?: JSONObject().put("type", "object"),
                label = t.optString("label").ifBlank { "Executando no PC" },
                client = this@PcAgentClient,
            )
        }
    }

    internal suspend fun runTool(name: String, input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("input", input).toString().toRequestBody(JSON)
        val json = try {
            call(request("tools/$name").post(body).build())
        } catch (e: IOException) {
            return@withContext ToolResult.error("PC inacessível: ${e.message ?: "sem resposta"}")
        }
        if (json.optBoolean("ok")) ToolResult.ok(json.optString("result"))
        else ToolResult.error(json.optString("error").ifBlank { "Erro desconhecido no PC." })
    }

    private fun request(path: String): Request.Builder {
        val url = base?.newBuilder()?.addPathSegments(path)?.build()
            ?: throw IOException("PC_AGENT_URL inválida")
        return Request.Builder().url(url).header("Authorization", "Bearer $token")
    }

    private fun call(request: Request): JSONObject =
        http.newCall(request).execute().use { resp ->
            val raw = resp.body.string()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            when {
                resp.code == 401 -> throw IOException("token recusado pelo PC (confira PC_AGENT_TOKEN)")
                json == null -> throw IOException("resposta inválida do PC (HTTP ${resp.code})")
                // Erros de ferramenta vêm com {"ok": false, "error": ...}; devolve pro chamador.
                else -> json
            }
        }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

/** Ferramenta anunciada pelo PC; executar = POST /tools/{name}. */
class RemoteTool(
    override val name: String,
    override val description: String,
    override val inputSchema: JSONObject,
    override val label: String,
    private val client: PcAgentClient,
) : JarvisTool {
    override suspend fun execute(input: JSONObject): ToolResult = client.runTool(name, input)
}
