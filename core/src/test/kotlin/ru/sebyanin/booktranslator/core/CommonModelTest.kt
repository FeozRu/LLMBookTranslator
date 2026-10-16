package ru.sebyanin.booktranslator.core

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.LlmModel
import ru.sebyanin.booktranslator.core.model.LlmProvider
import ru.sebyanin.booktranslator.core.storage.AppJson

/**
 * «Общая модель» (AppSettings.commonModel) — фолбэк для ролей без собственной привязки.
 * Цепочка profileFor: привязка роли → общая модель → первая модель в списке.
 * Несуществующие имена (удалённые модели) пропускаются молча на каждой ступени.
 */
class CommonModelTest {

    private fun settings(
        roleBindings: Map<String, String> = emptyMap(),
        commonModel: String? = null,
    ) = AppSettings(
        providers = listOf(LlmProvider(name = "zen", baseUrl = "https://opencode.ai/zen/v1")),
        models = listOf(
            LlmModel(name = "Первая", provider = "zen", model = "glm-5.3-flash"),
            LlmModel(name = "Вторая", provider = "zen", model = "claude-sonnet-5-5"),
        ),
        roleBindings = roleBindings,
        commonModel = commonModel,
    )

    @Test
    fun `привязка роли побеждает общую модель`() {
        val s = settings(roleBindings = mapOf(AgentRole.DRAFT.id to "Первая"), commonModel = "Вторая")
        assertEquals("Первая", s.profileFor(AgentRole.DRAFT)?.name)
    }

    @Test
    fun `роль без привязки получает общую модель`() {
        val s = settings(commonModel = "Вторая")
        assertEquals("Вторая", s.profileFor(AgentRole.EDITOR)?.name)
    }

    @Test
    fun `общей нет - первая модель в списке (старое поведение)`() {
        val s = settings()
        assertEquals("Первая", s.profileFor(AgentRole.EDITOR)?.name)
    }

    @Test
    fun `общая указывает на удалённую модель - первая модель в списке`() {
        val s = settings(commonModel = "удалённая")
        assertEquals("Первая", s.profileFor(AgentRole.EDITOR)?.name)
    }

    @Test
    fun `привязка роли на удалённую модель падает на общую`() {
        val s = settings(roleBindings = mapOf(AgentRole.CRITIC.id to "удалённая"), commonModel = "Вторая")
        assertEquals("Вторая", s.profileFor(AgentRole.CRITIC)?.name)
    }

    @Test
    fun `старый json без поля читается как null и null не сериализуется`() {
        val legacy = """
            {
              "providers": [{"name": "zen", "baseUrl": "https://opencode.ai/zen/v1", "apiKey": "k", "api": "OPENAI"}],
              "models": [{"name": "Первая", "provider": "zen", "model": "glm-5.3-flash"}]
            }
        """.trimIndent()
        val s = AppJson.decodeFromString<AppSettings>(legacy)
        assertNull(s.commonModel)
        assertFalse(
            AppJson.encodeToString(s).contains("commonModel"),
            "null-поле не должно попадать в JSON (explicitNulls=false)",
        )
    }

    @Test
    fun `json round-trip с общей моделью`() {
        val s = settings(commonModel = "Вторая")
        assertEquals(s, AppJson.decodeFromString<AppSettings>(AppJson.encodeToString(s)))
    }
}
