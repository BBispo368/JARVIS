package dev.breno.jarvis.agent

import org.json.JSONObject

/** Resultado de uma ferramenta; vira um bloco tool_result para o Claude. */
data class ToolResult(val content: String, val isError: Boolean = false) {
    companion object {
        fun ok(content: String) = ToolResult(content)
        fun error(content: String) = ToolResult(content, isError = true)
    }
}

/**
 * Uma ferramenta que o Claude pode chamar. Implementada no celular
 * (dev.breno.jarvis.tools) ou no PC (dev.breno.jarvis.pc.RemoteTool).
 */
interface JarvisTool {
    /** Nome usado pela API (^[a-zA-Z0-9_-]{1,64}$). */
    val name: String
    val description: String
    /** JSON Schema do input (type: object). */
    val inputSchema: JSONObject
    /** Texto curto em português mostrado na tela enquanto a ferramenta roda. */
    val label: String

    suspend fun execute(input: JSONObject): ToolResult

    fun toApiJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("input_schema", inputSchema)
}

/** Ajuda a montar schemas sem repetir boilerplate. */
object Schemas {
    fun obj(vararg properties: Pair<String, JSONObject>): JSONObject {
        val props = JSONObject()
        properties.forEach { (k, v) -> props.put(k, v) }
        return JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", org.json.JSONArray(properties.map { it.first }))
            .put("additionalProperties", false)
    }

    fun string(description: String, enum: List<String>? = null): JSONObject = JSONObject()
        .put("type", "string")
        .put("description", description)
        .apply { if (enum != null) put("enum", org.json.JSONArray(enum)) }

    fun integer(description: String): JSONObject = JSONObject()
        .put("type", "integer")
        .put("description", description)

    fun boolean(description: String): JSONObject = JSONObject()
        .put("type", "boolean")
        .put("description", description)
}
