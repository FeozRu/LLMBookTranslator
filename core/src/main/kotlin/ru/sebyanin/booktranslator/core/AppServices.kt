package ru.sebyanin.booktranslator.core

import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.pipeline.DraftStage
import ru.sebyanin.booktranslator.core.pipeline.EditStage
import ru.sebyanin.booktranslator.core.pipeline.ImportStage
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.OsDirs
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.storage.SettingsStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

/**
 * Композиционный корень: создаётся один раз в main, живёт всё приложение.
 * UI и MCP работают с одними и теми же сервисами и потоками состояния.
 */
class AppServices private constructor(
    val dataRoot: Path,
    private val store: SettingsStore,
) {
    val llm = LlmClient()

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val projectStore = ProjectStore { projectsRoot }
    val promptStore = PromptStore({ dataRoot }) { slug -> projectStore.projectDir(slug) }
    val knowledge = KnowledgeService(projectStore)

    val importStage = ImportStage(projectStore)
    val splitStage = SplitStage(projectStore)
    val assemblyStage = ru.sebyanin.booktranslator.core.pipeline.AssemblyStage(projectStore)
    val suggestionService = ru.sebyanin.booktranslator.core.glossary.SuggestionService(projectStore)
    val criticStage = ru.sebyanin.booktranslator.core.pipeline.CriticStage(
        projectStore, promptStore, knowledge, llm, { _settings.value },
    )
    val glossaryRevisor = ru.sebyanin.booktranslator.core.glossary.GlossaryRevisor(
        projectStore, promptStore, knowledge, llm, { _settings.value },
    )
    val alignSearch = ru.sebyanin.booktranslator.core.align.AlignSearch(projectStore)
    val epubBuilder = ru.sebyanin.booktranslator.core.builder.EpubBuilder(projectStore)
    val fb2Builder = ru.sebyanin.booktranslator.core.builder.Fb2Builder(projectStore)
    val qaStage = ru.sebyanin.booktranslator.core.pipeline.QaStage(projectStore, knowledge)

    private fun verifyForPreset(projectSlug: String): VerifyEngine {
        val preset = runCatching { projectStore.loadProject(projectSlug).preset }.getOrDefault("generic")
        return VerifyEngine(preset)
    }

    val draftStage = DraftStage(
        projectStore, promptStore, knowledge, llm, { _settings.value }, ::verifyForPreset,
    )
    val editStage = EditStage(
        projectStore, promptStore, knowledge, llm, { _settings.value }, ::verifyForPreset,
    )

    /** Корень проектов: переопределение из настроек или <Documents>/BookTranslator. */
    val projectsRoot: Path
        get() {
            val override = _settings.value.projectsRootOverride.trim()
            return if (override.isNotEmpty()) Path.of(override) else dataRoot
        }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        store.save(next)
        _settings.value = next
    }

    /** VerifyEngine под пресет конкретного проекта. */
    fun verifyFor(projectSlug: String): VerifyEngine = verifyForPreset(projectSlug)

    fun ensurePromptsSeeded() {
        promptStore.ensureGlobalDefaults()
    }

    companion object {
        const val APP_DIR_NAME = "BookTranslator"

        fun create(): AppServices {
            // -Dbt.dataRoot=<путь> — отладочный корень данных (bt.shot-проверки UI
            // не трогают реальные настройки/проекты пользователя)
            val dataRoot = System.getProperty("bt.dataRoot")?.trim().takeUnless { it.isNullOrEmpty() }?.let { Path.of(it) }
                ?: OsDirs.documentsDir().resolve(APP_DIR_NAME)
            Files.createDirectories(dataRoot)
            val services = AppServices(dataRoot, SettingsStore(dataRoot))
            services.promptStore.ensureGlobalDefaults()
            AppLog.info("app", "Данные: $dataRoot")
            return services
        }
    }
}
