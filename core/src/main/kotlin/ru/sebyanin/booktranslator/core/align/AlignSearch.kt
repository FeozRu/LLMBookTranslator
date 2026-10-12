package ru.sebyanin.booktranslator.core.align

import ru.sebyanin.booktranslator.core.storage.ProjectStore
import java.nio.file.Files

/** Конкорданс: поиск по выравниванию всех книг проекта («как это переводилось?»). */
class AlignSearch(private val store: ProjectStore) {

    fun search(
        projectSlug: String,
        bookSlug: String? = null,
        query: String,
        scope: String = "translation",
        limit: Int = 20,
    ): List<AlignHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val books = if (bookSlug != null) listOf(bookSlug) else {
            store.listBooks(projectSlug).map { it.slug }
        }
        val hits = mutableListOf<AlignHit>()
        for (slug in books) {
            val translationDir = store.bookDir(projectSlug, slug).resolve("translation")
            if (!Files.isDirectory(translationDir)) continue
            val files = Files.list(translationDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".align.json") }.sorted().toList()
            }
            for (file in files) {
                val align = runCatching {
                    ru.sebyanin.booktranslator.core.storage.AppJson.decodeFromString(
                        ChapterAlignment.serializer(),
                        Files.readString(file),
                    )
                }.getOrNull() ?: continue
                for (pair in align.pairs) {
                    val matched = when (scope) {
                        "source" -> pair.src.lowercase().contains(q)
                        else -> pair.tr.lowercase().contains(q) || pair.src.lowercase().contains(q)
                    }
                    if (matched) {
                        hits += AlignHit(slug, align.chapter, pair.block, pair.src, pair.tr)
                        if (hits.size >= limit) return hits
                    }
                }
            }
        }
        return hits
    }
}
