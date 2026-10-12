package ru.sebyanin.booktranslator.core.pipeline

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files

/**
 * Сомнение переводчика: маркер [[??: что непонятно]] из черновика (промпт DRAFT,
 * правило 7), оставшийся в ТЕКУЩЕМ тексте главы. Редактор обязан разрешать такие
 * пометки по словарю/заметкам, но неразрешимые оставляет на месте — их разбор
 * человек ведёт через блок сомнений («Исправить…» корректором или «Убрать пометку»).
 *
 * Сомнения НЕ хранятся: они выводятся из фрагментов при чтении (edited, иначе
 * draft), поэтому всегда синхронны с текстом — разрешённое редактором исчезает
 * само, а повторный прогон черновика обновляет список без всяких слияний.
 */
data class TranslatorDoubt(
    /** «t{глава}-{хэш места и текста}» — стабилен, пока текст пометки на месте. */
    val id: String,
    val chapter: Int,
    val fragmentId: String,
    val blockIndex: Int,
    /** Текст сомнения — содержимое маркера без обрамления [[??: ]]. */
    val text: String,
    /** Полный маркер — для механического удаления из блока. */
    val marker: String,
    /** Найден в editedBlocks (иначе — в draftBlocks, редактура ещё не шла). */
    val fromEdited: Boolean,
)

object TranslatorDoubts {

    /** Сомнения главы по текущему тексту фрагментов (порядок — как в конвейере). */
    fun list(store: ProjectStore, projectSlug: String, bookSlug: String, chapter: Int): List<TranslatorDoubt> {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val manifest = runCatching { SplitStage.loadManifest(bookDir) }.getOrNull() ?: return emptyList()
        val doubts = mutableListOf<TranslatorDoubt>()
        manifest.parts.filter { it.chapterIndex == chapter }.sortedBy { it.id }.forEach { part ->
            part.fragments.sortedBy { it.id }.forEach { frag ->
                val f = readFragment(bookDir, frag.id) ?: return@forEach
                val fromEdited = f.editedBlocks.isNotEmpty()
                (if (fromEdited) f.editedBlocks else f.draftBlocks).forEachIndexed { i, block ->
                    VerifyEngine.DOUBT_MARKER_RE.findAll(block).forEach { m ->
                        val text = m.groupValues[1].trim()
                        doubts += TranslatorDoubt(
                            id = idFor(chapter, frag.id, i, text),
                            chapter = chapter,
                            fragmentId = frag.id,
                            blockIndex = i,
                            text = text,
                            marker = m.value,
                            fromEdited = fromEdited,
                        )
                    }
                }
            }
        }
        return doubts
    }

    /**
     * Пометка из черновика, которую редактура разрешила САМА: в текущем тексте маркера
     * уже нет — решение вошло в текст без одобрения человеком. Показывается для проверки;
     * не согласен — «Исправить…» корректором по текущему тексту.
     */
    data class ResolvedByEditor(
        /** Сомнение так, как его поставил черновик (текст и маркер — из draftBlocks). */
        val doubt: TranslatorDoubt,
        /** Блок текущего текста (то, чем редактор закрыл сомнение). */
        val currentBlock: String,
    )

    fun resolvedByEditor(store: ProjectStore, projectSlug: String, bookSlug: String, chapter: Int): List<ResolvedByEditor> {
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val manifest = runCatching { SplitStage.loadManifest(bookDir) }.getOrNull() ?: return emptyList()
        val resolved = mutableListOf<ResolvedByEditor>()
        manifest.parts.filter { it.chapterIndex == chapter }.sortedBy { it.id }.forEach { part ->
            part.fragments.sortedBy { it.id }.forEach { frag ->
                val f = readFragment(bookDir, frag.id) ?: return@forEach
                if (f.editedBlocks.isEmpty()) return@forEach // черновик не редактировался — его пометки активны
                f.draftBlocks.forEachIndexed { i, draftBlock ->
                    VerifyEngine.DOUBT_MARKER_RE.findAll(draftBlock).forEach { m ->
                        if (i >= f.editedBlocks.size || m.value !in f.editedBlocks[i]) {
                            resolved += ResolvedByEditor(
                                doubt = TranslatorDoubt(
                                    id = idFor(chapter, frag.id, i, m.groupValues[1].trim()),
                                    chapter = chapter,
                                    fragmentId = frag.id,
                                    blockIndex = i,
                                    text = m.groupValues[1].trim(),
                                    marker = m.value,
                                    fromEdited = false,
                                ),
                                currentBlock = f.editedBlocks.getOrElse(i) { "" },
                            )
                        }
                    }
                }
            }
        }
        return resolved
    }

    /**
     * Убрать пометку из текста: человек решил, что сомнение правки не требует, —
     * маркер вычищается, перевод остаётся как есть. Правка механическая (удаление
     * служебной пометки), поэтому коридор длин не проверяется; блоки и якоря —
     * по-прежнему строго. Возвращает null при успехе, иначе — текст ошибки.
     */
    fun remove(store: ProjectStore, projectSlug: String, bookSlug: String, doubtId: String): String? {
        val chapter = doubtId.removePrefix("t").substringBefore("-").toIntOrNull()
            ?: return "id пометки некорректен: $doubtId"
        val doubt = list(store, projectSlug, bookSlug, chapter).firstOrNull { it.id == doubtId }
            ?: return "пометка уже убрана или текст главы обновился: $doubtId"
        val fragsDir = store.bookDir(projectSlug, bookSlug).resolve("work").resolve("fragments")
        val file = runCatching {
            AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(fragsDir.resolve("${doubt.fragmentId}.json")))
        }.getOrNull() ?: return "фрагмент ${doubt.fragmentId} не читается"
        if (doubt.blockIndex !in (if (doubt.fromEdited) file.editedBlocks else file.draftBlocks).indices) {
            return "блок ${doubt.blockIndex} исчез из фрагмента ${doubt.fragmentId}"
        }

        // Пометка вырезается, двойные пробелы вокруг схлопываются (блоки однострочные)
        val cleaned = (if (doubt.fromEdited) file.editedBlocks else file.draftBlocks).mapIndexed { i, block ->
            if (i == doubt.blockIndex) block.replace(doubt.marker, " ").replace(Regex("\\s{2,}"), " ").trim() else block
        }
        val preset = runCatching { store.loadProject(projectSlug).preset }.getOrDefault("generic")
        val verify = VerifyEngine(preset)
        val options = if (doubt.fromEdited) {
            VerifyEngine.Options(residualCheck = true, stripMarkers = false, ratioCorridor = null)
        } else {
            VerifyEngine.draftOptions()
        }
        val vr = verify.verifyTranslation(file.srcBlocks, cleaned, options)
        if (!vr.ok) return "после удаления пометки verify: ${vr.summary()}"

        val updated = if (doubt.fromEdited) file.copy(editedBlocks = cleaned, verifyEdited = vr.summary())
        else file.copy(draftBlocks = cleaned, verifyDraft = vr.summary())
        ProjectStore.atomicWrite(fragsDir.resolve("${file.id}.json"), AppJson.encodeToString(FragmentFile.serializer(), updated))
        AppLog.info("doubts", "Пометка убрана (${doubt.fragmentId}, блок ${doubt.blockIndex + 1}): ${doubt.text.take(60)}")
        return null
    }

    /**
     * id стабилен, пока маркер с тем же текстом стоит в том же блоке (хэш места + текста;
     * без номера вхождения — тогда удаление одной из двух одинаковых пометок блока
     * не инвалидирует id второй, и «Убрать все» снимает их по очереди).
     */
    private fun idFor(chapter: Int, fragmentId: String, blockIndex: Int, text: String): String =
        "t$chapter-%08x".format("$fragmentId#$blockIndex#$text".hashCode())

    private fun readFragment(bookDir: java.nio.file.Path, fragmentId: String): FragmentFile? = runCatching {
        AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(bookDir.resolve("work").resolve("fragments").resolve("$fragmentId.json")))
    }.getOrNull()
}
