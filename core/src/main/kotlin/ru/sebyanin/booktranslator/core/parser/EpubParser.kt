package ru.sebyanin.booktranslator.core.parser

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Разбор EPUB (2/3): container.xml → OPF → spine → XHTML-главы → блоки.
 * LLM в разборе не участвует; картинки вынимаются в ParsedImage.
 */
class EpubParser {

    fun parse(file: Path): ParsedBook {
        ZipFile(file.toFile()).use { zip ->
            val opfPath = locateOpf(zip)
            val opf = BlockExtractor.parseXml(readZip(zip, opfPath))

            val title = (opf.selectFirst("dc|title") ?: opf.selectFirst("title"))?.text()?.trim().orEmpty()
            val author = (opf.selectFirst("dc|creator") ?: opf.selectFirst("creator"))?.text()?.trim().orEmpty()
            val language = (opf.selectFirst("dc|language") ?: opf.selectFirst("language"))?.text()?.trim().orEmpty()

            // manifest: id → (zipPath, media-type)
            val manifest: Map<String, Pair<String, String>> =
                opf.select("manifest > item, item").associate { item ->
                    val id = item.attr("id")
                    val href = item.attr("href")
                    val media = item.attr("media-type").ifBlank {
                        when (href.substringAfterLast('.', "").lowercase()) {
                            "xhtml", "html", "htm" -> "application/xhtml+xml"
                            "ncx" -> "application/x-dtbncx+xml"
                            "css" -> "text/css"
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "gif" -> "image/gif"
                            "svg" -> "image/svg+xml"
                            else -> ""
                        }
                    }
                    id to (BlockExtractor.normalizeZipPath(opfPath, href) to media)
                }

            // spine: порядок чтения
            val spineIds = opf.select("spine > itemref, itemref").map { it.attr("idref") }

            val warnings = mutableListOf<String>()
            val chapters = mutableListOf<ParsedChapter>()
            val neededImages = linkedSetOf<String>()
            var chapterNo = 0

            for (idref in spineIds) {
                val (docPath, media) = manifest[idref] ?: continue
                if (media != "application/xhtml+xml" && media != "text/html") continue
                val name = docPath.substringAfterLast('/')
                if (name.equals("nav.xhtml", true) || name.equals("toc.xhtml", true)) continue
                val html = runCatching { readZip(zip, docPath) }
                    .getOrElse {
                        warnings += "Не читается документ spine: $docPath (${it.message})"
                        continue
                    }
                val doc = BlockExtractor.parseHtml(html)
                val resolver = BlockExtractor.zipPathResolver(docPath)
                val imageResolver: (String) -> String? = { src ->
                    resolver(src)?.also { neededImages.add(it) }
                }
                val blocks = BlockExtractor.extract(doc.body(), imageResolver)
                if (blocks.isEmpty()) continue
                chapterNo++
                chapters += ParsedChapter(BlockExtractor.chapterTitle(doc, chapterNo), blocks)
            }

            if (chapters.isEmpty()) warnings += "В spine не найдено ни одной главы с текстом"

            val images = neededImages.mapNotNull { path ->
                val bytes = readZipBytes(zip, path) ?: run {
                    warnings += "Картинка не найдена в архиве: $path"
                    return@mapNotNull null
                }
                ParsedImage(path.substringAfterLast('/'), bytes)
            }

            return ParsedBook(
                title = title.ifBlank { file.fileName.toString().substringBeforeLast('.') },
                author = author,
                language = language,
                chapters = chapters,
                images = images,
                warnings = warnings,
            )
        }
    }

    private fun locateOpf(zip: ZipFile): String {
        val container = readZip(zip, "META-INF/container.xml")
        val doc = BlockExtractor.parseXml(container)
        return doc.selectFirst("rootfile")?.attr("full-path")
            ?: error("В container.xml не найден rootfile (повреждённый EPUB?)")
    }

    private fun readZip(zip: ZipFile, path: String): String {
        val entry = findEntry(zip, path) ?: throw IllegalArgumentException("Нет файла в EPUB: $path")
        return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun readZipBytes(zip: ZipFile, path: String): ByteArray? {
        val entry = findEntry(zip, path) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun findEntry(zip: ZipFile, path: String) =
        zip.getEntry(path) ?: zip.entries().asSequence().firstOrNull {
            it.name.equals(path, ignoreCase = true) || it.name.endsWith("/$path", ignoreCase = true)
        }
}
