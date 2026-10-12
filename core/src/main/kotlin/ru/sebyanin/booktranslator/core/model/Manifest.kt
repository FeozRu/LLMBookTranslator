package ru.sebyanin.booktranslator.core.model

import kotlinx.serialization.Serializable

/**
 * Манифест работ книги — «закладка» конвейера: части, фрагменты, статусы этапов.
 * Именно по нему конвейер продолжается с любого места.
 */
@Serializable
data class WorkManifest(
    val schemaVersion: Int = 1,
    val bookSlug: String = "",
    val createdAt: String = "",
    /** part-0001 … */
    val parts: List<PartState> = emptyList(),
) {
    fun allFragments(): List<FragmentState> = parts.flatMap { it.fragments }
}

@Serializable
data class PartState(
    val id: String,
    val chapterIndex: Int,
    val chapterFile: String,
    /** Диапазон блоков главы [blockFrom; blockTo] включительно. */
    val blockFrom: Int,
    val blockTo: Int,
    val charCount: Int,
    val fragments: List<FragmentState> = emptyList(),
)

@Serializable
data class FragmentState(
    val id: String,
    val blockFrom: Int,
    val blockTo: Int,
    val charCount: Int,
    val draft: StageState = StageState(),
    val edited: StageState = StageState(),
) {
    val srcBlockCount: Int get() = blockTo - blockFrom + 1
}

@Serializable
data class StageState(
    /** pending | running | done | failed */
    val status: String = "pending",
    val file: String = "",
    val verify: String = "",
    val error: String = "",
    val attempts: Int = 0,
    val updatedAt: String = "",
) {
    val isDone: Boolean get() = status == "done"
    val isFailed: Boolean get() = status == "failed"
}

/** Тексты одного фрагмента: work/fragments/<id>.json. */
@Serializable
data class FragmentFile(
    val id: String,
    val srcBlocks: List<String> = emptyList(),
    val draftBlocks: List<String> = emptyList(),
    val editedBlocks: List<String> = emptyList(),
    val verifyDraft: String = "",
    val verifyEdited: String = "",
    val model: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val toolCalls: Int = 0,
)
