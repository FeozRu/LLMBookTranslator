package ru.sebyanin.booktranslator.core

import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlockTextTest {

    @Test
    fun `roundtrip всех видов блоков`() {
        val blocks = listOf(
            ChapterBlock(BlockKind.HEADING, "# Глава первая"),
            ChapterBlock(BlockKind.COMMENT, "<!-- шапка части 04-03 -->"),
            ChapterBlock(BlockKind.TEXT, "Сома открыл глаза."),
            ChapterBlock(BlockKind.SEPARATOR, BlockText.SEPARATOR_LINE),
            ChapterBlock(BlockKind.IMAGE, "{ [img:ill_01.jpg] }"),
            ChapterBlock(BlockKind.HEADING, "## Подзаголовок"),
            ChapterBlock(BlockKind.TEXT, "— Реплика, — сказал он."),
        )
        val encoded = BlockText.encode(blocks)
        val decoded = BlockText.decode(encoded)
        assertEquals(blocks, decoded)
    }

    @Test
    fun `пустые строки игнорируются`() {
        val decoded = BlockText.decode("первый\n\n  \nвторой\n")
        assertEquals(2, decoded.size)
        assertEquals("первый", decoded[0].text)
    }

    @Test
    fun `знаки без пробелов`() {
        val text = listOf(ChapterBlock(BlockKind.TEXT, "а б в"))
        assertEquals(3, BlockText.charCount(text))
        // Идеографические пробелы U+3000 не считаются знаками, ромбики — считаются
        val sep = listOf(ChapterBlock(BlockKind.SEPARATOR, "◇　◇　◇"))
        assertEquals(3, BlockText.charCount(sep))
    }

    @Test
    fun `якорные строки и якорный фрагмент`() {
        assertTrue(BlockText.isAnchorLine("{ [img:item/image/k003-004.jpg] }"))
        assertTrue(BlockText.isAnchorLine("◇　◇　◇"))
        assertTrue(BlockText.isAnchorLine("<!-- шапка части 04-03 -->"))
        assertFalse(BlockText.isAnchorLine("# Глава 5"))
        assertFalse(BlockText.isAnchorLine("Абзац обычного текста."))
        // искажённая форма якоря (как их порой портят модели) — не якорь
        assertFalse(BlockText.isAnchorLine("{{img:item/image/k003-004.jpg}}"))

        assertFalse(BlockText.allAnchors(emptyList()))
        assertTrue(BlockText.allAnchors(listOf("{ [img:a.jpg] }", "◇　◇　◇", "<!-- шапка -->")))
        assertFalse(BlockText.allAnchors(listOf("{ [img:a.jpg] }", "Текст.")))
    }
}
