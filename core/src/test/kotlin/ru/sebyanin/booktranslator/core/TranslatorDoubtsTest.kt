package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.FragmentState
import ru.sebyanin.booktranslator.core.model.PartState
import ru.sebyanin.booktranslator.core.model.StageState
import ru.sebyanin.booktranslator.core.model.WorkManifest
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.pipeline.TranslatorDoubts
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Сомнения переводчика: маркеры [[??: …]] выводятся из текущего текста фрагментов
 * (edited, иначе draft), удаление пометки вычищает маркер и проходит верификацию.
 */
class TranslatorDoubtsTest {

    @TempDir
    lateinit var dir: Path

    private val src = listOf("原文の一行目。", "原文の二行目。")

    private fun store(): ProjectStore {
        val s = ProjectStore { dir }
        s.createProject("test", "ja", "ru", "ja")
        return s
    }

    /** Книга без импорта: манифест с одним фрагментом + файл фрагмента пишутся руками. */
    private fun writeFragment(store: ProjectStore, file: FragmentFile): Path {
        val bookDir = store.bookDir("test", "b")
        Files.createDirectories(bookDir.resolve("work").resolve("fragments"))
        SplitStage.saveManifest(
            bookDir,
            WorkManifest(
                bookSlug = "b",
                parts = listOf(
                    PartState(
                        id = "part-0001", chapterIndex = 1, chapterFile = "chapter-0001.md",
                        blockFrom = 0, blockTo = 1, charCount = 10,
                        fragments = listOf(FragmentState(id = "part-0001-f1", blockFrom = 0, blockTo = 1, charCount = 10, draft = StageState(status = "done"))),
                    ),
                ),
            ),
        )
        ProjectStore.atomicWrite(
            bookDir.resolve("work").resolve("fragments").resolve("${file.id}.json"),
            AppJson.encodeToString(FragmentFile.serializer(), file),
        )
        return bookDir
    }

    private fun fragment(draft: List<String>, edited: List<String> = emptyList()) =
        FragmentFile(id = "part-0001-f1", srcBlocks = src, draftBlocks = draft, editedBlocks = edited)

    @Test
    fun `маркер в черновике находится, имя-маркер и чистый текст - нет`() {
        val store = store()
        writeFragment(
            store,
            fragment(
                draft = listOf(
                    "Первая строка перевода [[??: непонятно, кто говорит]].",
                    "Вторая строка [[имя?: 宰相 → канцлер]] без сомнения.",
                ),
            ),
        )
        val doubts = TranslatorDoubts.list(store, "test", "b", 1)
        assertEquals(1, doubts.size)
        assertEquals("непонятно, кто говорит", doubts.single().text)
        assertEquals("part-0001-f1", doubts.single().fragmentId)
        assertEquals(0, doubts.single().blockIndex)
        assertFalse(doubts.single().fromEdited)
    }

    @Test
    fun `одна question - тоже распознаётся`() {
        val store = store()
        writeFragment(store, fragment(draft = listOf("Текст [[?: к чему отсылает тире]] дальше.")))
        assertEquals(1, TranslatorDoubts.list(store, "test", "b", 1).size)
    }

    @Test
    fun `edited приоритетнее - разрешённая редактором пометка исчезает`() {
        val store = store()
        writeFragment(
            store,
            fragment(
                draft = listOf("Черновик с пометкой [[??: кто это]]."),
                edited = listOf("Редактор разрешил сомнение."),
            ),
        )
        assertTrue(TranslatorDoubts.list(store, "test", "b", 1).isEmpty())

        writeFragment(
            store,
            fragment(
                draft = listOf("Черновик с пометкой [[??: кто это]]."),
                edited = listOf("Редактор оставил [[??: кто это]] на месте."),
            ),
        )
        val doubts = TranslatorDoubts.list(store, "test", "b", 1)
        assertEquals(1, doubts.size)
        assertTrue(doubts.single().fromEdited)
    }

    @Test
    fun `id стабилен и различает разные места и тексты`() {
        val store = store()
        writeFragment(
            store,
            fragment(
                draft = listOf(
                    "Блок с двумя пометками [[??: первая]] и [[??: вторая]] — [[??: первая]].",
                ),
            ),
        )
        val doubts = TranslatorDoubts.list(store, "test", "b", 1)
        assertEquals(3, doubts.size)
        val first = doubts[0].id
        assertEquals(first, TranslatorDoubts.list(store, "test", "b", 1).first { it.text == "первая" }.id)
        assertNotEquals(doubts[0].id, doubts[1].id) // разные тексты
        assertEquals(doubts[0].id, doubts[2].id) // одинаковые пометки в одном блоке — один id
    }

    @Test
    fun `удаление из edited вычищает маркер и проходит verify`() {
        val store = store()
        writeFragment(
            store,
            fragment(
                draft = listOf("Черновик первая строка.", "Черновик вторая строка."),
                edited = listOf("Женихом?! [[??: 「—にまで」— непонятно, к чему отсылает «—»]] Что дальше.", "Вторая строка перевода."),
            ),
        )
        val doubt = TranslatorDoubts.list(store, "test", "b", 1).single()
        assertNull(TranslatorDoubts.remove(store, "test", "b", doubt.id))

        val file = AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(store.bookDir("test", "b").resolve("work").resolve("fragments").resolve("part-0001-f1.json")),
        )
        assertEquals("Женихом?! Что дальше.", file.editedBlocks[0])
        assertTrue(!file.editedBlocks[0].contains("にまで"))
        assertTrue(TranslatorDoubts.list(store, "test", "b", 1).isEmpty())
    }

    @Test
    fun `удаление до редактуры правит draftBlocks`() {
        val store = store()
        writeFragment(store, fragment(draft = listOf("Текст [[??: что это]] конец.", "Вторая строка перевода.")))
        val doubt = TranslatorDoubts.list(store, "test", "b", 1).single()
        assertNull(TranslatorDoubts.remove(store, "test", "b", doubt.id))
        val file = AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(store.bookDir("test", "b").resolve("work").resolve("fragments").resolve("part-0001-f1.json")),
        )
        assertEquals("Текст конец.", file.draftBlocks[0])
    }

    @Test
    fun `чужой или протухший id - ошибка, файл не меняется`() {
        val store = store()
        writeFragment(store, fragment(draft = listOf("Текст [[??: что это]].", "Вторая строка перевода.")))
        val err = TranslatorDoubts.remove(store, "test", "b", "t1-00000000")
        assertTrue(err != null)
        val err2 = TranslatorDoubts.remove(store, "test", "b", "мусор")
        assertTrue(err2 != null)
        // после ошибки пометка осталась на месте
        assertEquals(1, TranslatorDoubts.list(store, "test", "b", 1).size)
    }

    @Test
    fun `решённые редактурой - пометки черновика, которых нет в edited`() {
        val store = store()
        writeFragment(
            store,
            fragment(
                draft = listOf(
                    "Проводив [бабушку] [[??: в строке пропущены слова]], я думал.",
                    "Оставлено [[??: unresolved]] на месте.",
                    "Без пометок.",
                ),
                edited = listOf(
                    "Проводив бабушку, я думал.",
                    "Оставлено [[??: unresolved]] на месте.",
                    "Без пометок.",
                ),
            ),
        )
        // активные — по текущему тексту (edited), resolved-группа им не дублируется
        assertEquals(listOf("unresolved"), TranslatorDoubts.list(store, "test", "b", 1).map { it.text })
        val resolved = TranslatorDoubts.resolvedByEditor(store, "test", "b", 1)
        assertEquals(1, resolved.size)
        assertEquals("в строке пропущены слова", resolved.single().doubt.text)
        assertFalse(resolved.single().doubt.fromEdited)
        assertEquals("Проводив бабушку, я думал.", resolved.single().currentBlock)
    }

    @Test
    fun `нередактированный фрагмент не даёт resolved-группы`() {
        val store = store()
        writeFragment(store, fragment(draft = listOf("Только черновик [[??: кто это]].", "Вторая строка перевода.")))
        assertTrue(TranslatorDoubts.resolvedByEditor(store, "test", "b", 1).isEmpty())
        assertEquals(1, TranslatorDoubts.list(store, "test", "b", 1).size)
    }
}
