package ru.sebyanin.booktranslator.core.pipeline

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.BookStructure
import ru.sebyanin.booktranslator.core.model.ChapterMeta
import ru.sebyanin.booktranslator.core.parser.EpubParser
import ru.sebyanin.booktranslator.core.parser.Fb2Parser
import ru.sebyanin.booktranslator.core.parser.ParsedBook
import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/** Отчёт импорта: цифры для UI и стартовые sanity-проверки (суммы знаков, блоки, якоря). */
data class ImportReport(
    val book: BookMeta,
    val chapters: Int,
    val blocks: Int,
    val chars: Int,
    val images: Int,
    val anchors: Int,
    val warnings: List<String>,
)

/**
 * Этап 0 конвейера: файл epub/fb2 → детерминированный разбор →
 * books/<slug>/{book.json, source/, images/}. Пишет отчёт в журнал.
 */
class ImportStage(private val store: ProjectStore) {

    fun importBook(projectSlug: String, sourceFile: Path, titleOverride: String = ""): ImportReport {
        val fileName = sourceFile.fileName.toString()
        val format = when (fileName.substringAfterLast('.', "").lowercase()) {
            "epub" -> "epub"
            "fb2", "zip" -> "fb2"
            else -> error("Не поддерживаемый формат: $fileName (нужен .epub или .fb2/.fb2.zip)")
        }

        AppLog.info("import", "Разбор $fileName ($format)…")
        val parsed: ParsedBook = when (format) {
            "epub" -> EpubParser().parse(sourceFile)
            else -> Fb2Parser().parse(sourceFile)
        }
        check(parsed.chapters.isNotEmpty()) { "Не удалось извлечь главы: ${parsed.warnings.joinToString("; ")}" }

        val title = titleOverride.trim().ifBlank { parsed.title }
        val slug = store.uniqueBookSlug(projectSlug, title)
        val book = BookMeta(
            slug = slug,
            title = title,
            author = parsed.author,
            sourceFileName = fileName,
            format = format,
            createdAt = LocalDate.now().toString(),
        )

        val bookDir = store.bookDir(projectSlug, slug)
        Files.createDirectories(bookDir.resolve("source"))
        Files.createDirectories(bookDir.resolve("images"))
        Files.createDirectories(bookDir.resolve("work"))
        Files.createDirectories(bookDir.resolve("translation"))
        Files.createDirectories(bookDir.resolve("build"))

        // Оригинальный файл сохраняем рядом — для пересборки и сверки
        Files.copy(sourceFile, bookDir.resolve("source").resolve("original." + format), StandardCopyOption.REPLACE_EXISTING)

        // Картинки
        parsed.images.forEach { img ->
            Files.write(bookDir.resolve("images").resolve(img.fileName), img.bytes)
        }

        // Главы
        val chapters = parsed.chapters.mapIndexed { i, ch ->
            val meta = ChapterMeta(
                index = i + 1,
                title = ch.title,
                file = "chapter-%04d.md".format(i + 1),
                blockCount = ch.blocks.size,
                charCount = BlockText.charCount(ch.blocks),
            )
            store.writeChapterSource(projectSlug, slug, meta, ch.blocks)
            meta
        }

        store.saveBookMeta(projectSlug, book)
        store.saveStructure(
            projectSlug, slug,
            BookStructure(chapters = chapters, images = parsed.images.map { it.fileName }),
        )

        val blocks = chapters.sumOf { it.blockCount }
        val chars = chapters.sumOf { it.charCount }
        val anchors = parsed.chapters.sumOf { ch ->
            ch.blocks.count { it.kind == BlockKind.IMAGE || it.kind == BlockKind.SEPARATOR || it.kind == BlockKind.COMMENT }
        }

        val warnings = buildList {
            addAll(parsed.warnings)
            if (chapters.size < parsed.chapters.size) add("Некоторые документы spine оказались пустыми")
        }
        warnings.forEach { AppLog.warn("import", it) }
        AppLog.info(
            "import",
            "Импортировано «$title»: глав ${chapters.size}, блоков $blocks, знаков $chars, картинок ${parsed.images.size}",
        )

        return ImportReport(
            book = book,
            chapters = chapters.size,
            blocks = blocks,
            chars = chars,
            images = parsed.images.size,
            anchors = anchors,
            warnings = warnings,
        )
    }
}
