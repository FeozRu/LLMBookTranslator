package ru.sebyanin.booktranslator.core.pipeline

import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.AgentRunner
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.ProjectMeta
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.tools.ToolContext
import ru.sebyanin.booktranslator.core.tools.ToolFacade
import ru.sebyanin.booktranslator.core.tools.ToolRegistry
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/** Сводка прогона этапа. */
data class StageSummary(
    val stage: String,
    val total: Int,
    val done: Int,
    val failed: Int,
    val skipped: Int,
) {
    val allDone: Boolean get() = failed == 0 && done == total
}

/** Прогресс для UI: вызывается после каждого фрагмента. */
data class FragmentProgress(
    val stage: String,
    val fragmentId: String,
    val done: Int,
    val failed: Int,
    val total: Int,
)

/**
 * Результат приёма готового текста от внешнего исполнителя (MCP-поток
 * «агент переводит сам»): Accepted — записан и подтверждён манифестом,
 * Rejected — верификация не прошла, файлы и статусы не тронуты
 * (фрагмент остаётся в прежнем статусе, причину возвращают агенту
 * для самопереспроса), Error — фрагмента/черновика нет.
 */
sealed interface ExternalSubmitResult {
    data class Accepted(val blocks: Int, val verify: String) : ExternalSubmitResult
    data class Rejected(val reason: String) : ExternalSubmitResult
    data class Error(val message: String) : ExternalSubmitResult
}

/**
 * Общая механика этапов над фрагментами: семафор параллельности, ретраи,
 * атомарные обновления манифеста, счёт блоков после каждого шага.
 */
abstract class FragmentStage(
    protected val store: ProjectStore,
    protected val promptStore: PromptStore,
    protected val knowledge: KnowledgeService,
    protected val client: LlmClient,
    protected val settingsProvider: () -> AppSettings,
    /** VerifyEngine зависит от пресета проекта (ja/generic). */
    protected val verifyProvider: (String) -> VerifyEngine,
) {
    protected val runner = AgentRunner(client)
    protected val manifestMutex = Mutex()

    abstract val role: AgentRole
    abstract val stageName: String

    protected data class Target(
        val partId: String,
        val chapterIndex: Int,
        val fragmentId: String,
    )

    /** null = инструменты отключены (глобально, по роли или для проекта) — роль работает одним запросом. */
    internal fun toolsRegistry(settings: AppSettings, project: ProjectMeta): ToolRegistry? {
        val globallyOff = !settings.agentTools.enabledByDefault
        val offForRole = settings.agentTools.disabledRoles.contains(role.id) ||
            project.toolsDisabledRoles.contains(role.id)
        return if (globallyOff || offForRole) null
        else ToolFacade.defaultRegistry(store, knowledge, settings.agentTools.maxToolResultChars)
    }

    protected fun readFragmentFile(bookDir: Path, id: String): FragmentFile =
        AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(bookDir.resolve("work").resolve("fragments").resolve("$id.json")),
        )

    /** Фрагмент манифеста с главой и телом файла; null = id в книге нет. */
    protected data class LocatedFragment(val chapterIndex: Int, val file: FragmentFile)

    protected fun locateFragment(bookDir: Path, fragmentId: String): LocatedFragment? {
        val manifest = SplitStage.loadManifest(bookDir)
        manifest.parts.forEach { part ->
            if (part.fragments.any { it.id == fragmentId }) {
                return LocatedFragment(part.chapterIndex, readFragmentFile(bookDir, fragmentId))
            }
        }
        return null
    }

    protected fun writeFragmentFile(bookDir: Path, file: FragmentFile) {
        ProjectStore.atomicWrite(
            bookDir.resolve("work").resolve("fragments").resolve("${file.id}.json"),
            AppJson.encodeToString(FragmentFile.serializer(), file),
        )
    }

    /** Парсинг текста модели в блоки: пропуски пустых строк, срез код-заборов и шапки «# …». */
    protected fun parseBlocks(raw: String, srcBlocks: List<String>): List<String> {
        var lines = raw.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.size > 1 && lines.first().startsWith("```")) {
            lines = lines.drop(1)
            if (lines.isNotEmpty() && lines.last().startsWith("```")) lines = lines.dropLast(1)
        }
        // Шапка-заголовок, которой нет в исходнике, — срезается (поведение verify_translation.py)
        if (lines.isNotEmpty() && lines.first().startsWith("# ") &&
            srcBlocks.firstOrNull()?.startsWith("# ") != true
        ) {
            lines = lines.drop(1)
        }
        return lines
    }

    /**
     * Атомарное чтение-изменение-запись манифеста. Контракт: block возвращает
     * НОВУЮ версию манифеста (copy) — именно она и сохраняется.
     */
    protected suspend fun withManifest(
        bookDir: Path,
        block: suspend (ru.sebyanin.booktranslator.core.model.WorkManifest) -> ru.sebyanin.booktranslator.core.model.WorkManifest,
    ): ru.sebyanin.booktranslator.core.model.WorkManifest =
        manifestMutex.withLock {
            val updated = block(SplitStage.loadManifest(bookDir))
            SplitStage.saveManifest(bookDir, updated)
            updated
        }
}

/** Этап 2: черновой перевод (роль «Переводчик») + Терминолог (предложения терминов). */
class DraftStage(
    store: ProjectStore,
    promptStore: PromptStore,
    knowledge: KnowledgeService,
    client: LlmClient,
    settingsProvider: () -> AppSettings,
    verifyProvider: (String) -> VerifyEngine,
) : FragmentStage(store, promptStore, knowledge, client, settingsProvider, verifyProvider) {

    override val role = AgentRole.DRAFT
    override val stageName = "draft"

    companion object {
        /**
         * [[имя?: оригинал → вариант]] или (с пояснением для очереди глоссария)
         * [[имя?: оригинал → вариант | что это, коротко]].
         * Группа 3 необязательна: старые черновики без неё разбираются как раньше.
         */
        val NAME_MARKER = Regex("\\[\\[имя\\?:\\s*([^]→|]+)\\s*→\\s*([^]|]+?)\\s*(?:\\|\\s*([^]]+?))?\\s*]]")
    }

    suspend fun run(
        projectSlug: String,
        bookSlug: String,
        chapterIndices: Set<Int>? = null,
        forceFragmentIds: Set<String> = emptySet(),
        onProgress: (FragmentProgress) -> Unit = {},
    ): StageSummary {
        val settings = settingsProvider()
        val project = store.loadProject(projectSlug)
        val profile = settings.profileFor(role)
            ?: error("Не настроена модель LLM для роли «${role.title}» (Настройки → Модели)")
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val registry = toolsRegistry(settings, project)
        val ctx = ToolContext(projectSlug, bookSlug, store, knowledge)

        SplitStage.reconcileManifest(bookDir)
        val targets = collectTargets(bookDir, chapterIndices, forceFragmentIds)
        if (targets.isEmpty()) return StageSummary(stageName, 0, 0, 0, 0)

        val verify = verifyProvider(projectSlug)
        var done = 0
        var failed = 0
        val semaphore = Semaphore(settings.concurrency.coerceAtLeast(1))

        coroutineScope {
            targets.forEach { target ->
                launch {
                    semaphore.acquire()
                    try {
                        val ok = processFragment(target, bookDir, projectSlug, project, profile, registry, ctx, settings, verify)
                        if (ok) done++ else failed++
                    } catch (e: ru.sebyanin.booktranslator.core.llm.PaymentRequiredException) {
                        throw e // 402: остановка всего этапа (гасит остальные фрагменты), не провал одного
                    } catch (e: Exception) {
                        failed++
                        AppLog.error(stageName, "Фрагмент ${target.fragmentId}: ${e.message}")
                        markFailed(bookDir, target.fragmentId, e.message ?: "исключение")
                    } finally {
                        semaphore.release()
                        onProgress(FragmentProgress(stageName, target.fragmentId, done, failed, targets.size))
                    }
                }
            }
        }
        return StageSummary(stageName, targets.size, done, failed, 0)
    }

    private fun collectTargets(bookDir: Path, chapterIndices: Set<Int>?, forceIds: Set<String>): List<Target> {
        val manifest = SplitStage.loadManifest(bookDir)
        return manifest.parts.asSequence()
            .filter { chapterIndices == null || it.chapterIndex in chapterIndices }
            .flatMap { part ->
                part.fragments.map { Target(part.id, part.chapterIndex, it.id) }
            }
            .filter { target ->
                val frag = manifest.allFragments().firstOrNull { it.id == target.fragmentId } ?: return@filter false
                forceIds.contains(target.fragmentId) || !frag.draft.isDone
            }
            .toList()
    }

    private suspend fun processFragment(
        target: Target,
        bookDir: Path,
        projectSlug: String,
        project: ProjectMeta,
        profile: ru.sebyanin.booktranslator.core.model.LlmProfile,
        registry: ToolRegistry?,
        ctx: ToolContext,
        settings: AppSettings,
        verify: VerifyEngine,
    ): Boolean {
        markRunning(bookDir, target.fragmentId, stageName)
        val file = readFragmentFile(bookDir, target.fragmentId)

        // Якорный фрагмент (только img/разделители/комментарии) — LLM не нужен:
        // исходник переносится дословно, позиция иллюстраций обязана сохраниться 1:1
        if (BlockText.allAnchors(file.srcBlocks)) {
            writeFragmentFile(
                bookDir,
                file.copy(
                    draftBlocks = file.srcBlocks,
                    verifyDraft = "якорный фрагмент — перенесён без LLM",
                    model = "",
                    promptTokens = 0,
                    completionTokens = 0,
                    toolCalls = 0,
                ),
            )
            markDone(bookDir, target.fragmentId, stageName, "якорный фрагмент — без LLM")
            AppLog.info(stageName, "Фрагмент ${target.fragmentId}: якорный (${file.srcBlocks.size} блоков) — скопирован без LLM")
            return true
        }

        val selected = knowledge.selectForFragment(projectSlug, file.srcBlocks.joinToString("\n"))
        val template = promptStore.resolve(projectSlug, role)
        val brief = BriefBuilder.build(
            BriefBuilder.Input(
                template = template,
                srcBlocks = file.srcBlocks,
                dictionary = selected.dictionary,
                notes = selected.notes,
                conventions = store.readConventions(projectSlug),
                toolsDoc = if (registry != null) ToolFacade.docsText() else "",
            ),
        )

        var lastError = "неизвестная ошибка"
        attemptLoop@ for (attempt in 1..3) {
            try {
                val result = runner.run(
                    profile = profile,
                    userPrompt = brief,
                    tools = registry?.specs(),
                    executor = registry?.let { reg -> { call -> reg.execute(call.name, call.argumentsJson, ctx) } },
                    maxToolCalls = settings.agentTools.maxToolCallsPerFragment,
                    sourceLabel = "${stageName}:${target.fragmentId}",
                )
                val blocks = parseBlocks(result.text, file.srcBlocks)
                val verifyResult = verify.verifyTranslation(
                    file.srcBlocks, blocks,
                    VerifyEngine.draftOptions(),
                )
                if (!verifyResult.ok) {
                    lastError = verifyResult.summary()
                    AppLog.warn(stageName, "Фрагмент ${target.fragmentId}, попытка $attempt: ${verifyResult.summary()}")
                    continue@attemptLoop
                }
                // Успех: пишем тексты + верификацию + манифест
                writeFragmentFile(
                    bookDir,
                    file.copy(
                        draftBlocks = blocks,
                        verifyDraft = verifyResult.summary(),
                        model = profile.model,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        toolCalls = result.toolCallsTotal,
                    ),
                )
                markDone(bookDir, target.fragmentId, stageName, verifyResult.summary())

                // Терминолог: маркеры черновика → предложения глоссария
                proposeTermsFrom(projectSlug, target.chapterIndex, target.fragmentId, blocks)
                AppLog.info(stageName, "Фрагмент ${target.fragmentId}: готов (${blocks.size} блоков, ${result.toolCallsTotal} tool-вызовов)")
                return true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // остановка этапа пользователем — не ошибка попытки
            } catch (e: ru.sebyanin.booktranslator.core.llm.PaymentRequiredException) {
                throw e // 402: ретраи и следующий фрагмент упадут так же — остановка этапа
            } catch (e: Exception) {
                lastError = e.message ?: e::class.simpleName ?: "ошибка"
                AppLog.warn(stageName, "Фрагмент ${target.fragmentId}, попытка $attempt: $lastError")
                // Клиентская ошибка (4xx: плохой ключ/схема/запрос) — ретраи бессмысленны
                if (e is ru.sebyanin.booktranslator.core.llm.LlmException && e.terminal) break@attemptLoop
            }
        }
        markFailed(bookDir, target.fragmentId, lastError)
        return false
    }

    // ---- внешний исполнитель (MCP-поток «агент переводит сам») ----

    /**
     * Бриф для внешнего исполнителя: тот же полный текст задания, который
     * получила бы встроенная модель. Инструменты не прилагаются — у внешнего
     * исполнителя свои. null = фрагмент не найден.
     */
    suspend fun briefFor(projectSlug: String, bookSlug: String, fragmentId: String): String? {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val file = locateFragment(bookDir, fragmentId)?.file ?: return null
        if (BlockText.allAnchors(file.srcBlocks)) {
            return "ЯКОРНЫЙ ФРАГМЕНТ — перевода не требует: верни исходник дословно, строка в строку.\n<<<\n" +
                file.srcBlocks.joinToString("\n") + "\n>>>"
        }
        val selected = knowledge.selectForFragment(projectSlug, file.srcBlocks.joinToString("\n"))
        val template = promptStore.resolve(projectSlug, role)
        return BriefBuilder.build(
            BriefBuilder.Input(
                template = template,
                srcBlocks = file.srcBlocks,
                dictionary = selected.dictionary,
                notes = selected.notes,
                conventions = store.readConventions(projectSlug),
            ),
        )
    }

    /**
     * Приём готового черновика от внешнего исполнителя: механика этапа без LLM.
     * Провал верификации = Rejected без единой записи (фрагмент остаётся в прежнем
     * статусе, причина возвращается агенту для исправления и повторной отправки).
     * Уже готовый фрагмент перезаписывает (force-семантика этапа).
     */
    suspend fun submitExternal(
        projectSlug: String,
        bookSlug: String,
        fragmentId: String,
        rawText: String,
        sourceLabel: String,
    ): ExternalSubmitResult {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val located = locateFragment(bookDir, fragmentId)
            ?: return ExternalSubmitResult.Error("фрагмент не найден: $fragmentId")
        val file = located.file
        val blocks = parseBlocks(rawText, file.srcBlocks)
        val verifyResult = verifyProvider(projectSlug).verifyTranslation(
            file.srcBlocks, blocks,
            VerifyEngine.draftOptions(),
        )
        if (!verifyResult.ok) {
            AppLog.warn(stageName, "Внешний перевод $fragmentId отклонён: ${verifyResult.summary()}")
            return ExternalSubmitResult.Rejected(verifyResult.summary())
        }
        writeFragmentFile(
            bookDir,
            file.copy(
                draftBlocks = blocks,
                verifyDraft = verifyResult.summary(),
                model = sourceLabel,
                promptTokens = 0,
                completionTokens = 0,
                toolCalls = 0,
            ),
        )
        markDone(bookDir, fragmentId, stageName, verifyResult.summary())
        proposeTermsFrom(projectSlug, located.chapterIndex, fragmentId, blocks)
        AppLog.info(stageName, "Фрагмент $fragmentId: принят внешний перевод от $sourceLabel (${blocks.size} блоков)")
        return ExternalSubmitResult.Accepted(blocks.size, verifyResult.summary())
    }

    /** Терминолог: маркеры [[имя?: оригинал → вариант | что это]] → предложения глоссария. */
    private fun proposeTermsFrom(projectSlug: String, chapterIndex: Int, fragmentId: String, blocks: List<String>) {
        // Модель иногда упаковывает несколько терминов в один маркер
        // («Карла; カリル → Карл») — расщепляем на отдельные предложения
        blocks.forEach { line ->
            NAME_MARKER.findAll(line).forEach { m ->
                ru.sebyanin.booktranslator.core.glossary.GlossaryRevision
                    .splitPacked(m.groupValues[1].trim(), m.groupValues[2].trim())
                    .forEach { (original, translation) ->
                        knowledge.proposeTerm(
                            projectSlug,
                            original = original,
                            translation = translation,
                            context = "глава $chapterIndex · фрагмент $fragmentId",
                            reason = m.groupValues[3].trim(),
                        )
                    }
            }
        }
    }

    // ---- обновления манифеста ----

    private suspend fun markRunning(bookDir: Path, fragmentId: String, stage: String) = updateStage(bookDir, fragmentId) {
        it.copy(status = "running", updatedAt = now())
    }

    private suspend fun markDone(bookDir: Path, fragmentId: String, stage: String, summary: String) =
        updateStage(bookDir, fragmentId) {
            it.copy(
                status = "done",
                verify = summary,
                error = "",
                attempts = it.attempts + 1,
                updatedAt = now(),
            )
        }

    private suspend fun markFailed(bookDir: Path, fragmentId: String, error: String) = updateStage(bookDir, fragmentId) {
        it.copy(status = "failed", error = error.take(300), attempts = it.attempts + 1, updatedAt = now())
    }

    private suspend fun updateStage(bookDir: Path, fragmentId: String, transform: (ru.sebyanin.booktranslator.core.model.StageState) -> ru.sebyanin.booktranslator.core.model.StageState) {
        withManifest(bookDir) { manifest ->
            manifest.copy(
                parts = manifest.parts.map { part ->
                    part.copy(
                        fragments = part.fragments.map { frag ->
                            if (frag.id == fragmentId) frag.copy(draft = transform(frag.draft)) else frag
                        },
                    )
                },
            )
        }
    }

    private fun now() = LocalDateTime.now().toString()
}

/** Этап 3: литературная редактура черновика без оригинала (роль «Редактор»). */
class EditStage(
    store: ProjectStore,
    promptStore: PromptStore,
    knowledge: KnowledgeService,
    client: LlmClient,
    settingsProvider: () -> AppSettings,
    verifyProvider: (String) -> VerifyEngine,
) : FragmentStage(store, promptStore, knowledge, client, settingsProvider, verifyProvider) {

    override val role = AgentRole.EDITOR
    override val stageName = "edit"

    suspend fun run(
        projectSlug: String,
        bookSlug: String,
        chapterIndices: Set<Int>? = null,
        forceFragmentIds: Set<String> = emptySet(),
        onProgress: (FragmentProgress) -> Unit = {},
    ): StageSummary {
        val settings = settingsProvider()
        val project = store.loadProject(projectSlug)
        val profile = settings.profileFor(role)
            ?: error("Не настроена модель LLM для роли «${role.title}» (Настройки → Модели)")
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val registry = toolsRegistry(settings, project)
        val ctx = ToolContext(projectSlug, bookSlug, store, knowledge)

        SplitStage.reconcileManifest(bookDir)
        val manifest = SplitStage.loadManifest(bookDir)
        val targets = manifest.parts.asSequence()
            .filter { chapterIndices == null || it.chapterIndex in chapterIndices }
            .flatMap { part -> part.fragments.map { Target(part.id, part.chapterIndex, it.id) } }
            .filter { t ->
                val frag = manifest.allFragments().firstOrNull { it.id == t.fragmentId } ?: return@filter false
                if (forceFragmentIds.contains(t.fragmentId)) true
                else frag.draft.isDone && !frag.edited.isDone
            }
            .toList()

        if (targets.isEmpty()) return StageSummary(stageName, 0, 0, 0, 0)

        val verify = verifyProvider(projectSlug)
        var done = 0
        var failed = 0
        val semaphore = Semaphore(settings.concurrency.coerceAtLeast(1))

        coroutineScope {
            targets.forEach { target ->
                launch {
                    semaphore.acquire()
                    try {
                        if (processFragment(target, bookDir, projectSlug, profile, registry, ctx, settings, verify)) done++ else failed++
                    } catch (e: ru.sebyanin.booktranslator.core.llm.PaymentRequiredException) {
                        throw e // 402: остановка всего этапа (гасит остальные фрагменты), не провал одного
                    } catch (e: Exception) {
                        failed++
                        AppLog.error(stageName, "Фрагмент ${target.fragmentId}: ${e.message}")
                        updateStage(bookDir, target.fragmentId) {
                            it.copy(status = "failed", error = (e.message ?: "").take(300), updatedAt = LocalDateTime.now().toString())
                        }
                    } finally {
                        semaphore.release()
                        onProgress(FragmentProgress(stageName, target.fragmentId, done, failed, targets.size))
                    }
                }
            }
        }
        return StageSummary(stageName, targets.size, done, failed, 0)
    }

    private suspend fun processFragment(
        target: Target,
        bookDir: Path,
        projectSlug: String,
        profile: ru.sebyanin.booktranslator.core.model.LlmProfile,
        registry: ToolRegistry?,
        ctx: ToolContext,
        settings: AppSettings,
        verify: VerifyEngine,
    ): Boolean {
        updateStage(bookDir, target.fragmentId) { it.copy(status = "running", updatedAt = LocalDateTime.now().toString()) }
        val file = readFragmentFile(bookDir, target.fragmentId)
        if (file.draftBlocks.isEmpty()) {
            updateStage(bookDir, target.fragmentId) { it.copy(status = "failed", error = "нет черновика") }
            return false
        }

        // Якорный фрагмент — редактура сводится к той же дословной копии, LLM не вызывается
        if (BlockText.allAnchors(file.draftBlocks)) {
            writeFragmentFile(
                bookDir,
                file.copy(
                    editedBlocks = file.draftBlocks,
                    verifyEdited = "якорный фрагмент — перенесён без LLM",
                    model = "",
                    promptTokens = 0,
                    completionTokens = 0,
                    toolCalls = 0,
                ),
            )
            updateStage(bookDir, target.fragmentId) {
                it.copy(
                    status = "done",
                    verify = "якорный фрагмент — без LLM",
                    error = "",
                    attempts = it.attempts + 1,
                    updatedAt = LocalDateTime.now().toString(),
                )
            }
            AppLog.info(stageName, "Фрагмент ${target.fragmentId}: якорный (${file.draftBlocks.size} блоков) — скопирован без LLM")
            return true
        }

        // Словарь/заметки — по черновику (оригинала у редактора нет)
        val selected = knowledge.selectForFragment(projectSlug, file.srcBlocks.joinToString("\n"))
        val template = promptStore.resolve(projectSlug, role)
        val brief = BriefBuilder.build(
            BriefBuilder.Input(
                template = template,
                srcBlocks = file.draftBlocks,
                dictionary = selected.dictionary,
                notes = selected.notes,
                conventions = store.readConventions(projectSlug),
                toolsDoc = if (registry != null) ToolFacade.docsText() else "",
            ),
        )

        var lastError = "неизвестная ошибка"
        attemptLoop@ for (attempt in 1..3) {
            try {
                val result = runner.run(
                    profile = profile,
                    userPrompt = brief,
                    tools = registry?.specs(),
                    executor = registry?.let { reg -> { call -> reg.execute(call.name, call.argumentsJson, ctx) } },
                    maxToolCalls = settings.agentTools.maxToolCallsPerFragment,
                    sourceLabel = "${stageName}:${target.fragmentId}",
                )
                val blocks = parseBlocks(result.text, file.draftBlocks)
                val verifyResult = verify.verifyTranslation(file.srcBlocks, blocks, VerifyEngine.editedOptions())
                // Жёстко: блоки против исходника 1:1; мягко: остатки пометок — предупреждение
                if (blocks.size != file.srcBlocks.size) {
                    lastError = "блоков ${file.srcBlocks.size} → ${blocks.size}"
                    AppLog.warn(stageName, "Фрагмент ${target.fragmentId}, попытка $attempt: $lastError")
                    continue@attemptLoop
                }
                writeFragmentFile(
                    bookDir,
                    file.copy(
                        editedBlocks = blocks,
                        verifyEdited = verifyResult.summary(),
                        model = profile.model,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        toolCalls = result.toolCallsTotal,
                    ),
                )
                updateStage(bookDir, target.fragmentId) {
                    it.copy(
                        status = "done",
                        verify = verifyResult.summary(),
                        error = "",
                        attempts = it.attempts + 1,
                        updatedAt = LocalDateTime.now().toString(),
                    )
                }
                AppLog.info(stageName, "Фрагмент ${target.fragmentId}: отредактирован (${blocks.size} блоков)")
                return true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // остановка этапа пользователем — не ошибка попытки
            } catch (e: ru.sebyanin.booktranslator.core.llm.PaymentRequiredException) {
                throw e // 402: ретраи и следующий фрагмент упадут так же — остановка этапа
            } catch (e: Exception) {
                lastError = e.message ?: e::class.simpleName ?: "ошибка"
                AppLog.warn(stageName, "Фрагмент ${target.fragmentId}, попытка $attempt: $lastError")
                if (e is ru.sebyanin.booktranslator.core.llm.LlmException && e.terminal) break@attemptLoop
            }
        }
        updateStage(bookDir, target.fragmentId) {
            it.copy(status = "failed", error = lastError.take(300), attempts = it.attempts + 1, updatedAt = LocalDateTime.now().toString())
        }
        return false
    }

    // ---- внешний исполнитель (MCP-поток «агент переводит сам») ----

    /**
     * Бриф редактору: черновик без оригинала — тот же текст задания, что у этапа.
     * null = фрагмент не найден.
     */
    suspend fun briefFor(projectSlug: String, bookSlug: String, fragmentId: String): String? {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val file = locateFragment(bookDir, fragmentId)?.file ?: return null
        if (file.draftBlocks.isEmpty()) {
            return "НЕТ ЧЕРНОВИКА — сначала переведи фрагмент (stage=draft)."
        }
        if (BlockText.allAnchors(file.draftBlocks)) {
            return "ЯКОРНЫЙ ФРАГМЕНТ — редактуры не требует: верни черновик дословно, строка в строку.\n<<<\n" +
                file.draftBlocks.joinToString("\n") + "\n>>>"
        }
        val selected = knowledge.selectForFragment(projectSlug, file.srcBlocks.joinToString("\n"))
        val template = promptStore.resolve(projectSlug, role)
        return BriefBuilder.build(
            BriefBuilder.Input(
                template = template,
                srcBlocks = file.draftBlocks,
                dictionary = selected.dictionary,
                notes = selected.notes,
                conventions = store.readConventions(projectSlug),
            ),
        )
    }

    /** Приём готовой редактуры от внешнего исполнителя: механика этапа без LLM, провал verify — без записей. */
    suspend fun submitExternal(
        projectSlug: String,
        bookSlug: String,
        fragmentId: String,
        rawText: String,
        sourceLabel: String,
    ): ExternalSubmitResult {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val located = locateFragment(bookDir, fragmentId)
            ?: return ExternalSubmitResult.Error("фрагмент не найден: $fragmentId")
        val file = located.file
        if (file.draftBlocks.isEmpty()) {
            return ExternalSubmitResult.Error("нет черновика — сначала переведи фрагмент (stage=draft)")
        }
        val blocks = parseBlocks(rawText, file.draftBlocks)
        // Жёстко: блоки против исходника 1:1 (как у этапа)
        if (blocks.size != file.srcBlocks.size) {
            return ExternalSubmitResult.Rejected("блоков ${file.srcBlocks.size} → ${blocks.size}: нужна строка в строку с черновиком")
        }
        val verifyResult = verifyProvider(projectSlug).verifyTranslation(file.srcBlocks, blocks, VerifyEngine.editedOptions())
        if (!verifyResult.ok) {
            AppLog.warn(stageName, "Внешняя редактура $fragmentId отклонена: ${verifyResult.summary()}")
            return ExternalSubmitResult.Rejected(verifyResult.summary())
        }
        writeFragmentFile(
            bookDir,
            file.copy(
                editedBlocks = blocks,
                verifyEdited = verifyResult.summary(),
                model = sourceLabel,
                promptTokens = 0,
                completionTokens = 0,
                toolCalls = 0,
            ),
        )
        updateStage(bookDir, fragmentId) {
            it.copy(
                status = "done",
                verify = verifyResult.summary(),
                error = "",
                attempts = it.attempts + 1,
                updatedAt = LocalDateTime.now().toString(),
            )
        }
        AppLog.info(stageName, "Фрагмент $fragmentId: принята внешняя редактура от $sourceLabel (${blocks.size} блоков)")
        return ExternalSubmitResult.Accepted(blocks.size, verifyResult.summary())
    }

    private suspend fun updateStage(bookDir: Path, fragmentId: String, transform: (ru.sebyanin.booktranslator.core.model.StageState) -> ru.sebyanin.booktranslator.core.model.StageState) {
        withManifest(bookDir) { manifest ->
            manifest.copy(
                parts = manifest.parts.map { part ->
                    part.copy(
                        fragments = part.fragments.map { frag ->
                            if (frag.id == fragmentId) frag.copy(edited = transform(frag.edited)) else frag
                        },
                    )
                },
            )
        }
    }
}
