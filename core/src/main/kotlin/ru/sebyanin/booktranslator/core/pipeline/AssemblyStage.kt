package ru.sebyanin.booktranslator.core.pipeline

import ru.sebyanin.booktranslator.core.align.AlignPair
import ru.sebyanin.booktranslator.core.align.ChapterAlignment
import ru.sebyanin.booktranslator.core.align.SentenceSplitter
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files
import java.nio.file.Path

/** Сборка главы из фрагментов: склейка → счёт блоков → файл перевода → выравнивание. */
class AssemblyStage(private val store: ProjectStore) {

    enum class Version { DRAFT, EDITED }

    data class AssembleResult(
        val chapter: Int,
        val blocks: Int,
        val srcBlocks: Int,
        val ok: Boolean,
        val problems: List<String>,
    )

    fun assembleChapter(
        projectSlug: String,
        bookSlug: String,
        chapterIndex: Int,
        version: Version,
        verify: VerifyEngine,
    ): AssembleResult {
        val structure = store.loadStructure(projectSlug, bookSlug)
        val meta = structure.chapters.firstOrNull { it.index == chapterIndex }
            ?: error("Глава $chapterIndex не найдена")
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val manifest = SplitStage.loadManifest(bookDir)

        val parts = manifest.parts.filter { it.chapterIndex == chapterIndex }.sortedBy { it.id }
        if (parts.isEmpty()) error("У главы $chapterIndex нет нарезанных частей")

        val srcBlocks = mutableListOf<String>()
        val outBlocks = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val field = when (version) {
            Version.DRAFT -> { f: FragmentFile -> f.draftBlocks }
            Version.EDITED -> { f: FragmentFile -> f.editedBlocks }
        }

        parts.forEach { part ->
            part.fragments.sortedBy { it.id }.forEach { frag ->
                val file = readFragment(bookDir, frag.id)
                val texts = field(file)
                if (texts.isEmpty()) {
                    problems += "${frag.id}: нет текста (${version.name.lowercase()})"
                }
                val expected = frag.srcBlockCount
                if (texts.size != expected) {
                    problems += "${frag.id}: блоков $expected → ${texts.size}"
                }
                srcBlocks += file.srcBlocks
                outBlocks += texts
            }
        }

        val srcFile = store.readChapterSource(projectSlug, bookSlug, meta.file)
        val ok = problems.isEmpty() && srcBlocks.size == srcFile.size && outBlocks.size == srcFile.size
        if (srcBlocks.size != srcFile.size) {
            problems += "склейка: исходных блоков ${srcFile.size}, во фрагментах $srcBlocks"
        }

        // Файл перевода (для edited — канонический translation/, для draft — work/draft/)
        val dir = when (version) {
            Version.EDITED -> bookDir.resolve("translation")
            Version.DRAFT -> bookDir.resolve("work").resolve("draft")
        }
        Files.createDirectories(dir)
        ProjectStore.atomicWrite(dir.resolve(meta.file), outBlocks.joinToString("\n"))

        // Выравнивание: абзацы 1:1 + предложенное разбиение внутри пар
        val preset = runCatching { store.loadProject(projectSlug).preset }.getOrDefault("generic")
        val cjk = preset == "ja"
        val alignment = ChapterAlignment(
            chapter = chapterIndex,
            version = version.name.lowercase(),
            pairs = srcBlocks.mapIndexed { i, src ->
                val tr = outBlocks.getOrElse(i) { "" }
                val srcS = SentenceSplitter.split(src, cjk)
                val trS = SentenceSplitter.split(tr, false)
                val map: List<List<Int>> = if (srcS.size == trS.size && srcS.isNotEmpty()) {
                    srcS.indices.map { listOf(it) }
                } else {
                    approxMap(srcS, trS)
                }
                AlignPair(
                    block = i,
                    src = src,
                    tr = tr,
                    srcSentences = srcS,
                    trSentences = trS,
                    sentenceMap = map,
                    confidence = if (srcS.size == trS.size) "exact" else "approx",
                )
            },
        )
        ProjectStore.atomicWrite(
            dir.resolve(meta.file.removeSuffix(".md") + ".align.json"),
            AppJson.encodeToString(ChapterAlignment.serializer(), alignment),
        )

        if (ok) {
            AppLog.info("assemble", "Глава $chapterIndex собрана (${version.name.lowercase()}): ${outBlocks.size} блоков — сходится")
        } else {
            AppLog.warn("assemble", "Глава $chapterIndex: ${problems.joinToString("; ")}")
        }
        return AssembleResult(chapterIndex, outBlocks.size, srcFile.size, ok, problems)
    }

    /** Жадное отображение по долям длины: src i → диапазоны tr, пропорционально длинам. */
    private fun approxMap(srcS: List<String>, trS: List<String>): List<List<Int>> {
        if (srcS.isEmpty() || trS.isEmpty()) return srcS.indices.map { emptyList() }
        val srcLens = srcS.map { it.length.coerceAtLeast(1) }
        val totalSrc = srcLens.sum().toDouble()
        val trLens = trS.map { it.length.coerceAtLeast(1) }
        val totalTr = trLens.sum().toDouble()
        val map = srcS.indices.map { mutableListOf<Int>() }
        var trIdx = 0
        var srcAcc = 0.0
        srcS.indices.forEach { i ->
            srcAcc += srcLens[i] / totalSrc
            val trAcc = if (trIdx < trS.size) trLens.subList(0, trIdx + 1).sum() / totalTr else 1.0
            while (trIdx < trS.size && trAcc <= srcAcc) {
                map[i] += trIdx
                trIdx++
                if (trIdx >= trS.size) break
            }
        }
        while (trIdx < trS.size) {
            map[map.lastIndex] += trIdx
            trIdx++
        }
        return map
    }

    private fun readFragment(bookDir: Path, id: String): FragmentFile =
        AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(bookDir.resolve("work").resolve("fragments").resolve("$id.json")),
        )
}
