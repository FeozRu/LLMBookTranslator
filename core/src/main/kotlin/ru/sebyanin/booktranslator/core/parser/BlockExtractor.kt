package ru.sebyanin.booktranslator.core.parser

import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URLDecoder
import java.nio.file.Path

/**
 * Извлечение блоков (абзацев/заголовков/якорей) из XHTML-элементов EPUB.
 * Блок = непустая строка; всё структурное кодируется маркерами BlockText.
 */
object BlockExtractor {

    private val BLOCK_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "li")
    private val CONTAINER_TAGS = setOf("div", "section", "article", "main", "header", "body", "html", "figcaption")
    private val SKIP_TAGS = setOf("script", "style", "nav", "aside", "svg", "rt", "rp", "link", "meta")

    fun extract(body: Element, resolveImage: (String) -> String?): List<ChapterBlock> {
        val blocks = mutableListOf<ChapterBlock>()
        walk(body, blocks, resolveImage)
        return blocks.filter { it.text.isNotBlank() }
    }

    private fun walk(el: Element, out: MutableList<ChapterBlock>, resolveImage: (String) -> String?) {
        el.children().forEach { child ->
            val tag = child.tagName().lowercase()
            when {
                tag in SKIP_TAGS -> Unit

                tag == "hr" -> out.add(BlockText.separator())

                tag == "img" -> resolveImage(child.attr("src"))?.let { out.add(BlockText.imageMarker(it)) }

                tag in BLOCK_TAGS -> {
                    val text = inlineText(child, resolveImage).trim()
                    if (text.isNotEmpty()) {
                        when (tag) {
                            "h1" -> out.add(BlockText.heading(text, 1))
                            "h2" -> out.add(BlockText.heading(text, 2))
                            "h3" -> out.add(BlockText.heading(text, 3))
                            "blockquote", "pre" -> out.add(ChapterBlock(BlockKind.TEXT, text))
                            else -> out.add(ChapterBlock(BlockKind.TEXT, text))
                        }
                    }
                }

                tag in CONTAINER_TAGS -> walk(child, out, resolveImage)

                else -> {
                    // Таблицы и прочее редкое: текст как есть, отдельными блоками по ячейкам.
                    val text = inlineText(child, resolveImage).trim()
                    if (text.isNotEmpty()) out.add(ChapterBlock(BlockKind.TEXT, text))
                }
            }
        }
        // Текст, висящий прямо в контейнере без <p> (встречается в FB2→EPUB конвертациях):
        val ownText = el.childNodes().asSequence()
            .filterIsInstance<TextNode>()
            .joinToString("") { it.text() }
            .trim()
        if (ownText.isNotEmpty() && el.tagName().lowercase() in CONTAINER_TAGS) {
            out.add(ChapterBlock(BlockKind.TEXT, ownText))
        }
    }

    /** Инлайн-текст элемента: ruby-аннотации выкидываются, img → маркер, остальное склеивается. */
    fun inlineText(el: Element, resolveImage: (String) -> String?): String {
        val sb = StringBuilder()
        appendNodes(el.childNodes(), sb, resolveImage)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun appendNodes(nodes: List<Node>, sb: StringBuilder, resolveImage: (String) -> String?) {
        nodes.forEach { node ->
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        tag in SKIP_TAGS -> Unit
                        tag == "br" -> sb.append(" ")
                        tag == "img" -> resolveImage(node.attr("src"))?.let {
                            sb.append(" { [img:").append(it).append("] } ")
                        }
                        // Основание руби часто записано голым текстом между <rt> — выкидывать можно
                        // только rt/rp-ЭЛЕМЕНТЫ, текстовые узлы это само слово (конвенции z-lib/Aozora).
                        tag == "ruby" -> appendNodes(node.childNodes().filterNot { c ->
                            c is Element && c.tagName().lowercase() in setOf("rt", "rp")
                        }, sb, resolveImage)
                        else -> appendNodes(node.childNodes(), sb, resolveImage)
                    }
                }
                else -> Unit
            }
        }
    }

    /** Разрешение относительного src относительно пути главы внутри EPUB. */
    fun zipPathResolver(chapterPath: String): (String) -> String? = { src ->
        if (src.isBlank()) null
        else runCatching { normalizeZipPath(chapterPath, src) }.getOrNull()
    }

    fun normalizeZipPath(baseDirFile: String, href: String): String {
        val clean = href.substringBefore('#')
        val decoded = URLDecoder.decode(clean, Charsets.UTF_8)
        val baseDir = baseDirFile.substringBeforeLast('/', "")
        val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        val parts = mutableListOf<String>()
        combined.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    fun chapterTitle(doc: Element, fallbackIndex: Int): String {
        val heading = doc.selectFirst("h1, h2, h3, h4, h5, h6")?.text()?.trim()
        if (!heading.isNullOrEmpty()) return heading
        val title = doc.selectFirst("title")?.text()?.trim()
        if (!title.isNullOrEmpty()) return title
        return "Глава $fallbackIndex"
    }

    fun parseXml(xml: String): org.jsoup.nodes.Document =
        Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())

    fun parseHtml(html: String): org.jsoup.nodes.Document =
        Jsoup.parse(html)
}
