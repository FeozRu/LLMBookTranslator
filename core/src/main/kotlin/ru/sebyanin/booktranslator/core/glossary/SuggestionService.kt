package ru.sebyanin.booktranslator.core.glossary

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.CriticChapterFile
import ru.sebyanin.booktranslator.core.model.CriticDoubt
import ru.sebyanin.booktranslator.core.model.CriticSuggestion
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.GlossaryEntry
import ru.sebyanin.booktranslator.core.pipeline.CriticStage
import ru.sebyanin.booktranslator.core.pipeline.DraftStage
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.pipeline.TranslatorDoubt
import ru.sebyanin.booktranslator.core.pipeline.TranslatorDoubts
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files

/**
 * Ревью предложений корректора: применение — только exact-замены с проверкой
 * «встречается ровно 1 раз» по фрагментам главы (уроки прошлого: пачечные
 * замены и молчаливые вторые вхождения).
 */
class SuggestionService(private val store: ProjectStore) {

    fun loadChapter(projectSlug: String, bookSlug: String, chapter: Int): CriticChapterFile? {
        val f = store.bookDir(projectSlug, bookSlug).resolve("work").resolve("critic")
            .resolve("chapter-%04d.json".format(chapter))
        if (!Files.isRegularFile(f)) return null
        val data = AppJson.decodeFromString(CriticChapterFile.serializer(), Files.readString(f))
        // Лечение старых файлов: дублирующиеся id предложений (нумерация сбрасывалась
        // в каждом чанке) и сомнения цельным текстом без структуры — оба варианта
        // переиндексируются насквозь и перезаписываются
        val ids = data.suggestions.map { it.id }
        val dupIds = ids.size != ids.distinct().size
        val needsDoubtHeal = data.doubts.isEmpty() && data.doubtsText.isNotBlank()
        return if (dupIds || needsDoubtHeal) {
            val healed = data.copy(
                suggestions = if (dupIds) {
                    data.suggestions.mapIndexed { i, s -> s.copy(id = "c${chapter}-${i + 1}") }
                } else data.suggestions,
                doubts = if (needsDoubtHeal) {
                    CriticStage.splitDoubts(data.doubtsText).mapIndexed { i, t -> CriticDoubt("d${chapter}-${i + 1}", t) }
                } else data.doubts,
                doubtsText = "",
            )
            ProjectStore.atomicWrite(f, AppJson.encodeToString(CriticChapterFile.serializer(), healed))
            healed
        } else {
            data
        }
    }

    fun pending(projectSlug: String, bookSlug: String, chapter: Int): List<CriticSuggestion> =
        loadChapter(projectSlug, bookSlug, chapter)?.suggestions?.filter { it.status == "pending" } ?: emptyList()

    /** Где цитата живёт в переводе: фрагмент, индекс блока, исходный блок. */
    data class Location(val fragmentId: String, val blockIndex: Int, val srcBlock: String, val trBlock: String)

    private fun variants(raw: String): List<String> =
        listOf(raw.trim(), raw.trim('«', '»', '"', ' ').trim()).filter { it.length >= 3 }.distinct()

    /**
     * Поиск контекста для ревью: блок, содержащий «было» (до применения)
     * или «стало» (после — цитата «было» в переводе уже заменена).
     * 0 или >1 вхождений → null.
     */
    fun locate(projectSlug: String, bookSlug: String, suggestion: CriticSuggestion): Location? {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val manifest = runCatching {
            ru.sebyanin.booktranslator.core.pipeline.SplitStage.loadManifest(bookDir)
        }.getOrNull() ?: return null
        val wasVariants = variants(suggestion.was)
        val becameVariants = variants(suggestion.became)
        var found: Location? = null
        var hits = 0
        manifest.parts.filter { it.chapterIndex == suggestion.chapter }.forEach { part ->
            part.fragments.forEach { frag ->
                val f = bookDir.resolve("work").resolve("fragments").resolve("${frag.id}.json")
                val ff = runCatching {
                    AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(f))
                }.getOrNull() ?: return@forEach
                val texts = ff.editedBlocks.ifEmpty { ff.draftBlocks }
                texts.forEachIndexed { i, block ->
                    val matched = wasVariants.any { block.contains(it) } || becameVariants.any { block.contains(it) }
                    if (matched) {
                        hits++
                        found = Location(frag.id, i, ff.srcBlocks.getOrNull(i) ?: "", block)
                    }
                }
            }
        }
        return if (hits == 1) found else null
    }

    /**
     * Где термин появился в книге: блок ОРИГИНАЛА с термином (японский — точное
     * вхождение, надёжно) и парный блок перевода. Приоритет — фрагмент из
     * entry.context; если в оригинале не нашли — фолбэк на транскрипцию в переводе.
     */
    fun locateTerm(projectSlug: String, bookSlug: String, entry: GlossaryEntry): Location? {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val manifest = runCatching {
            SplitStage.loadManifest(bookDir)
        }.getOrNull() ?: return null
        val original = entry.original.trim()
        if (original.isEmpty()) return null
        val hint = entry.context.substringAfter("фрагмент", "").trim()
        val trNeedles = (listOf(entry.translation) + entry.alternatives)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()

        data class Hit(val fragId: String, val blockIndex: Int, val src: String, val tr: String)

        fun read(fragId: String): FragmentFile? = runCatching {
            AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(bookDir.resolve("work").resolve("fragments").resolve("$fragId.json")))
        }.getOrNull()

        fun firstSrcHit(fragId: String): Hit? {
            val ff = read(fragId) ?: return null
            val tr = ff.editedBlocks.ifEmpty { ff.draftBlocks }
            ff.srcBlocks.forEachIndexed { i, s ->
                if (s.contains(original)) return Hit(fragId, i, s, tr.getOrNull(i) ?: "")
            }
            return null
        }

        fun firstTrHit(fragId: String): Hit? {
            val ff = read(fragId) ?: return null
            val tr = ff.editedBlocks.ifEmpty { ff.draftBlocks }
            tr.forEachIndexed { i, t ->
                if (trNeedles.any { t.contains(it) }) return Hit(fragId, i, ff.srcBlocks.getOrNull(i) ?: "", t)
            }
            return null
        }

        val ordered = manifest.parts.asSequence()
            .flatMap { it.fragments }
            .map { it.id }
            .distinct()
            .toList()
            .let { ids -> if (hint.isNotEmpty()) ids.sortedBy { it != hint } else ids }

        ordered.firstNotNullOfOrNull { firstSrcHit(it) }?.let { hit ->
            return Location(hit.fragId, hit.blockIndex, hit.src, displayTranslation(hit.tr))
        }
        if (trNeedles.isNotEmpty()) {
            ordered.firstNotNullOfOrNull { firstTrHit(it) }?.let { hit ->
                return Location(hit.fragId, hit.blockIndex, hit.src, displayTranslation(hit.tr))
            }
        }
        return null
    }

    /** Черновик может держать маркер [[имя?: X → Y | …]] — для показа оставляем только вариант. */
    private fun displayTranslation(block: String): String =
        if (!block.contains("[[имя?:")) block
        else DraftStage.NAME_MARKER.replace(block) { m -> m.groupValues[2].trim() }

    sealed class ApplyOutcome {
        data class Applied(val suggestion: CriticSuggestion) : ApplyOutcome()
        data class Failed(val suggestion: CriticSuggestion, val reason: String) : ApplyOutcome()
    }

    /**
     * Применяет правку: ищет точное «было» в edited-блоках фрагментов главы.
     * Одиночная правка требует ровно 1 вхождение; пачечная (replaceAll) заменяет
     * ВСЕ вхождения — транзакционно: сначала проверка каждого затронутого фрагмента,
     * запись только если все проверки прошли (никаких молчаливых пачек).
     */
    fun apply(
        projectSlug: String,
        bookSlug: String,
        suggestion: CriticSuggestion,
        verify: VerifyEngine,
    ): ApplyOutcome {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val manifest = SplitStage.loadManifest(bookDir)
        val parts = manifest.parts.filter { it.chapterIndex == suggestion.chapter }.sortedBy { it.id }
        val fragsDir = bookDir.resolve("work").resolve("fragments")

        val needles = variants(suggestion.was)
        val became = variants(suggestion.became).firstOrNull { it.isNotEmpty() }
            ?: suggestion.became.trim('«', '»', '"', ' ').trim()

        // Поиск вхождений по всем фрагментам главы
        data class Hit(val fragId: String, val blockIdx: Int, val needle: String)

        val hits = mutableListOf<Hit>()
        parts.forEach { part ->
            part.fragments.forEach { frag ->
                val f = readFragment(fragsDir, frag.id)
                f.editedBlocks.forEachIndexed { i, block ->
                    needles.firstOrNull { block.contains(it) }?.let { needle ->
                        hits += Hit(frag.id, i, needle)
                    }
                }
            }
        }

        val isBatch = suggestion.replaceAll && hits.size > 1
        if (hits.isEmpty() || (!isBatch && hits.size != 1)) {
            val reason = when {
                hits.isEmpty() -> "цитата «было» не найдена в переводе"
                else -> "цитата встречается ${hits.size} раз, но правка одиночная (нужно ровно 1; для всех мест модель должна пометить «ВЕЗДЕ: да»)"
            }
            AppLog.warn("suggestions", "Правка ${suggestion.id} не применена: $reason")
            return ApplyOutcome.Failed(suggestion, reason)
        }

        // Правки в памяти по затронутым фрагментам
        val affected = hits.groupBy({ it.fragId }, { it.blockIdx })
        val changed = mutableMapOf<String, Pair<FragmentFile, List<String>>>()
        affected.forEach { (fragId, blockIdxs) ->
            val file = readFragment(fragsDir, fragId)
            val newBlocks = file.editedBlocks.toMutableList()
            blockIdxs.forEach { i ->
                val needle = hits.first { it.fragId == fragId && it.blockIdx == i }.needle
                newBlocks[i] = newBlocks[i].replace(needle, became)
            }
            changed[fragId] = file to newBlocks
        }

        // Верификация КАЖДОГО затронутого фрагмента; при любом сбое — ничего не пишем
        changed.forEach { (fragId, pair) ->
            val (file, newBlocks) = pair
            val vr = verify.verifyTranslation(file.srcBlocks, newBlocks, VerifyEngine.editedOptions())
            if (!vr.ok) {
                val reason = "после правки verify $fragId: ${vr.summary()}"
                AppLog.warn("suggestions", "Правка ${suggestion.id} не применена: $reason")
                return ApplyOutcome.Failed(suggestion, reason)
            }
        }

        changed.forEach { (fragId, pair) ->
            val (file, newBlocks) = pair
            val vr = verify.verifyTranslation(file.srcBlocks, newBlocks, VerifyEngine.editedOptions())
            writeFragment(fragsDir, file.copy(editedBlocks = newBlocks, verifyEdited = vr.summary()))
        }
        val applied = updateSuggestion(projectSlug, bookSlug, suggestion.id) { it.copy(status = "applied") }
        if (isBatch) {
            AppLog.info("suggestions", "Правка ${suggestion.id} применена ВЕЗДЕ: ${hits.size} мест (${affected.keys.joinToString()})")
        } else {
            AppLog.info("suggestions", "Правка ${suggestion.id} применена (${hits.first().fragId}, блок ${hits.first().blockIdx + 1})")
        }
        return ApplyOutcome.Applied(applied ?: suggestion.copy(status = "applied"))
    }

    fun reject(projectSlug: String, bookSlug: String, suggestionId: String): CriticSuggestion? =
        updateSuggestion(projectSlug, bookSlug, suggestionId) { it.copy(status = "rejected") }

    /** Сомнение разобрано человеком: исчезает из очереди, статус хранится до перепрогона корректора. */
    fun resolveDoubt(projectSlug: String, bookSlug: String, doubtId: String, note: String = ""): CriticDoubt? =
        updateDoubt(projectSlug, bookSlug, doubtId) { it.copy(status = "resolved", note = note) }

    /** Сомнения переводчика ([[??: …]] в текущем тексте главы) — выводятся из фрагментов, не хранятся. */
    fun translatorDoubts(projectSlug: String, bookSlug: String, chapter: Int): List<TranslatorDoubt> =
        TranslatorDoubts.list(store, projectSlug, bookSlug, chapter)

    /** Сомнения, которые редактура разрешила сама (маркера в тексте уже нет). */
    fun translatorDoubtsResolvedByEditor(projectSlug: String, bookSlug: String, chapter: Int): List<TranslatorDoubts.ResolvedByEditor> =
        TranslatorDoubts.resolvedByEditor(store, projectSlug, bookSlug, chapter)

    /** Убрать пометку [[??: …]] из текста (null при успехе, иначе текст ошибки). */
    fun removeTranslatorMarker(projectSlug: String, bookSlug: String, doubtId: String): String? =
        TranslatorDoubts.remove(store, projectSlug, bookSlug, doubtId)

    private fun updateDoubt(
        projectSlug: String,
        bookSlug: String,
        doubtId: String,
        transform: (CriticDoubt) -> CriticDoubt,
    ): CriticDoubt? {
        val chapter = doubtId.removePrefix("d").substringBefore("-").toIntOrNull() ?: return null
        val dir = store.bookDir(projectSlug, bookSlug).resolve("work").resolve("critic")
        val file = dir.resolve("chapter-%04d.json".format(chapter))
        if (!Files.isRegularFile(file)) return null
        val data = AppJson.decodeFromString<CriticChapterFile>(Files.readString(file))
        var updated: CriticDoubt? = null
        val next = data.copy(
            doubts = data.doubts.map {
                if (it.id == doubtId) transform(it).also { d -> updated = d } else it
            },
        )
        ProjectStore.atomicWrite(file, AppJson.encodeToString(CriticChapterFile.serializer(), next))
        return updated
    }

    private fun updateSuggestion(
        projectSlug: String,
        bookSlug: String,
        suggestionId: String,
        transform: (CriticSuggestion) -> CriticSuggestion,
    ): CriticSuggestion? {
        val dir = store.bookDir(projectSlug, bookSlug).resolve("work").resolve("critic")
        val chapter = suggestionId.substringAfter("c").substringBefore("-").toIntOrNull() ?: return null
        val file = dir.resolve("chapter-%04d.json".format(chapter))
        if (!Files.isRegularFile(file)) return null
        val data = AppJson.decodeFromString<CriticChapterFile>(Files.readString(file))
        var updated: CriticSuggestion? = null
        val next = data.copy(
            suggestions = data.suggestions.map {
                if (it.id == suggestionId) transform(it).also { s -> updated = s } else it
            },
        )
        ProjectStore.atomicWrite(file, AppJson.encodeToString(CriticChapterFile.serializer(), next))
        return updated
    }

    private fun readFragment(fragsDir: java.nio.file.Path, id: String): FragmentFile =
        AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(fragsDir.resolve("$id.json")))

    private fun writeFragment(fragsDir: java.nio.file.Path, file: FragmentFile) {
        ProjectStore.atomicWrite(fragsDir.resolve("${file.id}.json"), AppJson.encodeToString(FragmentFile.serializer(), file))
    }
}
