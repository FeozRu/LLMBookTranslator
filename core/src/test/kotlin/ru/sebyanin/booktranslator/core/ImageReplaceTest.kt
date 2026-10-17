package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.builder.EpubBuilder
import ru.sebyanin.booktranslator.core.builder.Fb2Builder
import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.BookStructure
import ru.sebyanin.booktranslator.core.model.ChapterMeta
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.pipeline.BlockEditor
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Замена изображения в якорном блоке: файл копируется в images/ книги под плоским
 * именем, маркер { [img:…] } в эффективном поле фрагмента получает это имя, ручная
 * правка якоря в редакторе запрещена. Билдеры обязаны резолвить плоское имя даже из
 * якорей с исходным путём EPUB («item/image/k002.jpg») — иначе ссылка в собранной
 * книге не совпадает с упакованной картинкой.
 */
class ImageReplaceTest {

    private fun harness(): Triple<ProjectStore, String, java.nio.file.Path> {
        val tmp = Files.createTempDirectory("bt-imgrepl")
        val store = ProjectStore { tmp }
        val project = store.createProject("Картинки", "ja", "ru", "generic")
        val book = BookMeta(slug = "b", title = "Книга", author = "Автор", format = "epub")
        store.saveBookMeta(project.slug, book)
        val meta = ChapterMeta(index = 1, title = "Глава 1", file = "chapter-0001.md", blockCount = 2, charCount = 42)
        store.saveStructure(project.slug, book.slug, BookStructure(listOf(meta), images = listOf("k002.jpg")))
        return Triple(store, project.slug, tmp)
    }

    private fun editor(store: ProjectStore, tmp: java.nio.file.Path): BlockEditor =
        BlockEditor(
            store,
            PromptStore({ tmp }) { slug -> store.projectDir(slug) },
            KnowledgeService(store),
            LlmClient(),
            { AppSettings() },
        )

    private fun writeFragment(
        store: ProjectStore,
        p: String,
        b: String,
        id: String,
        edited: List<String>,
        src: List<String> = listOf("{ [img:item/image/k002.jpg] }", "Текст."),
    ): FragmentFile {
        val frag = FragmentFile(
            id = id,
            srcBlocks = src,
            draftBlocks = listOf("{ [img:item/image/k002.jpg] }", "Текст черновика."),
            editedBlocks = edited,
        )
        val dir = store.bookDir(p, b).resolve("work").resolve("fragments")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$id.json"), AppJson.encodeToString(FragmentFile.serializer(), frag))
        return frag
    }

    private fun readFragment(store: ProjectStore, p: String, b: String, id: String): FragmentFile =
        AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(store.bookDir(p, b).resolve("work").resolve("fragments").resolve("$id.json")),
        )

    private fun tempFile(dir: java.nio.file.Path, name: String, vararg bytes: Byte): java.nio.file.Path =
        Files.write(dir.resolve(name), bytes)

    @Test
    fun `safeImageName снимает путь и запрещённые символы`() {
        assertEquals("k002.jpg", BlockEditor.safeImageName("item/image/k002.jpg"))
        assertEquals("мы_картинка_.png", BlockEditor.safeImageName("..\\мы картинка!.png"))
        assertEquals("image", BlockEditor.safeImageName("///"))
    }

    @Test
    fun `importImage копирует файл в images под плоским именем`() {
        val (store, p, tmp) = harness()
        val src = tempFile(Files.createTempDirectory("up"), "cover art.jpg", 1, 2, 3)
        val name = editor(store, tmp).importImage(p, "b", src)
        assertEquals("cover_art.jpg", name)
        assertTrue(Files.isRegularFile(store.bookDir(p, "b").resolve("images").resolve("cover_art.jpg")))
    }

    @Test
    fun `importImage при коллизии с другим содержимым даёт уникальное имя`() {
        val (store, p, tmp) = harness()
        val b = "b"
        val e = editor(store, tmp)
        val dir1 = Files.createTempDirectory("up1")
        val dir2 = Files.createTempDirectory("up2")
        val dir3 = Files.createTempDirectory("up3")
        val a = tempFile(dir1, "k002.jpg", 1)
        val same = tempFile(dir2, "k002.jpg", 1) // то же имя и содержимое — переиспользуем
        val other = tempFile(dir3, "k002.jpg", 9, 9) // то же имя, другое содержимое — суффикс
        assertEquals("k002.jpg", e.importImage(p, b, a))
        assertEquals("k002.jpg", e.importImage(p, b, same))
        assertEquals("k002-2.jpg", e.importImage(p, b, other))
    }

    @Test
    fun `replaceImage меняет маркер в эффективном поле`() {
        val (store, p, tmp) = harness()
        val b = "b"
        writeFragment(store, p, b, "p1-f1", listOf("{ [img:item/image/k002.jpg] }", "Текст редактуры."))
        val err = editor(store, tmp).replaceImage(p, b, BlockEditor.BlockRef("p1-f1", 0, fromEdited = true), "k002-2.jpg")
        assertNull(err)
        assertEquals("{ [img:k002-2.jpg] }", readFragment(store, p, b, "p1-f1").editedBlocks[0])
    }

    @Test
    fun `replaceImage отказывает на блоке без якоря и не пишет файл`() {
        val (store, p, tmp) = harness()
        val b = "b"
        val before = writeFragment(store, p, b, "p1-f1", listOf("{ [img:item/image/k002.jpg] }", "Текст редактуры."))
        val err = editor(store, tmp).replaceImage(p, b, BlockEditor.BlockRef("p1-f1", 1, fromEdited = true), "x.png")
        assertNotNull(err)
        assertEquals(before, readFragment(store, p, b, "p1-f1"))
    }

    @Test
    fun `saveEdit запрещает ручную замену якоря изображения`() {
        val (store, p, tmp) = harness()
        val b = "b"
        val before = writeFragment(store, p, b, "p1-f1", listOf("{ [img:item/image/k002.jpg] }", "Текст редактуры."))
        val err = editor(store, tmp).saveEdit(p, b, BlockEditor.BlockRef("p1-f1", 0, fromEdited = true), "{ [img:hack.jpg] }")
        assertNotNull(err)
        assertTrue(err!!.contains("нельзя"), err)
        assertEquals(before, readFragment(store, p, b, "p1-f1"))
    }

    @Test
    fun `saveEdit правит текст вокруг нетронутого якоря`() {
        val (store, p, tmp) = harness()
        val b = "b"
        writeFragment(
            store, p, b, "p1-f1",
            edited = listOf("Взгляд { [img:x.jpg] } продолжился."),
            src = listOf("Взгляд { [img:x.jpg] } продолжился."),
        )
        val err = editor(store, tmp)
            .saveEdit(p, b, BlockEditor.BlockRef("p1-f1", 0, fromEdited = true), "Новый взгляд { [img:x.jpg] } продолжился.")
        assertNull(err)
        assertEquals(
            "Новый взгляд { [img:x.jpg] } продолжился.",
            readFragment(store, p, b, "p1-f1").editedBlocks[0],
        )
    }

    @Test
    fun `epub резолвит плоское имя из якоря с путём и пакует картинку`() {
        val (store, p, _) = harness()
        val b = "b"
        val bookDir = store.bookDir(p, b)
        Files.createDirectories(bookDir.resolve("translation"))
        Files.writeString(
            bookDir.resolve("translation").resolve("chapter-0001.md"),
            "{ [img:item/image/k002.jpg] }\nПервая строка текста.\n",
        )
        Files.createDirectories(bookDir.resolve("images"))
        Files.write(bookDir.resolve("images").resolve("k002.jpg"), byteArrayOf(1, 2, 3, 4))

        val result = EpubBuilder(store).build(p, b)
        ZipFile(result.file.toFile()).use { zip ->
            assertNotNull(zip.getEntry("OEBPS/images/k002.jpg"), "картинка должна быть упакована плоским именем")
            val body = zip.getInputStream(zip.getEntry("OEBPS/chapter-0001.xhtml")).readBytes().decodeToString()
            assertTrue(body.contains("src=\"images/k002.jpg\""), body.take(400))
            assertFalse(body.contains("item/image"), body.take(400))
        }
    }

    @Test
    fun `fb2 ссылается на плоский binary id из якоря с путём`() {
        val (store, p, _) = harness()
        val b = "b"
        val bookDir = store.bookDir(p, b)
        Files.createDirectories(bookDir.resolve("translation"))
        Files.writeString(
            bookDir.resolve("translation").resolve("chapter-0001.md"),
            "{ [img:item/image/k002.jpg] }\nПервая строка текста.\n",
        )
        Files.createDirectories(bookDir.resolve("images"))
        Files.write(bookDir.resolve("images").resolve("k002.jpg"), byteArrayOf(1, 2, 3, 4))

        val out = Fb2Builder(store).build(p, b)
        val xml = Files.readString(out)
        assertTrue(xml.contains("<image l:href=\"#img-k002\"/>"), xml.take(600))
        assertTrue(xml.contains("<binary id=\"img-k002\""), xml.take(600))
        assertFalse(xml.contains("item/image"), xml.take(600))
    }
}
