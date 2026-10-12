package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.model.CriticSuggestion
import ru.sebyanin.booktranslator.core.pipeline.CriticStage

/** Разбор блока СОМНЕНИЯ на отдельные сомнения и парсинг ответа корректора. */
class CriticDoubtsTest {

    @Test
    fun `каждое сомнение с тире на новой строке`() {
        val text = """
            СОМНЕНИЯ:
            — 血の池 передано как «кровавое озеро»; не требует ли конвенция единого «пруд» для 池?
            — Оформление речи Кандаты: реплики через тире против «ёлочек».
        """.trimIndent()
        val doubts = CriticStage.splitDoubts(text.substringAfter("СОМНЕНИЯ:"))
        assertEquals(2, doubts.size)
        assertTrue(doubts[0].startsWith("血の池"))
        assertTrue(doubts[1].startsWith("Оформление"))
    }

    @Test
    fun `продолжение без маркера относится к предыдущему пункту`() {
        val doubts = CriticStage.splitDoubts("— первый пункт\nпродолжение первого\n— второй")
        assertEquals(listOf("первый пункт\nпродолжение первого", "второй"), doubts)
    }

    @Test
    fun `пустые строки и одиночные тире пропускаются`() {
        val doubts = CriticStage.splitDoubts("— один\n\n—\n— два")
        assertEquals(listOf("один", "два"), doubts)
    }

    @Test
    fun `парсер не теряет сомнения из ответа корректора`() {
        val answer = """
            1. [желательно] [повтор] БЫЛО: «тьма» СТАЛО: «мрак» ПОЧЕМУ: повтор корня в радиусе двух предложений
            Итого предложений: 1
            СОМНЕНИЯ:
            — 肝腎 в «драгоценный» — оттенок смещён; уточнить, кто «упадёт».
        """.trimIndent()
        val parsed = CriticStage.parseSuggestions(answer, 1)
        assertEquals(1, parsed.suggestions.size)
        assertEquals(listOf("肝腎 в «драгоценный» — оттенок смещён; уточнить, кто «упадёт»."), parsed.doubts)
    }

    @Test
    fun `сомнения нет - пустой список`() {
        val parsed = CriticStage.parseSuggestions("Итого предложений: 0\nСОМНЕНИЯ: нет", 1)
        assertEquals(0, parsed.suggestions.size)
        assertTrue(parsed.doubts.isEmpty())
    }

    @Test
    fun `правки по сомнению продолжают нумерацию главы`() {
        val existing = listOf(
            ru.sebyanin.booktranslator.core.model.CriticSuggestion(id = "c1-1", chapter = 1, was = "а", became = "б"),
            ru.sebyanin.booktranslator.core.model.CriticSuggestion(id = "c1-11", chapter = 1, was = "в", became = "г"),
        )
        val parsed = listOf(
            ru.sebyanin.booktranslator.core.model.CriticSuggestion(id = "c1-1", chapter = 1, was = "тьма", became = "мрак"),
        )
        val merged = CriticStage.appendSuggestions(existing, parsed, 1, "тьма была вокруг")
        assertEquals("c1-12", merged.single().id)
        assertEquals(1, merged.single().grepCount)
    }

    @Test
    fun `перенос при прогоне - только неразобранные правки по пометкам переводчика`() {
        val existing = listOf(
            CriticSuggestion(id = "c1-1", chapter = 1, was = "а", became = "б", source = "t1-00000001"), // перенесётся
            CriticSuggestion(id = "c1-2", chapter = 1, was = "в", became = "г", source = "t1-00000002", status = "applied"), // решена — нет
            CriticSuggestion(id = "c1-3", chapter = 1, was = "д", became = "е", source = "c1-9"), // от корректора — нет
            CriticSuggestion(id = "c1-4", chapter = 1, was = "ж", became = "з"), // без источника — нет
        )
        val carried = CriticStage.carryOver(existing)
        assertEquals(listOf("c1-1"), carried.map { it.id })
    }
}
