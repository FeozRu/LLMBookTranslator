package ru.sebyanin.booktranslator.core

import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.OpenAiCompatClient
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.BookStructure
import ru.sebyanin.booktranslator.core.model.ChapterMeta
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.LlmProfile
import ru.sebyanin.booktranslator.core.pipeline.DraftStage
import ru.sebyanin.booktranslator.core.pipeline.EditStage
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking

/**
 * Якорные фрагменты (только img/разделители/комментарии) проходят черновик и
 * редактуру копией исходника. Клиент направлен на закрытый порт 9 (discard):
 * любой реальный вызов LLM упал бы отказом соединения и завалил бы тест.
 */
class AnchorFragmentTest {

    @Test
    fun `якорный фрагмент минует LLM в черновике и редактуре`() = runBlocking {
        val tmp = Files.createTempDirectory("bt-anchor")
        val store = ProjectStore { tmp }
        val project = store.createProject("Якорный", "ja", "ru", "ja")

        val blocks = listOf(
            BlockText.imageMarker("item/image/k003-004.jpg"),
            ChapterBlock(BlockKind.COMMENT, "<!-- шапка части -->"),
            BlockText.separator(),
            BlockText.imageMarker("item/image/k003-005.jpg"),
        )
        val meta = ChapterMeta(
            index = 1,
            title = "Иллюстрации",
            file = "chapter-0001.md",
            blockCount = blocks.size,
            charCount = BlockText.charCount(blocks),
        )
        val book = BookMeta(slug = "b", title = "Книга", author = "Автор", format = "epub")
        store.writeChapterSource(project.slug, book.slug, meta, blocks)
        store.saveBookMeta(project.slug, book)
        store.saveStructure(project.slug, book.slug, BookStructure(listOf(meta), images = listOf("item/image/k003-004.jpg")))

        val split = SplitStage(store).split(project.slug, book.slug, partMaxChars = 5000, fragmentTargetChars = 1700)
        assertEquals(1, split.fragments)

        val promptStore = PromptStore({ tmp }) { slug -> store.projectDir(slug) }
        val knowledge = KnowledgeService(store)
        val settings = AppSettings(
            profiles = listOf(
                LlmProfile(name = "test", baseUrl = "http://127.0.0.1:9/v1", model = "m", requestTimeoutSec = 5),
            ),
        )
        val srcLines = blocks.map { it.text }

        OpenAiCompatClient().use { client ->
            val draftSummary = DraftStage(
                store, promptStore, knowledge, client, { settings }, { VerifyEngine("ja") },
            ).run(project.slug, book.slug)
            assertEquals(1, draftSummary.done)
            assertEquals(0, draftSummary.failed)

            val editSummary = EditStage(
                store, promptStore, knowledge, client, { settings }, { VerifyEngine("ja") },
            ).run(project.slug, book.slug)
            assertEquals(1, editSummary.done)
            assertEquals(0, editSummary.failed)

            val bookDir = store.bookDir(project.slug, book.slug)
            val fragId = SplitStage.loadManifest(bookDir).allFragments().first().id
            val file = AppJson.decodeFromString(
                FragmentFile.serializer(),
                bookDir.resolve("work").resolve("fragments").resolve("$fragId.json").readText(),
            )
            assertEquals(srcLines, file.srcBlocks)
            assertEquals(srcLines, file.draftBlocks)
            assertEquals(srcLines, file.editedBlocks)
            assertEquals(0, file.promptTokens)
            assertEquals(0, file.completionTokens)
            assertEquals("якорный фрагмент — перенесён без LLM", file.verifyDraft)
            assertEquals("якорный фрагмент — перенесён без LLM", file.verifyEdited)
        }
    }
}
