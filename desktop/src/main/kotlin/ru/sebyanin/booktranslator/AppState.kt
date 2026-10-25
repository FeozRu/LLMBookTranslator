package ru.sebyanin.booktranslator

import ru.sebyanin.booktranslator.core.glossary.GlossaryRevision
import ru.sebyanin.booktranslator.core.glossary.TermReplacer
import ru.sebyanin.booktranslator.core.AppServices
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.log.LogEvent
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.BookStructure
import ru.sebyanin.booktranslator.core.model.GlossaryEntry
import ru.sebyanin.booktranslator.core.model.GlossaryRevisionDecision
import ru.sebyanin.booktranslator.core.model.KnowledgeNote
import ru.sebyanin.booktranslator.core.model.ProjectMeta
import ru.sebyanin.booktranslator.core.model.WorkManifest
import ru.sebyanin.booktranslator.core.pipeline.AssemblyStage
import ru.sebyanin.booktranslator.core.pipeline.BlockEditor
import ru.sebyanin.booktranslator.core.pipeline.FragmentProgress
import ru.sebyanin.booktranslator.core.pipeline.QaReport
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

/** Статус главы для списка слева. */
data class ChapterUi(
    val index: Int,
    val title: String,
    val status: String, // queued | draft | drafted | editing | done | failed
    val label: String,
    val questions: Int = 0,
)

/** Оффер «заменить старый вариант термина в переведённом тексте»: канонический перевод сменился с from на to. */
data class TermReplaceOffer(val from: String, val to: String)

/** Живое состояние UI: проекты → книги → главы/этапы; действия пользователя. */
class AppState(val services: AppServices, private val scope: CoroutineScope) {

    val projects = MutableStateFlow<List<ProjectMeta>>(emptyList())
    val currentProject = MutableStateFlow<ProjectMeta?>(null)
    val books = MutableStateFlow<List<BookMeta>>(emptyList())
    val currentBook = MutableStateFlow<BookMeta?>(null)
    val structure = MutableStateFlow<BookStructure?>(null)
    val manifest = MutableStateFlow<WorkManifest?>(null)
    val glossary = MutableStateFlow<List<GlossaryEntry>>(emptyList())
    val notes = MutableStateFlow<List<KnowledgeNote>>(emptyList())
    val selectedChapter = MutableStateFlow<Int?>(null)
    val viewVersion = MutableStateFlow("edited") // draft | edited
    val runningStage = MutableStateFlow<String?>(null)
    val stageProgress = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)
    val logEvents: StateFlow<List<LogEvent>> = AppLog.events

    /** Запрос на переключение центральной вкладки извне (напр., после запуска критика). */
    val requestedTab = MutableStateFlow<String?>(null)

    /** Версия данных корректора: инкрементируется, когда правки меняются на диске. */
    val criticVersion = MutableStateFlow(0)

    /** Решения ревизора глоссария: файл вне манифеста, уровень ПРОЕКТА. */
    val glossaryRevision = MutableStateFlow<List<GlossaryRevisionDecision>>(emptyList())

    /** Последний отчёт QA текущей книги (сбрасывается при смене книги). */
    val qaReport = MutableStateFlow<QaReport?>(null)

    /**
     * Кэш [chapterBlocks]: строки окна непрерывного чтения пересобираются при каждой
     * догрузке/вытеснении главы, и без кэша пересборка синхронно перечитывала с диска
     * ВСЕ главы окна (исходник + фрагменты + собранный файл на главу) прямо в середине
     * кадра — догрузка блокировала UI-поток, а зарегистрированный в это же время
     * requestScrollToItem затем отбрасывал накопленную за паузу прокрутку (вид
     * «возвращало» при скролле). Штамп — версии данных: viewVersion (черновик/
     * редактура) и criticVersion (refreshBook поднимает после любого этапа и правки
     * блоков); ключ — slugs книги.
     */
    private val chapterViewCache = mutableMapOf<String, Pair<Pair<String, Int>, ChapterView>>()

    /**
     * Версия решений ревизора: инкрементируется при перезагрузке знаний
     * (после прогона и после каждого действия) — урок criticVersion.
     */
    val glossaryRevisionVersion = MutableStateFlow(0)

    /** Оффер замены термина в переведённом тексте; снимается кнопкой или сам, когда менять нечего. */
    val termReplaceOffer = MutableStateFlow<TermReplaceOffer?>(null)

    /** Готовое примечание от модели — забирает панель примечаний редактора блоков и снимается ею. */
    val noteDraft = MutableStateFlow<String?>(null)

    private var stageJob: Job? = null

    init {
        refreshProjects()
        restoreSession()
        // Отладка: -Dbt.msg="..." сеет статус-сообщение (в духе -Dbt.theme) — проверить
        // карточку простоя без запуска этапа. Кириллица в -Dbt.* через шелл Windows
        // искажается, для не-ASCII текста есть -Dbt.msgFile=<путь> (UTF-8)
        System.getProperty("bt.msg")?.let { message.value = it }
        System.getProperty("bt.msgFile")?.let { p ->
            runCatching { message.value = Files.readString(Path.of(p)).trim() }
        }
        // Отладка: -Dbt.stage="draft" сеет идущий этап — проверить маскота/статус
        // «Выполняется…» без реального прогона (кнопка «Остановить» не нажимать)
        System.getProperty("bt.stage")?.let { runningStage.value = it }
        // Отладка: -Dbt.termOffer="паутина|нить" сеет оффер замены термина — банер
        // в Глоссарии без правки канона (нужен bt.dataRoot с сид-книгой, где слово есть).
        // Кириллица через шелл искажается — есть файловый вариант bt.termOfferFile (UTF-8)
        fun seedTermOffer(raw: String) {
            val parts = raw.trim().split("|").map { it.trim() }
            if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                termReplaceOffer.value = TermReplaceOffer(from = parts[0], to = parts[1])
            }
        }
        System.getProperty("bt.termOffer")?.let { seedTermOffer(it) }
        System.getProperty("bt.termOfferFile")?.let { p ->
            runCatching { Files.readString(Path.of(p)) }.getOrNull()?.let { seedTermOffer(it) }
        }
    }

    // ---------- навигация ----------

    fun refreshProjects() {
        projects.value = runCatching { services.projectStore.listProjects() }
            .getOrElse { emptyList() }
    }

    /** Восстановление сессии: последний проект + его последняя книга. */
    private fun restoreSession() {
        val lastSlug = services.settings.value.lastProjectSlug
        if (lastSlug.isBlank()) return
        projects.value.firstOrNull { it.slug == lastSlug }?.let { openProject(it) }
    }

    fun openProject(meta: ProjectMeta) {
        currentProject.value = meta
        books.value = runCatching { services.projectStore.listBooks(meta.slug) }.getOrElse { emptyList() }
        refreshKnowledge()
        // Последняя книга этого проекта (если ещё существует)
        val lastBookSlug = services.settings.value.lastBookByProject[meta.slug]
        val lastBook = books.value.firstOrNull { it.slug == lastBookSlug }
        if (lastBook != null) {
            openBook(lastBook)
        } else {
            currentBook.value = null
            structure.value = null
            manifest.value = null
            selectedChapter.value = null
        }
        services.updateSettings { it.copy(lastProjectSlug = meta.slug) }
    }

    fun openBook(meta: BookMeta) {
        currentProject.value?.let { p ->
            services.updateSettings { it.copy(lastBookByProject = it.lastBookByProject + (p.slug to meta.slug)) }
        }
        currentBook.value = meta
        qaReport.value = null
        refreshBook()
        selectedChapter.value = structure.value?.chapters?.firstOrNull()?.index
    }

    fun refreshBook() {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runCatching {
            structure.value = services.projectStore.loadStructure(p.slug, b.slug)
        }
        manifest.value = runCatching {
            SplitStage.loadManifest(services.projectStore.bookDir(p.slug, b.slug))
        }.getOrNull()
        books.value = runCatching { services.projectStore.listBooks(p.slug) }.getOrElse { books.value }
        refreshKnowledge()
        // Статусы правок корректора живут вне манифеста — подписчикам нужен свой сигнал
        criticVersion.value += 1
    }

    fun refreshKnowledge() {
        val p = currentProject.value ?: return
        glossary.value = runCatching { services.knowledge.glossary(p.slug) }.getOrElse { emptyList() }
        notes.value = runCatching { services.knowledge.notes(p.slug) }.getOrElse { emptyList() }
        glossaryRevision.value = runCatching { services.projectStore.loadGlossaryRevision(p.slug).decisions }
            .getOrElse { emptyList() }
        glossaryRevisionVersion.value += 1
    }

    // ---------- действия ----------

    fun createProject(name: String, sourceLang: String, targetLang: String, preset: String, open: Boolean = true) {
        runCatching {
            val meta = services.projectStore.createProject(name, sourceLang, targetLang, preset)
            services.promptStore.seedProjectFromGlobal(meta.slug)
            refreshProjects()
            if (open) openProject(meta)
        }.onFailure { fail(it) }
    }

    fun importBook(path: String) {
        val p = currentProject.value ?: return
        scope.launch {
            runningStage.value = "import"
            withContext(Dispatchers.IO) {
                runCatching {
                    val report = services.importStage.importBook(p.slug, Path.of(path))
                    message.value = "Импортировано: ${report.chapters} глав, ${report.blocks} блоков, ${report.chars} знаков"
                    report.book
                }.onSuccess { book ->
                    books.value = services.projectStore.listBooks(p.slug)
                    openBook(book)
                }.onFailure { fail(it) }
            }
            runningStage.value = null
        }
    }

    private fun runStage(name: String, progressText: (FragmentProgress) -> String = { "" }, block: suspend () -> Unit) {
        if (runningStage.value != null) {
            message.value = "Уже выполняется этап «${runningStage.value}»"
            return
        }
        stageJob = scope.launch {
            runningStage.value = name
            stageProgress.value = ""
            runCatching { block() }.onFailure {
                when {
                    it is kotlinx.coroutines.CancellationException -> {
                        AppLog.info(name, "Этап остановлен пользователем (готовое сохранено)")
                    }
                    // HTTP 402: этап остановился сам целиком — объясняем человеку, что делать
                    it is ru.sebyanin.booktranslator.core.llm.PaymentRequiredException -> {
                        message.value = "Ошибка: все задачи остановлены — ${it.message}. " +
                            "Пополните баланс и запустите этап заново; готовое сохранено."
                        AppLog.error("ui", it.message ?: it.toString())
                    }
                    else -> fail(it)
                }
            }
            runningStage.value = null
            stageProgress.value = ""
            refreshBook()
        }
    }

    fun runSplit() {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runStage("split") {
            withContext(Dispatchers.IO) {
                val s = services.settings.value
                services.splitStage.split(p.slug, b.slug, s.partMaxChars, s.fragmentTargetChars)
                    .let { message.value = "Нарезка: ${it.parts} частей, ${it.fragments} фрагментов" }
            }
        }
    }

    fun runDraft(chapters: Set<Int>? = null) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runStage(
            "draft",
            progressText = { "черновик: ${it.done + it.failed}/${it.total} (${it.failed} ош.)" },
        ) {
            // Прогресс виден СРАЗУ, а не после первого ответа модели
            val total = manifest.value?.allFragments()?.count { !it.draft.isDone } ?: 0
            stageProgress.value = "черновик: 0/$total"
            services.draftStage.run(p.slug, b.slug, chapters) { progress ->
                stageProgress.value = "черновик: ${progress.done + progress.failed}/${progress.total} (ошибок ${progress.failed})"
                refreshManifest(p.slug, b.slug)
            }.let { summary ->
                // total == 0 — целей у прогона нет: все фрагменты уже переведены
                // («готово 0, ошибок 0» здесь выглядит как работа впустую)
                message.value = if (summary.total == 0) "Черновик: нечего делать — все фрагменты уже переведены"
                else "Черновик: готово ${summary.done}, ошибок ${summary.failed}"
            }
        }
    }

    fun runEdit(chapters: Set<Int>? = null) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runStage("edit") {
            val total = manifest.value?.allFragments()?.count { it.draft.isDone && !it.edited.isDone } ?: 0
            stageProgress.value = "редактура: 0/$total"
            services.editStage.run(p.slug, b.slug, chapters) { progress ->
                stageProgress.value = "редактура: ${progress.done + progress.failed}/${progress.total} (ошибок ${progress.failed})"
                refreshManifest(p.slug, b.slug)
            }.let { summary ->
                message.value = if (summary.total == 0) "Редактура: нечего делать — все фрагменты уже отредактированы"
                else "Редактура: готово ${summary.done}, ошибок ${summary.failed}"
            }
        }
    }

    fun runCritic(chapters: Set<Int>? = null, forceRedo: Boolean = false) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runStage("critic") {
            val total = chapters?.size ?: structure.value?.chapters?.size ?: 0
            stageProgress.value = "корректор: 0/$total глав"
            services.criticStage.run(p.slug, b.slug, chapters, forceRedo) { _, done, total ->
                stageProgress.value = "корректор: $done/$total глав"
            }.let { s ->
                // Пропущенные (уже пройденные) главы — не работа: done считает
                // только реально обработанные, skipped идёт отдельным полем
                message.value = when {
                    s.total == 0 -> "Корректор: нечего делать — нет глав для проверки"
                    s.skipped == s.total -> "Корректор: нечего делать — все главы уже проверены"
                    else -> "Корректор: глав ${s.done}, ошибок ${s.failed}" +
                        (if (s.skipped > 0) " (пропущено уже пройденных: ${s.skipped})" else "")
                }
            }
        }
    }

    /**
     * Разбор сомнения корректором: человек согласился с сомнением (note — комментарий).
     * Результат — точечные правки в обычной очереди ревью; сомнение помечается
     * разобранным самим разбором, при ошибке остаётся неразобранным.
     */
    fun runDoubtFix(doubtId: String, note: String) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runStage("critic") {
            stageProgress.value = "корректор: разбор сомнения $doubtId"
            withContext(Dispatchers.IO) {
                services.criticStage.fixDoubt(p.slug, b.slug, doubtId, note)
            }.let { r ->
                message.value = if (r.added > 0) {
                    "Сомнение разобрано: корректор предложил ${r.added} правок — они в очереди ревью"
                } else {
                    "Сомнение разобрано: корректор не нашёл, что править"
                }
            }
        }
    }

    /**
     * Прогон LLM-ревизора по глоссарию: пакеты ~30 записей, вердикты
     * keep/drop/fix/split в glossary-revision.json. Скоуп: "queue" — очередь
     * предложений, "canon" — утверждённый канон. Решения не применяются сами.
     */
    fun runGlossaryRevisor(scope: String = "queue") {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runStage("revisor") {
            val label = if (scope == "canon") "ревизор (канон)" else "ревизор"
            val total = glossary.value.count { if (scope == "canon") it.isApproved else it.status == "proposed" }
            // Прогресс виден СРАЗУ, а не после первого ответа модели
            stageProgress.value = "$label: 0/$total записей"
            withContext(Dispatchers.IO) {
                services.glossaryRevisor.run(p.slug, b.slug, scope) { done, tot ->
                    stageProgress.value = "$label: $done/$tot записей"
                }
            }.let { s ->
                val scopeNote = if (scope == "canon") " по канону" else ""
                message.value = if (s.reviewed == 0) "Ревизор$scopeNote: записей нет — замечаний нет"
                else "Ревизор$scopeNote: ${s.drop + s.fix} замечаний по ${s.reviewed} записям (drop: ${s.drop}, fix: ${s.fix})"
            }
        }
    }

    /**
     * Слой 1 замены термина: механическая транзакционная замена точных вхождений
     * старого варианта по всей книге (без LLM). Результат — в колбэк для банера.
     */
    fun replaceTermEverywhere(from: String, to: String, onDone: (TermReplacer.ReplaceOutcome) -> Unit = {}) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { services.termReplacer.replaceEverywhere(p.slug, b.slug, from, to, services.verifyFor(p.slug)) }
                    .getOrElse { TermReplacer.ReplaceOutcome.Failed(it.message ?: it.toString()) }
            }
            // Фрагменты правились на диске — рабочая зона и критик должны увидеть свежий текст
            refreshBook()
            // Итог — и в статус: банер может сняться сам, когда менять больше нечего
            message.value = when (outcome) {
                is TermReplacer.ReplaceOutcome.Done ->
                    "Замена термина: ${outcome.replaced} вхождений в ${outcome.fragments.size} фрагментах"
                is TermReplacer.ReplaceOutcome.Failed ->
                    "Замена термина не выполнена: ${outcome.reason}"
            }
            onDone(outcome)
        }
    }

    /**
     * Слой 2 замены термина: LLM-прогон «замени формы X → Y» только по фрагментам,
     * где слово встречается (id целей — из скана банера). Полный перезапуск
     * редактуры не запускается.
     */
    fun runTermFix(from: String, to: String, targets: List<String>) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        if (targets.isEmpty()) {
            message.value = "Фрагментов с формами термина не найдено — прогон модели не нужен"
            return
        }
        runStage("termfix") {
            stageProgress.value = "замена термина: 0/${targets.size} фрагментов"
            withContext(Dispatchers.IO) {
                services.termFixer.run(p.slug, b.slug, from, to, targets, services.verifyFor(p.slug)) { done, tot ->
                    stageProgress.value = "замена термина: $done/$tot фрагментов"
                }
            }.let { s ->
                message.value = buildString {
                    append("Замена форм термина по модели: изменено ${s.changed} из ${s.fragments} фрагментов")
                    if (s.unchanged > 0) append(", без форм: ${s.unchanged}")
                    if (s.failed > 0) append(", ошибок: ${s.failed} — можно повторить прогон")
                }
            }
        }
    }

    fun assembleCurrentChapter() {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        val ch = selectedChapter.value ?: return
        runStage("assemble") {
            withContext(Dispatchers.IO) {
                val verify = services.verifyFor(p.slug)
                val version = if (allEditedDone(ch)) AssemblyStage.Version.EDITED else AssemblyStage.Version.DRAFT
                val result = services.assemblyStage.assembleChapter(p.slug, b.slug, ch, version, verify)
                message.value = if (result.ok) "Глава $ch собрана (${result.blocks} блоков)"
                else "Глава $ch: ${result.problems.joinToString("; ")}"
            }
        }
    }

    /**
     * Ручная правка блока «Перевода» (редактор по клику): сохранение в эффективное
     * поле фрагмента (verify внутри BlockEditor при сбое оставляет файл нетронутым)
     * и тихая пересборка главы — собранный файл читают предпросмотр и экспорт,
     * без пересборки правка осталась бы невидимой.
     */
    fun saveBlockEdit(ref: BlockEditor.BlockRef, chapter: Int, newText: String) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runStage("edit-block") {
            withContext(Dispatchers.IO) {
                val error = services.blockEditor.saveEdit(p.slug, b.slug, ref, newText)
                if (error != null) error("правка не сохранена: $error")
                val verify = services.verifyFor(p.slug)
                val version = if (allEditedDone(chapter)) AssemblyStage.Version.EDITED else AssemblyStage.Version.DRAFT
                val result = services.assemblyStage.assembleChapter(p.slug, b.slug, chapter, version, verify)
                message.value = if (result.ok) "Блок сохранён, глава пересобрана"
                else "Блок сохранён, но глава не пересобралась: ${result.problems.joinToString("; ")}"
            }
        }
    }

    /**
     * Замена изображения в якорном блоке «Перевода»: выбор файла, копия в images/
     * книги (уникальное имя при коллизии), смена маркера в эффективном поле фрагмента
     * (BlockEditor.replaceImage) и тихая пересборка главы. Ручная правка якоря
     * запрещена (BlockEditor.saveEdit отклоняет) — картинка меняется только так.
     */
    fun replaceBlockImage(ref: BlockEditor.BlockRef, chapter: Int) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        val picked = pickImageFile() ?: return
        runStage("replace-image") {
            withContext(Dispatchers.IO) {
                val name = services.blockEditor.importImage(p.slug, b.slug, picked)
                val error = services.blockEditor.replaceImage(p.slug, b.slug, ref, name)
                if (error != null) error("замена не сохранена: $error")
                val st = structure.value
                if (st != null && name !in st.images) {
                    services.projectStore.saveStructure(p.slug, b.slug, st.copy(images = st.images + name))
                    structure.value = services.projectStore.loadStructure(p.slug, b.slug)
                }
                val verify = services.verifyFor(p.slug)
                val version = if (allEditedDone(chapter)) AssemblyStage.Version.EDITED else AssemblyStage.Version.DRAFT
                val result = services.assemblyStage.assembleChapter(p.slug, b.slug, chapter, version, verify)
                message.value = if (result.ok) {
                    "Изображение заменено на $name, глава пересобрана"
                } else {
                    "Изображение заменено, но глава не пересобралась: ${result.problems.joinToString("; ")}"
                }
            }
        }
    }

    /** Нативный диалог выбора картинки; не-изображение — сообщение и отмена. */
    private fun pickImageFile(): java.nio.file.Path? {
        val dialog = FileDialog(Frame(), "Выберите изображение", FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val path = java.nio.file.Path.of(dialog.directory, name)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("jpg", "jpeg", "png", "gif", "svg", "webp")) {
            message.value = "Это не изображение (нужны jpg, png, gif, svg или webp): $name"
            return null
        }
        return path
    }

    /** Примечание от модели к выделенному месту (роль «Примечания»; дефолт — общая модель). */
    fun requestNote(srcBlock: String, trBlock: String, selection: String) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runStage("note") {
            stageProgress.value = "примечание: запрос к модели…"
            val note = services.blockEditor.requestNote(p.slug, b.slug, srcBlock, trBlock, selection)
            noteDraft.value = note
            message.value = "Примечание готово — проверьте и вставьте"
        }
    }

    fun buildEpub() = buildBook("epub", askTarget = true)
    fun buildFb2() = buildBook("fb2", askTarget = true)

    /** Сборка без диалога — для запуска извне (MCP): файл остаётся в build/. */
    fun buildBookAuto(kind: String) = buildBook(kind, askTarget = false)

    private fun buildBook(kind: String, askTarget: Boolean) {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        // Место и имя спрашиваем ДО сборки; отмена — ничего не запускаем
        val target = if (askTarget) {
            askSaveTarget("${b.slug}-ru", if (kind == "epub") "epub" else "fb2") ?: return
        } else null
        runStage("build-$kind") {
            withContext(Dispatchers.IO) {
                if (kind == "epub") {
                    val r = services.epubBuilder.build(p.slug, b.slug)
                    val dest = target?.let { copyToTarget(r.file, it) } ?: r.file
                    val where = if (dest == r.file) "" else " → ${dest.parent}"
                    message.value = "EPUB: ${dest.fileName}$where (глав ${r.chapters}, примечаний ${r.notes})"
                } else {
                    val f = services.fb2Builder.build(p.slug, b.slug)
                    val dest = target?.let { copyToTarget(f, it) } ?: f
                    val where = if (dest == f) "" else " → ${dest.parent}"
                    message.value = "FB2: ${dest.fileName}$where"
                }
            }
        }
    }

    /** Нативный диалог «Сохранить как»: место и имя; null — пользователь отменил. */
    private fun askSaveTarget(baseName: String, ext: String): Path? {
        val dialog = FileDialog(Frame(), "Сохранить книгу", FileDialog.SAVE)
        dialog.file = "$baseName.$ext"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val dir = dialog.directory ?: return null
        val withExt = if (name.endsWith(".$ext", ignoreCase = true)) name else "$name.$ext"
        return Path.of(dir, withExt)
    }

    /** Сборка остаётся в build/ проекта; выбранное место получает копию. */
    private fun copyToTarget(built: Path, target: Path): Path {
        if (built.toAbsolutePath() == target.toAbsolutePath()) return built
        return Files.copy(built, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Открыть папку книги (или проекта, если книга не выбрана) в файловом менеджере. */
    fun openFolder() {
        val p = currentProject.value ?: return
        val b = currentBook.value
        val dir = if (b != null) services.projectStore.bookDir(p.slug, b.slug) else services.projectStore.projectDir(p.slug)
        runCatching { Desktop.getDesktop().open(dir.toFile()) }.onFailure { fail(it) }
    }

    fun runQa() {
        val p = currentProject.value ?: return
        val b = currentBook.value ?: return
        runStage("qa") {
            withContext(Dispatchers.IO) {
                val report = services.qaStage.run(p.slug, b.slug, services.verifyFor(p.slug))
                qaReport.value = report
                message.value = if (report.ok) "QA: чисто (${report.chaptersTranslated}/${report.chapters} глав; предупреждений ${report.warnings.size})"
                else "QA: проблем ${report.problems.size}: ${report.problems.take(3).joinToString("; ")}"
            }
        }
    }

    fun stopStage() {
        stageJob?.cancel()
        stageJob = null
        runningStage.value = null
        message.value = "Этап остановлен (готовое сохранено; незавершённые фрагменты можно перезапустить)"
    }

    /** Запуск этапа извне (MCP): находит проект/книгу и запускает этап, если конвейер свободен. */
    fun launchStage(project: String, book: String, stage: String, chapters: List<Int>?): Result<String> {
        if (runningStage.value != null) {
            return Result.failure(IllegalStateException("уже выполняется этап «${runningStage.value}»"))
        }
        return runCatching {
            val p = services.projectStore.listProjects().firstOrNull { it.slug == project }
                ?: error("проект не найден: $project")
            val b = services.projectStore.listBooks(project).firstOrNull { it.slug == book }
                ?: error("книга не найдена: $book")
            openProject(p)
            openBook(b)
            val ch = chapters?.toSet()
            when (stage) {
                "split" -> runSplit()
                "draft" -> runDraft(ch)
                "edit" -> runEdit(ch)
                "critic" -> runCritic(ch)
                "revisor" -> runGlossaryRevisor("queue")
                "revisor_canon" -> runGlossaryRevisor("canon")
                "qa" -> runQa()
                "build_epub" -> buildBookAuto("epub")
                "build_fb2" -> buildBookAuto("fb2")
                else -> error("неизвестный этап: $stage")
            }
            "$stage/$book"
        }
    }

    /**
     * MCP-инструмент изменил файлы мимо конвейера (критик, фрагменты, глоссарий):
     * открытой книге нужен полный refreshBook (манифест + criticVersion),
     * остальным книгам проекта — глоссарий (он общий на проект).
     */
    fun externalMutated(project: String, book: String?) {
        val p = currentProject.value ?: return
        if (p.slug != project) return
        if (book != null && currentBook.value?.slug == book) refreshBook() else refreshKnowledge()
    }

    // ---------- знания ----------

    fun approveTerm(entry: GlossaryEntry, translation: String) {
        val p = currentProject.value ?: return
        services.knowledge.approveTerm(p.slug, entry.original, translation)
        // Канонический перевод изменился — предложить перенос изменения в текст
        // (банер «Замена термина»): утверждение альтернативы, правка в «Терминах»,
        // «Поправить на» у ревизора — все пути через approveTerm
        if (translation.isNotBlank() && translation != entry.translation) {
            termReplaceOffer.value = TermReplaceOffer(from = entry.translation, to = translation)
        }
        refreshKnowledge()
    }

    fun rejectTerm(entry: GlossaryEntry) {
        val p = currentProject.value ?: return
        services.knowledge.rejectTerm(p.slug, entry.original)
        refreshKnowledge()
    }

    /** Отклонить как дубль ревизии: пометка-причина остаётся в записи глоссария. */
    fun rejectTermDuplicate(entry: GlossaryEntry, note: String) {
        val p = currentProject.value ?: return
        services.knowledge.updateTerm(p.slug, entry.original) { it.copy(note = note) }
        services.knowledge.rejectTerm(p.slug, entry.original)
        refreshKnowledge()
    }

    // ---------- решения по замечаниям ревизора (только по кнопке человека) ----------

    /** drop: убрать термин из очереди как мусор/дубль, с причиной ревизора в note. */
    fun applyRevisionDrop(decision: GlossaryRevisionDecision) {
        val p = currentProject.value ?: return
        val entry = glossary.value.firstOrNull { it.original == decision.original }
        if (entry == null) {
            message.value = "Запись «${decision.original}» уже не в очереди"
            markRevisionDecision(p.slug, decision.id, "dismissed")
            return
        }
        rejectTermDuplicate(entry, "убрано ревизором: ${decision.reason}")
        markRevisionDecision(p.slug, decision.id, "applied")
    }

    /** fix: утвердить термин с предложенным ревизором переводом. */
    fun applyRevisionFix(decision: GlossaryRevisionDecision) {
        val p = currentProject.value ?: return
        val entry = glossary.value.firstOrNull { it.original == decision.original }
        if (entry == null) {
            message.value = "Запись «${decision.original}» уже не в очереди"
            markRevisionDecision(p.slug, decision.id, "dismissed")
            return
        }
        approveTerm(entry, decision.suggested)
        markRevisionDecision(p.slug, decision.id, "applied")
    }

    /** Человек не согласен с замечанием: решение уходит в dismissed, запись не трогается. */
    fun dismissRevision(decision: GlossaryRevisionDecision) {
        val p = currentProject.value ?: return
        markRevisionDecision(p.slug, decision.id, "dismissed")
    }

    /** split от ревизора: упакованная запись делится на отдельные предложения по частям решения. */
    fun applyRevisionSplit(decision: GlossaryRevisionDecision) {
        val p = currentProject.value ?: return
        val entry = glossary.value.firstOrNull { it.original == decision.original }
        if (entry == null) {
            message.value = "Запись «${decision.original}» уже не в очереди"
            markRevisionDecision(p.slug, decision.id, "dismissed")
            return
        }
        val parts = decision.parts.map { it.original to it.translation }
        if (parts.size < 2) {
            message.value = "У решения «${decision.original}» нет частей для разделения"
            markRevisionDecision(p.slug, decision.id, "dismissed")
            return
        }
        splitEntryInternal(entry, parts, "разделено ревизором: было упаковано несколько терминов")
        markRevisionDecision(p.slug, decision.id, "applied")
    }

    /** Разделить упакованную запись из механической ревизии (перевод содержит ещё пары «→»). */
    fun splitPackedEntry(entry: GlossaryEntry) {
        val parts = GlossaryRevision.splitPacked(entry.original, entry.translation)
        if (parts.size < 2) {
            message.value = "Не удалось разделить «${entry.original}»: пар «→» не найдено"
            return
        }
        splitEntryInternal(
            entry, parts,
            "разделено ревизией: было упаковано несколько терминов (" +
                parts.joinToString("; ") { "${it.first} → ${it.second}" } + ")",
        )
    }

    /**
     * Разделение упакованной записи: первая пара с тем же оригиналом ПЕРЕПИСЫВАЕТ
     * запись на чистый перевод (запись остаётся в очереди), остальные пары добавляются
     * в очередь отдельными предложениями. Человек дальше утверждает каждую как обычно.
     */
    private fun splitEntryInternal(entry: GlossaryEntry, parts: List<Pair<String, String>>, note: String) {
        val p = currentProject.value ?: return
        val first = parts.first()
        if (first.first == entry.original) {
            services.knowledge.updateTerm(p.slug, entry.original) { it.copy(translation = first.second, note = note) }
        } else {
            services.knowledge.updateTerm(p.slug, entry.original) { it.copy(note = note) }
            services.knowledge.rejectTerm(p.slug, entry.original)
        }
        parts.drop(1).forEach { (original, translation) ->
            services.knowledge.proposeTerm(p.slug, original, translation, context = entry.context, reason = entry.reason)
        }
        refreshKnowledge()
    }

    private fun markRevisionDecision(projectSlug: String, id: String, status: String) {
        runCatching {
            val file = services.projectStore.loadGlossaryRevision(projectSlug)
            services.projectStore.saveGlossaryRevision(
                projectSlug,
                file.copy(decisions = file.decisions.map { if (it.id == id) it.copy(status = status) else it }),
            )
        }.onFailure { fail(it) }
        refreshKnowledge()
    }

    fun approveNote(note: KnowledgeNote, textOverride: String? = null) {
        val p = currentProject.value ?: return
        services.knowledge.approveNote(p.slug, note.entity, note.situation, textOverride)
        refreshKnowledge()
    }

    fun rejectNote(note: KnowledgeNote) {
        val p = currentProject.value ?: return
        services.knowledge.rejectNote(p.slug, note.entity, note.situation)
        refreshKnowledge()
    }

    /** Термин удалён из глоссария целиком (утверждённые термины правятся руками). */
    fun removeTerm(entry: GlossaryEntry) {
        val p = currentProject.value ?: return
        services.knowledge.removeTerm(p.slug, entry.original)
        refreshKnowledge()
    }

    /** Заметка удалена из «библии» целиком. */
    fun removeNote(note: KnowledgeNote) {
        val p = currentProject.value ?: return
        services.knowledge.removeNote(p.slug, note.entity, note.situation)
        refreshKnowledge()
    }

    // ---------- производное ----------

    private fun refreshManifest(projectSlug: String, bookSlug: String) {
        manifest.value = runCatching {
            SplitStage.loadManifest(services.projectStore.bookDir(projectSlug, bookSlug))
        }.getOrNull()
    }

    fun chapterUiList(): List<ChapterUi> {
        val st = structure.value ?: return emptyList()
        val mf = manifest.value
        val p = currentProject.value ?: return emptyList()
        val b = currentBook.value ?: return emptyList()
        return st.chapters.map { ch ->
            val parts = mf?.parts?.filter { it.chapterIndex == ch.index } ?: emptyList()
            val frags = parts.flatMap { it.fragments }
            val (status, label) = when {
                frags.isEmpty() -> "queued" to "нужна нарезка"
                frags.any { it.draft.isFailed } -> "failed" to "ошибка черновика"
                frags.all { it.draft.isDone } && frags.none { it.edited.isDone } -> "drafted" to "черновик готов"
                frags.any { it.edited.isFailed } -> "failed" to "ошибка редактуры"
                frags.all { it.edited.isDone } -> "done" to "переведено"
                frags.any { it.draft.isDone } -> "editing" to "в работе"
                else -> "draft" to "черновик"
            }
            val pending = services.suggestionService.pending(p.slug, b.slug, ch.index).size
            ChapterUi(ch.index, ch.title, status, label, pending)
        }
    }

    private fun allEditedDone(chapter: Int): Boolean =
        manifest.value?.parts?.filter { it.chapterIndex == chapter }
            ?.flatMap { it.fragments }?.all { it.edited.isDone } == true

    /** Блоки выбранной главы: исходник + текущая версия перевода. Если выбранной версии
     *  ещё нет (например, редактуры), показывается другая — [ChapterView.shownVersion]. */
    data class ChapterView(
        val src: List<String>,
        val out: List<String>,
        val shownVersion: String,
        /** Параллельно out: какой фрагмент/блок стоит за строкой — ручной редактор блоков.
         *  Пустые строки-заполнители (перевода ещё нет) не редактируются. */
        val refs: List<BlockEditor.BlockRef> = emptyList(),
    )

    fun chapterBlocks(chapter: Int): ChapterView {
        val empty = ChapterView(emptyList(), emptyList(), viewVersion.value)
        val p = currentProject.value ?: return empty
        val b = currentBook.value ?: return empty
        val st = structure.value ?: return empty
        val meta = st.chapters.firstOrNull { it.index == chapter } ?: return empty

        val stamp = viewVersion.value to criticVersion.value
        val key = "${p.slug}/${b.slug}/$chapter"
        chapterViewCache[key]?.takeIf { it.first == stamp }?.let { return it.second }

        val src = services.projectStore.readChapterSource(p.slug, b.slug, meta.file).map { it.text }

        val requested = viewVersion.value
        val alternate = if (requested == "edited") "draft" else "edited"

        fun collect(version: String): Pair<List<String>, List<BlockEditor.BlockRef>> {
            val bookDir = services.projectStore.bookDir(p.slug, b.slug)
            // Ссылки блок → фрагмент всегда считаются проходом по фрагментам: собранный
            // файл генерируется из тех же полей, а правка блока пересобирает главу —
            // порядок строк совпадает
            val refs = mutableListOf<BlockEditor.BlockRef>()
            val texts = mutableListOf<String>()
            manifest.value?.parts?.filter { it.chapterIndex == chapter }?.sortedBy { it.id }?.forEach { part ->
                part.fragments.sortedBy { it.id }.forEach { frag ->
                    val f = runCatching {
                        ru.sebyanin.booktranslator.core.storage.AppJson.decodeFromString(
                            ru.sebyanin.booktranslator.core.model.FragmentFile.serializer(),
                            Files.readString(bookDir.resolve("work").resolve("fragments").resolve("${frag.id}.json")),
                        )
                    }.getOrNull()
                    val field: List<String>? = if (version == "edited") f?.editedBlocks else f?.draftBlocks
                    if (!field.isNullOrEmpty()) {
                        val fromEdited = version == "edited" && (f?.editedBlocks?.isNotEmpty() == true)
                        field.forEachIndexed { i, t ->
                            refs += BlockEditor.BlockRef(frag.id, i, fromEdited)
                            texts += t
                        }
                    } else {
                        // Перевода ещё нет — заполнитель по числу блоков исходника
                        f?.srcBlocks?.forEachIndexed { i, _ ->
                            refs += BlockEditor.BlockRef(frag.id, i, false)
                            texts += ""
                        }
                    }
                }
            }
            // Собранный файл — тот же порядок строк (свежий: правка блока пересобирает главу),
            // берём его текст; расхождение размеров = файл протух — показываем склейку фрагментов
            val dir = if (version == "edited") bookDir.resolve("translation") else bookDir.resolve("work").resolve("draft")
            val assembled = dir.resolve(meta.file)
            if (Files.isRegularFile(assembled)) {
                val lines = Files.readAllLines(assembled).filter { it.isNotBlank() }
                if (lines.size == texts.size) return lines to refs
            }
            return texts to refs
        }

        val view = run {
            val (requestedTexts, requestedRefs) = collect(requested)
            val hasAnyText = requestedTexts.any { it.isNotBlank() && it != "— ещё не переведено —" }
            if (hasAnyText) ChapterView(src, requestedTexts, requested, requestedRefs)
            else {
                // Фолбэк: показать то, что есть
                val (alternateTexts, alternateRefs) = collect(alternate)
                if (alternateTexts.any { it.isNotBlank() }) ChapterView(src, alternateTexts, alternate, alternateRefs)
                else ChapterView(src, requestedTexts, requested, requestedRefs)
            }
        }
        chapterViewCache[key] = stamp to view
        return view
    }

    fun bookProgress(): Pair<Int, Int> {
        val chapters = chapterUiList()
        if (chapters.isEmpty()) return 0 to 0
        val done = chapters.count { it.status == "done" }
        return done to chapters.size
    }

    private fun fail(e: Throwable) {
        message.value = "Ошибка: ${e.message?.take(300) ?: e::class.simpleName}"
        AppLog.error("ui", e.message ?: e.toString())
    }
}
