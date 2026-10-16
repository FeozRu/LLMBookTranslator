package ru.sebyanin.booktranslator.core

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AgentToolsSettings
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.LlmModel
import ru.sebyanin.booktranslator.core.model.LlmProvider
import ru.sebyanin.booktranslator.core.model.ProjectMeta
import ru.sebyanin.booktranslator.core.pipeline.CriticStage
import ru.sebyanin.booktranslator.core.pipeline.DraftStage
import ru.sebyanin.booktranslator.core.pipeline.EditStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files

/**
 * Галка «без инструментов» на роль (Настройки → Роли → модель): пишет role.id
 * в settings.agentTools.disabledRoles. Этапы над фрагментами (черновик, редактура)
 * и корректор обязаны собирать реестр инструментов null'ом для перечисленных ролей;
 * общий выключатель enabledByDefault=false сильнее галки; проектный список
 * toolsDisabledRoles тоже отключает (те же роли, но per-project).
 * Старый settings.json без поля читается с галками снятыми.
 */
class AgentToolsRolesTest {

    private fun harness(): Triple<ProjectStore, ProjectMeta, java.nio.file.Path> {
        val tmp = Files.createTempDirectory("bt-tools-roles")
        val store = ProjectStore { tmp }
        val project = store.createProject("ГалкаИнструменты", "ja", "ru", "ja")
        return Triple(store, store.loadProject(project.slug), tmp)
    }

    private fun settings(disabledRoles: List<String> = emptyList(), enabledByDefault: Boolean = true) = AppSettings(
        providers = listOf(LlmProvider(name = "zen", baseUrl = "https://opencode.ai/zen/v1")),
        models = listOf(LlmModel(name = "test", provider = "zen", model = "m")),
        agentTools = AgentToolsSettings(enabledByDefault = enabledByDefault, disabledRoles = disabledRoles),
    )

    private fun draftStage(store: ProjectStore, tmp: java.nio.file.Path, s: () -> AppSettings) =
        DraftStage(
            store,
            PromptStore({ tmp }) { slug -> store.projectDir(slug) },
            KnowledgeService(store),
            LlmClient(),
            s,
            { VerifyEngine("ja") },
        )

    private fun editStage(store: ProjectStore, tmp: java.nio.file.Path, s: () -> AppSettings) =
        EditStage(
            store,
            PromptStore({ tmp }) { slug -> store.projectDir(slug) },
            KnowledgeService(store),
            LlmClient(),
            s,
            { VerifyEngine("ja") },
        )

    @Test
    fun `пустой disabledRoles - инструменты включены как раньше`() {
        val (store, project, tmp) = harness()
        val registry = draftStage(store, tmp) { settings() }.toolsRegistry(settings(), project)
        assertNotNull(registry)
        assertTrue(registry!!.specs().size >= 5, "инструментов: ${registry.specs().size}")
    }

    @Test
    fun `роль в disabledRoles - реестр null, остальные не тронуты`() {
        val (store, project, tmp) = harness()
        val s = settings(disabledRoles = listOf(AgentRole.DRAFT.id))
        assertNull(draftStage(store, tmp) { s }.toolsRegistry(s, project))
        assertNotNull(editStage(store, tmp) { s }.toolsRegistry(s, project))
    }

    @Test
    fun `общий выключатель enabledByDefault=false сильнее галки роли`() {
        val (store, project, tmp) = harness()
        val s = settings(enabledByDefault = false)
        assertNull(draftStage(store, tmp) { s }.toolsRegistry(s, project))
    }

    @Test
    fun `проектный список toolsDisabledRoles тоже отключает`() {
        val (store, project, tmp) = harness()
        val s = settings()
        val perProject = project.copy(toolsDisabledRoles = listOf(AgentRole.EDITOR.id))
        assertNotNull(draftStage(store, tmp) { s }.toolsRegistry(s, perProject))
        assertNull(editStage(store, tmp) { s }.toolsRegistry(s, perProject))
    }

    @Test
    fun `корректор - галка снимает инструменты`() {
        val (store, project, tmp) = harness()
        val off = settings(disabledRoles = listOf(AgentRole.CRITIC.id))
        assertNull(
            CriticStage(
                store,
                PromptStore({ tmp }) { slug -> store.projectDir(slug) },
                KnowledgeService(store),
                LlmClient(),
                { off },
            ).toolRegistry(project, off),
        )
        assertNotNull(
            CriticStage(
                store,
                PromptStore({ tmp }) { slug -> store.projectDir(slug) },
                KnowledgeService(store),
                LlmClient(),
                { settings() },
            ).toolRegistry(project, settings()),
        )
    }

    @Test
    fun `старый json без agentTools читается с галками снятыми и round-trip сохраняет список`() {
        val legacy = """
            {
              "providers": [{"name": "zen", "baseUrl": "https://opencode.ai/zen/v1", "apiKey": "k", "api": "OPENAI"}],
              "models": [{"name": "Первая", "provider": "zen", "model": "glm-5.3-flash"}]
            }
        """.trimIndent()
        val loaded = AppJson.decodeFromString(AppSettings.serializer(), legacy)
        assertEquals(emptyList<String>(), loaded.agentTools.disabledRoles)
        assertTrue(loaded.agentTools.enabledByDefault)

        val withChecks = loaded.copy(
            agentTools = loaded.agentTools.copy(disabledRoles = listOf(AgentRole.EDITOR.id, AgentRole.CRITIC.id)),
        )
        // AppJson prettyPrint — сравниваем без пробелов
        val encoded = AppJson.encodeToString(withChecks)
        // AppJson prettyPrint — сравниваем без пробелов
        val compact = encoded.replace(Regex("\\s+"), "")
        assertTrue(compact.contains(""""disabledRoles":["editor","critic"]"""), compact.take(400))
        assertEquals(withChecks.agentTools, AppJson.decodeFromString(AppSettings.serializer(), encoded).agentTools)
    }
}
