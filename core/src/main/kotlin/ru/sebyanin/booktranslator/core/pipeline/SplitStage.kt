package ru.sebyanin.booktranslator.core.pipeline

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.FragmentState
import ru.sebyanin.booktranslator.core.model.PartState
import ru.sebyanin.booktranslator.core.model.WorkManifest
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

data class SplitReport(
    val chapters: Int,
    val parts: Int,
    val fragments: Int,
    val blockCheckOk: Boolean,
)

/**
 * Этап 1: главы → части (≤partMaxChars) → фрагменты (~fragmentTargetChars).
 * Границы — только по блокам. После нарезки счёт блоков сверяется со счётом главы.
 * Повторный запуск пересоздаёт манифест, сохраняя статусы готовых фрагментов с теми же id
 * (id детерминированы: part-NNNN / part-NNNN-fM), если границы не изменились.
 */
class SplitStage(private val store: ProjectStore) {

    fun split(
        projectSlug: String,
        bookSlug: String,
        partMaxChars: Int,
        fragmentTargetChars: Int,
    ): SplitReport {
        val structure = store.loadStructure(projectSlug, bookSlug)
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val workDir = bookDir.resolve("work")
        val partsDir = workDir.resolve("parts")
        val fragsDir = workDir.resolve("fragments")
        Files.createDirectories(partsDir)
        Files.createDirectories(fragsDir)

        val oldManifest = runCatching { loadManifest(bookDir) }.getOrNull()
        val oldFragById = oldManifest?.allFragments()?.associateBy { it.id } ?: emptyMap()

        val parts = mutableListOf<PartState>()
        var partNo = 0

        structure.chapters.forEach { chapter ->
            val blocks = store.readChapterSource(projectSlug, bookSlug, chapter.file)
            check(blocks.size == chapter.blockCount) {
                "Расхождение блоков в ${chapter.file}: файл ${blocks.size}, структура ${chapter.blockCount}"
            }
            cutRanges(blocks, partMaxChars).forEach { partRange ->
                partNo++
                val partId = "part-%04d".format(partNo)
                val partBlocks = blocks.subList(partRange.first, partRange.last + 1)
                val partFile = partsDir.resolve("$partId.md")
                ProjectStore.atomicWrite(partFile, BlockText.encode(partBlocks))

                val fragments = cutRanges(partBlocks, fragmentTargetChars).mapIndexed { i, fragRange ->
                    val fragId = "$partId-f${i + 1}"
                    FragmentState(
                        id = fragId,
                        blockFrom = fragRange.first,
                        blockTo = fragRange.last,
                        charCount = BlockText.charCount(partBlocks.subList(fragRange.first, fragRange.last + 1)),
                        // Статусы сохраняем только если границы совпали
                        draft = oldFragById[fragId]?.takeIf { it.srcBlockCount == fragRange.last - fragRange.first + 1 }?.draft ?: ru.sebyanin.booktranslator.core.model.StageState(),
                        edited = oldFragById[fragId]?.takeIf { it.srcBlockCount == fragRange.last - fragRange.first + 1 }?.edited ?: ru.sebyanin.booktranslator.core.model.StageState(),
                    )
                }

                // Файлы фрагментов: src-блоки фиксируем на диске (брифы собираются только из файлов)
                fragments.forEach { frag ->
                    val fragFile = fragsDir.resolve("${frag.id}.json")
                    val existing = runCatching {
                        AppJson.decodeFromString<FragmentFile>(Files.readString(fragFile))
                    }.getOrNull()
                    val src = partBlocks.subList(frag.blockFrom, frag.blockTo + 1).map { it.text }
                    val content = (existing ?: FragmentFile(id = frag.id)).copy(
                        id = frag.id,
                        srcBlocks = src,
                    )
                    ProjectStore.atomicWrite(fragFile, AppJson.encodeToString(FragmentFile.serializer(), content))
                }

                parts += PartState(
                    id = partId,
                    chapterIndex = chapter.index,
                    chapterFile = chapter.file,
                    blockFrom = partRange.first,
                    blockTo = partRange.last,
                    charCount = BlockText.charCount(partBlocks),
                    fragments = fragments,
                )
            }
        }

        // Золотое правило: счёт блоков после каждого шага
        val sumFragBlocks = parts.sumOf { p -> p.fragments.sumOf { it.srcBlockCount } }
        val sumChapterBlocks = structure.chapters.sumOf { it.blockCount }
        val ok = sumFragBlocks == sumChapterBlocks
        if (!ok) AppLog.error("split", "Счёт блоков: фрагменты $sumFragBlocks ≠ главы $sumChapterBlocks")
        check(ok) { "Счёт блоков разошёлся: фрагменты $sumFragBlocks, главы $sumChapterBlocks" }

        val manifest = WorkManifest(
            bookSlug = bookSlug,
            createdAt = LocalDate.now().toString(),
            parts = parts,
        )
        saveManifest(bookDir, manifest)

        val fragCount = parts.sumOf { it.fragments.size }
        AppLog.info("split", "Нарезка «$bookSlug»: частей ${parts.size}, фрагментов $fragCount, блоков $sumFragBlocks (сходится)")
        return SplitReport(structure.chapters.size, parts.size, fragCount, ok)
    }

    /** Режет блоки на диапазоны [start; end] по лимиту знаков; граница — только между блоками. */
    private fun cutRanges(blocks: List<ChapterBlock>, maxChars: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        var acc = 0
        for (i in blocks.indices) {
            val blockLen = blocks[i].text.count { !it.isWhitespace() }
            acc += blockLen
            val remaining = blocks.size - 1 - i
            if (acc >= maxChars && remaining > 0) {
                ranges += start..i
                start = i + 1
                acc = 0
            }
        }
        if (start <= blocks.lastIndex) ranges += start..blocks.lastIndex
        return ranges
    }

    companion object {
        fun manifestFile(bookDir: Path): Path = bookDir.resolve("work").resolve("manifest.json")

        fun loadManifest(bookDir: Path): WorkManifest =
            AppJson.decodeFromString(
                WorkManifest.serializer(),
                Files.readString(manifestFile(bookDir)),
            )

        fun saveManifest(bookDir: Path, manifest: WorkManifest) {
            ProjectStore.atomicWrite(manifestFile(bookDir), AppJson.encodeToString(WorkManifest.serializer(), manifest))
        }

        /**
         * Самолечение манифеста: если статус этапа не done, но в файле фрагмента
         * лежит готовый текст нужного размера (блоки сходятся), статус восстанавливается.
         * Причина: баг withManifest (сохранялся неизменённый манифест) — тексты фрагментов
         * писались корректно, а статусы терялись.
         */
        fun reconcileManifest(bookDir: Path): Int {
            val manifest = runCatching { loadManifest(bookDir) }.getOrNull() ?: return 0
            val fragsDir = bookDir.resolve("work").resolve("fragments")
            var fixed = 0
            val newParts = manifest.parts.map { part ->
                part.copy(
                    fragments = part.fragments.map { frag ->
                        val file = fragsDir.resolve("${frag.id}.json")
                        val ff = runCatching {
                            AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(file))
                        }.getOrNull() ?: return@map frag
                        var f = frag
                        if (!f.draft.isDone && ff.draftBlocks.size == f.srcBlockCount && ff.draftBlocks.isNotEmpty()) {
                            f = f.copy(draft = f.draft.copy(status = "done", verify = "восстановлено из файлов", updatedAt = ""))
                            fixed++
                        }
                        if (!f.edited.isDone && ff.editedBlocks.size == f.srcBlockCount && ff.editedBlocks.isNotEmpty()) {
                            f = f.copy(edited = f.edited.copy(status = "done", verify = "восстановлено из файлов", updatedAt = ""))
                            fixed++
                        }
                        f
                    },
                )
            }
            if (fixed > 0) {
                saveManifest(bookDir, manifest.copy(parts = newParts))
                AppLog.info("split", "Манифест самолечится: восстановлено статусов $fixed")
            }
            return fixed
        }
    }
}
