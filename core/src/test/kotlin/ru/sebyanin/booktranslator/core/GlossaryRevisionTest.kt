package ru.sebyanin.booktranslator.core

import ru.sebyanin.booktranslator.core.glossary.GlossaryRevision
import ru.sebyanin.booktranslator.core.model.GlossaryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ревизия глоссария: составное имя избыточно, когда все его части уже в словаре. */
class GlossaryRevisionTest {

    private fun entry(original: String, translation: String, status: String = "proposed") =
        GlossaryEntry(original = original, translation = translation, status = status)

    @Test
    fun `составное имя покрыто предложенными частями — избыточно`() {
        val redundant = GlossaryRevision.findRedundant(
            listOf(
                entry("トモエ・イヌイ", "Томоэ Инуи"),
                entry("トモエ", "Томоэ"),
                entry("イヌイ", "Инуи"),
            ),
        )
        assertEquals(1, redundant.size)
        assertEquals("トモエ・イヌイ", redundant[0].composite.original)
        assertEquals(listOf("トモエ", "イヌイ"), redundant[0].parts.map { it.original })
        assertEquals("Томоэ Инуи", redundant[0].composed)
        assertTrue(redundant[0].consistent)
    }

    @Test
    fun `без одной из частей составное имя остаётся`() {
        val redundant = GlossaryRevision.findRedundant(
            listOf(
                entry("ハクヤ・クオンミン", "Хакуя Квонмин"),
                entry("ハクヤ", "Хакуя"),
            ),
        )
        assertTrue(redundant.isEmpty())
    }

    @Test
    fun `утверждённые части тоже покрывают составное имя`() {
        val redundant = GlossaryRevision.findRedundant(
            listOf(
                entry("ジュナ・ドーマ", "Джуна Дома"),
                entry("ジュナ", "Джуна", status = "approved"),
                entry("ドーマ", "Дома"),
            ),
        )
        assertEquals(1, redundant.size)
    }

    @Test
    fun `отклонённая часть не считается покрытием`() {
        val redundant = GlossaryRevision.findRedundant(
            listOf(
                entry("アイーシャ・ウドガルド", "Айша Удогардо"),
                entry("アイーシャ", "Айша"),
                entry("ウドガルド", "Удгард", status = "rejected"),
            ),
        )
        assertTrue(redundant.isEmpty())
    }

    @Test
    fun `расхождение перевода с частями помечается`() {
        val redundant = GlossaryRevision.findRedundant(
            listOf(
                entry("アイーシャ・ウドガルド", "Айша Удогардо"),
                entry("アイーシャ", "Айша"),
                entry("ウドガルド", "Удгард", status = "approved"),
            ),
        )
        assertEquals(1, redundant.size)
        assertFalse(redundant[0].consistent)
        assertEquals("Айша Удгард", redundant[0].composed)
    }

    @Test
    fun `утверждённое составное имя не убирается и одиночные имена не трогаем`() {
        val redundant = GlossaryRevision.findRedundant(
            listOf(
                entry("トモエ・イヌイ", "Томоэ Инуи", status = "approved"),
                entry("トモエ", "Томоэ"),
                entry("イヌイ", "Инуи"),
                entry("ローレライ", "Лорелей"),
            ),
        )
        assertTrue(redundant.isEmpty())
    }

    @Test
    fun `в каноне составное имя с утверждёнными частями — избыточно`() {
        val canon = GlossaryRevision.findRedundantApproved(
            listOf(
                entry("トモエ・イヌイ", "Томоэ Инуи", status = "approved"),
                entry("トモエ", "Томоэ", status = "approved"),
                entry("イヌイ", "Инуи", status = "approved"),
            ),
        )
        assertEquals(1, canon.size)
        assertEquals("トモエ・イヌイ", canon[0].composite.original)
        assertTrue(canon[0].consistent)
    }

    @Test
    fun `в каноне непокрытое составное имя и предложенный состав не трогаются`() {
        val canon = GlossaryRevision.findRedundantApproved(
            listOf(
                entry("ジュナ・ドーマ", "Джуна Дома", status = "approved"),
                entry("ジュナ", "Джуна", status = "approved"),
                entry("ドーマ", "Дома", status = "proposed"),
                entry("トモエ・イヌイ", "Томоэ Инуи"),
                entry("トモエ", "Томоэ", status = "approved"),
                entry("イヌイ", "Инуи", status = "approved"),
            ),
        )
        assertTrue(canon.isEmpty())
    }

    @Test
    fun `splitParts работает только для настоящих составов`() {
        assertNotNull(GlossaryRevision.splitParts("ハクヤ・クオンミン"))
        assertNotNull(GlossaryRevision.splitParts("ポンチョ･イシヅカ"))
        assertNull(GlossaryRevision.splitParts("ローレライ"))
        assertNull(GlossaryRevision.splitParts("・"))
        assertNull(GlossaryRevision.splitParts("トモエ・"))
    }

    // ---------- упакованные записи (несколько терминов в одном предложении) ----------

    @Test
    fun `упакованный перевод разбирается на пары`() {
        val parts = GlossaryRevision.splitPacked("カリラ", "Карла; カリル → Карл")
        assertEquals(listOf("カリラ" to "Карла", "カリル" to "Карл"), parts)
    }

    @Test
    fun `три термина в одном маркере - три пары`() {
        val parts = GlossaryRevision.splitPacked("カリラ", "Карла; カリル → Карл; カリラ子 → Карла-младшая")
        assertEquals(3, parts.size)
        assertEquals("Карла-младшая", parts[2].second)
    }

    @Test
    fun `варианты без стрелки не делятся - это один термин`() {
        val parts = GlossaryRevision.splitPacked("カリラ", "Карла; Карлуша")
        assertEquals(listOf("カリラ" to "Карла; Карлуша"), parts)
    }

    @Test
    fun `обычный перевод не меняется`() {
        assertEquals(listOf("ソーマ" to "Сома"), GlossaryRevision.splitPacked("ソーマ", "Сома"))
    }

    @Test
    fun `findPacked находит упакованные в очереди и каноне`() {
        val packed = GlossaryRevision.findPacked(
            listOf(
                entry("カリラ", "Карла; カリル → Карл"),
                entry("ソーマ王", "Сома-король; ソーマ → Сома", status = "approved"),
                entry("ローレライ", "Лорелей"),
                entry("битая", "хвост →", status = "rejected"),
            ),
        )
        assertEquals(2, packed.size)
        assertEquals("カリラ", packed[0].entry.original)
        assertEquals(2, packed[0].parts.size)
        assertEquals("ソーマ王", packed[1].entry.original)
    }
}
