package ru.sebyanin.booktranslator.core.builder

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Сборка переведённой книги в EPUB 3 — детерминированный код, без LLM.
 * Примечания { [note: …] } становятся сносками в конце главы (нумерация сквозная по главе).
 */
class EpubBuilder(private val store: ProjectStore) {

    data class BuildResult(val file: Path, val chapters: Int, val notes: Int)

    fun build(
        projectSlug: String,
        bookSlug: String,
        translatorName: String = "",
    ): BuildResult {
        val meta = store.loadBook(projectSlug, bookSlug)
        val structure = store.loadStructure(projectSlug, bookSlug)
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val translationDir = bookDir.resolve("translation")
        val imagesDir = bookDir.resolve("images")
        val buildDir = bookDir.resolve("build")
        Files.createDirectories(buildDir)

        data class Chapter(val index: Int, val title: String, val blocks: List<ChapterBlock>)

        val chapters = structure.chapters.map { m ->
            val text = Files.readString(translationDir.resolve(m.file))
            Chapter(m.index, m.title, BlockText.decode(text))
        }
        check(chapters.isNotEmpty()) { "Нет собранных переводов — сначала запустите сборку глав" }

        var totalNotes = 0
        val files = linkedMapOf<String, String>() // path -> content

        files["mimetype"] = "application/epub+zip"
        files["META-INF/container.xml"] = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

        files["OEBPS/style.css"] = """
            body { font-family: serif; line-height: 1.5; margin: 1em; }
            h1, h2 { margin: 1.5em 0 0.7em; }
            p { margin: 0.5em 0; text-indent: 1.2em; }
            p.scene, hr.scene { text-align: center; text-indent: 0; margin: 1.2em 0; }
            .note-ref { vertical-align: super; font-size: 0.75em; }
            .note { font-size: 0.85em; color: #444; text-indent: 0; }
            img { max-width: 100%; }
        """.trimIndent()

        val chapterFiles = mutableListOf<String>()
        val imageItems = structure.images.map { img ->
            "  <item id=\"img-${img.substringBeforeLast('.')}\" href=\"images/$img\" media-type=\"${mimeOf(img)}\"/>"
        }

        chapters.forEach { ch ->
            val fileName = "chapter-%04d.xhtml".format(ch.index)
            chapterFiles += fileName
            val body = StringBuilder()
            val notes = mutableListOf<String>()
            ch.blocks.forEach { block ->
                when (block.kind) {
                    BlockKind.HEADING -> {
                        val level = block.text.indexOfFirst { it != '#' } + 1
                        body.append("<h").append(level.coerceIn(1, 3)).append(">")
                            .append(esc(block.text.substringAfter(' '))).append("</h").append(level.coerceIn(1, 3)).append(">\n")
                    }
                    BlockKind.SEPARATOR -> body.append("<p class=\"scene\">◇　◇　◇</p>\n")
                    BlockKind.IMAGE -> {
                        // Якорь может хранить исходный путь из EPUB («item/image/k002.jpg») —
                        // в images/ книги лежат плоские имена, ссылка и manifest должны совпадать
                        val img = BlockText.imageFileOf(block)?.substringAfterLast('/') ?: return@forEach
                        body.append("<div><img src=\"images/").append(esc(img)).append("\" alt=\"иллюстрация\"/></div>\n")
                    }
                    BlockKind.COMMENT -> Unit // шапки-комментарии в финальную книгу не идут
                    BlockKind.TEXT -> {
                        val noteMarker = Regex("\\{ \\[note:(.+?)] }")
                        var line = block.text
                        val found = noteMarker.findAll(line).toList()
                        found.forEach { m ->
                            val n = notes.size + 1
                            notes += m.groupValues[1].trim()
                            line = line.replace(m.value, noteRef(ch.index, n))
                        }
                        body.append("<p>").append(escKeepXml(line)).append("</p>\n")
                    }
                }
            }
            totalNotes += notes.size

            val notesSection = if (notes.isEmpty()) "" else buildString {
                append("\n<hr class=\"scene\"/>\n<section class=\"notes\" id=\"notes-${ch.index}\">")
                notes.forEachIndexed { i, n ->
                    append("\n<p class=\"note\" id=\"").append(noteId(ch.index, i + 1)).append("\">[")
                    append(i + 1).append("] ").append(esc(n)).append("</p>")
                }
                append("\n</section>")
            }

            files["OEBPS/$fileName"] = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="ru" lang="ru">
<head><title>${esc(ch.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
<h1>${esc(ch.title)}</h1>
$body$notesSection
</body>
</html>"""
        }

        // Примечания вынесены в главы; nav + opf:
        files["OEBPS/nav.xhtml"] = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="ru">
<head><title>Оглавление</title></head>
<body>
<nav epub:type="toc" id="toc"><h1>Оглавление</h1><ol>
${chapterFiles.joinToString("\n") { f ->
            val ch = chapters[chapterFiles.indexOf(f)]
            "<li><a href=\"$f\">${esc(ch.title)}</a></li>"
        }}
</ol></nav>
</body>
</html>"""

        val uuid = "urn:uuid:book-translator-${bookSlug}-${System.currentTimeMillis()}"
        val translatorMeta = if (translatorName.isNotBlank()) {
            "    <dc:creator id=\"translator\">" + esc(translatorName) + "</dc:creator>\n" +
                "    <meta refines=\"#translator\" property=\"role\" scheme=\"marc:relators\">trl</meta>"
        } else ""
        files["OEBPS/content.opf"] = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">$uuid</dc:identifier>
    <dc:title>${esc(meta.title)}</dc:title>
    <dc:creator>${esc(meta.author)}</dc:creator>
    <dc:language>ru</dc:language>
$translatorMeta
    <meta property="dcterms:modified">${java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))}</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="css" href="style.css" media-type="text/css"/>
${chapterFiles.joinToString("\n") { "    <item id=\"" + it.removeSuffix(".xhtml") + "\" href=\"$it\" media-type=\"application/xhtml+xml\"/>" }}
${imageItems.joinToString("\n")}
  </manifest>
  <spine>
    <itemref idref="nav"/>
${chapterFiles.joinToString("\n") { "    <itemref idref=\"" + it.removeSuffix(".xhtml") + "\"/>" }}
  </spine>
</package>"""

        val outFile = buildDir.resolve("${bookSlug}-ru.epub")
        ZipOutputStream(Files.newOutputStream(outFile)).use { zip ->
            // mimetype — ПЕРВОЙ записью, STORED, с size/CRC (требование EPUB; без CRC —
            // «STORED entry missing size» и невалидная книга)
            val mime = files.remove("mimetype")!!.toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = java.util.zip.CRC32().apply { update(mime) }.value
            }
            zip.putNextEntry(mimeEntry)
            zip.write(mime)
            zip.closeEntry()
            zip.setLevel(9)

            files.entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            structure.images.forEach { img ->
                val imgFile = imagesDir.resolve(img)
                if (Files.isRegularFile(imgFile)) {
                    zip.putNextEntry(ZipEntry("OEBPS/images/$img"))
                    Files.copy(imgFile, zip as OutputStream)
                    zip.closeEntry()
                }
            }
        }
        AppLog.info("build", "EPUB собран: ${outFile.fileName} (глав ${chapters.size}, примечаний $totalNotes)")
        return BuildResult(outFile, chapters.size, totalNotes)
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private val NOTE_REF_TAG = Regex("<sup class=\"note-ref\">.*?</sup>")

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Экранируем всё, кроме уже вставленных inline-тегов сносок. */
    private fun escKeepXml(s: String): String {
        val markers = NOTE_REF_TAG.findAll(s).map { it.value }.toList()
        var idx = 0
        var out = esc(NOTE_REF_TAG.replace(s) { "\u0001${idx++}\u0001" })
        markers.forEachIndexed { i, m -> out = out.replace("\u0001$i\u0001", m) }
        return out
    }

    companion object {
        /** id примечания в файле главы; секция примечаний лежит в конце ТОГО ЖЕ файла — href без имени файла. */
        fun noteId(chapterIndex: Int, n: Int): String = "n$chapterIndex-$n"

        fun noteRef(chapterIndex: Int, n: Int): String =
            "<sup class=\"note-ref\"><a href=\"#${noteId(chapterIndex, n)}\" id=\"ref-$chapterIndex-$n\">[$n]</a></sup>"
    }
}

/** Сборка FB2 2.0 (контейнер XML, картинки base64, примечания — body name="notes"). */
class Fb2Builder(private val store: ProjectStore) {

    fun build(projectSlug: String, bookSlug: String, translatorName: String = ""): Path {
        val meta = store.loadBook(projectSlug, bookSlug)
        val structure = store.loadStructure(projectSlug, bookSlug)
        val bookDir = store.bookDir(projectSlug, bookSlug)
        val translationDir = bookDir.resolve("translation")
        val imagesDir = bookDir.resolve("images")
        val buildDir = bookDir.resolve("build")
        Files.createDirectories(buildDir)

        var noteCounter = 0
        val noteBodies = StringBuilder()

        val sections = structure.chapters.joinToString("\n") { m ->
            val blocks = BlockText.decode(Files.readString(translationDir.resolve(m.file)))
            val body = StringBuilder()
            blocks.forEach { b ->
                when (b.kind) {
                    BlockKind.HEADING -> Unit // заголовок секции уже в <title>
                    BlockKind.SEPARATOR -> body.append("<empty-line/>\n")
                    BlockKind.COMMENT -> Unit
                    BlockKind.IMAGE -> BlockText.imageFileOf(b)?.let { raw ->
                        val img = raw.substringAfterLast('/')
                        body.append("<image l:href=\"#img-").append(img.substringBeforeLast('.')).append("\"/>")
                    }
                    BlockKind.TEXT -> {
                        var line = b.text
                        val noteMarker = Regex("\\{ \\[note:(.+?)] }")
                        noteMarker.findAll(line).forEach { match ->
                            noteCounter++
                            val id = noteId(noteCounter)
                            noteBodies.append(
                                "<section id=\"$id\"><title><p>[$noteCounter]</p></title><p>${xml(match.groupValues[1].trim())}</p></section>\n",
                            )
                            line = line.replace(match.value, noteRef(noteCounter))
                        }
                        body.append("<p>").append(xmlKeep(line)).append("</p>\n")
                    }
                }
            }
            """
<section>
<title><p>${xml(m.title)}</p></title>
$body</section>"""
        }
        check(structure.chapters.isNotEmpty()) { "Нет собранных переводов" }

        val binaries = structure.images.joinToString("\n") { img ->
            val f = imagesDir.resolve(img)
            if (!Files.isRegularFile(f)) return@joinToString ""
            val bytes = Base64.getEncoder().encodeToString(Files.readAllBytes(f))
            "<binary id=\"img-${img.substringBeforeLast('.')}\" content-type=\"${mimeOf(img)}\">$bytes</binary>"
        }

        val fb2 = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description>
  <title-info>
    <genre>sf_fantasy</genre>
    <author><first-name>${xml(meta.author)}</first-name></author>
    <book-title>${xml(meta.title)}</book-title>
    <lang>ru</lang>${if (translatorName.isNotBlank()) "<translator>${xml(translatorName)}</translator>" else ""}
  </title-info>
  <document-info>
    <program-name>BookTranslator</program-name>
  </document-info>
</description>
<body>
$sections
</body>
${if (noteCounter > 0) "<body name=\"notes\">\n$noteBodies</body>" else ""}
$binaries
</FictionBook>"""

        val outFile = buildDir.resolve("${bookSlug}-ru.fb2")
        Files.writeString(outFile, fb2)
        AppLog.info("build", "FB2 собран: ${outFile.fileName} (глав ${structure.chapters.size}, примечаний $noteCounter)")
        return outFile
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private val NOTE_LINK_TAG = Regex("<a l:href=\"#n\\d+\" type=\"note\">\\[\\d+]</a>")

    private fun xml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Экранируем всё, кроме наших inline-ссылок на сноски. */
    private fun xmlKeep(s: String): String {
        val markers = NOTE_LINK_TAG.findAll(s).map { it.value }.toList()
        var idx = 0
        var out = xml(NOTE_LINK_TAG.replace(s) { "\u0001${idx++}\u0001" })
        markers.forEachIndexed { i, m -> out = out.replace("\u0001$i\u0001", m) }
        return out
    }

    companion object {
        /** id секции примечания в body name="notes"; ссылка в тексте ведёт на тот же id. */
        fun noteId(n: Int): String = "n$n"

        fun noteRef(n: Int): String = "<a l:href=\"#${noteId(n)}\" type=\"note\">[$n]</a>"
    }
}
