package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.sebyanin.booktranslator.core.glossary.TermFixer
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import java.nio.file.Path

/**
 * Слой 2 замены термина (LLM): разбор ответа модели (LLM в тестах НЕ вызывается)
 * и сборка промпта из шаблона роли.
 */
class TermFixerTest {

    @TempDir
    lateinit var dir: Path

    private fun fixer(): TermFixer =
        TermFixer(ProjectStore { dir }, PromptStore({ dir }, { dir }), LlmClient(), { AppSettings() })

    // ---------- парсер блоков ----------

    @Test
    fun `чистый json-объект с blocks разбирается`() {
        val parsed = TermFixer.parseBlocks("""{"blocks": ["Кара улыбнулась.", "Кары не было"]}""", 2)
        assertEquals(listOf("Кара улыбнулась.", "Кары не было"), parsed)
    }

    @Test
    fun `json в заборе с текстом вокруг`() {
        val raw = """
            Вот исправленные блоки:
            ```json
            {"blocks": ["Карла улыбнулась.", "Вторая строка."]}
            ```
            Итого: 2 блока.
        """.trimIndent()
        assertEquals(listOf("Карла улыбнулась.", "Вторая строка."), TermFixer.parseBlocks(raw, 2))
    }

    @Test
    fun `голый массив строк - фолбэк`() {
        assertEquals(listOf("а", "б"), TermFixer.parseBlocks("""["а", "б"]""", 2))
    }

    @Test
    fun `неверное число блоков - null`() {
        assertNull(TermFixer.parseBlocks("""{"blocks": ["только один"]}""", 2))
        assertNull(TermFixer.parseBlocks("""["а", "б", "в"]""", 2))
    }

    @Test
    fun `мусор вместо json - null`() {
        assertNull(TermFixer.parseBlocks("Я не смог ничего заменить, извините.", 2))
        assertNull(TermFixer.parseBlocks("""{"other": [1, 2]}""", 2))
    }

    // ---------- промпт ----------

    @Test
    fun `промпт собирается из шаблона роли`() {
        val template = "СТАРЫЙ ВАРИАНТ: {{FROM}}\nНОВЫЙ: {{TO}}\n<<<\n{{SOURCE}}\n>>>"
        val prompt = fixer().buildPrompt(template, "Карла", "Кара", listOf("Карла тут.", "Карлы нет"))
        assertTrue(prompt.contains("СТАРЫЙ ВАРИАНТ: Карла"))
        assertTrue(prompt.contains("НОВЫЙ: Кара"))
        assertTrue(prompt.contains("1. Карла тут."))
        assertTrue(prompt.contains("2. Карлы нет"))
        assertFalse(prompt.contains("{{FROM}}"))
    }
}
