package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.parser.EpubParser
import ru.sebyanin.booktranslator.core.parser.Fb2Parser
import ru.sebyanin.booktranslator.core.storage.BlockKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ParsersTest {

    @Test
    fun `epub parser on synthetic book`() {
        val dir = Files.createTempDirectory("epub-test")
        val epub = dir.resolve("book.epub")
        ZipOutputStream(Files.newOutputStream(epub)).use { zip ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val entry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = java.util.zip.CRC32().apply { update(mime) }.value
            }
            zip.putNextEntry(entry)
            zip.write(mime)
            zip.closeEntry()

            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<container><rootfile full-path="OEBPS/content.opf"/></container>""",
            )
            put(
                "OEBPS/content.opf",
                """<package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  |<metadata><dc:title>Тест</dc:title><dc:creator>Автор</dc:creator><dc:language>ja</dc:language></metadata>
                  |<manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  |<item id="img" href="pic.png" media-type="image/png"/></manifest>
                  |<spine><itemref idref="c1"/></spine></package>""".trimMargin(),
            )
            put("OEBPS/pic.png", "")
            put(
                "OEBPS/ch1.xhtml",
                """<html><body>
                  |<h1>Глава</h1>
                  |<p>Первый <img src="pic.png"/> абзац.</p>
                  |<hr/>
                  |<p>Второй абзац.</p>
                  |</body></html>""".trimMargin(),
            )
        }

        val book = EpubParser().parse(epub)
        assertEquals("Тест", book.title)
        assertEquals(1, book.chapters.size)
        val blocks = book.chapters[0].blocks
        assertEquals(BlockKind.HEADING, blocks[0].kind)
        assertEquals("Глава", blocks[0].text.removePrefix("# "))
        // img внутри абзаца → инлайн-маркер в тексте (путь внутри zip)
        assertTrue(blocks[1].text.contains("{ [img:OEBPS/pic.png] }"), "текст: ${blocks[1].text}")
        assertEquals(BlockKind.SEPARATOR, blocks[2].kind)
        assertEquals("Второй абзац.", blocks[3].text)
        assertEquals(1, book.images.size)
        assertEquals("pic.png", book.images[0].fileName)
    }

    @Test
    fun `fb2 parser on synthetic book`() {
        val dir = Files.createTempDirectory("fb2-test")
        val fb2 = dir.resolve("book.fb2")
        Files.writeString(
            fb2,
            """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info>
              <book-title>Тест FB2</book-title>
              <author><first-name>Иван</first-name><last-name>Автор</last-name></author>
              <lang>ru</lang>
            </title-info></description>
            <body>
              <section>
                <title><p>Глава 1</p></title>
                <p>Первый абзац.</p>
                <empty-line/>
                <p>Второй <emphasis>абзац</emphasis>.</p>
              </section>
              <section>
                <title><p>Глава 2</p></title>
                <p>Третий абзац.</p>
              </section>
            </body>
            </FictionBook>
            """.trimIndent(),
        )

        val book = Fb2Parser().parse(fb2)
        assertEquals("Тест FB2", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals("Глава 1", book.chapters[0].title)
        val blocks = book.chapters[0].blocks
        assertEquals(BlockKind.HEADING, blocks[0].kind)
        assertEquals("Первый абзац.", blocks[1].text)
        assertEquals(BlockKind.SEPARATOR, blocks[2].kind)
        assertEquals("Второй абзац.", blocks[3].text)
    }

    /** Регрессия: основание руби, записанное голым текстом между <rt>, не должно исчезать. */
    @Test
    fun `epub parser keeps ruby base text`() {
        val dir = Files.createTempDirectory("epub-ruby-test")
        val epub = dir.resolve("book.epub")
        ZipOutputStream(Files.newOutputStream(epub)).use { zip ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val entry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = java.util.zip.CRC32().apply { update(mime) }.value
            }
            zip.putNextEntry(entry)
            zip.write(mime)
            zip.closeEntry()
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("META-INF/container.xml", """<container><rootfile full-path="OEBPS/content.opf"/></container>""")
            put(
                "OEBPS/content.opf",
                """<package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  |<metadata><dc:title>Руби</dc:title><dc:creator>Автор</dc:creator><dc:language>ja</dc:language></metadata>
                  |<manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  |<spine><itemref idref="c1"/></spine></package>""".trimMargin(),
            )
            // Форма z-lib: основание — текстовые узлы внутри <ruby>; форма Aozora: явный <rb>.
            put(
                "OEBPS/ch1.xhtml",
                """<html><body>
                  |<p>その隣に<ruby>佇<rt>たたず</rt></ruby>む<ruby>綺<rt>き</rt>麗<rt>れい</rt></ruby>な女性。</p>
                  |<p><ruby><rb>宰相</rb><rt>そうま</rt></ruby>の<ruby>咳<rt>せき</rt>払<rt>ばら</rt></ruby>いに。</p>
                  |</body></html>""".trimMargin(),
            )
        }

        val book = EpubParser().parse(epub)
        val texts = book.chapters[0].blocks.map { it.text }
        assertTrue(texts.contains("その隣に佇む綺麗な女性。"), "текст: $texts")
        assertTrue(texts.contains("宰相の咳払いに。"), "текст: $texts")
        assertTrue(texts.none { it.contains("たたず") || it.contains("そうま") }, "фуригана должна выкидываться: $texts")
    }

    /** Реальная тестовая книга из test-data/ (пропускается, если файл не собран). */
    @Test
    fun `epub parser on real test book`() {
        // user.dir при :core:test — <корень>/core, книга лежит в <корень>/test-data
        val f = Path.of(System.getProperty("user.dir")).resolveSibling("test-data").resolve("akutagawa-test.epub")
        assumeTrue(Files.isRegularFile(f), "test-data/akutagawa-test.epub не собран")
        val book = EpubParser().parse(f)
        assertEquals("Тестовая книга: Акутагава — Нить паука и В чаще", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals("蜘蛛の糸", book.chapters[0].title)
        assertEquals("藪の中", book.chapters[1].title)
        val blocks = book.chapters.sumOf { it.blocks.size }
        assertTrue(blocks in 60..70, "блоков: $blocks")
        assertTrue(book.chapters[1].blocks.any { it.kind == BlockKind.HEADING })
        assertTrue(book.chapters[0].blocks.any { it.kind == BlockKind.TEXT && it.text.contains("御釈迦様") })
    }
}
