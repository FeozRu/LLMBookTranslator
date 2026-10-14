package ru.sebyanin.booktranslator.core.pipeline

import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.AgentRunner
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.llm.PromptDefaults
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.CriticChapterFile
import ru.sebyanin.booktranslator.core.model.CriticDoubt
import ru.sebyanin.booktranslator.core.model.CriticSuggestion
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.ProjectMeta
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.tools.ToolContext
import ru.sebyanin.booktranslator.core.tools.ToolFacade
import ru.sebyanin.booktranslator.core.tools.ToolRegistry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.nio.file.Files
import java.time.LocalDateTime

/**
 * Этап 4: корректор по главе. Пары «перевод+оригинал» чанками ≤criticChunkMaxChars,
 * выход — предложения «было → стало → почему» + СОМНЕНИЯ. Правки применяет человек.
 */
class CriticStage(
    private val store: ProjectStore,
    private val promptStore: PromptStore,
    private val knowledge: KnowledgeService,
    private val client: LlmClient,
    private val settingsProvider: () -> AppSettings,
) {
    private val runner = AgentRunner(client)

    suspend fun run(
        projectSlug: String,
        bookSlug: String,
        chapterIndices: Set<Int>? = null,
        forceRedo: Boolean = false,
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): StageSummary {
        val settings = settingsProvider()
        val profile = settings.profileFor(AgentRole.CRITIC)
            ?: error("Не настроена модель LLM для роли «${AgentRole.CRITIC.title}»")
        val project = store.loadProject(projectSlug)
        val structure = store.loadStructure(projectSlug, bookSlug)
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val criticDir = bookDir.resolve("work").resolve("critic")
        Files.createDirectories(criticDir)

        val registry = toolRegistry(project, settings)
        val ctx = ToolContext(projectSlug, bookSlug, store, knowledge)
        val template = promptStore.resolve(projectSlug, AgentRole.CRITIC)
        val conventions = store.readConventions(projectSlug)

        val chapters = structure.chapters.filter { chapterIndices == null || it.index in chapterIndices }
        var done = 0
        var failed = 0
        // Уже пройденные главы — не работа: чтобы итоговое сообщение честно
        // говорило «нечего делать», а не «глав N, ошибок 0»
        var skipped = 0
        val semaphore = Semaphore(settings.concurrency.coerceAtLeast(1))

        coroutineScope {
            chapters.forEach { meta ->
                launch {
                    semaphore.acquire()
                    try {
                        val file = criticDir.resolve("chapter-%04d.json".format(meta.index))
                        val existing = runCatching {
                            AppJson.decodeFromString<CriticChapterFile>(Files.readString(file))
                        }.getOrNull()
                        // model пуст у файла, созданного разбором пометки переводчика до
                        // первого прогона корректора, — такая глава ещё НЕ пройдена
                        if (existing != null && existing.model.isNotBlank() && !forceRedo) {
                            AppLog.info("critic", "Глава ${meta.index}: уже пройдена (${existing.suggestions.size} предложений), пропуск")
                            skipped++
                        } else {
                            // Правки по пометкам переводчика (t-источник) переживают прогон:
                            // цитаты про текущий текст, человек их ещё не разобрал
                            val carry = carryOver(existing?.suggestions.orEmpty())
                            processChapter(
                                projectSlug, bookSlug, meta.index, template, conventions,
                                profile, registry, ctx, settings, criticDir, carry,
                            )
                            done++
                        }
                    } catch (e: ru.sebyanin.booktranslator.core.llm.PaymentRequiredException) {
                        throw e // 402: остановка всего прогона (гасит остальные главы), не провал одной
                    } catch (e: Exception) {
                        failed++
                        AppLog.error("critic", "Глава ${meta.index}: ${e.message}")
                    } finally {
                        semaphore.release()
                        onProgress("critic", done + failed, chapters.size)
                    }
                }
            }
        }
        return StageSummary("critic", chapters.size, done, failed, skipped)
    }

    private suspend fun processChapter(
        projectSlug: String,
        bookSlug: String,
        chapterIndex: Int,
        template: String,
        conventions: String,
        profile: ru.sebyanin.booktranslator.core.model.LlmProfile,
        registry: ToolRegistry?,
        ctx: ToolContext,
        settings: AppSettings,
        criticDir: java.nio.file.Path,
        carry: List<CriticSuggestion> = emptyList(),
    ) {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val pairs = chapterPairs(bookDir, chapterIndex)
        if (pairs.isEmpty()) error("нет переводов для главы $chapterIndex")

        // Чанки по знакам перевода
        val chunks = mutableListOf<List<PairBlock>>()
        var acc = mutableListOf<PairBlock>()
        var accLen = 0
        pairs.forEach { p ->
            val len = p.tr.count { !it.isWhitespace() }
            if (accLen > 0 && accLen + len > settings.criticChunkMaxChars) {
                chunks += acc
                acc = mutableListOf()
                accLen = 0
            }
            acc += p
            accLen += len
        }
        if (acc.isNotEmpty()) chunks += acc

        val selected = knowledge.selectForFragment(projectSlug, pairs.joinToString("\n") { it.src })

        val suggestions = mutableListOf<CriticSuggestion>()
        val doubts = mutableListOf<String>()
        chunks.forEachIndexed { ci, chunk ->
            val brief = BriefBuilder.build(
                BriefBuilder.Input(
                    template = template,
                    srcBlocks = chunk.map { it.src },
                    translationBlocks = chunk.map { it.tr },
                    dictionary = selected.dictionary,
                    notes = selected.notes,
                    conventions = conventions,
                    toolsDoc = if (registry != null) ToolFacade.docsText() else "",
                ),
            )
            val result = runner.run(
                profile = profile,
                userPrompt = brief,
                tools = registry?.specs(),
                executor = registry?.let { reg -> { call -> reg.execute(call.name, call.argumentsJson, ctx) } },
                maxToolCalls = settings.agentTools.maxToolCallsPerFragment,
                sourceLabel = "critic:${chapterIndex}/c${ci + 1}",
            )
            val parsed = parseSuggestions(result.text, chapterIndex)
            suggestions += parsed.suggestions
            if (parsed.doubts.isNotEmpty()) doubts += parsed.doubts
        }

        // Сквозная нумерация по главе: parseSuggestions нумерует с единицы в каждом
        // чанке — без переиндексации id дублируются и правка применяется к неверному.
        // Перенесённые правки по пометкам переводчика продолжают ту же нумерацию
        val unique = (suggestions + carry).mapIndexed { i, s -> s.copy(id = "c${chapterIndex}-${i + 1}") }

        // Автопроверка цитат: «было» должно встречаться в переводе ровно 1 раз
        val translation = pairs.joinToString("\n") { it.tr }
        val checked = unique.map { s ->
            val needle = s.was.trim('«', '»', '"', ' ').trim()
            val count = if (needle.isEmpty()) 0 else translation.split(needle).size - 1
            s.copy(grepCount = count)
        }

        val file = CriticChapterFile(
            chapter = chapterIndex,
            suggestions = checked,
            doubts = doubts.mapIndexed { i, text -> CriticDoubt("d${chapterIndex}-${i + 1}", text) },
            model = profile.model,
        )
        ProjectStore.atomicWrite(
            criticDir.resolve("chapter-%04d.json".format(chapterIndex)),
            AppJson.encodeToString(CriticChapterFile.serializer(), file),
        )
        AppLog.info(
            "critic",
            "Глава $chapterIndex: ${checked.size} предложений (${checked.count { it.grepCount == 1 }} применимых), чанков ${chunks.size}" +
                if (carry.isEmpty()) "" else ", перенесено из пометок переводчика: ${carry.size}",
        )
    }

    /** Инструменты агента: включены, если не отключены в настройках или в проекте. */
    private fun toolRegistry(project: ProjectMeta, settings: AppSettings): ToolRegistry? =
        if (settings.agentTools.enabledByDefault &&
            !settings.agentTools.disabledRoles.contains(AgentRole.CRITIC.id) &&
            !project.toolsDisabledRoles.contains(AgentRole.CRITIC.id)
        ) {
            ToolFacade.defaultRegistry(store, knowledge, settings.agentTools.maxToolResultChars)
        } else null

    private data class PairBlock(val src: String, val tr: String)

    /** Пары «оригинал + перевод» по фрагментам главы (edited; при отсутствии — draft). */
    private fun chapterPairs(bookDir: java.nio.file.Path, chapterIndex: Int): List<PairBlock> {
        val manifest = SplitStage.loadManifest(bookDir)
        val parts = manifest.parts.filter { it.chapterIndex == chapterIndex }.sortedBy { it.id }
        val pairs = mutableListOf<PairBlock>()
        parts.forEach { part ->
            part.fragments.sortedBy { it.id }.forEach { frag ->
                val f = readFragment(bookDir, frag.id)
                val tr = f.editedBlocks.ifEmpty { f.draftBlocks }
                if (tr.size != f.srcBlocks.size) {
                    AppLog.warn("critic", "Глава $chapterIndex, фрагмент ${frag.id}: блоки не сходятся (${f.srcBlocks.size}→${tr.size}) — по возможности исправь в ответе")
                }
                f.srcBlocks.indices.forEach { i ->
                    pairs += PairBlock(f.srcBlocks[i], tr.getOrNull(i) ?: "")
                }
            }
        }
        return pairs
    }

    /** Итог разбора сомнения корректором. */
    data class DoubtFixSummary(val doubtId: String, val added: Int)

    /**
     * Разбор сомнения: человек согласился (note — опциональный комментарий) — корректор
     * ищет затронутые места и возвращает точечные правки; они дописываются в обычную
     * очередь ревью. Для сомнения корректора (id dN-M) оно помечается разобранным той
     * же записью. Для пометки переводчика (id tN-…, маркер [[??: …]] из черновика)
     * текст сомнения выводится из текущего перевода, а файл корректора создаётся при
     * необходимости. При ошибке ничего не пишется — разбор можно повторить.
     */
    suspend fun fixDoubt(projectSlug: String, bookSlug: String, doubtId: String, note: String): DoubtFixSummary =
        if (doubtId.startsWith("t")) fixTranslatorDoubt(projectSlug, bookSlug, doubtId, note)
        else fixCriticDoubt(projectSlug, bookSlug, doubtId, note)

    private suspend fun fixCriticDoubt(
        projectSlug: String,
        bookSlug: String,
        doubtId: String,
        note: String,
    ): DoubtFixSummary {
        val chapter = doubtId.removePrefix("d").substringBefore("-").toIntOrNull()
            ?: error("id сомнения некорректен: $doubtId")
        val criticFile = criticFile(projectSlug, bookSlug, chapter)
        if (!Files.isRegularFile(criticFile)) error("файл корректора главы $chapter не найден")
        val doubt = AppJson.decodeFromString<CriticChapterFile>(Files.readString(criticFile))
            .doubts.firstOrNull { it.id == doubtId } ?: error("сомнение не найдено: $doubtId")
        val added = runFixAndAppend(projectSlug, bookSlug, chapter, doubt.text, doubtId, note, resolveInFile = doubtId to note)
        return DoubtFixSummary(doubtId, added)
    }

    private suspend fun fixTranslatorDoubt(
        projectSlug: String,
        bookSlug: String,
        doubtId: String,
        note: String,
    ): DoubtFixSummary {
        val chapter = doubtId.removePrefix("t").substringBefore("-").toIntOrNull()
            ?: error("id пометки некорректен: $doubtId")
        // Активная пометка (маркер ещё в тексте) или решённая редактурой — во втором
        // случае человек не согласен с решением редактора и просит правку по текущему тексту
        val doubt = TranslatorDoubts.list(store, projectSlug, bookSlug, chapter).firstOrNull { it.id == doubtId }
            ?: TranslatorDoubts.resolvedByEditor(store, projectSlug, bookSlug, chapter)
                .firstOrNull { it.doubt.id == doubtId }?.doubt
            ?: error("пометка уже убрана или текст главы обновился: $doubtId")
        val added = runFixAndAppend(projectSlug, bookSlug, chapter, doubt.text, doubtId, note, resolveInFile = null)
        return DoubtFixSummary(doubtId, added)
    }

    /** Общая часть разбора: правки корректора атомарно дописываются в файл главы (создаётся при необходимости). */
    private suspend fun runFixAndAppend(
        projectSlug: String,
        bookSlug: String,
        chapter: Int,
        doubtText: String,
        doubtId: String,
        note: String,
        resolveInFile: Pair<String, String>?,
    ): Int {
        val settings = settingsProvider()
        val profile = settings.profileFor(AgentRole.CRITIC)
            ?: error("Не настроена модель LLM для роли «${AgentRole.CRITIC.title}»")
        val project = store.loadProject(projectSlug)
        val registry = toolRegistry(project, settings)
        val ctx = ToolContext(projectSlug, bookSlug, store, knowledge)
        val conventions = store.readConventions(projectSlug)

        val pairs = chapterPairs(store.bookDir(projectSlug, bookSlug), chapter)
        if (pairs.isEmpty()) error("нет переводов для главы $chapter")
        val selected = knowledge.selectForFragment(projectSlug, pairs.joinToString("\n") { it.src })
        val translation = pairs.joinToString("\n") { it.tr }

        val brief = BriefBuilder.build(
            BriefBuilder.Input(
                template = PromptDefaults.DOUBT_FIX
                    .replace("{{DOUBT}}", doubtText)
                    .replace("{{NOTE}}", note.trim().ifEmpty { "(без комментария)" }),
                srcBlocks = pairs.map { it.src },
                translationBlocks = pairs.map { it.tr },
                dictionary = selected.dictionary,
                notes = selected.notes,
                conventions = conventions,
                toolsDoc = if (registry != null) ToolFacade.docsText() else "",
            ),
        )
        val result = runner.run(
            profile = profile,
            userPrompt = brief,
            tools = registry?.specs(),
            executor = registry?.let { reg -> { call -> reg.execute(call.name, call.argumentsJson, ctx) } },
            maxToolCalls = settings.agentTools.maxToolCallsPerFragment,
            sourceLabel = "critic-doubt:$doubtId",
        )
        val parsed = parseSuggestions(result.text, chapter).suggestions

        val criticFile = criticFile(projectSlug, bookSlug, chapter)
        Files.createDirectories(criticFile.parent)
        // Перезагрузка перед записью: пока модель работала, человек мог применить или
        // отклонить предложения — дописываем к СВЕЖЕМУ файлу, иначе потеряем его решения
        val fresh = if (Files.isRegularFile(criticFile)) {
            AppJson.decodeFromString<CriticChapterFile>(Files.readString(criticFile))
        } else {
            CriticChapterFile(chapter = chapter)
        }
        val appended = appendSuggestions(fresh.suggestions, parsed, chapter, translation)
            .map { it.copy(source = doubtId) }
        val next = fresh.copy(
            suggestions = fresh.suggestions + appended,
            doubts = resolveInFile?.let { (id, nt) ->
                fresh.doubts.map { if (it.id == id) it.copy(status = "resolved", note = nt) else it }
            } ?: fresh.doubts,
        )
        ProjectStore.atomicWrite(criticFile, AppJson.encodeToString(CriticChapterFile.serializer(), next))

        AppLog.info(
            "critic",
            if (appended.isEmpty()) "Сомнение $doubtId разобрано: правок не потребовалось"
            else "Сомнение $doubtId разобрано: корректор предложил ${appended.size} правок",
        )
        return appended.size
    }

    private fun criticFile(projectSlug: String, bookSlug: String, chapter: Int): java.nio.file.Path =
        store.bookDir(projectSlug, bookSlug).resolve("work").resolve("critic").resolve("chapter-%04d.json".format(chapter))

    data class Parsed(val suggestions: List<CriticSuggestion>, val doubts: List<String>)

    companion object {
        private val BULLET_PREFIX = Regex("^[—–•-]\\s*")

        /**
         * Правки, пережидающие прогон корректора: сделанные по пометкам переводчика
         * (t-источник) и ещё не разобранные человеком. Решённые (applied/rejected)
         * уже в тексте или отклонены — историю их действия прогон не трогает.
         */
        fun carryOver(existing: List<CriticSuggestion>): List<CriticSuggestion> =
            existing.filter { it.source.startsWith("t") && it.status == "pending" }

        /** Разбор блока «СОМНЕНИЯ» на отдельные сомнения: строка с маркера-тире начинает новый пункт, строки без маркера — продолжение предыдущего. */
        fun splitDoubts(text: String): List<String> {
            val items = mutableListOf<StringBuilder>()
            text.lines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                val stripped = line.replaceFirst(BULLET_PREFIX, "").trim()
                if (stripped.isEmpty()) return@forEach // строка-разделитель «—»
                if (stripped != line || items.isEmpty()) items += StringBuilder(stripped)
                else items.last().append("\n").append(stripped)
            }
            return items.map { it.toString() }
        }

        /** Парсинг формата «N. [приоритет] (категория) БЫЛО: … СТАЛО: … [СТАЛО-б: …] ПОЧЕМУ: …». */
        fun parseSuggestions(text: String, chapter: Int): Parsed {
            val doubts = Regex("СОМНЕНИЯ:?\\s*(.+)", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)?.trim().orEmpty()
                .takeIf { it.isNotEmpty() && !it.equals("нет", true) }
                ?.let { splitDoubts(it) } ?: emptyList()

            val re = Regex(
                """\d+\.\s*\[(критично|желательно|по желанию)]\s*\[([^\]]+)]\s*БЫЛО:\s*(.+?)\s*СТАЛО:\s*(.+?)\s*ПОЧЕМУ:\s*(.+?)(?=\n\d+\.|\nИтого|\Z)""",
                RegexOption.DOT_MATCHES_ALL,
            )
            val suggestions = re.findAll(text).mapIndexed { i, m ->
                // Альтернативные «стало»: строки СТАЛО-б: / СТАЛО-в: … в границах предложения
                val altRe = Regex("(?mi)^\\s*СТАЛО-\\s*[бвгbd]\\s*:\\s*(.+)$")
                val alternatives = altRe.findAll(m.value).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
                // Пачечная правка: одна и та же фраза крива во всех вхождениях (ВЕЗДЕ: да)
                val replaceAll = Regex("(?mi)^\\s*ВЕЗДЕ:\\s*(да|yes)\\s*$").containsMatchIn(m.value)
                CriticSuggestion(
                    id = "c${chapter}-${i + 1}",
                    chapter = chapter,
                    priority = m.groupValues[1],
                    category = m.groupValues[2].trim(),
                    was = m.groupValues[3].trim(),
                    became = m.groupValues[4].trim(),
                    alternatives = alternatives,
                    replaceAll = replaceAll,
                    why = m.groupValues[5].trim().lineSequence().firstOrNull() ?: "",
                )
            }.toList()
            return Parsed(suggestions, doubts)
        }

        /** Новые правки по сомнению: продолжают нумерацию главы и получают grepCount по текущему переводу. */
        fun appendSuggestions(
            existing: List<CriticSuggestion>,
            parsed: List<CriticSuggestion>,
            chapter: Int,
            translation: String,
        ): List<CriticSuggestion> {
            val offset = existing.mapNotNull { it.id.substringAfterLast("-").toIntOrNull() }.maxOrNull() ?: 0
            return parsed.mapIndexed { i, s ->
                val needle = s.was.trim('«', '»', '"', ' ').trim()
                val count = if (needle.isEmpty()) 0 else translation.split(needle).size - 1
                s.copy(id = "c${chapter}-${offset + i + 1}", grepCount = count)
            }
        }
    }

    private fun readFragment(bookDir: java.nio.file.Path, id: String): FragmentFile =
        AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(bookDir.resolve("work").resolve("fragments").resolve("$id.json")),
        )
}
