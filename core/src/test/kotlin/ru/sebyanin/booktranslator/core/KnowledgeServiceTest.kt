package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import java.nio.file.Path

/** Удаление из канона человеком: термин и заметка убираются целиком, повторное удаление — false. */
class KnowledgeServiceTest {

    @TempDir
    lateinit var dir: Path

    private fun service(): KnowledgeService {
        val store = ProjectStore { dir }
        store.createProject("test", "ja", "ru", "ja")
        return KnowledgeService(store)
    }

    @Test
    fun `removeTerm удаляет запись и сообщает об отсутствии`() {
        val knowledge = service()
        assertTrue(knowledge.addManualTerm("test", "ライトエルフ", "светлый эльф"))
        assertTrue(knowledge.removeTerm("test", "ライトエルフ"))
        assertEquals(0, knowledge.glossary("test").size)
        assertFalse(knowledge.removeTerm("test", "ライトエルフ"))
    }

    @Test
    fun `removeNote удаляет по entity и situation`() {
        val knowledge = service()
        assertTrue(knowledge.addManualNote("test", "Кандата", "обращение", "говорит развязно"))
        assertTrue(knowledge.removeNote("test", "Кандата", "обращение"))
        assertEquals(0, knowledge.notes("test").size)
        assertFalse(knowledge.removeNote("test", "Кандата", "обращение"))
    }
}
