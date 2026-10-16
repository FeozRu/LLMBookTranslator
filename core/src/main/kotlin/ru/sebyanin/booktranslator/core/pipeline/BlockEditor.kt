package ru.sebyanin.booktranslator.core.pipeline

import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.AgentRunner
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files

/**
 * Ручной редактор блоков в «Переводе»: правка текста блока человеком и примечания
 * { [note: …] } по выделению. Правится «эффективное» поле фрагмента (editedBlocks,
 * если редактура была, иначе draftBlocks) — тот же выбор поля, что у TermReplacer/
 * SuggestionService: именно его читают предпросмотр, критик и сборка/экспорт.
 * Примечания — существующий формат сносок EDITOR-промпта: билдеры EPUB/FB2 уже
 * превращают маркер в ссылку и секцию примечаний, отдельной поддержки не нужно.
 */
class BlockEditor(
    private val store: ProjectStore,
    private val promptStore: PromptStore,
    private val knowledge: KnowledgeService,
    client: LlmClient,
    private val settingsProvider: () -> AppSettings,
) {
    private val runner = AgentRunner(client)

    /** Ссылка на блок в эффективном поле фрагмента. */
    data class BlockRef(val fragmentId: String, val blockIndex: Int, val fromEdited: Boolean)

    companion object {
        /** Тот же формат, что разбирают билдеры EPUB/FB2. */
        val NOTE_MARKER_RE = Regex("\\{ \\[note:(.+?)] }")

        /**
         * Маркер примечания вставляется сразу после выделения (пробел-разделитель
         * добавляется). Пустое примечание или пустое выделение — текст без изменений.
         */
        fun insertNoteMarker(text: String, selectionStart: Int, selectionEnd: Int, note: String): String {
            val cleaned = cleanNote(note)
            if (cleaned.isEmpty()) return text
            val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
            val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
            if (start == end) return text
            return text.substring(0, end) + " { [note: $cleaned] }" + text.substring(end)
        }

        /** Текст примечания одной строкой: переносы схлопнуты, обрамления маркера и кавычки сняты. */
        fun cleanNote(raw: String): String = raw
            .replace(Regex("\\s+"), " ") // маркер однострочный — сначала в одну строку
            .replace(NOTE_MARKER_RE) { it.groupValues[1].trim() }
            .trim()
            .removeSurrounding("\"")
            .trim()
            .take(600)

        /** Нормализация текста блока перед записью: блоки однострочные, переносы — пробел. */
        fun normalizeBlock(text: String): String = text.replace(Regex("\\s*\\r?\\n\\s*"), " ").trim()
    }

    /**
     * Правка блока человеком: замена в эффективном поле, verify (якоря, блоки 1:1,
     * остаточные знаки; коридор длин для ручной правки не проверяется — человек
     * вправе переписать), атомарная запись с verify-сводкой. null = успех,
     * иначе текст причины и файл не тронут.
     */
    fun saveEdit(projectSlug: String, bookSlug: String, ref: BlockRef, newText: String): String? {
        val fragsDir = store.bookDir(projectSlug, bookSlug).resolve("work").resolve("fragments")
        val file = runCatching {
            AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(fragsDir.resolve("${ref.fragmentId}.json")))
        }.getOrNull() ?: return "фрагмент ${ref.fragmentId} не читается"
        val field = if (ref.fromEdited) file.editedBlocks else file.draftBlocks
        if (ref.blockIndex !in field.indices) return "блок ${ref.blockIndex + 1} исчез из фрагмента ${ref.fragmentId}"
        val block = normalizeBlock(newText)
        if (block == field[ref.blockIndex]) return null // текст не изменился — писать нечего
        val updatedBlocks = field.mapIndexed { i, b -> if (i == ref.blockIndex) block else b }

        val preset = runCatching { store.loadProject(projectSlug).preset }.getOrDefault("generic")
        val verify = VerifyEngine(preset)
        // Тот же набор опций, что у механического удаления пометки (TranslatorDoubts.remove):
        // ручная правка может законно сместить длину — коридор не проверяем
        val options = if (ref.fromEdited) {
            VerifyEngine.Options(residualCheck = true, stripMarkers = false, ratioCorridor = null)
        } else {
            VerifyEngine.draftOptions()
        }
        val vr = verify.verifyTranslation(file.srcBlocks, updatedBlocks, options)
        if (!vr.ok) return "verify: ${vr.summary()}"

        val updated = if (ref.fromEdited) file.copy(editedBlocks = updatedBlocks, verifyEdited = vr.summary())
        else file.copy(draftBlocks = updatedBlocks, verifyDraft = vr.summary())
        ProjectStore.atomicWrite(fragsDir.resolve("${file.id}.json"), AppJson.encodeToString(FragmentFile.serializer(), updated))
        AppLog.info("editor", "Правка блока сохранена (${ref.fragmentId}, блок ${ref.blockIndex + 1}${if (ref.fromEdited) ", редактура" else ", черновик"})")
        return null
    }

    /**
     * Примечание от LLM к выделенному месту: бриф (оригинал и перевод абзаца,
     * словарь, заметки, конвенции) по роли NOTE — дефолт по цепочке привязок,
     * т.е. общая модель. Ответ — только текст примечания.
     */
    suspend fun requestNote(projectSlug: String, bookSlug: String, srcBlock: String, trBlock: String, selection: String): String {
        val profile = settingsProvider().profileFor(AgentRole.NOTE)
            ?: error("Не настроена модель LLM для роли «${AgentRole.NOTE.title}»")
        val template = promptStore.resolve(projectSlug, AgentRole.NOTE)
        val conventions = store.readConventions(projectSlug)
        val selected = knowledge.selectForFragment(projectSlug, srcBlock)
        val brief = BriefBuilder.build(
            BriefBuilder.Input(
                template = template.replace("{{SELECTION}}", selection.trim().ifEmpty { "(не указано)" }),
                srcBlocks = listOf(srcBlock),
                translationBlocks = listOf(trBlock),
                dictionary = selected.dictionary,
                notes = selected.notes,
                conventions = conventions,
            ),
        )
        val result = runner.run(profile = profile, userPrompt = brief, tools = null, sourceLabel = "note")
        val note = cleanNote(result.text)
        if (note.isEmpty()) error("модель вернула пустой ответ вместо примечания")
        AppLog.info("editor", "Примечание от модели: ${note.take(80)}")
        return note
    }
}
