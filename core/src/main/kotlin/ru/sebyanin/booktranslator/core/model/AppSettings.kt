package ru.sebyanin.booktranslator.core.model

import kotlinx.serialization.Serializable

/** Протокол, по которому клиент общается с провайдером. */
@Serializable
enum class ApiKind {
    /** OpenAI-совместимый chat/completions (OpenAI, DeepSeek, GLM, OpenRouter, Ollama…). */
    OPENAI,

    /** Anthropic Messages (api.anthropic.com, совместимые шлюзы вроде OpenCode Zen). */
    ANTHROPIC,
}

/** Провайдер — точка входа API: адрес, ключ, протокол. Общий для нескольких моделей. */
@Serializable
data class LlmProvider(
    val name: String,
    /** Префикс до /chat/completions (OpenAI) или /messages (Anthropic), например https://api.anthropic.com/v1 */
    val baseUrl: String,
    val apiKey: String = "",
    val api: ApiKind = ApiKind.OPENAI,
)

/** Модель — то, что вызывается поверх провайдера: id модели и параметры генерации. */
@Serializable
data class LlmModel(
    val name: String,
    /** Имя провайдера из AppSettings.providers. */
    val provider: String,
    /** Идентификатор модели в API провайдера. */
    val model: String = "",
    /** null = параметр не отправляется: часть новых моделей (Claude 5.5+) отвергает temperature вовсе. */
    val temperature: Double? = null,
    /** Reasoning-модели тратят квоту на рассуждения: 16384 — безопасный дефолт. */
    val maxTokens: Int = 16384,
    /** Таймаут одного запроса (в секундах) — с запасом на медленные редактурные проходы. */
    val requestTimeoutSec: Int = 900,
    /** Принудительно отключить рассуждения: шлём thinking {type:disabled} в OpenAI-запросе.
     *  Гибридные модели (DeepSeek/GLM) рассуждают по умолчанию и биллят рассуждения как
     *  выходные токены — на черновике это львиная доля счёта. false (дефолт) = параметр
     *  не отправляется вовсе, модель живёт по своему серверному дефолту (у DeepSeek
     *  рассуждения включены, у Клода выключены). Для Anthropic-провайдеров флаг не
     *  действует — там thinking включается только явно. */
    val noThinking: Boolean = false,
) {
    /** Сборка точки вызова: параметры модели + адрес/ключ/протокол её провайдера. */
    fun resolve(providers: List<LlmProvider>): LlmProfile {
        val p = providers.firstOrNull { it.name == provider }
        return LlmProfile(
            name = name,
            baseUrl = p?.baseUrl.orEmpty(),
            apiKey = p?.apiKey.orEmpty(),
            model = model,
            api = p?.api ?: ApiKind.OPENAI,
            temperature = temperature,
            maxTokens = maxTokens,
            requestTimeoutSec = requestTimeoutSec,
            noThinking = noThinking,
        )
    }
}

/** Собранная точка вызова LLM: модель + её провайдер. Этапы и клиент работают с ней; UI — с LlmProvider/LlmModel. */
@Serializable
data class LlmProfile(
    val name: String,
    /** Полный префикс до /chat/completions или /messages. */
    val baseUrl: String,
    val apiKey: String = "",
    val model: String = "",
    val api: ApiKind = ApiKind.OPENAI,
    /** null = параметр не отправляется. */
    val temperature: Double? = null,
    /** Reasoning-модели тратят квоту на рассуждения: 16384 — безопасный дефолт. */
    val maxTokens: Int = 16384,
    /** Таймаут одного запроса (в секундах) — с запасом на медленные редактурные проходы. */
    val requestTimeoutSec: Int = 900,
    /** true = thinking {type:disabled} в OpenAI-запросе (см. LlmModel.noThinking). */
    val noThinking: Boolean = false,
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
    val providers: List<LlmProvider> = emptyList(),
    val models: List<LlmModel> = emptyList(),
    /** Привязки roleId → имя модели. */
    val roleBindings: Map<String, String> = emptyMap(),
    /** LEGACY (schema 1): старые монопрофили. Только для миграции — читаются и сворачиваются в providers+models. */
    val profiles: List<LlmProfile> = emptyList(),
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
        val byName = roleBindings[role.id]?.let { name -> models.firstOrNull { it.name == name } }
        return (byName ?: models.firstOrNull())?.resolve(providers)
    }
}

/**
 * Миграция schema 1 (монопрофили) → провайдеры + модели. Каждый уникальный
 * (baseUrl, apiKey) сворачивается в одного провайдера (имя — хост адреса),
 * каждый профиль — в модель с тем же именем (привязки ролей остаются валидными).
 * null = мигрировать нечего (модели уже есть или профилей нет).
 */
fun AppSettings.migratedFromProfiles(): AppSettings? {
    if (models.isNotEmpty() || profiles.isEmpty()) return null

    val providers = LinkedHashMap<String, LlmProvider>()
    fun providerFor(p: LlmProfile): LlmProvider {
        // Нормализуем слэш на конце — «…/v1» и «…/v1/» это один провайдер
        val baseUrl = p.baseUrl.trim().trimEnd('/')
        val key = baseUrl + "|" + p.apiKey.trim()
        return providers.getOrPut(key) {
            val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()
            val base = (host ?: baseUrl.ifEmpty { "провайдер" }).removePrefix("www.")
            var name = base
            var n = 2
            while (providers.values.any { it.name == name }) {
                name = "$base $n"
                n++
            }
            LlmProvider(name = name, baseUrl = baseUrl, apiKey = p.apiKey.trim(), api = ApiKind.OPENAI)
        }
    }

    val models = profiles.map { p ->
        LlmModel(
            name = p.name,
            provider = providerFor(p).name,
            model = p.model,
            temperature = p.temperature,
            maxTokens = p.maxTokens,
            requestTimeoutSec = p.requestTimeoutSec,
        )
    }
    return copy(providers = providers.values.toList(), models = models, profiles = emptyList())
}
