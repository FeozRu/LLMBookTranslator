package ru.sebyanin.booktranslator.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.BookStructure
import ru.sebyanin.booktranslator.core.model.ChapterMeta
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.pipeline.DraftStage
import ru.sebyanin.booktranslator.core.pipeline.EditStage
import ru.sebyanin.booktranslator.core.pipeline.ExternalSubmitResult
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Внешний исполнитель через MCP («агент переводит сам»): бриф фрагмента тот же,
 * что у этапа; приём текста — с верификацией и статусами манифеста; провал
 * verify не трогает файлы; якорные фрагменты проходят копией.
 */
class ExternalTranslateTest {

    @TempDir
    lateinit var dir: Path

    private val src = listOf("古い人形が棚の上に静かに座っていた。", "彼は小さな店でそれを買った。")
    private val draft = listOf(
        "Старая кукла тихо сидела на полке. [[имя?: 店 → лавка | небольшая торговая лавка]]",
        "Он купил её в маленькой лавке.",
    )
    private val edited = listOf("Старая кукла тихо сидела на полке.", "Он купил её в маленькой лавочке.")

    private class Fixture(
        val store: ProjectStore,
        val draftStage: DraftStage,
        val editStage: EditStage,
        val knowledge: KnowledgeService,
    ) {
        fun fragmentFile(bookSlug: String, id: String): FragmentFile {
            val path = store.bookDir("test", bookSlug).resolve("work").resolve("fragments").resolve("$id.json")
            return AppJson.decodeFromString(FragmentFile.serializer(), path.readText())
        }

        fun firstFragmentId(bookSlug: String): String =
            SplitStage.loadManifest(store.bookDir("test", bookSlug)).allFragments().first().id
    }

    /** Книга из одного текстового фрагмента (2 блока) — базовый сценарий. */
    private fun fixture(bookSlug: String = "b", blocks: List<ChapterBlock> = src.map { ChapterBlock(BlockKind.TEXT, it) }): Fixture {
        val store = ProjectStore { dir }
        val project = store.createProject("test", "ja", "ru", "ja")
        val meta = ChapterMeta(
            index = 1,
            title = "Глава",
            file = "chapter-0001.md",
            blockCount = blocks.size,
            charCount = BlockText.charCount(blocks),
        )
        val book = BookMeta(slug = bookSlug, title = "Книга", author = "Автор", format = "epub")
        store.writeChapterSource(project.slug, book.slug, meta, blocks)
        store.saveBookMeta(project.slug, book)
        store.saveStructure(project.slug, book.slug, BookStructure(listOf(meta)))
        SplitStage(store).split(project.slug, book.slug, partMaxChars = 5000, fragmentTargetChars = 1700)

        val promptStore = PromptStore({ dir }) { slug -> store.projectDir(slug) }
        val knowledge = KnowledgeService(store)
        LlmClient().use { client ->
            return Fixture(
                store,
                DraftStage(store, promptStore, knowledge, client, { AppSettings() }, { VerifyEngine("ja") }),
                EditStage(store, promptStore, knowledge, client, { AppSettings() }, { VerifyEngine("ja") }),
                knowledge,
            )
        }
    }

    @Test
    fun `бриф черновика - то же задание что у этапа`() = runBlocking {
        val f = fixture()
        val id = f.firstFragmentId("b")
        val brief = f.draftStage.briefFor("test", "b", id)!!
        assertTrue(brief.contains("СЛОВАРЬ"), "в брифе есть секция словаря")
        assertTrue(brief.contains("ИСХОДНИК"), "в брифе есть исходник")
        assertTrue(brief.contains(src[0]), "строки исходника в брифе")
        assertEquals(null, f.draftStage.briefFor("test", "b", "нет-фрагмента"))
    }

    @Test
    fun `кривой черновик отвергается без единой записи`() = runBlocking {
        val f = fixture()
        val id = f.firstFragmentId("b")
        // один блок вместо двух — verify требует 1:1
        val result = f.draftStage.submitExternal("test", "b", id, "Только одна строка.", "test-agent")
        assertTrue(result is ExternalSubmitResult.Rejected)
        val frag = SplitStage.loadManifest(f.store.bookDir("test", "b")).allFragments().first()
        assertEquals("pending", frag.draft.status, "статус не тронут")
        assertEquals(emptyList<String>(), f.fragmentFile("b", id).draftBlocks, "файл не тронут")
    }

    @Test
    fun `принятый черновик пишет тексты статус и предложение термина`() = runBlocking {
        val f = fixture()
        val id = f.firstFragmentId("b")
        val result = f.draftStage.submitExternal("test", "b", id, draft.joinToString("\n"), "test-agent")
        assertTrue(result is ExternalSubmitResult.Accepted)
        assertEquals(2, (result as ExternalSubmitResult.Accepted).blocks)

        val frag = SplitStage.loadManifest(f.store.bookDir("test", "b")).allFragments().first()
        assertEquals("done", frag.draft.status)
        val file = f.fragmentFile("b", id)
        assertEquals(draft, file.draftBlocks)
        assertEquals("test-agent", file.model)
        assertTrue(file.verifyDraft.isNotBlank())
        // терминолог: маркер черновика стал предложением глоссария
        val proposed = f.knowledge.glossary("test").firstOrNull { it.original == "店" }
        assertEquals("лавка", proposed?.translation)
        assertEquals("proposed", proposed?.status)
    }

    @Test
    fun `редактура - бриф по черновику приём и жёсткий отказ по блокам`() = runBlocking {
        val f = fixture()
        val id = f.firstFragmentId("b")
        assertEquals("НЕТ ЧЕРНОВИКА — сначала переведи фрагмент (stage=draft).", f.editStage.briefFor("test", "b", id))
        assertTrue(f.editStage.submitExternal("test", "b", id, "Текст", "test") is ExternalSubmitResult.Error)

        f.draftStage.submitExternal("test", "b", id, draft.joinToString("\n"), "test-agent")
        val brief = f.editStage.briefFor("test", "b", id)!!
        assertTrue(brief.contains("ЧЕРНОВИК"), "редактору — черновик, не оригинал")
        assertTrue(brief.contains(draft[0]))

        val rejected = f.editStage.submitExternal("test", "b", id, "Одна строка вместо двух.", "test-editor")
        assertTrue(rejected is ExternalSubmitResult.Rejected)
        assertEquals("pending", SplitStage.loadManifest(f.store.bookDir("test", "b")).allFragments().first().edited.status)

        val accepted = f.editStage.submitExternal("test", "b", id, edited.joinToString("\n"), "test-editor")
        assertTrue(accepted is ExternalSubmitResult.Accepted)
        val frag = SplitStage.loadManifest(f.store.bookDir("test", "b")).allFragments().first()
        assertEquals("done", frag.edited.status)
        assertEquals(edited, f.fragmentFile("b", id).editedBlocks)
    }

    @Test
    fun `якорный фрагмент - бриф-маркер и приём дословной копией`() = runBlocking {
        val blocks = listOf(
            BlockText.imageMarker("item/image/k003-004.jpg"),
            BlockText.separator(),
        )
        val f = fixture(bookSlug = "anchors", blocks = blocks)
        val id = f.firstFragmentId("anchors")
        val brief = f.draftStage.briefFor("test", "anchors", id)!!
        assertTrue(brief.contains("ЯКОРНЫЙ ФРАГМЕНТ"))
        val result = f.draftStage.submitExternal("test", "anchors", id, blocks.joinToString("\n") { it.text }, "test-agent")
        assertTrue(result is ExternalSubmitResult.Accepted)
        assertEquals(blocks.map { it.text }, f.fragmentFile("anchors", id).draftBlocks)
    }
}
