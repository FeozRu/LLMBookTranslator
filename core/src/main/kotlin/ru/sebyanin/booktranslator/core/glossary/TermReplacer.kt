package ru.sebyanin.booktranslator.core.glossary

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files
import java.nio.file.Path

/**
 * Слой 1 замены термина в переведённом тексте: механическая транзакционная
 * замена точных вхождений старого канонического варианта по ВСЕЙ книге.
 * Семантика — пачечная правка «ВЕЗДЕ» корректора (SuggestionService.apply),
 * но по всем фрагментам манифеста и по эффективному тексту
 * (editedBlocks.ifEmpty { draftBlocks }). Русская морфология («Карла»/«Карлы»)
 * механике недоступна — для форм есть слой 2, [TermFixer].
 */
class TermReplacer(private val store: ProjectStore) {

    /** Точное вхождение в одном блоке: место + текст блока (человек должен видеть, что заменится). */
    data class TermHit(
        val fragmentId: String,
        val blockIndex: Int,
        val text: String,
        val count: Int,
    )

    data class Scan(
        val from: String,
        /** Точные вхождения старого варианта по блокам, в порядке манифеста. */
        val exact: List<TermHit>,
        /** id фрагментов, где слово встречается в любой форме (цели LLM-прогона). */
        val stemFragmentIds: List<String>,
    ) {
        val occurrences: Int get() = exact.sumOf { it.count }
        val fragmentIds: List<String> get() = exact.map { it.fragmentId }.distinct()
    }

    sealed class ReplaceOutcome {
        data class Done(val replaced: Int, val fragments: List<String>, val touchedDraft: Boolean) : ReplaceOutcome()
        data class Failed(val reason: String) : ReplaceOutcome()
    }

    /**
     * Скан книги: точные вхождения [from] по эффективным блокам всех фрагментов
     * + фрагменты с любой словоформой (стем, см. [stemOf]).
     */
    fun scan(projectSlug: String, bookSlug: String, from: String): Scan {
        val needle = from.trim()
        val stemRegex = stemOf(needle)?.let { stem ->
            Regex("(?i)(?<![\\p{L}\\p{Nd}])" + Regex.escape(stem) + "[\\p{L}\\p{Nd}]*")
        }
        val exact = mutableListOf<TermHit>()
        val stemIds = mutableListOf<String>()
        if (needle.length >= 2) {
            fragmentFiles(projectSlug, bookSlug).forEach { ff ->
                val blocks = ff.editedBlocks.ifEmpty { ff.draftBlocks }
                blocks.forEachIndexed { i, block ->
                    countOccurrences(block, needle).takeIf { it > 0 }?.let { n ->
                        exact += TermHit(ff.id, i, block, n)
                    }
                }
                if (stemRegex != null && blocks.any { stemRegex.containsMatchIn(it) }) stemIds += ff.id
            }
        }
        return Scan(needle, exact, stemIds)
    }

    /**
     * Заменяет ВСЕ точные вхождения старого варианта на новый — транзакционно:
     * каждый затронутый фрагмент верифицируется (остатки исходника, якоря, блоки 1:1;
     * без коридора длин — правка точечная, длина смещается ровно на размер замены),
     * запись на диск только если ВСЕ проверки прошли. Маркер [[имя?: …]] в черновике
     * правится как обычный текст — канон меняется и внутри пометки.
     */
    fun replaceEverywhere(
        projectSlug: String,
        bookSlug: String,
        from: String,
        to: String,
        verify: VerifyEngine,
    ): ReplaceOutcome {
        val needle = from.trim()
        val replacement = to.trim()
        if (needle.isEmpty() || replacement.isEmpty()) return ReplaceOutcome.Failed("пустой вариант замены")
        if (needle.length < 2) return ReplaceOutcome.Failed("вариант «$needle» слишком короткий — замена опасна")
        if (needle == replacement) return ReplaceOutcome.Failed("старый и новый варианты совпадают")

        val bookDir = store.bookDir(projectSlug, bookSlug)
        val manifest = runCatching { SplitStage.loadManifest(bookDir) }.getOrNull()
            ?: return ReplaceOutcome.Failed("манифест книги не найден")
        val fragsDir = bookDir.resolve("work").resolve("fragments")

        data class Edit(val file: FragmentFile, val blocks: List<String>, val inEdited: Boolean)

        val changed = LinkedHashMap<String, Edit>()
        var replaced = 0
        manifest.parts.asSequence().flatMap { it.fragments }.map { it.id }.distinct().forEach { id ->
            val ff = readFragment(fragsDir, id) ?: return@forEach
            val inEdited = ff.editedBlocks.isNotEmpty()
            val blocks = if (inEdited) ff.editedBlocks else ff.draftBlocks
            if (blocks.none { it.contains(needle) }) return@forEach
            val newBlocks = blocks.map { b ->
                val n = countOccurrences(b, needle)
                if (n == 0) b else { replaced += n; b.replace(needle, replacement) }
            }
            changed[id] = Edit(ff, newBlocks, inEdited)
        }
        if (changed.isEmpty()) return ReplaceOutcome.Failed("«$needle» не найдено в переведённом тексте книги")

        // Верификация КАЖДОГО затронутого фрагмента; при любом сбое — ничего не пишем
        val verified = changed.map { (id, e) ->
            Triple(id, e, verify.verifyTranslation(e.file.srcBlocks, e.blocks, VerifyEngine.termReplaceOptions(stripMarkers = !e.inEdited)))
        }
        verified.firstOrNull { !it.third.ok }?.let { (id, _, vr) ->
            val reason = "после замены verify $id: ${vr.summary()}"
            AppLog.warn("glossary", "Замена термина не применена: $reason")
            return ReplaceOutcome.Failed(reason)
        }
        verified.forEach { (id, e, vr) ->
            val updated = if (e.inEdited) e.file.copy(editedBlocks = e.blocks, verifyEdited = vr.summary())
            else e.file.copy(draftBlocks = e.blocks, verifyDraft = vr.summary())
            ProjectStore.atomicWrite(fragsDir.resolve("$id.json"), AppJson.encodeToString(FragmentFile.serializer(), updated))
        }
        AppLog.info("glossary", "Замена термина «$needle» → «$replacement»: $replaced вхождений в ${changed.size} фрагментах")
        return ReplaceOutcome.Done(replaced, changed.keys.toList(), changed.values.any { !it.inEdited })
    }

    private fun fragmentFiles(projectSlug: String, bookSlug: String): List<FragmentFile> {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val manifest = runCatching { SplitStage.loadManifest(bookDir) }.getOrNull() ?: return emptyList()
        val fragsDir = bookDir.resolve("work").resolve("fragments")
        return manifest.parts.asSequence()
            .flatMap { it.fragments }.map { it.id }.distinct()
            .mapNotNull { readFragment(fragsDir, it) }
            .toList()
    }

    private fun readFragment(fragsDir: Path, id: String): FragmentFile? = runCatching {
        AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(fragsDir.resolve("$id.json")))
    }.getOrNull()

    companion object {
        /** Число вхождений подстроки (не блоков: «Карла, Карла» — это два вхождения). */
        fun countOccurrences(text: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var idx = text.indexOf(needle)
            while (idx >= 0) {
                count++
                idx = text.indexOf(needle, idx + needle.length)
            }
            return count
        }

        /**
         * Стем для поиска словоформ: у слова от 4 знаков отрезаем последнюю букву
         * («Карла» → «Карл» — поймает «Карлы», «Карле»; «Удгардо» → «Удгард»),
         * короткое ищется целиком. Префикс с границей слова; ложные попадания
         * (другое имя с тем же началом) отсеивает модель слоя 2.
         */
        fun stemOf(from: String): String? {
            val w = from.trim()
            return when {
                w.length >= 4 -> w.dropLast(1)
                w.length == 3 -> w
                else -> null
            }
        }
    }
}
