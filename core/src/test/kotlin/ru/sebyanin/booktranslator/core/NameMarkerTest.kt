package ru.sebyanin.booktranslator.core

import ru.sebyanin.booktranslator.core.pipeline.DraftStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Регулярка маркера [[имя?: …]]: старый формат без пояснения обязан работать как раньше. */
class NameMarkerTest {

    private fun parse(line: String) = DraftStage.NAME_MARKER.findAll(line).map {
        Triple(it.groupValues[1].trim(), it.groupValues[2].trim(), it.groupValues[3].trim())
    }.toList()

    @Test
    fun `старый формат без пояснения`() {
        val marks = parse(" она надела [[имя?: 市女笠 → итигасу]] и вышла ")
        assertEquals(1, marks.size)
        assertEquals("市女笠", marks[0].first)
        assertEquals("итигасу", marks[0].second)
        assertEquals("", marks[0].third)
    }

    @Test
    fun `новый формат с пояснением`() {
        val marks = parse("[[имя?: 梶 → оти | бумажное дерево, гребень из него]]")
        assertEquals(Triple("梶", "оти", "бумажное дерево, гребень из него"), marks.single())
    }

    @Test
    fun `без пробелов вокруг разделителей`() {
        val marks = parse("[[имя?:大正→Тайсэй|эпоха, 1912–1926]]")
        assertEquals(Triple("大正", "Тайсэй", "эпоха, 1912–1926"), marks.single())
    }

    @Test
    fun `пояснение может содержать стрелку`() {
        val marks = parse("[[имя?: 中有 → Тёу | буддийский термин: между смертью → и перерождением]]")
        assertEquals(Triple("中有", "Тёу", "буддийский термин: между смертью → и перерождением"), marks.single())
    }

    @Test
    fun `несколько маркеров в одной строке`() {
        val marks = parse("а [[имя?: 巫女 → мико | служанка храма]] и [[имя?: 梶 → оти]] тут")
        assertEquals(2, marks.size)
        assertEquals("мико", marks[0].second)
        assertEquals("оти", marks[1].second)
        assertEquals("", marks[1].third)
    }

    @Test
    fun `два вхождения одного термина — оба найдены`() {
        val marks = parse("[[имя?: 梶 → оти]] и снова [[имя?: 梶 → оти | дерево]]")
        assertEquals(2, marks.size)
        assertEquals("", marks[0].third)
        assertEquals("дерево", marks[1].third)
    }
}
