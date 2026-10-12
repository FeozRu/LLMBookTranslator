package ru.sebyanin.booktranslator.core.tools

import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.ToolSpec
import ru.sebyanin.booktranslator.core.llm.prop
import ru.sebyanin.booktranslator.core.llm.buildJsonSchema
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.WorkManifest
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/** Контекст вызова инструмента: к какой книге/проекту он применяется. */
data class ToolContext(
    val projectSlug: String,
    val bookSlug: String?,
    val store: ProjectStore,
    val knowledge: KnowledgeService,
)

/** Read-only инструмент агента (function calling). Ответ всегда строка с лимитом длины. */
interface AgentTool {
    val spec: ToolSpec
    suspend fun execute(args: JsonObject, ctx: ToolContext): String
}

class ToolRegistry(private val maxResultChars: Int) {

    private val tools = linkedMapOf<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.spec.name] = tool
    }

    fun registerDefaults(store: ProjectStore, knowledge: KnowledgeService) {
        register(GlossaryLookupTool())
        register(NotesLookupTool())
        register(AlignmentSearchTool(store))
        register(ReadContextTool(store))
        register(SourceSearchTool(store))
        knowledge.let { } // KnowledgeService уже в ToolContext
    }

    fun specs(): List<ToolSpec> = tools.values.map { it.spec }

    suspend fun execute(name: String, argumentsJson: String, ctx: ToolContext): String {
        val tool = tools[name] ?: return "Неизвестный инструмент: $name"
        val args = runCatching {
            AppJson.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
        }.getOrElse { JsonObject(emptyMap()) }
        val result = tool.execute(args, ctx)
        return if (result.length > maxResultChars) result.take(maxResultChars) + "\n…(обрезано)" else result
    }
}

// ---------------- Инструменты ----------------

class GlossaryLookupTool : AgentTool {
    override val spec = ToolSpec(
        name = "glossary_lookup",
        description = "Поиск по каноническому глоссарию проекта: термины, имена, топонимы и их утверждённые переводы.",
        parametersSchema = buildJsonSchema(
            mapOf("query" to prop("string", "Поисковая подстрока")),
            listOf("query"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isEmpty()) return "Пустой запрос"
        val lower = query.lowercase()
        val hits = ctx.knowledge.approvedTerms(ctx.projectSlug).filter {
            it.original.lowercase().contains(lower) || it.translation.lowercase().contains(lower)
        }.take(20)
        return if (hits.isEmpty()) "В глоссарии нет утверждённых записей по «$query»"
        else hits.joinToString("\n") { e -> "${e.original} → ${e.translation} [${e.category}]" }
    }
}

class NotesLookupTool : AgentTool {
    override val spec = ToolSpec(
        name = "notes_lookup",
        description = "Заметки «библии перевода» о сущности: голос персонажа, манера речи, обращения, регистр.",
        parametersSchema = buildJsonSchema(
            mapOf("entity" to prop("string", "Имя сущности")),
            listOf("entity"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val entity = args["entity"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (entity.isEmpty()) return "Пустая сущность"
        val lower = entity.lowercase()
        val hits = ctx.knowledge.notes(ctx.projectSlug)
            .filter { it.status == "approved" && it.entity.lowercase().contains(lower) }
            .take(15)
        return if (hits.isEmpty()) "Нет заметок об «$entity»"
        else hits.joinToString("\n") { n ->
            "- ${n.entity}" + (if (n.situation.isNotBlank()) " [${n.situation}]" else "") + ": ${n.text}"
        }
    }
}

class AlignmentSearchTool(private val store: ProjectStore) : AgentTool {
    override val spec = ToolSpec(
        name = "alignment_search",
        description = "Как фраза уже переводилась в этой книге: ищет по исходнику и переводам, возвращает пары «оригинал || перевод» с указанием главы и блока. Сверяй повторы и воспоминания персонажей.",
        parametersSchema = buildJsonSchema(
            mapOf(
                "text" to JsonPrimitive("string"),
                "scope" to buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("source"), JsonPrimitive("translation"))))
                },
            ),
            listOf("text"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val text = args["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        val scope = args["scope"]?.jsonPrimitive?.content ?: "translation"
        val book = ctx.bookSlug ?: return "Книга не выбрана"
        if (text.isEmpty()) return "Пустой запрос"
        val lower = text.lowercase()
        val bookDir = ctx.store.bookDir(ctx.projectSlug, book)
        val manifest = runCatching { SplitStage.loadManifest(bookDir) }.getOrNull()
            ?: return "Книга ещё не нарезана"

        val results = mutableListOf<String>()
        manifest.parts.forEach { part ->
            part.fragments.forEach { frag ->
                val file = readFragment(bookDir, frag.id) ?: return@forEach
                val translation = file.editedBlocks.ifEmpty { file.draftBlocks }
                if (translation.isEmpty()) return@forEach
                val chapterNo = part.chapterIndex
                val srcJoined = file.srcBlocks
                when (scope) {
                    "source" -> srcJoined.forEachIndexed { i, s ->
                        if (s.lowercase().contains(lower) && translation.getOrNull(i) != null && results.size < 10) {
                            results += "гл $chapterNo, блок ${part.blockFrom + frag.blockFrom + i + 1}:\nORIG: $s\nRU: ${translation[i]}"
                        }
                    }
                    else -> translation.forEachIndexed { i, t ->
                        if (t.lowercase().contains(lower) && srcJoined.getOrNull(i) != null && results.size < 10) {
                            results += "гл $chapterNo, блок ${part.blockFrom + frag.blockFrom + i + 1}:\nORIG: ${srcJoined[i]}\nRU: $t"
                        }
                    }
                }
            }
        }
        return if (results.isEmpty()) "Совпадений «$text» не найдено"
        else results.joinToString("\n---\n")
    }

    private fun readFragment(bookDir: java.nio.file.Path, id: String) = runCatching {
        AppJson.decodeFromString<ru.sebyanin.booktranslator.core.model.FragmentFile>(
            Files.readString(bookDir.resolve("work").resolve("fragments").resolve("$id.json")),
        )
    }.getOrNull()
}

class ReadContextTool(private val store: ProjectStore) : AgentTool {
    override val spec = ToolSpec(
        name = "read_context",
        description = "Окрестность блока в главе: исходные абзацы (и их перевод, если готов) вокруг указанного места — для стыков фрагментов.",
        parametersSchema = buildJsonSchema(
            mapOf(
                "chapter" to buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Номер главы"))
                },
                "from_block" to prop("integer"),
                "to_block" to prop("integer"),
            ),
            listOf("chapter", "from_block", "to_block"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val book = ctx.bookSlug ?: return "Книга не выбрана"
        val chapter = args["chapter"]?.jsonPrimitive?.int ?: return "Не указана глава"
        val from = (args["from_block"]?.jsonPrimitive?.int ?: 1).coerceAtLeast(1)
        val to = (args["to_block"]?.jsonPrimitive?.int ?: from + 20).coerceAtMost(from + 60)
        val structure = ctx.store.loadStructure(ctx.projectSlug, book)
        val meta = structure.chapters.firstOrNull { it.index == chapter }
            ?: return "Глава $chapter не найдена"
        val blocks = ctx.store.readChapterSource(ctx.projectSlug, book, meta.file)
        val lo = (from - 1).coerceIn(0, blocks.lastIndex)
        val hi = (to - 1).coerceIn(lo, blocks.lastIndex)
        val lines = (lo..hi).map { i -> "[${i + 1}] (${blocks[i].kind.name.lowercase()}) ${blocks[i].text}" }
        return lines.joinToString("\n").ifEmpty { "Пустой диапазон" }
    }
}

class SourceSearchTool(private val store: ProjectStore) : AgentTool {
    override val spec = ToolSpec(
        name = "source_search",
        description = "Поиск по оригиналу всей книги: вхождения фразы в исходных абзацах с указанием главы и блока.",
        parametersSchema = buildJsonSchema(
            mapOf("text" to prop("string", "Фраза для поиска")),
            listOf("text"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val book = ctx.bookSlug ?: return "Книга не выбрана"
        val text = args["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (text.isEmpty()) return "Пустой запрос"
        val lower = text.lowercase()
        val structure = ctx.store.loadStructure(ctx.projectSlug, book)
        val results = mutableListOf<String>()
        for (meta in structure.chapters) {
            val blocks = ctx.store.readChapterSource(ctx.projectSlug, book, meta.file)
            blocks.forEachIndexed { i, b ->
                if (b.text.lowercase().contains(lower) && results.size < 10) {
                    results += "гл ${meta.index}, блок ${i + 1}: ${b.text}"
                }
            }
        }
        return if (results.isEmpty()) "В оригинале не найдено: «$text»"
        else results.joinToString("\n")
    }
}

/** Утилита для MCP: те же инструменты, внешний вход. */
object ToolFacade {
    fun defaultRegistry(store: ProjectStore, knowledge: KnowledgeService, maxResultChars: Int = 4000): ToolRegistry =
        ToolRegistry(maxResultChars).also { it.registerDefaults(store, knowledge) }

    fun docsText(): String = buildString {
        appendLine("Доступны инструменты (вызывай при необходимости):")
        appendLine("— glossary_lookup(query): утверждённые термины глоссария.")
        appendLine("— notes_lookup(entity): заметки о голосе/манере сущности.")
        appendLine("— alignment_search(text, scope=source|translation): как фраза переводилась ранее (глава+блок, пара ORIG/RU).")
        appendLine("— read_context(chapter, from_block, to_block): окрестность блока главы (оригинал).")
        appendLine("— source_search(text): вхождения фразы в оригинале книги.")
        appendLine("Без необходимости не вызывай: нужное уже есть в задании.")
    }
}
