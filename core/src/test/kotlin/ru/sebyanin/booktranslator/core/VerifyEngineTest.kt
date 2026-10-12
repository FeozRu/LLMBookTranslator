package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.verify.VerifyEngine

/**
 * Остаточные знаки исходника и [[…]]-пометки: сомнение переводчика [[??: …]]
 * с кана внутри — легитимная пометка (разбор за человеком), незнакомое [[…]] — нет.
 */
class VerifyEngineTest {

    private val ja = VerifyEngine("ja")

    // ~24 знака: перевод коридора длин (1.1–3.0 после редактуры) проходит сам собой
    private val src = listOf("原文の一行目には何かの説明が書かれているとする。")

    @Test
    fun `кана внутри сомнения переводчика не остаток после редактуры`() {
        val out = listOf("Женихом?! [[??: 「—にまで」— непонятно, к чему отсылает «—»]] дальше.")
        val vr = ja.verifyTranslation(src, out, VerifyEngine.editedOptions())
        assertTrue(vr.ok)
        assertTrue(vr.warnings.any { it.contains("пометки") })
    }

    @Test
    fun `кана в незнакомой скобке - остаток`() {
        val out = listOf("Текст [[что-то: かんな]] и продолжение мысли.")
        val vr = ja.verifyTranslation(src, out, VerifyEngine.editedOptions())
        assertFalse(vr.ok)
        assertTrue(vr.problems.any { it.contains("остаточные") })
    }

    @Test
    fun `кана вне пометок по-прежнему остаток`() {
        val vr = ja.verifyTranslation(src, listOf("Текст かんな без всякой пометки тут."), VerifyEngine.editedOptions())
        assertFalse(vr.ok)
        assertTrue(vr.problems.any { it.contains("остаточные") })
    }

    @Test
    fun `в черновике все пометки легальны`() {
        val out = listOf(
            "Канцлер [[имя?: 宰相 → канцлер]] и сомнение [[??: 誰だ]] — черновой текст.",
        )
        val vr = ja.verifyTranslation(src, out, VerifyEngine.draftOptions())
        assertTrue(vr.ok)
        assertTrue(vr.warnings.isEmpty())
    }

    @Test
    fun `на финале сомнение переводчика тоже не мешает residual-проверке`() {
        val out = listOf("Текст с оставленной пометкой [[??: 誰だ]] в самом финале книги.")
        val vr = ja.verifyTranslation(src, out, VerifyEngine.finalOptions())
        assertTrue(vr.ok)
        assertTrue(vr.warnings.any { it.contains("пометки") })
    }
}
