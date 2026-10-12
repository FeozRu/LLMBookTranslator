package ru.sebyanin.booktranslator.core

import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.BookStructure
import ru.sebyanin.booktranslator.core.model.ChapterMeta
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.pipeline.AssemblyStage
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

/** Детерминированная часть конвейера без LLM: нарезка → мок-черновик → сборка → выравнивание. */
class PipelineSmokeTest {

    @Test
    fun `split mock-draft assemble roundtrip`() {
        val tmp = Files.createTempDirectory("bt-test")
        val store = ProjectStore { tmp }
        val project = store.createProject("Тестовый проект", "ja", "ru", "ja")

        // Синтетическая книга: 2 главы по 30 абзацев + якоря
        val book = BookMeta(slug = "test-book", title = "Тестовая книга", author = "Автор", format = "epub")
        val chapters = (1..2).map { i ->
            val blocks = buildList {
                add(ChapterBlock(BlockKind.HEADING, "# Глава $i"))
                add(ChapterBlock(BlockKind.COMMENT, "<!-- шапка $i -->"))
                repeat(12) { add(ChapterBlock(BlockKind.TEXT, "Абзац $it главы $i с достаточно длинным текстом для нарезки. " .repeat(6))) }
                add(BlockText.separator())
                repeat(12) { add(ChapterBlock(BlockKind.TEXT, "Второй блок $it главы $i. ".repeat(8))) }
                add(BlockText.imageMarker("ill_$i.png"))
            }
            val meta = ChapterMeta(
                index = i,
                title = "Глава $i",
                file = "chapter-%04d.md".format(i),
                blockCount = blocks.size,
                charCount = BlockText.charCount(blocks),
            )
            store.writeChapterSource(project.slug, book.slug, meta, blocks)
            meta
        }
        store.saveBookMeta(project.slug, book)
        store.saveStructure(project.slug, book.slug, BookStructure(chapters, images = listOf("ill_1.png", "ill_2.png")))

        // Нарезка
        val splitReport = SplitStage(store).split(project.slug, book.slug, partMaxChars = 5000, fragmentTargetChars = 1700)
        assertEquals(2, splitReport.chapters)
        assertTrue(splitReport.parts >= 2, "должна получиться хотя бы пара частей, вышло ${splitReport.parts}")
        assertTrue(splitReport.fragments > splitReport.parts)
        assertTrue(splitReport.blockCheckOk)

        // Мок-черновик: каждой строке исходника — строка перевода (1:1)
        val bookDir = store.bookDir(project.slug, book.slug)
        val manifest = SplitStage.loadManifest(bookDir)
        val verify = VerifyEngine("ja")
        manifest.parts.forEach { part ->
            part.fragments.forEach { frag ->
                val file = bookDir.resolve("work").resolve("fragments").resolve("${frag.id}.json")
                val ff = AppJson.decodeFromString<FragmentFile>(Files.readString(file))
                val draft = ff.srcBlocks.map { line ->
                    // Якоря переносятся дословно (как обязан делать переводчик)
                    val isAnchor = line.startsWith("<!--") || line.startsWith("{ [img:") || Regex("^◇(　◇)*$").matches(line)
                    if (isAnchor) line else "RU: $line"
                }
                assertEquals(ff.srcBlocks.size, draft.size)
                val vr = verify.verifyTranslation(ff.srcBlocks, draft, VerifyEngine.draftOptions())
                assertTrue(vr.ok, "verify draft ${frag.id}: ${vr.summary()}")
                file.writeText(AppJson.encodeToString(FragmentFile.serializer(), ff.copy(draftBlocks = draft)))
            }
        }

        // Сборка обеих глав
        val assembly = AssemblyStage(store)
        (1..2).forEach { ch ->
            val result = assembly.assembleChapter(project.slug, book.slug, ch, AssemblyStage.Version.DRAFT, verify)
            assertTrue(result.ok, "сборка главы $ch: ${result.problems}")
            assertEquals(chapters[ch - 1].blockCount, result.blocks)
        }

        // Выравнивание записано и парность сохранена
        val alignFile = bookDir.resolve("work").resolve("draft").resolve("chapter-0001.align.json")
        assertTrue(Files.isRegularFile(alignFile))
        val align = AppJson.decodeFromString(
            ru.sebyanin.booktranslator.core.align.ChapterAlignment.serializer(),
            Files.readString(alignFile),
        )
        assertEquals(chapters[0].blockCount, align.pairs.size)
        assertTrue(align.pairs.all { it.tr == it.src || it.tr == "RU: ${it.src}" })
    }
}
