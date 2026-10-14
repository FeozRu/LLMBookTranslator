package ru.sebyanin.booktranslator.core

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.llm.ApiMessage
import ru.sebyanin.booktranslator.core.llm.ChatCompletionRequest
import ru.sebyanin.booktranslator.core.llm.ThinkingParam
import ru.sebyanin.booktranslator.core.model.LlmModel
import ru.sebyanin.booktranslator.core.model.LlmProvider
import ru.sebyanin.booktranslator.core.storage.AppJson

/**
 * Галка «Принудительно отключить рассуждения» на модели (noThinking, дефолт — СНЯТА):
 * true шлёт thinking {type:disabled} (гибридные модели DeepSeek/GLM биллят рассуждения
 * как выходные токены), false — поле thinking в запрос не попадает вовсе и модель живёт
 * по своему серверному дефолту (у DeepSeek рассуждения включены, у Клода выключены).
 */
class ThinkingToggleTest {

    private fun request(noThinking: Boolean): String {
        val profile = LlmModel(
            name = "DeepSeek V4 Pro",
            provider = "zen",
            model = "deepseek-v4-pro",
            noThinking = noThinking,
        ).resolve(listOf(LlmProvider(name = "zen", baseUrl = "https://opencode.ai/zen/v1")))
        assertEquals(noThinking, profile.noThinking)
        val request = ChatCompletionRequest(
            model = profile.model,
            messages = listOf(ApiMessage.user("тест")),
            maxTokens = profile.maxTokens,
            thinking = if (profile.noThinking) ThinkingParam("disabled") else null,
        )
        return AppJson.encodeToString(request)
    }

    @Test
    fun `дефолт - галка снята и поле thinking в запрос не отправляется`() {
        val model = LlmModel(name = "m", provider = "zen", model = "deepseek-v4-pro")
        assertFalse(model.noThinking, "дефолт должен быть noThinking=false")
        assertFalse(request(noThinking = false).contains("\"thinking\""))
    }

    @Test
    fun `поставленная галка кладёт thinking disabled в тело запроса`() {
        val body = request(noThinking = true)
        assertTrue(body.contains("\"thinking\""), "нет поля thinking: $body")
        assertTrue(body.contains("\"disabled\""), "нет type:disabled: $body")
    }

    @Test
    fun `старый settings json без поля читается как галка снята`() {
        val legacy = """
            {
              "providers": [{"name": "zen", "baseUrl": "https://opencode.ai/zen/v1", "apiKey": "k", "api": "OPENAI"}],
              "models": [{"name": "V4 Pro", "provider": "zen", "model": "deepseek-v4-pro"}]
            }
        """.trimIndent()
        val settings = AppJson.decodeFromString<ru.sebyanin.booktranslator.core.model.AppSettings>(legacy)
        val model = settings.models.single()
        assertFalse(model.noThinking)
    }
}
