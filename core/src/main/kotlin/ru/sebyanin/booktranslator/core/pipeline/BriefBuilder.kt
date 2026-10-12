package ru.sebyanin.booktranslator.core.pipeline

import ru.sebyanin.booktranslator.core.llm.PromptDefaults
import ru.sebyanin.booktranslator.core.model.GlossaryEntry
import ru.sebyanin.booktranslator.core.model.KnowledgeNote

/**
 * Сборка брифа: код подставляет словарь/заметки/конвенции/указания/исходник
 * в шаблон промпта. Бриф всегда собирается из данных на диске — проекций UI нет.
 */
object BriefBuilder {

    data class Input(
        val template: String,
        val srcBlocks: List<String>,
        val dictionary: List<GlossaryEntry> = emptyList(),
        val notes: List<KnowledgeNote> = emptyList(),
        val conventions: String = "",
        /** Указания пользователя («сделай речь суше») для этой главы/фрагмента. */
        val instructions: String = "",
        /** Для корректора: перевод. */
        val translationBlocks: List<String>? = null,
        val toolsDoc: String = "",
    )

    fun build(input: Input): String = input.template
        .replace("{{DICTIONARY}}", dictionaryText(input.dictionary))
        .replace("{{NOTES}}", notesText(input.notes))
        .replace("{{CONVENTIONS}}", input.conventions.trim())
        .replace("{{INSTRUCTIONS}}", instructionsLine(input.instructions))
        .replace("{{TOOLS_DOC}}", toolsDocLine(input.toolsDoc))
        .replace("{{SOURCE}}", input.srcBlocks.joinToString("\n"))
        .replace("{{TRANSLATION}}", input.translationBlocks?.joinToString("\n") ?: "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim() + "\n"

    fun dictionaryText(entries: List<GlossaryEntry>): String =
        if (entries.isEmpty()) "(пусто — новое передавай транскрипцией с пометкой [[имя?: <оригинал> → <вариант>]])"
        else entries.joinToString("; ") { e ->
            buildString {
                append("${e.original} → ${e.translation}")
                if (e.note.isNotBlank()) append(" (${e.note})")
            }
        }

    fun notesText(notes: List<KnowledgeNote>): String =
        if (notes.isEmpty()) "(нет)"
        else notes.joinToString("\n") { n ->
            "- ${n.entity}" +
                (if (n.situation.isNotBlank()) " [${n.situation}]" else "") +
                ": ${n.text}"
        }

    private fun instructionsLine(instructions: String): String =
        instructions.trim().takeIf { it.isNotEmpty() }?.let { "\nУКАЗАНИЕ АВТОРА (приоритетнее прочего, но якоря и 1:1 обязательно): $it\n" } ?: ""

    private fun toolsDocLine(toolsDoc: String): String =
        toolsDoc.trim().takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: ""

    /** Документация об инструментах для брифа. */
    fun defaultToolsDoc(): String = PromptDefaults.TOOLS_DOC_DEFAULT.trim()
}
