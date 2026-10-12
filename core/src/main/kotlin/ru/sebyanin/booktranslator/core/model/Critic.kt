package ru.sebyanin.booktranslator.core.model

import kotlinx.serialization.Serializable

/** Одно предложение корректора: «было → стало → почему». */
@Serializable
data class CriticSuggestion(
    val id: String,
    val chapter: Int,
    val priority: String = "желательно",
    val category: String = "прочее",
    val was: String,
    val became: String,
    /** Альтернативные «стало» (строки СТАЛО-б, СТАЛО-в): человек выбирает вариант. */
    val alternatives: List<String> = emptyList(),
    /** Правка касается ВСЕХ вхождений цитаты в главе (маркер ВЕЗДЕ: да от модели). */
    val replaceAll: Boolean = false,
    val why: String = "",
    /** pending | approved | rejected | applied */
    val status: String = "pending",
    /** Сколько раз «было» встречается в собранном переводе главы; 1 = применимо точно. */
    val grepCount: Int = -1,
    /** Флаг сомнительности (из блока СОМНЕНИЯ). */
    val doubt: Boolean = false,
    /** Происхождение: id сомнения (dN-M), если правка сделана по запросу человека. */
    val source: String = "",
)

/** Одно сомнение корректора из блока СОМНЕНИЯ: свободный текст, решение — за человеком. */
@Serializable
data class CriticDoubt(
    val id: String,
    val text: String,
    /** pending | resolved */
    val status: String = "pending",
    /** Комментарий человека при согласии («сделай речь суше») — уходит корректору при разборе. */
    val note: String = "",
)

@Serializable
data class CriticChapterFile(
    val chapter: Int,
    val suggestions: List<CriticSuggestion> = emptyList(),
    val doubts: List<CriticDoubt> = emptyList(),
    /** Старое поле (цельный текст); читается только при самолечении старых файлов. */
    val doubtsText: String = "",
    val model: String = "",
)
