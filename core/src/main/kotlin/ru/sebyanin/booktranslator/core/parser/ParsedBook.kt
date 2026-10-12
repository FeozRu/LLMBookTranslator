package ru.sebyanin.booktranslator.core.parser

import ru.sebyanin.booktranslator.core.storage.ChapterBlock

/** Результат детерминированного разбора EPUB/FB2 — до записи на диск. */
data class ParsedBook(
    val title: String,
    val author: String,
    val language: String,
    val chapters: List<ParsedChapter>,
    val images: List<ParsedImage>,
    val warnings: List<String> = emptyList(),
)

data class ParsedChapter(
    val title: String,
    val blocks: List<ChapterBlock>,
)

data class ParsedImage(
    val fileName: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is ParsedImage && other.fileName == fileName
    override fun hashCode(): Int = fileName.hashCode()
}
