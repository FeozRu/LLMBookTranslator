package ru.sebyanin.booktranslator.core.parser

import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipFile

/**
 * Разбор FB2 2.0 (включая fb2.zip): description/title-info, main body → section'ы → блоки.
 * Сносочные body (name="notes") в v1 пропускаются с предупреждением.
 */
class Fb2Parser {

    fun parse(file: Path): ParsedBook {
        val xml = readFb2Xml(file)
        val doc = BlockExtractor.parseXml(xml)

        val titleInfo = doc.selectFirst("description > title-info, title-info")
        val title = titleInfo?.selectFirst("book-title")?.text()?.trim().orEmpty()
        val author = titleInfo?.selectFirst("author")?.let { a ->
            listOfNotNull(
                a.selectFirst("first-name")?.text()?.trim(),
                a.selectFirst("last-name")?.text()?.trim(),
            ).joinToString(" ").trim()
        }.orEmpty()
        val language = titleInfo?.selectFirst("lang")?.text()?.trim().orEmpty()

        val warnings = mutableListOf<String>()

        val binaries = doc.select("binary").associate { bin ->
            val id = bin.attr("id").removePrefix("#")
            val contentType = bin.attr("content-type")
            val bytes = runCatching { Base64.getDecoder().decode(bin.text().replace(Regex("\\s"), "")) }
                .getOrElse {
                    warnings += "Не декодируется картинка id=$id"
                    ByteArray(0)
                }
            val ext = when {
                contentType.contains("jpeg", true) -> "jpg"
                contentType.contains("png", true) -> "png"
                contentType.contains("gif", true) -> "gif"
                else -> "img"
            }
            id to ParsedImage("$id.$ext", bytes)
        }

        // Основной body — без атрибута name
        val mainBody = doc.select("body").firstOrNull { it.attr("name").isBlank() }
            ?: return ParsedBook(
                title = title.ifBlank { file.fileName.toString().substringBeforeLast('.') },
                author = author,
                language = language,
                chapters = emptyList(),
                images = emptyList(),
                warnings = warnings + "В FB2 не найден основной body",
            )

        doc.select("body").filter { it.attr("name").isNotBlank() }.forEach {
            warnings += "Пропущено тело «${it.attr("name")}» (сноски — не в этой версии)"
        }

        val neededImages = linkedSetOf<String>()
        val imageResolver: (String) -> String? = { href ->
            val id = href.removePrefix("#").substringBefore('#')
            binaries[id]?.let {
                neededImages.add(id)
                it.fileName
            }
        }

        val sections = mainBody.children().filter { it.tagName().lowercase() == "section" }
        val chapters = mutableListOf<ParsedChapter>()
        var chapterNo = 0

        if (sections.isNotEmpty()) {
            sections.forEach { section ->
                val blocks = extractSection(section, imageResolver)
                if (blocks.isEmpty()) return@forEach
                chapterNo++
                val sectionTitle = section.selectFirst("> title, title")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "Глава $chapterNo"
                chapters += ParsedChapter(sectionTitle, blocks)
            }
        } else {
            // Плоский body без секций — вся книга одной главой (редко, но бывает)
            val blocks = extractSection(mainBody, imageResolver)
            if (blocks.isNotEmpty()) chapters += ParsedChapter(title.ifBlank { "Текст" }, blocks)
        }

        if (chapters.isEmpty()) warnings += "Не удалось извлечь текст из основного body"

        return ParsedBook(
            title = title.ifBlank { file.fileName.toString().substringBeforeLast('.') },
            author = author,
            language = language,
            chapters = chapters,
            images = neededImages.mapNotNull { id -> binaries[id]?.takeIf { it.bytes.isNotEmpty() } },
            warnings = warnings,
        )
    }

    private fun extractSection(section: org.jsoup.nodes.Element, imageResolver: (String) -> String?): List<ChapterBlock> {
        val out = mutableListOf<ChapterBlock>()
        fun walk(el: org.jsoup.nodes.Element) {
            el.children().forEach { child ->
                when (child.tagName().lowercase()) {
                    "title" -> {
                        val text = child.text().trim()
                        if (text.isNotEmpty()) out.add(BlockText.heading(text, 2))
                    }
                    "p" -> {
                        val text = BlockExtractor.inlineText(child, imageResolver)
                        if (text.isNotEmpty()) out.add(ChapterBlock(BlockKind.TEXT, text))
                    }
                    "subtitle" -> {
                        val text = BlockExtractor.inlineText(child, imageResolver)
                        if (text.isNotEmpty()) out.add(ChapterBlock(BlockKind.TEXT, text))
                    }
                    "empty-line" -> out.add(BlockText.separator())
                    "image" -> {
                        val href = child.attr("l:href").ifBlank { child.attr("href") }
                        imageResolver(href)?.let { out.add(BlockText.imageMarker(it)) }
                    }
                    "epigraph", "cite", "stanza", "poem", "v", "text-author" -> walk(child)
                    "section" -> walk(child) // вложенные секции не выделяем отдельной главой
                    else -> Unit
                }
            }
        }
        walk(section)
        return out
    }

    private fun readFb2Xml(file: Path): String {
        val name = file.fileName.toString().lowercase()
        if (name.endsWith(".zip") || name.endsWith(".fb2.zip")) {
            ZipFile(file.toFile()).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".fb2") }
                    ?: error("В zip не найден .fb2: ${file.fileName}")
                return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
        return Files.readString(file, Charsets.UTF_8)
    }
}
