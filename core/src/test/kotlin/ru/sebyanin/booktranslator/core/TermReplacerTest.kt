package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.sebyanin.booktranslator.core.glossary.TermReplacer
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.FragmentState
import ru.sebyanin.booktranslator.core.model.PartState
import ru.sebyanin.booktranslator.core.model.StageState
import ru.sebyanin.booktranslator.core.model.WorkManifest
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files
import java.nio.file.Path

/**
 * Слой 1 замены термина: скан книги (точные вхождения + стем-фрагменты),
 * транзакционная замена по всем фрагментам (edited приоритетен над draft),
 * правка маркера [[имя?: …]] в черновике, отказы и транзакционность.
 */
class TermReplacerTest {

    @TempDir
    lateinit var dir: Path

    private val from = "Карла"
    private val to = "Кара"

    private fun store(): ProjectStore {
        val s = ProjectStore { dir }
        s.createProject("test", "ja", "ru", "ja")
        return s
    }

    /** f1 — edited («Карла» ×2), f2 — draft («Карлы» + «Карла» внутри маркера). */
    private fun writeBook(store: ProjectStore, f2ExtraKana: Boolean = false) {
        val bookDir = store.bookDir("test", "b")
        Files.createDirectories(bookDir.resolve("work").resolve("fragments"))
        SplitStage.saveManifest(
            bookDir,
            WorkManifest(
                bookSlug = "b",
                parts = listOf(
                    PartState(
                        id = "part-0001", chapterIndex = 1, chapterFile = "chapter-0001.md",
                        blockFrom = 0, blockTo = 3, charCount = 40,
                        fragments = listOf(
                            FragmentState(
                                id = "part-0001-f1", blockFrom = 0, blockTo = 1, charCount = 20,
                                draft = StageState(status = "done"), edited = StageState(status = "done"),
                            ),
                            FragmentState(id = "part-0001-f2", blockFrom = 2, blockTo = 3, charCount = 20, draft = StageState(status = "done")),
                        ),
                    ),
                ),
            ),
        )
        val f1 = FragmentFile(
            id = "part-0001-f1",
            srcBlocks = listOf("カルラは笑った。", "カルラの本だ。"),
            draftBlocks = listOf("Черновик с Карлой — не должен меняться."),
            editedBlocks = listOf("Карла улыбнулась.", "Книгу Карла оставила на столе."),
        )
        val f2 = FragmentFile(
            id = "part-0001-f2",
            srcBlocks = listOf("カルラの名前。", "次の行。"),
            draftBlocks = listOf(
                "Имя — Карлы [[имя?: カルラ → Карла | героиня]].",
                if (f2ExtraKana) "Вторая строка に каной." else "Вторая строка без кан.",
            ),
            editedBlocks = emptyList(),
        )
        listOf(f1, f2).forEach { f ->
            ProjectStore.atomicWrite(
                bookDir.resolve("work").resolve("fragments").resolve("${f.id}.json"),
                AppJson.encodeToString(FragmentFile.serializer(), f),
            )
        }
    }

    private fun fragment(store: ProjectStore, id: String): FragmentFile =
        AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(store.bookDir("test", "b").resolve("work").resolve("fragments").resolve("$id.json")),
        )

    @Test
    fun `скан находит точные вхождения и стем-фрагменты`() {
        val store = store()
        writeBook(store)
        val scan = TermReplacer(store).scan("test", "b", from)
        // три точных вхождения: f1 (блоки 1 и 2) и f2 («Карла» внутри маркера)
        assertEquals(3, scan.occurrences)
        assertEquals(3, scan.exact.size)
        assertEquals(listOf("part-0001-f1", "part-0001-f2"), scan.fragmentIds)
        val f1Hits = scan.exact.filter { it.fragmentId == "part-0001-f1" }
        assertEquals(listOf(0, 1), f1Hits.map { it.blockIndex })
        assertEquals("Карла улыбнулась.", f1Hits[0].text)
        // последнее вхождение — маркер в f2, одно на блок
        assertEquals("part-0001-f2", scan.exact.last().fragmentId)
        assertEquals(1, scan.exact.last().count)
        assertTrue(scan.exact.last().text.contains("[[имя?:"))
        // «Карлы» — не точное вхождение, но стем «Карл» его ловит
        assertEquals(listOf("part-0001-f1", "part-0001-f2"), scan.stemFragmentIds)
    }

    @Test
    fun `замена везде меняет edited и draft и правит маркер`() {
        val store = store()
        writeBook(store)
        val outcome = TermReplacer(store).replaceEverywhere("test", "b", from, to, VerifyEngine("ja"))
        assertTrue(outcome is TermReplacer.ReplaceOutcome.Done)
        outcome as TermReplacer.ReplaceOutcome.Done
        assertEquals(3, outcome.replaced)
        assertEquals(listOf("part-0001-f1", "part-0001-f2"), outcome.fragments)
        assertTrue(outcome.touchedDraft)

        val f1 = fragment(store, "part-0001-f1")
        assertEquals("Кара улыбнулась.", f1.editedBlocks[0])
        assertEquals("Книгу Кара оставила на столе.", f1.editedBlocks[1])
        // черновик не тронут: edited приоритетен
        assertEquals("Черновик с Карлой — не должен меняться.", f1.draftBlocks[0])
        assertTrue(f1.verifyEdited.isNotBlank())

        val f2 = fragment(store, "part-0001-f2")
        // «Карлы» не точное вхождение — остаётся; «Карла» внутри маркера меняется
        assertEquals("Имя — Карлы [[имя?: カルラ → Кара | героиня]].", f2.draftBlocks[0])
        assertTrue(f2.verifyDraft.isNotBlank())
    }

    @Test
    fun `транзакционность - сбой verify на одном фрагменте не пишет ничего`() {
        val store = store()
        // в f2 остаточная кана вне маркеров — verify f2 падает после замены
        writeBook(store, f2ExtraKana = true)
        val outcome = TermReplacer(store).replaceEverywhere("test", "b", from, to, VerifyEngine("ja"))
        assertTrue(outcome is TermReplacer.ReplaceOutcome.Failed)
        // f1, чья проверка прошла бы, тоже не записан
        assertTrue(fragment(store, "part-0001-f1").editedBlocks[0].contains(from))
        assertFalse(fragment(store, "part-0001-f1").editedBlocks[0].contains(to))
    }

    @Test
    fun `отказы - короткое слово совпадение и не найдено`() {
        val store = store()
        writeBook(store)
        val replacer = TermReplacer(store)
        assertTrue(replacer.replaceEverywhere("test", "b", "К", to, VerifyEngine("ja")) is TermReplacer.ReplaceOutcome.Failed)
        assertTrue(replacer.replaceEverywhere("test", "b", from, from, VerifyEngine("ja")) is TermReplacer.ReplaceOutcome.Failed)
        assertTrue(replacer.replaceEverywhere("test", "b", "Мирославна", to, VerifyEngine("ja")) is TermReplacer.ReplaceOutcome.Failed)
    }

    @Test
    fun `стем и счётчик вхождений`() {
        assertEquals("Карл", TermReplacer.stemOf("Карла"))
        assertEquals("Удгард", TermReplacer.stemOf("Удгардо"))
        assertEquals("Оцу", TermReplacer.stemOf("Оцу")) // короткое ищется целиком
        assertEquals(null, TermReplacer.stemOf("йо"))
        assertEquals(2, TermReplacer.countOccurrences("Карла, Карла и Карлы", "Карла"))
        assertEquals(0, TermReplacer.countOccurrences("никого", "Карла"))
        assertEquals(0, TermReplacer.countOccurrences("что угодно", ""))
    }
}
