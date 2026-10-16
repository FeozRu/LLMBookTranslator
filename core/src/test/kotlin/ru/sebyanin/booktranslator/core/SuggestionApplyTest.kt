package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.sebyanin.booktranslator.core.glossary.SuggestionService
import ru.sebyanin.booktranslator.core.model.CriticSuggestion
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
 * Применение правок корректора: отказ одиночной правки при нескольких вхождениях
 * (с флагом «можно принудительно»), ручное «Применить везде всё равно» — транзакционно,
 * locateAll как список мест для показа человеку.
 */
class SuggestionApplyTest {

    @TempDir
    lateinit var dir: Path

    private val was = "старая кукла"
    private val became = "старая кукла из ткани"

    private fun store(): ProjectStore {
        val s = ProjectStore { dir }
        s.createProject("test", "ja", "ru", "ja")
        return s
    }

    /** Два фрагмента одной главы: цитата «старая кукла» в f1 (блок 1) и f2 (блок 1). */
    private fun writeBook(store: ProjectStore, f2Edited: List<String> = defaultF2Edited) {
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
                            FragmentState(id = "part-0001-f1", blockFrom = 0, blockTo = 1, charCount = 10, draft = StageState(status = "done")),
                            FragmentState(id = "part-0001-f2", blockFrom = 2, blockTo = 3, charCount = 10, draft = StageState(status = "done")),
                        ),
                    ),
                ),
            ),
        )
        val f1 = FragmentFile(
            id = "part-0001-f1",
            srcBlocks = listOf("古い人形が棚の上に静かに座っていた。", "次の行も同じくらいの長さ。"),
            draftBlocks = emptyList(),
            editedBlocks = listOf("У окна стояла старая кукла, пыльная и милая.", "Вторая строка перевода."),
        )
        val f2 = FragmentFile(
            id = "part-0001-f2",
            srcBlocks = listOf("彼は小さな店で古い人形を買った。", "別の行。"),
            draftBlocks = emptyList(),
            editedBlocks = f2Edited,
        )
        listOf(f1, f2).forEach { f ->
            ProjectStore.atomicWrite(
                bookDir.resolve("work").resolve("fragments").resolve("${f.id}.json"),
                AppJson.encodeToString(FragmentFile.serializer(), f),
            )
        }
    }

    private val defaultF2Edited = listOf("Это была старая кукла из магазина.", "Ещё одна строка текста.")

    private fun fragment(store: ProjectStore, id: String): FragmentFile =
        AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(store.bookDir("test", "b").resolve("work").resolve("fragments").resolve("$id.json")),
        )

    private fun suggestion(replaceAll: Boolean = false) = CriticSuggestion(
        id = "c1-1", chapter = 1, was = was, became = became,
        replaceAll = replaceAll, grepCount = 2,
    )

    @Test
    fun `одиночная правка при двух вхождениях отказывается и предлагает везд`() {
        val store = store()
        writeBook(store)
        val service = SuggestionService(store)
        val outcome = service.apply("test", "b", suggestion(), VerifyEngine("ja"))
        assertTrue(outcome is SuggestionService.ApplyOutcome.Failed)
        outcome as SuggestionService.ApplyOutcome.Failed
        assertEquals(2, outcome.forceEverywhereCount)
        // файлы не тронуты: цитата на месте, «стало» не появилось
        assertTrue(fragment(store, "part-0001-f1").editedBlocks[0].contains(was))
        assertTrue(fragment(store, "part-0001-f2").editedBlocks[0].contains(was))
        assertFalse(fragment(store, "part-0001-f1").editedBlocks[0].contains(became))
    }

    @Test
    fun `принудительное везде меняет все вхождения в обоих фрагментах`() {
        val store = store()
        writeBook(store)
        val service = SuggestionService(store)
        val outcome = service.apply("test", "b", suggestion(), VerifyEngine("ja"), forceEverywhere = true)
        assertTrue(outcome is SuggestionService.ApplyOutcome.Applied)
        val f1 = fragment(store, "part-0001-f1")
        val f2 = fragment(store, "part-0001-f2")
        assertTrue(f1.editedBlocks[0].contains(became))
        assertTrue(f2.editedBlocks[0].contains(became))
        // точная цитата «было» ни в одном фрагменте не осталась
        assertFalse(f1.editedBlocks[0].contains("кукла, пыльная"))
        assertFalse(f2.editedBlocks[0].contains("кукла из магазина"))
        assertEquals("applied", (outcome as SuggestionService.ApplyOutcome.Applied).suggestion.status)
    }

    @Test
    fun `locateAll находит все места - locate требует однозначность`() {
        val store = store()
        writeBook(store)
        val service = SuggestionService(store)
        val all = service.locateAll("test", "b", suggestion())
        assertEquals(2, all.size)
        assertEquals("part-0001-f1", all[0].fragmentId)
        assertEquals(0, all[0].blockIndex)
        assertEquals("part-0001-f2", all[1].fragmentId)
        assertNull(service.locate("test", "b", suggestion()))
    }

    @Test
    fun `принудительное везде транзакционно - сбой verify не пишет ничего`() {
        val store = store()
        // во втором фрагменте остаточная кана в переводе — verify второго фрагмента падает
        writeBook(store, f2Edited = listOf("Это была старая кукла из 店 магазина.", "Ещё одна строка текста."))
        val service = SuggestionService(store)
        val outcome = service.apply("test", "b", suggestion(), VerifyEngine("ja"), forceEverywhere = true)
        assertTrue(outcome is SuggestionService.ApplyOutcome.Failed)
        outcome as SuggestionService.ApplyOutcome.Failed
        assertNull(outcome.forceEverywhereCount)
        // первый фрагмент, чья проверка прошла, всё равно не записан
        assertFalse(fragment(store, "part-0001-f1").editedBlocks[0].contains(became))
    }
}
