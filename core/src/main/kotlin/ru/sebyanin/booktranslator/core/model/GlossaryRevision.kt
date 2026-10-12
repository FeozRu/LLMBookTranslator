package ru.sebyanin.booktranslator.core.model

import kotlinx.serialization.Serializable

/** Пара «оригинал → перевод» для вердикта split (запись упаковала несколько терминов). */
@Serializable
data class GlossaryRevisionPart(
    val original: String,
    val translation: String,
)

/**
 * Решение LLM-ревизора по одной записи очереди глоссария.
 * Никогда не применяется само: drop/fix — только по кнопке человека.
 */
@Serializable
data class GlossaryRevisionDecision(
    /** Сквозной id «r<N>» в пределах файла. */
    val id: String,
    /** Оригинал записи очереди (точное совпадение с GlossaryEntry.original). */
    val original: String,
    /** keep | drop | fix | split. */
    val verdict: String,
    /** Новый перевод — только для fix. */
    val suggested: String = "",
    /** Пары для разделения — только для split. */
    val parts: List<GlossaryRevisionPart> = emptyList(),
    val reason: String = "",
    /** pending | applied | dismissed. */
    val status: String = "pending",
    /** Прогон, из которого пришло решение: queue | canon. */
    val scope: String = "queue",
    val createdAt: String = "",
) {
    val isActionable: Boolean
        get() = status == "pending" && (verdict == "drop" || verdict == "fix" || verdict == "split")
}

/** Файл решений ревизора на уровне ПРОЕКТА: <projectDir>/glossary-revision.json. */
@Serializable
data class GlossaryRevisionFile(
    val schemaVersion: Int = 1,
    val decisions: List<GlossaryRevisionDecision> = emptyList(),
)
