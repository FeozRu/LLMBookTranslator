package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.builder.EpubBuilder
import ru.sebyanin.booktranslator.core.builder.Fb2Builder

/**
 * Связки сносок: href ссылки-сноски обязан указывать на id примечания. Раньше EPUB
 * ссылался на несуществующий notes.xhtml (примечания лежат в том же файле главы),
 * FB2 — на #note-n1 при id секции n1.
 */
class BookBuildersNoteLinksTest {

    @Test
    fun `epub - href сноски равен id примечания в том же файле главы`() {
        val ref = EpubBuilder.noteRef(3, 2)
        val id = EpubBuilder.noteId(3, 2)
        assertEquals("n3-2", id)
        val href = Regex("href=\"#([^\"]+)\"").find(ref)!!.groupValues[1]
        assertEquals(id, href)
        assertFalse(ref.contains("notes.xhtml"))
    }

    @Test
    fun `fb2 - href сноски равен id секции примечания`() {
        val ref = Fb2Builder.noteRef(7)
        val id = Fb2Builder.noteId(7)
        assertEquals("n7", id)
        val href = Regex("l:href=\"#([^\"]+)\"").find(ref)!!.groupValues[1]
        assertEquals(id, href)
    }
}
