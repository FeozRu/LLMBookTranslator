package ru.sebyanin.booktranslator.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import ru.sebyanin.booktranslator.core.model.LlmProfile
import ru.sebyanin.booktranslator.core.storage.AppJson

/** ---------------- Сетевые DTO протокола Anthropic Messages ---------------- */

@Serializable
data class AnthropicRequest(
    val model: String,
    /** Anthropic, в отличие от OpenAI, требует max_tokens в каждом запросе. */
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val temperature: Double? = null,
    val messages: List<AnthropicMessage> = emptyList(),
    val tools: List<AnthropicTool>? = null,
)

/** Блоки контента (text/tool_use/tool_result) собираются вручную как JSON — без полиморфной десериализации. */
@Serializable
data class AnthropicMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

/**
 * Чистые преобразования доменных типов (OpenAI-форма, на которой живёт конвейер)
 * в протокол Anthropic Messages и обратно: system — top-level строкой, инструменты —
 * tool_use/tool_result-блоки, аргументы инструментов — JSON-объект, а не строка.
 * Без сети — тестируется напрямую.
 */
object AnthropicWire {

    const val API_VERSION = "2023-06-01"

    fun endpointUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/messages"

    fun buildRequest(profile: LlmProfile, messages: List<ApiMessage>, tools: List<ToolSpec>?): AnthropicRequest =
        AnthropicRequest(
            model = profile.model,
            maxTokens = profile.maxTokens,
            system = messages
                .filter { it.role == "system" }
                .mapNotNull { it.content }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .ifEmpty { null },
            temperature = profile.temperature,
            messages = toAnthropicMessages(messages),
            tools = tools?.map { AnthropicTool(it.name, it.description, it.parametersSchema) },
        )

    /**
     * ApiMessage → сообщения Anthropic. Сообщения tool становятся tool_result-блоками
     * в роли user; подряд идущие сообщения одной роли склеиваются в одно (протокол
     * требует чередования user/assistant — после tool-ответов AgentRunner досылает
     * ещё одно user-сообщение «лимит исчерпан»).
     */
    fun toAnthropicMessages(messages: List<ApiMessage>): List<AnthropicMessage> {
        val out = mutableListOf<AnthropicMessage>()
        for (m in messages) {
            if (m.role == "system") continue
            val role = if (m.role == "tool") "user" else m.role
            val blocks = mutableListOf<JsonObject>()
            when (m.role) {
                "tool" -> blocks += toolResultBlock(m.tool_call_id.orEmpty(), m.content.orEmpty())
                "assistant" -> {
                    m.content?.takeIf { it.isNotBlank() }?.let { blocks += textBlock(it) }
                    m.tool_calls.orEmpty().forEach { blocks += toolUseBlock(it) }
                }
                else -> m.content?.takeIf { it.isNotBlank() }?.let { blocks += textBlock(it) }
            }
            if (blocks.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.role == role) {
                out[out.size - 1] = AnthropicMessage(role, JsonArray(last.content.jsonArray + blocks))
            } else {
                out += AnthropicMessage(role, JsonArray(blocks))
            }
        }
        return out
    }

    /** Тело ответа → доменный LlmResponse. stop_reason=max_tokens — терминальная ошибка (как length у OpenAI). */
    fun parseResponse(body: String, profile: LlmProfile): LlmResponse {
        val root = AppJson.parseToJsonElement(body).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull == "error") {
            val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            throw LlmException("Ошибка ${profile.name}: ${message ?: body.take(300)}")
        }
        val texts = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        val rawToolCalls = mutableListOf<ApiToolCall>()
        for (block in root["content"]?.jsonArray.orEmpty()) {
            val obj = block.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> texts.append(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "tool_use" -> {
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    // Аргументы у Anthropic — готовый JSON-объект; конвейеру нужна строка
                    val args = (obj["input"] ?: JsonObject(emptyMap())).toString()
                    toolCalls += LlmToolCall(id, name, args)
                    rawToolCalls += ApiToolCall(id, "function", ApiToolCallFunction(name, args))
                }
            }
        }
        when (root["stop_reason"]?.jsonPrimitive?.contentOrNull) {
            "max_tokens" -> throw LlmException.terminal(
                "Ответ обрезан лимитом max_tokens=${profile.maxTokens} (stop_reason=max_tokens). " +
                    "Поднимите «Max tokens» в модели «${profile.name}» — reasoning-модели тратят квоту на рассуждения.",
            )
            "refusal" -> throw LlmException("Модель ${profile.name} отказалась выполнять запрос (stop_reason=refusal)")
        }
        val usage = root["usage"]?.jsonObject
        return LlmResponse(
            content = texts.toString(),
            toolCalls = toolCalls,
            rawToolCalls = rawToolCalls,
            promptTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull,
            completionTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull,
        )
    }

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun toolUseBlock(call: ApiToolCall): JsonObject = buildJsonObject {
        put("type", "tool_use")
        put("id", call.id)
        put("name", call.function.name.orEmpty())
        put(
            "input",
            runCatching { AppJson.parseToJsonElement(call.function.arguments.ifBlank { "{}" }) }
                .getOrElse { JsonObject(emptyMap()) },
        )
    }

    private fun toolResultBlock(toolCallId: String, content: String): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        put("content", content)
    }
}
