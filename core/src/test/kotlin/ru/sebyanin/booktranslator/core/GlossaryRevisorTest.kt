package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.glossary.GlossaryRevisor
import ru.sebyanin.booktranslator.core.model.GlossaryEntry
import ru.sebyanin.booktranslator.core.model.GlossaryRevisionDecision

/**
 * Ревизор глоссария: парсер вердиктов (LLM в тестах НЕ вызывается), чанкер
 * пакетов и слияние решений файла. Примеры — реальные классы проблем genkoku.
 */
class GlossaryRevisorTest {

    private fun entry(original: String, translation: String, status: String = "proposed") =
        GlossaryEntry(original = original, translation = translation, status = status)

    // ---------- парсер ----------

    private val known = setOf("アイーシャ・ウドガルド", "とても", "ソーマ", "ハルバート・マグナ", "カリラ")

    @Test
    fun `чистый json-массив разбирается`() {
        val raw = """
            [{"original": "アイーシャ・ウドガルド", "verdict": "fix", "reason": "не по Поливанову", "suggested": "Айша Удгард"},
             {"original": "とても", "verdict": "drop", "reason": "обычное наречие"},
             {"original": "ソーマ", "verdict": "keep", "reason": "в порядке"}]
        """.trimIndent()
        val parsed = GlossaryRevisor.parseVerdicts(raw, known)
        assertEquals(3, parsed.valid.size)
        assertEquals("fix", parsed.valid[0].verdict)
        assertEquals("Айша Удгард", parsed.valid[0].suggested)
        assertEquals("drop", parsed.valid[1].verdict)
        assertTrue(parsed.valid[1].suggested.isEmpty())
        assertEquals("keep", parsed.valid[2].verdict)
        assertTrue(parsed.ignoredOriginals.isEmpty())
    }

    @Test
    fun `json в заборе с текстом вокруг`() {
        val raw = """
            Проанализирую записи очереди.
            ```json
            [{"original": "ハルバート・マグナ", "verdict": "fix", "reason": "часть «ハルバート» передаётся как «Харуберт»", "suggested": "Харуберт Магна"}]
            ```
            Итого: 1 замечание.
        """.trimIndent()
        val parsed = GlossaryRevisor.parseVerdicts(raw, known)
        assertEquals(1, parsed.valid.size)
        assertEquals("Харуберт Магна", parsed.valid[0].suggested)
        assertTrue(parsed.ignoredOriginals.isEmpty())
    }

    @Test
    fun `сломанный массив - фолбэк на отдельные объекты`() {
        val raw = """
            Вот вердикты:
            [{"original": "とても", "verdict": "drop", "reason": "мусор"},]
            {"original": "ソーマ", "verdict": "keep", "reason": "норм"}
        """.trimIndent()
        val parsed = GlossaryRevisor.parseVerdicts(raw, known)
        assertEquals(2, parsed.valid.size)
        assertEquals(listOf("とても", "ソーマ"), parsed.valid.map { it.original })
    }

    @Test
    fun `неизвестный оригинал игнорируется`() {
        val raw = """[{"original": "アイーシャ・ウドガルド", "verdict": "keep", "reason": ""},
                        {"original": "ウドガルド", "verdict": "drop", "reason": "нет в очереди"}]"""
        val parsed = GlossaryRevisor.parseVerdicts(raw, known)
        assertEquals(1, parsed.valid.size)
        assertEquals(listOf("ウドガルド"), parsed.ignoredOriginals)
    }

    @Test
    fun `вердикт не из списка игнорируется`() {
        val raw = """[{"original": "とても", "verdict": "replace", "reason": "что-то"}]"""
        val parsed = GlossaryRevisor.parseVerdicts(raw, known)
        assertTrue(parsed.valid.isEmpty())
        assertEquals(listOf("とても"), parsed.ignoredOriginals)
    }

    @Test
    fun `split-вердикт с частями разбирается`() {
        val raw = """
            [{"original": "カリラ", "verdict": "split", "reason": "в одной записи два термина",
              "parts": [{"original": "カリラ", "translation": "Карла"}, {"original": "カリル", "translation": "Карл"}]}]
        """.trimIndent()
        val parsed = GlossaryRevisor.parseVerdicts(raw, known)
        assertEquals(1, parsed.valid.size)
        val split = parsed.valid.single()
        assertEquals("split", split.verdict)
        assertEquals(2, split.parts.size)
        assertEquals("Карл", split.parts[1].translation)
        assertTrue(parsed.ignoredOriginals.isEmpty())
    }

    @Test
    fun `split без частей игнорируется, части при drop отбрасываются`() {
        val raw = """
            [{"original": "カリラ", "verdict": "split", "reason": "части забыли", "parts": []},
             {"original": "とても", "verdict": "drop", "reason": "мусор", "parts": [{"original": "x", "translation": "y"}]}]
        """.trimIndent()
        val parsed = GlossaryRevisor.parseVerdicts(raw, known)
        assertEquals(1, parsed.valid.size)
        assertEquals("drop", parsed.valid.single().verdict)
        assertTrue(parsed.valid.single().parts.isEmpty())
        assertEquals(listOf("カリラ"), parsed.ignoredOriginals)
    }

    // ---------- чанкер ----------

    @Test
    fun `границы пакетов`() {
        assertTrue(GlossaryRevisor.chunk(emptyList()).isEmpty())
        assertEquals(1, GlossaryRevisor.chunk((1..30).map { entry("o$it", "t$it") }).size)
        val batch31 = GlossaryRevisor.chunk((1..31).map { entry("o$it", "t$it") })
        assertEquals(listOf(30, 1), batch31.map { it.size })
        val batch75 = GlossaryRevisor.chunk((1..75).map { entry("o$it", "t$it") })
        assertEquals(listOf(30, 30, 15), batch75.map { it.size })
    }

    // ---------- слияние решений ----------

    @Test
    fun `повторный прогон заменяет pending и не трогает applied и dismissed`() {
        val existing = listOf(
            GlossaryRevisionDecision(id = "r1", original = "とても", verdict = "drop", reason = "старое", status = "pending"),
            GlossaryRevisionDecision(id = "r2", original = "ソーマ", verdict = "fix", suggested = "Сома", status = "applied"),
            GlossaryRevisionDecision(id = "r3", original = "ハルバート・マグナ", verdict = "drop", reason = "человек не согласился", status = "dismissed"),
        )
        val fresh = listOf(
            GlossaryRevisor.RawVerdict("とても", "fix", "новая причина", "очень"),
            GlossaryRevisor.RawVerdict("ソーマ", "fix", "уже применено — тот же вердикт", ""),
            GlossaryRevisor.RawVerdict("ハルバート・マグナ", "drop", "повтор", ""),
            GlossaryRevisor.RawVerdict("アイーシャ・ウドガルド", "fix", "Поливанов", "Айша Удгард"),
        )
        var counter = 0
        val merged = GlossaryRevisor.mergeDecisions(existing, fresh, now = { "t${counter++}" })

        // r1: pending заменён свежим, id сохранён
        val r1 = merged.first { it.id == "r1" }
        assertEquals("fix", r1.verdict)
        assertEquals("новая причина", r1.reason)
        assertEquals("очень", r1.suggested)
        assertTrue(r1.status == "pending")
        // applied/dismissed не тронуты
        assertEquals("applied", merged.first { it.id == "r2" }.status)
        assertEquals("Сома", merged.first { it.id == "r2" }.suggested)
        assertEquals("dismissed", merged.first { it.id == "r3" }.status)
        // новая запись продолжает нумерацию
        val r4 = merged.first { it.id == "r4" }
        assertEquals("アイーシャ・ウドガルド", r4.original)
        assertEquals("pending", r4.status)
        assertEquals(4, merged.size)
    }

    @Test
    fun `дубль внутри свежих вердиктов берётся один раз`() {
        val fresh = listOf(
            GlossaryRevisor.RawVerdict("とても", "drop", "первый", ""),
            GlossaryRevisor.RawVerdict("とても", "fix", "второй", "очень"),
        )
        val merged = GlossaryRevisor.mergeDecisions(emptyList(), fresh, now = { "t" })
        assertEquals(1, merged.size)
        assertEquals("первый", merged.single().reason)
    }

    @Test
    fun `split-решение сохраняет части при слиянии`() {
        val parts = listOf(
            ru.sebyanin.booktranslator.core.model.GlossaryRevisionPart("カリラ", "Карла"),
            ru.sebyanin.booktranslator.core.model.GlossaryRevisionPart("カリル", "Карл"),
        )
        // новая запись
        val fresh = listOf(GlossaryRevisor.RawVerdict("カリラ", "split", "упаковано", "", parts = parts))
        val merged = GlossaryRevisor.mergeDecisions(emptyList(), fresh, now = { "t" })
        assertEquals(2, merged.single().parts.size)
        // повторный прогон заменяет pending — части обновляются, а не наследуются старые
        val newParts = listOf(ru.sebyanin.booktranslator.core.model.GlossaryRevisionPart("カリラ", "Карла"))
        val again = GlossaryRevisor.mergeDecisions(
            merged,
            listOf(GlossaryRevisor.RawVerdict("カリラ", "split", "уточнил", "", parts = newParts)),
            now = { "t2" },
        )
        assertEquals(1, again.single().parts.size)
        assertEquals("уточнил", again.single().reason)
    }

    // ---------- скоупы: очередь и канон ----------

    @Test
    fun `решение запоминает скоуп прогона`() {
        val merged = GlossaryRevisor.mergeDecisions(
            emptyList(),
            listOf(GlossaryRevisor.RawVerdict("とても", "drop", "мусор", "")),
            scope = GlossaryRevisor.SCOPE_CANON,
            now = { "t" },
        )
        assertEquals("canon", merged.single().scope)
    }

    @Test
    fun `повторный прогон другого скоупа обновляет pending`() {
        val existing = listOf(
            GlossaryRevisionDecision(id = "r1", original = "とても", verdict = "drop", reason = "из очереди", scope = "queue"),
        )
        val merged = GlossaryRevisor.mergeDecisions(
            existing,
            listOf(GlossaryRevisor.RawVerdict("とても", "fix", "бредовый вариант", "очень")),
            scope = GlossaryRevisor.SCOPE_CANON,
            now = { "t" },
        )
        assertEquals(1, merged.size)
        assertEquals("canon", merged.single().scope)
        assertEquals("fix", merged.single().verdict)
    }

    @Test
    fun `разобранная запись с тем же вердиктом не дублируется`() {
        val existing = listOf(
            GlossaryRevisionDecision(id = "r1", original = "とても", verdict = "drop", status = "dismissed"),
        )
        val merged = GlossaryRevisor.mergeDecisions(
            existing,
            listOf(GlossaryRevisor.RawVerdict("とても", "drop", "опять мусор", "")),
            scope = GlossaryRevisor.SCOPE_CANON,
            now = { "t" },
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `разобранная запись с новым вердиктом добавляется отдельным решением`() {
        // Термин утвердили из очереди (fix применён), канонный прогон нашёл проблему —
        // молчание прогона проглотило бы замечание
        val existing = listOf(
            GlossaryRevisionDecision(id = "r1", original = "とても", verdict = "fix", suggested = "очень", status = "applied", scope = "queue"),
        )
        val merged = GlossaryRevisor.mergeDecisions(
            existing,
            listOf(GlossaryRevisor.RawVerdict("とても", "drop", "в каноне не нужен", "")),
            scope = GlossaryRevisor.SCOPE_CANON,
            now = { "t" },
        )
        assertEquals(2, merged.size)
        val fresh = merged.last()
        assertEquals("r2", fresh.id)
        assertEquals("canon", fresh.scope)
        assertEquals("pending", fresh.status)
        // старое не тронуто
        assertEquals("applied", merged.first().status)
    }

    @Test
    fun `keep по разобранной записи пропускается`() {
        val existing = listOf(
            GlossaryRevisionDecision(id = "r1", original = "とても", verdict = "fix", status = "applied"),
        )
        val merged = GlossaryRevisor.mergeDecisions(
            existing,
            listOf(GlossaryRevisor.RawVerdict("とても", "keep", "", "")),
            scope = GlossaryRevisor.SCOPE_CANON,
            now = { "t" },
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `зависшие pending снимаются, разобранные остаются`() {
        val decisions = listOf(
            GlossaryRevisionDecision(id = "r1", original = "живой", verdict = "drop", status = "pending"),
            GlossaryRevisionDecision(id = "r2", original = "утверждён", verdict = "fix", status = "pending"),
            GlossaryRevisionDecision(id = "r3", original = "отклонён", verdict = "drop", status = "applied"),
            GlossaryRevisionDecision(id = "r4", original = "ушёл совсем", verdict = "drop", status = "pending"),
        )
        val cleaned = GlossaryRevisor.dropStalePending(decisions, validOriginals = setOf("живой", "утверждён"))
        // «утверждён» больше не в очереди, но есть в каноне — pending живёт;
        // «отклонён» решён человеком — остаётся как история; «ушёл совсем» — снимается
        assertEquals(listOf("r1", "r2", "r3"), cleaned.map { it.id })
    }

    // ---------- выдержки ----------

    @Test
    fun `выдержка обрезается вокруг термина и не длиннее лимита`() {
        val long = "а".repeat(400) + "Удгард" + "б".repeat(400)
        val cut = GlossaryRevisor.excerpt(long, "Удгард", maxChars = 300)
        assertTrue(cut.length <= 302, "длина ${cut.length}")
        assertTrue(cut.contains("Удгард"))
        assertTrue(cut.startsWith("…") && cut.endsWith("…"))
        // короткий текст не режется
        assertEquals("короткий", GlossaryRevisor.excerpt("короткий", null))
        // без вхождения — окно с начала
        val head = GlossaryRevisor.excerpt(long, "нет такого", maxChars = 100)
        assertTrue(head.length <= 102)
        assertFalse(head.contains("Удгард"))
    }

    @Test
    fun `константы ТЗ - пакет 30 записей и выдержка 300 знаков`() {
        assertEquals(30, GlossaryRevisor.BATCH_SIZE)
        assertEquals(300, GlossaryRevisor.EXCERPT_MAX_CHARS)
    }
}
