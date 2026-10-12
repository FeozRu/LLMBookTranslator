package ru.sebyanin.booktranslator.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectMeta(
    val slug: String,
    val name: String,
    /** ISO-код или название исходного языка, например "ja", "en". */
    val sourceLang: String,
    val targetLang: String = "ru",
    /** Пресет проверок/конвенций: "ja" (кана, Поливанов, суффиксы) или "generic". */
    val preset: String = "generic",
    /** Переопределение привязки roleId → имя профиля LLM (глобальные — в настройках). */
    val roleBindingsOverride: Map<String, String> = emptyMap(),
    /** Роли, которым инструменты отключены именно в этом проекте. */
    val toolsDisabledRoles: List<String> = emptyList(),
    val createdAt: String = "",
)

@Serializable
data class BookMeta(
    val slug: String,
    val title: String,
    val author: String = "",
    val sourceFileName: String = "",
    /** "epub" | "fb2". */
    val format: String = "",
    val createdAt: String = "",
)

@Serializable
data class ChapterMeta(
    val index: Int,
    val title: String,
    val file: String,
    val blockCount: Int,
    /** Знаки без пробелов — та же метрика, что в verify. */
    val charCount: Int,
)

@Serializable
data class BookStructure(
    val chapters: List<ChapterMeta> = emptyList(),
    val images: List<String> = emptyList(),
)

/** Запись глоссария: отображение «термин → перевод» + история решения. */
@Serializable
data class GlossaryEntry(
    val original: String,
    val translation: String,
    /** person | place | term | other. */
    val category: String = "term",
    /** approved | proposed | rejected. */
    val status: String = "proposed",
    val note: String = "",
    /** Контекст первого появления (глава/фрагмент). */
    val context: String = "",
    /** Пояснение предложившего: что это за слово и почему вынесено на утверждение. */
    val reason: String = "",
    /** Альтернативные варианты перевода (каждый можно утвердить отдельно). */
    val alternatives: List<String> = emptyList(),
    val rejectedVariants: List<String> = emptyList(),
    val proposedAt: String = "",
    val decidedAt: String = "",
) {
    val isApproved: Boolean get() = status == "approved"
}

@Serializable
data class GlossaryFile(
    val schemaVersion: Int = 1,
    val entries: List<GlossaryEntry> = emptyList(),
)

/** Заметка «библии перевода»: свободный текст, привязанный к сущности и ситуации. */
@Serializable
data class KnowledgeNote(
    val entity: String,
    /** Ситуация применения: «при испуге», «обращение к Соме», «стиль-гид серии»… */
    val situation: String = "",
    val text: String,
    /** approved | proposed | rejected. */
    val status: String = "proposed",
    val source: String = "",
    val proposedAt: String = "",
    val decidedAt: String = "",
)

@Serializable
data class NotesFile(
    val schemaVersion: Int = 1,
    val notes: List<KnowledgeNote> = emptyList(),
)
