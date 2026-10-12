package ru.sebyanin.booktranslator.core.align

import kotlinx.serialization.Serializable

/** Выравнивание одной пары «абзац оригинала ↔ абзац перевода». */
@Serializable
data class AlignPair(
    /** 0-based индекс блока в главе. */
    val block: Int,
    val src: String,
    val tr: String,
    /** Предложенное разбиение оригинала. */
    val srcSentences: List<String> = emptyList(),
    /** Предложенное разбиение перевода. */
    val trSentences: List<String> = emptyList(),
    /** Карта srcIdx → trIdx (один src может идти в несколько tr). */
    val sentenceMap: List<List<Int>> = emptyList(),
    /** exact — числа предложений совпали; approx — расставлено по порядку эвристикой. */
    val confidence: String = "exact",
)

/** Выравнивание главы: translation/chapter-NNNN.align.json */
@Serializable
data class ChapterAlignment(
    val chapter: Int,
    val version: String = "edited",
    val pairs: List<AlignPair> = emptyList(),
)

/** Результат конкорданс-поиска. */
data class AlignHit(
    val bookSlug: String,
    val chapter: Int,
    val block: Int,
    val src: String,
    val tr: String,
)

/** Деление абзаца на предложения (по знакам конца с учётом …？！?!.). */
object SentenceSplitter {

    fun split(text: String, cjk: Boolean): List<String> {
        val ends = if (cjk) charArrayOf('。', '！', '？', '…') else charArrayOf('.', '!', '?', '…')
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            sb.append(ch)
            if (ch in ends) {
                // забираем повторяющие знаки (?!…)
                while (i + 1 < text.length && text[i + 1] in ends) {
                    i++
                    sb.append(text[i])
                }
                // закрывающие кавычки/скобки прилипают к предложению
                while (i + 1 < text.length && text[i + 1] in "»\")」』") {
                    i++
                    sb.append(text[i])
                }
                val s = sb.toString().trim()
                if (s.isNotEmpty()) out += s
                sb.clear()
            }
            i++
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }
}
