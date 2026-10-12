package ru.sebyanin.booktranslator.core.model

import kotlinx.serialization.Serializable

/** Профиль LLM — точка входа любого OpenAI-совместимого API (OpenAI, GLM, DeepSeek, OpenRouter, Ollama…). */
@Serializable
data class LlmProfile(
    val name: String,
    /** Полный префикс до /chat/completions, например https://api.openai.com/v1 */
    val baseUrl: String,
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.3,
    /** Reasoning-модели тратят квоту на рассуждения: 16384 — безопасный дефолт. */
    val maxTokens: Int = 16384,
    /** Таймаут одного запроса (в секундах) — с запасом на медленные редактурные проходы. */
    val requestTimeoutSec: Int = 900,
)

@Serializable
data class McpSettings(
    val enabled: Boolean = false,
    val port: Int = 8420,
    val token: String = "",
)

/** Инструменты агентов (function calling): read-only, с лимитами. */
@Serializable
data class AgentToolsSettings(
    val enabledByDefault: Boolean = true,
    /** Роли, которым инструменты отключены (id из AgentRole). */
    val disabledRoles: List<String> = emptyList(),
    val maxToolCallsPerFragment: Int = 10,
    val maxToolResultChars: Int = 4000,
)

/** Глобальные настройки приложения: <Documents>/BookTranslator/settings.json */
@Serializable
data class AppSettings(
    val schemaVersion: Int = 1,
    val profiles: List<LlmProfile> = emptyList(),
    /** Привязки roleId → имя профиля. */
    val roleBindings: Map<String, String> = emptyMap(),
    /** Пусто = <Documents>/BookTranslator. */
    val projectsRootOverride: String = "",
    val concurrency: Int = 2,
    // Размеры нарезки (в знаках исходника без пробелов; из опыта Genkoku)
    val partMaxChars: Int = 5000,
    val fragmentTargetChars: Int = 1700,
    val criticChunkMaxChars: Int = 3000,
    val mcp: McpSettings = McpSettings(),
    val agentTools: AgentToolsSettings = AgentToolsSettings(),
    /** Сессия: последний открытый проект (восстанавливается при запуске). */
    val lastProjectSlug: String = "",
    /** Сессия: последняя книга каждого проекта (проектSlug → книгаSlug). */
    val lastBookByProject: Map<String, String> = emptyMap(),
    /** Тема интерфейса: system — как в ОС, light, dark. */
    val themeMode: String = "system",
    /** Размер шрифта предпросмотра главы (sp) — степпер в шапке рабочей зоны. */
    val previewFontSize: Int = 14,
) {
    fun profileFor(role: AgentRole): LlmProfile? {
        val byName = roleBindings[role.id]?.let { name -> profiles.firstOrNull { it.name == name } }
        return byName ?: profiles.firstOrNull()
    }
}
