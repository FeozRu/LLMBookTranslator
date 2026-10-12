package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.LlmModel
import ru.sebyanin.booktranslator.core.model.LlmProfile
import ru.sebyanin.booktranslator.core.model.LlmProvider
import ru.sebyanin.booktranslator.core.model.migratedFromProfiles
import ru.sebyanin.booktranslator.core.storage.AppJson

/** Миграция schema 1 (монопрофили) → провайдеры + модели. */
class SettingsMigrationTest {

    @Test
    fun `legacy-настройки читаются и сворачиваются - одинаковый адрес-ключ даёт одного провайдера`() {
        val legacy = """
            {
              "schemaVersion": 1,
              "profiles": [
                {"name": "Deepseek", "baseUrl": "https://api.deepseek.com/v1", "apiKey": "sk-1",
                 "model": "deepseek-flash", "temperature": 0.5, "maxTokens": 16384, "requestTimeoutSec": 600},
                {"name": "Deepseek cheap", "baseUrl": "https://api.deepseek.com/v1/", "apiKey": "sk-1",
                 "model": "deepseek-flash", "temperature": 0.2, "maxTokens": 8192, "requestTimeoutSec": 300},
                {"name": "Zen Kimi", "baseUrl": "https://opencode.ai/zen/v1", "apiKey": "sk-2",
                 "model": "kimi-k3", "temperature": 0.3, "maxTokens": 16384, "requestTimeoutSec": 900}
              ],
              "roleBindings": {"editor": "Deepseek cheap"}
            }
        """.trimIndent()
        val settings = AppJson.decodeFromString<AppSettings>(legacy)
        val migrated = settings.migratedFromProfiles()
            ?: error("миграция обязана произойти")

        assertEquals(2, migrated.providers.size)
        // trim базового адреса: слэш на конце не порождает второго провайдера
        assertEquals("api.deepseek.com", migrated.providers[0].name)
        assertEquals("https://api.deepseek.com/v1", migrated.providers[0].baseUrl)
        assertEquals("sk-1", migrated.providers[0].apiKey)
        assertEquals("opencode.ai", migrated.providers[1].name)

        assertEquals(3, migrated.models.size)
        val first = migrated.models[0]
        assertEquals("Deepseek", first.name)
        assertEquals("api.deepseek.com", first.provider)
        assertEquals("deepseek-flash", first.model)
        assertEquals(0.5, first.temperature)
        assertEquals(16384, first.maxTokens)
        assertEquals(600, first.requestTimeoutSec)
        assertEquals("api.deepseek.com", migrated.models[1].provider)
        assertEquals("opencode.ai", migrated.models[2].provider)
        assertTrue(migrated.profiles.isEmpty())

        // привязка роли осталась валидной (имена моделей = старым именам профилей) и резолвится
        val resolved = migrated.profileFor(AgentRole.EDITOR)
            ?: error("привязка должна резолвиться")
        assertEquals("Deepseek cheap", resolved.name)
        assertEquals("https://api.deepseek.com/v1", resolved.baseUrl)
        assertEquals("sk-1", resolved.apiKey)
        assertEquals(300, resolved.requestTimeoutSec)
    }

    @Test
    fun `миграции нет когда модели уже есть или профилей нет`() {
        val withModels = AppSettings(
            providers = listOf(LlmProvider(name = "p", baseUrl = "https://x/v1")),
            models = listOf(LlmModel(name = "m", provider = "p")),
            profiles = listOf(LlmProfile(name = "old", baseUrl = "https://y/v1")),
        )
        assertNull(withModels.migratedFromProfiles())
        assertNull(AppSettings().migratedFromProfiles())
    }

    @Test
    fun `пересекающиеся имена провайдеров получают суффиксы - новых полей нет`() {
        val legacy = AppSettings(
            profiles = listOf(
                LlmProfile(name = "A", baseUrl = "https://same.host/v1", apiKey = "k1", model = "m1"),
                LlmProfile(name = "B", baseUrl = "https://same.host", apiKey = "k2", model = "m2"),
            ),
        )
        val migrated = legacy.migratedFromProfiles()!!
        // ключ дедупликации — trim(baseUrl)+apiKey, поэтому два разных ключа = два провайдера с одним хостом
        assertEquals(listOf("same.host", "same.host 2"), migrated.providers.map { it.name })
        assertEquals(listOf("same.host", "same.host 2"), migrated.models.map { it.provider })

        // устойчивость схемы: encode → decode возвращает то же самое
        val json = AppJson.encodeToString(AppSettings.serializer(), migrated)
        assertEquals(migrated, AppJson.decodeFromString(AppSettings.serializer(), json))
    }
}
