package ru.sebyanin.booktranslator.core

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.llm.AnthropicRequest
import ru.sebyanin.booktranslator.core.llm.AnthropicWire
import ru.sebyanin.booktranslator.core.llm.ApiMessage
import ru.sebyanin.booktranslator.core.llm.ApiToolCall
import ru.sebyanin.booktranslator.core.llm.ApiToolCallFunction
import ru.sebyanin.booktranslator.core.llm.LlmException
import ru.sebyanin.booktranslator.core.llm.ToolSpec
import ru.sebyanin.booktranslator.core.llm.prop
import ru.sebyanin.booktranslator.core.model.ApiKind
import ru.sebyanin.booktranslator.core.model.LlmProfile
import ru.sebyanin.booktranslator.core.storage.AppJson

/**
 * Чистые преобразования протокола Anthropic Messages: ApiMessage (домен конвейера)
 * ↔ блоки Messages, парсинг ответа. Без сети.
 */
class AnthropicWireTest {

    private val profile = LlmProfile(
        name = "Sonnet", baseUrl = "https://api.anthropic.com/v1", apiKey = "sk-ant",
        model = "claude-sonnet-5-5", api = ApiKind.ANTHROPIC, maxTokens = 16384,
    )

    @Test
    fun `system выносится наверх - tool-пары превращаются в блоки - одинаковые роли склеиваются`() {
        val messages = listOf(
            ApiMessage.system("Система"),
            ApiMessage.user("Бриф фрагмента"),
            ApiMessage.assistantToolCalls(
                "Посмотрю глоссарий.",
                listOf(ApiToolCall("tu_1", "function", ApiToolCallFunction("get_glossary", """{"term":"кошко"}"""))),
            ),
            ApiMessage.tool("tu_1", "get_glossary", "термин найден"),
            ApiMessage.user("Лимит исчерпан, завершай."),
        )
        val out = AnthropicWire.toAnthropicMessages(messages)
        // system исчез; tool → user; «tool_result» и следующий user склеились в одно сообщение
        assertEquals(listOf("user", "assistant", "user"), out.map { it.role })

        val assistant = out[1].content.jsonArray.map { it.jsonObject }
        assertEquals(2, assistant.size)
        assertEquals("text", assistant[0]["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Посмотрю глоссарий.", assistant[0]["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tool_use", assistant[1]["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tu_1", assistant[1]["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("get_glossary", assistant[1]["name"]?.jsonPrimitive?.contentOrNull)
        // аргументы — объект, не строка
        assertEquals("кошко", assistant[1]["input"]?.jsonObject?.get("term")?.jsonPrimitive?.contentOrNull)

        val user = out[2].content.jsonArray.map { it.jsonObject }
        assertEquals(2, user.size)
        assertEquals("tool_result", user[0]["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tu_1", user[0]["tool_use_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("термин найден", user[0]["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("text", user[1]["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Лимит исчерпан, завершай.", user[1]["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `запрос несёт system top-level, max_tokens и input_schema`() {
        val req = AnthropicWire.buildRequest(
            profile,
            listOf(ApiMessage.system("Сис"), ApiMessage.user("Юзер")),
            listOf(ToolSpec("tool1", "Описание", ru.sebyanin.booktranslator.core.llm.buildJsonSchema(mapOf("a" to prop("string", "Параметр"))))),
        )
        assertEquals("Сис", req.system)
        assertEquals(16384, req.maxTokens)
        assertEquals(1, req.messages.size)
        val encoded = AppJson.encodeToString(AnthropicRequest.serializer(), req)
        assertTrue(encoded.contains("\"input_schema\""))
        assertTrue(encoded.contains("\"max_tokens\""))
        assertTrue(encoded.contains("\"system\""))
    }

    @Test
    fun `ответ разбирается - текст и tool_use, usage на месте`() {
        val body = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
             "content":[
               {"type":"text","text":"Итог: "},
               {"type":"tool_use","id":"tu_9","name":"read_block","input":{"fragmentId":"part-0001-f1","n":3}},
               {"type":"text","text":"готово"}],
             "stop_reason":"tool_use",
             "usage":{"input_tokens":111,"output_tokens":22}}
        """.trimIndent()
        val resp = AnthropicWire.parseResponse(body, profile)
        assertEquals("Итог: готово", resp.content)
        assertEquals(1, resp.toolCalls.size)
        assertEquals("tu_9", resp.toolCalls[0].id)
        assertEquals("read_block", resp.toolCalls[0].name)
        // argsJson парсится обратно в тот же объект
        val args = AppJson.parseToJsonElement(resp.toolCalls[0].argumentsJson).jsonObject
        assertEquals("part-0001-f1", args["fragmentId"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3, args["n"]?.jsonPrimitive?.intOrNull)
        assertTrue(resp.wantsTools)
        assertEquals(111, resp.promptTokens)
        assertEquals(22, resp.completionTokens)
    }

    @Test
    fun `stop_reason max_tokens - терминальная ошибка`() {
        val body = """{"content":[{"type":"text","text":"обрыв"}],"stop_reason":"max_tokens","usage":{"input_tokens":1,"output_tokens":2}}"""
        val e = assertThrows(LlmException::class.java) { AnthropicWire.parseResponse(body, profile) }
        assertTrue(e.terminal)
        assertTrue(e.message!!.contains("Max tokens"))
    }

    @Test
    fun `тело ошибки протокола уходит в исключение`() {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: field required"}}"""
        val e = assertThrows(LlmException::class.java) { AnthropicWire.parseResponse(body, profile) }
        assertTrue(e.message!!.contains("max_tokens: field required"))
    }

    @Test
    fun `адрес - baseUrl плюс messages`() {
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicWire.endpointUrl("https://api.anthropic.com/v1/"))
        assertEquals("https://opencode.ai/zen/v1/messages", AnthropicWire.endpointUrl("https://opencode.ai/zen/v1"))
    }
}
