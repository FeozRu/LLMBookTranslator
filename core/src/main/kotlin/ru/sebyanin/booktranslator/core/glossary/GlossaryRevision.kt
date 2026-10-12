package ru.sebyanin.booktranslator.core.glossary

import ru.sebyanin.booktranslator.core.model.GlossaryEntry

/**
 * Ревизия очереди глоссария: составное имя (части через «・») избыточно, если
 * каждая его часть уже предложена или утверждена отдельной записью — перевод
 * составного термина собирается из частей (запрос пользователя: «двух последних
 * достаточно»). Отклонённые части покрытием не считаются: человек уже отверг
 * их вариант, составное имя остаётся.
 */
object GlossaryRevision {

    /** Разделители составных имён: ・ (U+30FB) и полуширинный ･ (U+FF65). */
    private val SEPARATORS = charArrayOf('・', '･')

    data class Redundant(
        val composite: GlossaryEntry,
        /** Записи-части, покрывающие состав, в порядке частей оригинала. */
        val parts: List<GlossaryEntry>,
        /** Перевод, собранный из частей. */
        val composed: String,
        /** Совпадает ли перевод составной записи со склейкой частей. */
        val consistent: Boolean,
    )

    /** Части составного имени или null, если оригинал не составной. */
    fun splitParts(original: String): List<String>? {
        if (SEPARATORS.none { original.indexOf(it) >= 0 }) return null
        val parts = original.split(*SEPARATORS).map { it.trim() }
        return if (parts.size >= 2 && parts.all { it.isNotBlank() }) parts else null
    }

    /** Предложенные составные имена, полностью покрытые своими частями. */
    fun findRedundant(entries: List<GlossaryEntry>): List<Redundant> {
        val active = entries.filter { it.status == "proposed" || it.status == "approved" }
        val byOriginal = active.associateBy { it.original }
        return active.filter { it.status == "proposed" }.mapNotNull { composite ->
            val parts = splitParts(composite.original) ?: return@mapNotNull null
            val covered = parts.map { byOriginal[it] ?: return@mapNotNull null }
            val composed = covered.joinToString(" ") { it.translation.trim() }
            Redundant(
                composite = composite,
                parts = covered,
                composed = composed,
                consistent = composed.equals(composite.translation.trim(), ignoreCase = true),
            )
        }
    }

    /**
     * То же для канона: утверждённые составные имена, все части которых тоже
     * утверждены. Покрывают только утверждённые части — предлагаемое убирание
     * правит канон, поэтому части обязаны уже иметь канонический перевод.
     */
    fun findRedundantApproved(entries: List<GlossaryEntry>): List<Redundant> {
        val approved = entries.filter { it.status == "approved" }
        val byOriginal = approved.associateBy { it.original }
        return approved.mapNotNull { composite ->
            val parts = splitParts(composite.original) ?: return@mapNotNull null
            val covered = parts.map { byOriginal[it] ?: return@mapNotNull null }
            val composed = covered.joinToString(" ") { it.translation.trim() }
            Redundant(
                composite = composite,
                parts = covered,
                composed = composed,
                consistent = composed.equals(composite.translation.trim(), ignoreCase = true),
            )
        }
    }

    /**
     * «Упакованная» запись: в одном маркере/предложении несколько РАЗНЫХ терминов —
     * перевод содержит ещё пары «оригинал → перевод» (пример из прогона:
     * カリラ → «Карла; カリл → Карл»). Делается механикой: «→» в переводе —
     * надёжный признак, LLM не нужен.
     */
    data class Packed(
        val entry: GlossaryEntry,
        /** Разобранные пары «оригинал → перевод», включая первую. */
        val parts: List<Pair<String, String>>,
    )

    /** Разбор упакованного перевода. Сегменты без «→» — продолжение перевода предыдущей пары. */
    fun splitPacked(original: String, translation: String): List<Pair<String, String>> {
        val t = translation.trim()
        if (!t.contains('→')) return listOf(original.trim() to t)
        val pairs = mutableListOf<Pair<String, String>>()
        var currentOriginal = original.trim()
        var currentTr = mutableListOf<String>()
        fun flush() {
            if (currentTr.isNotEmpty()) pairs += currentOriginal to currentTr.joinToString("; ")
            currentTr = mutableListOf()
        }
        t.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { seg ->
            val arrow = seg.indexOf('→')
            if (arrow > 0) {
                flush()
                currentOriginal = seg.take(arrow).trim()
                currentTr += seg.substring(arrow + 1).trim()
            } else {
                currentTr += seg
            }
        }
        flush()
        return pairs.filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .ifEmpty { listOf(original.trim() to t) }
    }

    /** Упакованные записи (и в очереди, и в каноне; статус фильтрует вызывающий). */
    fun findPacked(entries: List<GlossaryEntry>): List<Packed> =
        entries.mapNotNull { e ->
            val parts = splitPacked(e.original, e.translation)
            if (parts.size >= 2) Packed(e, parts) else null
        }
}
