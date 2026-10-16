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

    @Test
    fun `канал ГЛОССАРИЙ разбирается в предложения терминов`() {
        val answer = """
            1. [желательно] [термин] БЫЛО: «тёмный камергалант» СТАЛО: «канцлер» ПОЧЕМУ: транскрипция титула
            ГЛОССАРИЙ: [[имя?: 宰相 → канцлер | титул правителя, встречается по всей главе]]
            Итого предложений: 1
            СОМНЕНИЯ: нет
        """.trimIndent()
        val parsed = CriticStage.parseSuggestions(answer, 1)
        assertEquals(1, parsed.suggestions.size)
        val term = parsed.glossary.single()
        assertEquals("宰相", term.original)
        assertEquals("канцлер", term.translation)
        assertEquals("титул правителя, встречается по всей главе", term.reason)
    }

    @Test
    fun `несколько строк ГЛОССАРИЙ дают несколько терминов`() {
        val answer = """
            ГЛОССАРИЙ: [[имя?: 宰相 → канцлер | титул]]
            ГЛОССАРИЙ: [[имя?: 魔王 → владыка демонов | враг героя]]
            ГЛОССАРИЙ: [[имя?: 刀 → клинок]]
            Итого предложений: 0
            СОМНЕНИЯ: нет
        """.trimIndent()
        val parsed = CriticStage.parseSuggestions(answer, 2)
        assertEquals(listOf("宰相", "魔王", "刀"), parsed.glossary.map { it.original })
        assertEquals("клинок", parsed.glossary[2].translation)
    }

    @Test
    fun `ГЛОССАРИЙ без маркера или без стрелки - пустой список`() {
        assertTrue(CriticStage.parseSuggestions("ГЛОССАРИЙ: нет\nСОМНЕНИЯ: нет", 1).glossary.isEmpty())
        // Маркер требует стрелку «оригинал → вариант» — без неё это не предложение
        assertTrue(CriticStage.parseSuggestions("ГЛОССАРИЙ: [[имя?: 宰相]]\nСОМНЕНИЯ: нет", 1).glossary.isEmpty())
    }

    @Test
    fun `строка ГЛОССАРИЙ в блоке СОМНЕНИЯ не становится сомнением`() {
        val answer = """
            Итого предложений: 0
            СОМНЕНИЯ:
            — 血の池 передано как «кровавое озеро»; уточнить конвенцию.
            ГЛОССАРИЙ: [[имя?: 宰相 → канцлер | титул]]
        """.trimIndent()
        val parsed = CriticStage.parseSuggestions(answer, 1)
        assertEquals(1, parsed.doubts.size)
        assertTrue(parsed.doubts.single().startsWith("血の池"))
        assertEquals(1, parsed.glossary.size)
    }

    @Test
    fun `термин без пояснения получает пустую причину`() {
        val parsed = CriticStage.parseSuggestions("ГЛОССАРИЙ: [[имя?: 刀 → клинок]]\nСОМНЕНИЯ: нет", 1)
        assertEquals("", parsed.glossary.single().reason)
    }
}
