package ru.sebyanin.booktranslator.core.storage

import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.BookStructure
import ru.sebyanin.booktranslator.core.model.ChapterMeta
import ru.sebyanin.booktranslator.core.model.GlossaryFile
import ru.sebyanin.booktranslator.core.model.NotesFile
import ru.sebyanin.booktranslator.core.model.ProjectMeta
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** CRUD проектов и книг поверх файловой модели. Все записи атомарные (tmp + move). */
class ProjectStore(private val rootProvider: () -> Path) {

    val root: Path get() = rootProvider()

    fun projectsDir(): Path = root.resolve("projects")

    fun listProjects(): List<ProjectMeta> {
        val dir = projectsDir()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.resolve("project.json") }
                .filter { Files.isRegularFile(it) }
                .map { runCatching { AppJson.decodeFromString<ProjectMeta>(Files.readString(it)) }.getOrNull() }
                .toList()
                .filterNotNull()
                .sortedBy { it.name.lowercase() }
        }
    }

    fun createProject(name: String, sourceLang: String, targetLang: String, preset: String): ProjectMeta {
        val meta = ProjectMeta(
            slug = uniqueSlug(projectsDir(), slugify(name)),
            name = name.trim(),
            sourceLang = sourceLang.trim().lowercase(),
            targetLang = targetLang.trim().lowercase(),
            preset = preset,
            createdAt = today(),
        )
        val dir = projectsDir().resolve(meta.slug)
        Files.createDirectories(dir.resolve("books"))
        writeJson(dir.resolve("project.json"), meta)
        writeJson(dir.resolve("glossary.json"), GlossaryFile())
        writeJson(dir.resolve("notes.json"), NotesFile())
        if (!Files.exists(dir.resolve("conventions.md"))) {
            Files.writeString(dir.resolve("conventions.md"), DEFAULT_CONVENTIONS)
        }
        return meta
    }

    fun loadProject(slug: String): ProjectMeta =
        readJson(projectDir(slug).resolve("project.json"), ProjectMeta.serializer())

    fun projectDir(slug: String): Path = projectsDir().resolve(slug)

    fun listBooks(projectSlug: String): List<BookMeta> {
        val dir = projectDir(projectSlug).resolve("books")
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.resolve("book.json") }
                .filter { Files.isRegularFile(it) }
                .map { runCatching { AppJson.decodeFromString<BookMeta>(Files.readString(it)) }.getOrNull() }
                .toList()
                .filterNotNull()
        }
    }

    fun bookDir(projectSlug: String, bookSlug: String): Path =
        projectDir(projectSlug).resolve("books").resolve(bookSlug)

    fun loadBook(projectSlug: String, bookSlug: String): BookMeta =
        readJson(bookDir(projectSlug, bookSlug).resolve("book.json"), BookMeta.serializer())

    fun saveBookMeta(projectSlug: String, meta: BookMeta) {
        writeJson(bookDir(projectSlug, meta.slug).resolve("book.json"), meta)
    }

    fun saveStructure(projectSlug: String, bookSlug: String, structure: BookStructure) {
        writeJson(bookDir(projectSlug, bookSlug).resolve("source").resolve("structure.json"), structure)
    }

    fun loadStructure(projectSlug: String, bookSlug: String): BookStructure =
        readJson(
            bookDir(projectSlug, bookSlug).resolve("source").resolve("structure.json"),
            BookStructure.serializer(),
        )

    fun writeChapterSource(projectSlug: String, bookSlug: String, chapter: ChapterMeta, blocks: List<ChapterBlock>) {
        val file = bookDir(projectSlug, bookSlug).resolve("source").resolve(chapter.file)
        Files.createDirectories(file.parent)
        atomicWrite(file, BlockText.encode(blocks))
    }

    fun readChapterSource(projectSlug: String, bookSlug: String, chapterFile: String): List<ChapterBlock> =
        BlockText.decode(
            Files.readString(bookDir(projectSlug, bookSlug).resolve("source").resolve(chapterFile)),
        )

    // ---- глоссарий и «библия» ----

    fun loadGlossary(projectSlug: String): GlossaryFile =
        readJson(projectDir(projectSlug).resolve("glossary.json"), GlossaryFile.serializer())

    fun saveGlossary(projectSlug: String, file: GlossaryFile) {
        writeJson(projectDir(projectSlug).resolve("glossary.json"), file)
    }

    fun loadNotes(projectSlug: String): NotesFile =
        readJson(projectDir(projectSlug).resolve("notes.json"), NotesFile.serializer())

    fun saveNotes(projectSlug: String, file: NotesFile) {
        writeJson(projectDir(projectSlug).resolve("notes.json"), file)
    }

    /** Решения LLM-ревизора — на уровне проекта, рядом с glossary.json. */
    fun loadGlossaryRevision(projectSlug: String): ru.sebyanin.booktranslator.core.model.GlossaryRevisionFile {
        val f = projectDir(projectSlug).resolve("glossary-revision.json")
        if (!Files.isRegularFile(f)) return ru.sebyanin.booktranslator.core.model.GlossaryRevisionFile()
        return runCatching {
            AppJson.decodeFromString(ru.sebyanin.booktranslator.core.model.GlossaryRevisionFile.serializer(), Files.readString(f))
        }.getOrDefault(ru.sebyanin.booktranslator.core.model.GlossaryRevisionFile())
    }

    fun saveGlossaryRevision(projectSlug: String, file: ru.sebyanin.booktranslator.core.model.GlossaryRevisionFile) {
        atomicWrite(
            projectDir(projectSlug).resolve("glossary-revision.json"),
            AppJson.encodeToString(ru.sebyanin.booktranslator.core.model.GlossaryRevisionFile.serializer(), file),
        )
    }

    fun readConventions(projectSlug: String): String {
        val f = projectDir(projectSlug).resolve("conventions.md")
        return if (Files.isRegularFile(f)) Files.readString(f) else ""
    }

    fun writeConventions(projectSlug: String, text: String) {
        atomicWrite(projectDir(projectSlug).resolve("conventions.md"), text)
    }

    fun uniqueBookSlug(projectSlug: String, title: String): String =
        uniqueSlug(projectDir(projectSlug).resolve("books"), slugify(title.ifBlank { "book" }))

    // ---- утилиты ----

    private fun today(): String = LocalDate.now().format(DateTimeFormatter.ISO_DATE)

    private fun writeJson(path: Path, value: Any) {
        @Suppress("UNCHECKED_CAST")
        val text = when (value) {
            is ProjectMeta -> AppJson.encodeToString(ProjectMeta.serializer(), value)
            is BookMeta -> AppJson.encodeToString(BookMeta.serializer(), value)
            is BookStructure -> AppJson.encodeToString(BookStructure.serializer(), value)
            is GlossaryFile -> AppJson.encodeToString(GlossaryFile.serializer(), value)
            is NotesFile -> AppJson.encodeToString(NotesFile.serializer(), value)
            else -> error("Неизвестный тип для JSON: ${value::class}")
        }
        atomicWrite(path, text)
    }

    private inline fun <reified T> readJson(path: Path, serializer: kotlinx.serialization.KSerializer<T>): T {
        if (!Files.isRegularFile(path)) {
            // Пустые глоссарий/заметки читаются до первого сохранения
            error("Файл не найден: $path")
        }
        return AppJson.decodeFromString(serializer, Files.readString(path))
    }

    companion object {
        fun atomicWrite(path: Path, text: String) {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, text)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        fun slugify(input: String): String {
            val translit = buildString {
                input.forEach { ch ->
                    append(
                        when (ch.lowercaseChar()) {
                            'а' -> "a"; 'б' -> "b"; 'в' -> "v"; 'г' -> "g"; 'д' -> "d"
                            'е' -> "e"; 'ё' -> "yo"; 'ж' -> "zh"; 'з' -> "z"; 'и' -> "i"
                            'й' -> "y"; 'к' -> "k"; 'л' -> "l"; 'м' -> "m"; 'н' -> "n"
                            'о' -> "o"; 'п' -> "p"; 'р' -> "r"; 'с' -> "s"; 'т' -> "t"
                            'у' -> "u"; 'ф' -> "f"; 'х' -> "h"; 'ц' -> "ts"; 'ч' -> "ch"
                            'ш' -> "sh"; 'щ' -> "sch"; 'ъ' -> ""; 'ы' -> "y"; 'ь' -> ""
                            'э' -> "e"; 'ю' -> "yu"; 'я' -> "ya"
                            else -> ch.lowercaseChar().toString()
                        },
                    )
                }
            }
            val cleaned = translit.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)
            return cleaned.ifBlank { "item" }
        }

        private fun uniqueSlug(parent: Path, base: String): String {
            var slug = base
            var i = 2
            while (Files.exists(parent.resolve(slug))) {
                slug = "$base-${i++}"
            }
            return slug
        }

        private val DEFAULT_CONVENTIONS = """
            |# Конвенции проекта
            |
            |Правила переносятся целиком в каждый бриф агентам. Редактируйте под серию.
            |
            |## Типографика
            |— Реплики диалога — через тире: «— Реплика, — сказал он. — Продолжение.»
            |— Кавычки 1-го уровня — «ёлочки»; вложенные — прямые двойные "…".
            |— Дословная чужая речь внутри реплики-тире — «ёлочки» (это кавычки 1-го уровня).
            |— «?!» — всегда в этом порядке; многоточие — одним символом «…».
            |
            |## Термины и единство
            |— Один повторяющийся объект — одно русское слово на всю книгу («дворец», не «замок»).
            |— Имена и термины — строго по глоссарию.
            |
            |## Голоса персонажей
            |— Реплики — в голосе говорящего; характерные манеры см. в заметках «библии перевода».
            |
        """.trimMargin()
    }
}
