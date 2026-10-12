package ru.sebyanin.booktranslator.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** ---------------- Доменные типы (то, чем живёт конвейер) ---------------- */

data class LlmToolCall(
    val id: String,
    val name: String,
    /** JSON-строка аргументов — парсит исполнитель инструмента. */
    val argumentsJson: String,
)

data class LlmResponse(
    val content: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
    /** Сырые tool_calls ответа — нужны, чтобы дослать их обратно в сообщении assistant. */
    val rawToolCalls: List<ApiToolCall> = emptyList(),
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}

/** Спецификация инструмента для function calling: имя, описание, JSON-Schema параметров. */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema типа {"type":"object","properties":…}. */
    val parametersSchema: JsonElement = defaultEmptyObjectSchema(),
) {
    companion object {
        fun defaultEmptyObjectSchema(): JsonElement = JsonObject(mapOf("type" to JsonPrimitive("object")))
    }
}

/** ---------------- Сетевые DTO протокола /chat/completions ---------------- */

@Serializable
data class ApiMessage(
    val role: String,
    val content: String? = null,
    /** Только для сообщений assistant с вызовами; null не сериализуется (explicitNulls=false). */
    val tool_calls: List<ApiToolCall>? = null,
    val tool_call_id: String? = null,
    val name: String? = null,
) {
    companion object {
        fun system(text: String) = ApiMessage(role = "system", content = text)
        fun user(text: String) = ApiMessage(role = "user", content = text)
        fun assistant(text: String) = ApiMessage(role = "assistant", content = text)
        fun assistantToolCalls(text: String, calls: List<ApiToolCall>) =
            ApiMessage(role = "assistant", content = text.ifBlank { null }, tool_calls = calls)

        fun tool(toolCallId: String, name: String, content: String) =
            ApiMessage(role = "tool", content = content, tool_call_id = toolCallId, name = name)
    }
}

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiToolCallFunction,
)

@Serializable
data class ApiToolCallFunction(
    val name: String? = null,
    val arguments: String = "",
)

@Serializable
data class ApiTool(
    val type: String = "function",
    val function: ApiToolFunction,
)

@Serializable
data class ApiToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonElement = JsonNull,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: List<ApiTool>? = null,
    val stream: Boolean = false,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ApiChoice> = emptyList(),
    val usage: ApiUsage? = null,
)

@Serializable
data class ApiChoice(
    val index: Int = 0,
    val message: ApiResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ApiResponseMessage(
    val role: String = "assistant",
    val content: String? = null,
    val tool_calls: List<ApiToolCall> = emptyList(),
)

@Serializable
data class ApiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

/**
 * Сборка schema параметров инструмента. Значение свойства обязано быть объектом
 * {"type": …} — голая строка "string" отвергается строгими провайдерами (DeepSeek: 400).
 * Голые JsonPrimitive автоматически оборачиваются в {"type": значение}.
 */
fun buildJsonSchema(properties: Map<String, JsonElement>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            JsonObject(
                properties.mapValues { (_, v) ->
                    if (v is JsonObject) v else buildJsonObject { put("type", v) }
                },
            ),
        )
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

/** Свойство-параметр: {"type": type} с необязательным описанием. */
fun prop(type: String, description: String? = null): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    if (description != null) put("description", JsonPrimitive(description))
}
