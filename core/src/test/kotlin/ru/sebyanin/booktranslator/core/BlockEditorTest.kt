package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.llm.PromptDefaults
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.pipeline.BlockEditor
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ручной редактор блоков «Перевода»: вставка маркера примечания { [note: …] } сразу
 * после выделения, сохранение правки в эффективное поле фрагмента (editedBlocks,
 * иначе draftBlocks) с verify-гейтом — сломанный якорь = отказ, файл не тронут.
 */
class BlockEditorTest {

    @TempDir
    lateinit var dir: Path

    private val srcBlocks = listOf(
        "古い人形が棚の上に静かに座っていた。",
        "{ [img:pic.jpg] }",
        "次の行も同じくらいの長さ。",
    )
    private val editedBlocks = listOf(
        "Старая кукла тихо сидела на полке у окна.",
        "{ [img:pic.jpg] }",
        "Следующая строка той же длины.",
    )

    private fun store(): ProjectStore {
        val s = ProjectStore { dir }
        s.createProject("test", "ja", "ru", "ja")
        return s
    }

    private fun editor(store: ProjectStore) = BlockEditor(
        store,
        PromptStore({ dir.resolve("data") }) { slug -> dir.resolve("data").resolve("projects").resolve(slug) },
        KnowledgeService(store),
        LlmClient(),
        { AppSettings() },
    )

    private fun writeFragment(store: ProjectStore, ff: FragmentFile) {
        val frags = store.bookDir("test", "b").resolve("work").resolve("fragments")
        Files.createDirectories(frags)
        ProjectStore.atomicWrite(frags.resolve("${ff.id}.json"), AppJson.encodeToString(FragmentFile.serializer(), ff))
    }

    private fun readFragment(store: ProjectStore, id: String): FragmentFile =
        AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(store.bookDir("test", "b").resolve("work").resolve("fragments").resolve("$id.json")),
        )

    // ---------- вставка маркера ----------

    @Test
    fun `маркер вставляется сразу после выделения`() {
        val text = "Старая кукла сидела на полке."
        val start = text.indexOf("кукла")
        val end = start + "кукла".length
        val out = BlockEditor.insertNoteMarker(text, start, end, "фуруги — ширма; сидеть у окна почётно")
        assertEquals("Старая кукла { [note: фуруги — ширма; сидеть у окна почётно] } сидела на полке.", out)
    }

    @Test
    fun `перевёрнутое выделение равно прямому - пустое примечание и пустое выделение ничего не вставляют`() {
        val text = "Старая кукла сидела."
        val start = text.indexOf("кукла")
        val end = start + "кукла".length
        assertEquals(
            BlockEditor.insertNoteMarker(text, end, start, "примечание"),
            BlockEditor.insertNoteMarker(text, start, end, "примечание"),
        )
        assertEquals(text, BlockEditor.insertNoteMarker(text, start, end, "   "))
        assertEquals(text, BlockEditor.insertNoteMarker(text, start, start, "примечание"))
    }

    @Test
    fun `текст примечания чистится - переносы схлопываются - обрамления снимаются`() {
        assertEquals("ширма фуруги. Вторая строка", BlockEditor.cleanNote("{ [note: ширма фуруги.\nВторая строка] }"))
        assertEquals("просто слова", BlockEditor.cleanNote("\"просто слова\""))
    }

    // ---------- сохранение правки ----------

    @Test
    fun `правка блока пишется в editedBlocks - перенос строк схлопывается в пробел`() {
        val store = store()
        writeFragment(store, FragmentFile(id = "f1", srcBlocks = srcBlocks, editedBlocks = editedBlocks))
        val newText = "Старая кукла сидела на полке у самого окна."
        val error = editor(store).saveEdit(
            "test", "b",
            BlockEditor.BlockRef("f1", 0, fromEdited = true),
            "$newText\nвторая строка правки",
        )
        assertNull(error)
        val ff = readFragment(store, "f1")
        assertEquals("$newText вторая строка правки", ff.editedBlocks[0])
        assertEquals(editedBlocks[1], ff.editedBlocks[1])
        assertEquals(editedBlocks[2], ff.editedBlocks[2])
        assertTrue(ff.verifyEdited.isNotBlank())
    }

    @Test
    fun `правка пишется в draftBlocks когда редактуры не было`() {
        val store = store()
        val draft = listOf("Черновик первой строки.", "{ [img:pic.jpg] }", "Черновик третьей строки.")
        writeFragment(store, FragmentFile(id = "f1", srcBlocks = srcBlocks, draftBlocks = draft))
        val error = editor(store).saveEdit(
            "test", "b",
            BlockEditor.BlockRef("f1", 2, fromEdited = false),
            "Черновик третьей строки, поправлен человеком.",
        )
        assertNull(error)
        val ff = readFragment(store, "f1")
        assertEquals("Черновик третьей строки, поправлен человеком.", ff.draftBlocks[2])
        assertEquals(draft[0], ff.draftBlocks[0])
        assertTrue(ff.editedBlocks.isEmpty())
        assertTrue(ff.verifyDraft.isNotBlank())
    }

    @Test
    fun `сломанный якорь - отказ и файл не тронут`() {
        val store = store()
        writeFragment(store, FragmentFile(id = "f1", srcBlocks = srcBlocks, editedBlocks = editedBlocks))
        val before = readFragment(store, "f1")
        // Правка блока 0 сама по себе безобидна, но удалит якорь из блока 1 нельзя —
        // здесь она просто оставляет якорь на месте; ломаем ДРУГОЙ способ: правим блок 1
        val error = editor(store).saveEdit("test", "b", BlockEditor.BlockRef("f1", 1, fromEdited = true), "якорь стёрли")
        assertNotNull(error)
        assertTrue(error!!.contains("якоря"), "в причине должен быть якорный сбой: $error")
        assertEquals(before, readFragment(store, "f1"))
    }

    @Test
    fun `неизменённый текст файл не пишет`() {
        val store = store()
        writeFragment(store, FragmentFile(id = "f1", srcBlocks = srcBlocks, editedBlocks = editedBlocks))
        val before = readFragment(store, "f1")
        val error = editor(store).saveEdit(
            "test", "b",
            BlockEditor.BlockRef("f1", 0, fromEdited = true),
            editedBlocks[0] + "\n ",
        )
        assertNull(error)
        assertEquals(before, readFragment(store, "f1"))
    }

    // ---------- роль «Примечания» ----------

    @Test
    fun `роль примечаний - файл note md и плейсхолдер выделения в промпте`() {
        assertEquals("note.md", PromptStore.fileName(AgentRole.NOTE))
        assertTrue(PromptDefaults.forRole(AgentRole.NOTE).contains("{{SELECTION}}"))
    }
}
