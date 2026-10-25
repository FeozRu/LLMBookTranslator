package ru.sebyanin.booktranslator.core.mcp

import ru.sebyanin.booktranslator.core.AppServices
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.pipeline.ExternalSubmitResult
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.BlockText
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Запуск этапа извне (реализуется UI-слоем, где живёт очередь этапов).
 */
fun interface StageLauncher {
    /** stage: split|draft|edit|critic|assemble|qa|build_epub|build_fb2. Возвращает runId или ошибку. */
    fun launch(project: String, book: String, stage: String, chapters: List<Int>?): Result<String>
}

/**
 * Уведомление UI об изменениях, сделанных MCP-инструментом мимо конвейера
 * (критик-файлы, фрагменты, глоссарий): UI-слой поднимает свои версионные
 * сигналы (criticVersion и пр.), чтобы открытые панели перечитали данные.
 * book = null — мутация уровня проекта (глоссарий).
 */
fun interface MutationNotifier {
    fun mutated(project: String, book: String?)
}

/**
 * Встроенный MCP-сервер (Streamable HTTP, localhost): внешний MCP-клиент
 * читает состояние конвейера и запускает этапы.
 */
class McpServer(
    private val services: AppServices,
    private val stageLauncher: StageLauncher,
    private val onMutated: MutationNotifier,
) {
    @Volatile private var engine: EmbeddedServer<*, *>? = null

    fun start(port: Int, token: String) {
        if (engine != null) return
        val expectedToken = token.trim()
        engine = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            if (expectedToken.isNotEmpty()) {
                intercept(ApplicationCallPipeline.Plugins) {
                    val path = call.request.path()
                    if (path.startsWith("/mcp")) {
                        val auth = call.request.headers["Authorization"] ?: ""
                        if (auth != "Bearer $expectedToken") {
                            call.respondText("bad token", status = io.ktor.http.HttpStatusCode.Unauthorized)
                            finish()
                        }
                    }
                }
            }
            mcpStatelessStreamableHttp("/mcp") {
                buildServer()
            }
        }.also { srv ->
            srv.start(wait = false)
            AppLog.info("mcp", "MCP-сервер: http://127.0.0.1:$port/mcp")
        }
    }

    fun stop() {
        val current = engine ?: return
        engine = null
        current.stop(500, 1500)
        AppLog.info("mcp", "MCP-сервер остановлен")
    }

    private fun buildServer(): Server = Server(
        Implementation(name = "book-translator", version = "0.1.0"),
        ServerOptions(
            capabilities = io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities(
                tools = io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities.Tools(),
            ),
        ),
    ) {
        registerTools(this)
    }

    private fun text(content: String) = CallToolResult(content = listOf(TextContent(content)))

    /** Строковый аргумент: честный разбор JSON-строки (переносы/кавычки в text-параметрах выживают). */
    private fun argString(args: JsonObject?, key: String): String? =
        (args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    private fun argInts(args: JsonObject?, key: String): List<Int>? =
        args?.get(key)?.let { el ->
            runCatching {
                el.toString().trim('[', ']').split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    /** Файл фрагмента или null (нет файла/битый) — для чтения без выбросов. */
    private fun readFragmentOrNull(bookDir: Path, id: String): FragmentFile? = runCatching {
        AppJson.decodeFromString(
            FragmentFile.serializer(),
            Files.readString(bookDir.resolve("work").resolve("fragments").resolve("$id.json")),
        )
    }.getOrNull()

    private fun registerTools(server: Server) {
        with(server) {
            addTool(
                name = "list_projects",
                description = "Список проектов перевода (slug, имя, языки).",
            ) {
                val sb = StringBuilder()
                services.projectStore.listProjects().forEach { p ->
                    sb.appendLine("${p.slug} | ${p.name} | ${p.sourceLang}->${p.targetLang} | пресет ${p.preset}")
                }
                text(if (sb.isEmpty()) "проектов нет" else sb.toString())
            }

            addTool(
                name = "list_books",
                description = "Книги проекта.",
            ) {
                val p = argString(it.params.arguments, "project")
                    ?: return@addTool text("нет параметра project")
                val sb = StringBuilder()
                services.projectStore.listBooks(p).forEach { b ->
                    sb.appendLine("${b.slug} | ${b.title} | ${b.author} | ${b.format}")
                }
                text(if (sb.isEmpty()) "книг нет" else sb.toString())
            }

            addTool(
                name = "get_book_status",
                description = "Статус книги: главы, фрагменты, готовность этапов черновик/редактура, ошибки.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val bookDir = services.projectStore.bookDir(p, b)
                val manifest = runCatching { SplitStage.loadManifest(bookDir) }.getOrNull()
                    ?: return@addTool text("книга не нарезана")
                val frags = manifest.allFragments()
                val sb = StringBuilder()
                sb.appendLine("фрагментов: ${frags.size}")
                sb.appendLine("черновик: готово ${frags.count { f -> f.draft.isDone }}, ошибок ${frags.count { f -> f.draft.isFailed }}")
                sb.appendLine("редактура: готово ${frags.count { f -> f.edited.isDone }}, ошибок ${frags.count { f -> f.edited.isFailed }}")
                frags.firstOrNull { f -> f.draft.isFailed }?.let { sb.appendLine("пример ошибки: ${it.id}: ${it.draft.error}") }
                text(sb.toString())
            }

            addTool(
                name = "run_stage",
                description = "Запустить этап конвейера: split | draft | edit | critic | assemble | qa | build_epub | build_fb2. chapters — список номеров глав (опционально).",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val stage = argString(it.params.arguments, "stage") ?: return@addTool text("нет stage")
                val chapters = argInts(it.params.arguments, "chapters")
                stageLauncher.launch(p, b, stage, chapters).fold(
                    onSuccess = { id -> text("запущено: $id") },
                    onFailure = { e -> text("не запущено: ${e.message}") },
                )
            }

            addTool(
                name = "list_fragments",
                description = "Очередь работы для внешнего исполнителя: не завершённые фрагменты (pending/failed/running). stage: draft|edit, опционально chapter.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val stage = argString(it.params.arguments, "stage") ?: "draft"
                if (stage != "draft" && stage != "edit") return@addTool text("stage должен быть draft|edit")
                val chapter = argString(it.params.arguments, "chapter")?.toIntOrNull()
                val bookDir = services.projectStore.bookDir(p, b)
                val manifest = runCatching { SplitStage.loadManifest(bookDir) }.getOrNull()
                    ?: return@addTool text("книга не нарезана")
                val lines = manifest.parts.asSequence()
                    .filter { chapter == null || it.chapterIndex == chapter }
                    .flatMap { part -> part.fragments.map { part.chapterIndex to it } }
                    .filter { (_, f) -> !(if (stage == "draft") f.draft else f.edited).isDone }
                    .map { (ch, f) ->
                        val state = if (stage == "draft") f.draft else f.edited
                        val anchor = readFragmentOrNull(bookDir, f.id)?.let { file ->
                            BlockText.allAnchors(if (stage == "draft") file.srcBlocks else file.draftBlocks)
                        } == true
                        "${f.id} | глава $ch | блоков ${f.srcBlockCount} | ${state.status}" +
                            if (anchor) " | якорный (верни дословно)" else ""
                    }
                    .toList()
                text(if (lines.isEmpty()) "готово — незавершённых фрагментов нет" else lines.joinToString("\n"))
            }

            addTool(
                name = "get_fragment_brief",
                description = "Бриф фрагмента для внешнего исполнителя: полный текст задания (словарь, заметки, конвенции, исходник) — тот же, что получает встроенная модель. stage: draft|edit.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val fragment = argString(it.params.arguments, "fragment") ?: return@addTool text("нет fragment")
                val stage = argString(it.params.arguments, "stage") ?: "draft"
                val brief = when (stage) {
                    "draft" -> services.draftStage.briefFor(p, b, fragment)
                    "edit" -> services.editStage.briefFor(p, b, fragment)
                    else -> return@addTool text("stage должен быть draft|edit")
                } ?: return@addTool text("фрагмент не найден: $fragment")
                text(brief)
            }

            addTool(
                name = "submit_translation",
                description = "Принять готовый текст фрагмента от внешнего исполнителя: верификация, запись, статус done (готовый фрагмент перезаписывает). stage: draft|edit; text — перевод/редактура; source — кто перевёл (для журнала). Отказ = причина верификации, исправь и пришли снова.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val fragment = argString(it.params.arguments, "fragment") ?: return@addTool text("нет fragment")
                val stage = argString(it.params.arguments, "stage") ?: "draft"
                val raw = argString(it.params.arguments, "text") ?: return@addTool text("нет text")
                val source = argString(it.params.arguments, "source") ?: "external"
                val result = when (stage) {
                    "draft" -> services.draftStage.submitExternal(p, b, fragment, raw, source)
                    "edit" -> services.editStage.submitExternal(p, b, fragment, raw, source)
                    else -> return@addTool text("stage должен быть draft|edit")
                }
                when (result) {
                    is ExternalSubmitResult.Accepted -> {
                        onMutated.mutated(p, b)
                        text("принято: $fragment (${result.blocks} блоков; ${result.verify})")
                    }
                    is ExternalSubmitResult.Rejected -> text("не принято: ${result.reason} — исправь и пришли снова")
                    is ExternalSubmitResult.Error -> text(result.message)
                }
            }

            addTool(
                name = "get_glossary",
                description = "Глоссарий проекта (фильтр: all | approved | proposed).",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val filter = argString(it.params.arguments, "filter") ?: "approved"
                val entries = services.knowledge.glossary(p).filter { filter == "all" || it.status == filter }
                text(
                    if (entries.isEmpty()) "пусто"
                    else entries.joinToString("\n") { e ->
                        "${e.original} -> ${e.translation} [${e.status}/${e.category}]" + if (e.context.isBlank()) "" else " (${e.context})"
                    },
                )
            }

            addTool(
                name = "get_notes",
                description = "«Библия перевода»: заметки о голосах/манерах сущностей.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val notes = services.knowledge.notes(p).filter { it.status == "approved" }
                text(
                    if (notes.isEmpty()) "пусто"
                    else notes.joinToString("\n") { n -> "${n.entity} [${n.situation}]: ${n.text}" },
                )
            }

            addTool(
                name = "resolve_proposals",
                description = "Утвердить/отклонить предложение Терминолога: action=approve|reject, original — термин из глоссария, translation — новый перевод (для approve, опционально).",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val action = argString(it.params.arguments, "action") ?: return@addTool text("нет action")
                val original = argString(it.params.arguments, "original") ?: return@addTool text("нет original")
                val translation = argString(it.params.arguments, "translation")
                when (action) {
                    "approve" -> {
                        services.knowledge.approveTerm(p, original, translation ?: "")
                        onMutated.mutated(p, null)
                        text("утверждено: $original")
                    }
                    "reject" -> {
                        services.knowledge.rejectTerm(p, original)
                        onMutated.mutated(p, null)
                        text("отклонено: $original")
                    }
                    else -> text("action должен быть approve|reject")
                }
            }

            addTool(
                name = "run_glossary_revisor",
                description = "Прогнать LLM-ревизора по глоссарию проекта (тратит токены профиля роли «Ревизор глоссария»). scope: queue — очередь предложений (по умолчанию), canon — утверждённый канон.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val scope = argString(it.params.arguments, "scope") ?: "queue"
                if (scope != "queue" && scope != "canon") return@addTool text("scope должен быть queue|canon")
                stageLauncher.launch(p, b, if (scope == "canon") "revisor_canon" else "revisor", null).fold(
                    onSuccess = { id -> text("запущено: $id") },
                    onFailure = { e -> text("не запущено: ${e.message}") },
                )
            }

            addTool(
                name = "get_glossary_revision",
                description = "Решения ревизора глоссария (вердикты keep/drop/fix, статусы pending/applied/dismissed).",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val file = services.projectStore.loadGlossaryRevision(p)
                if (file.decisions.isEmpty()) return@addTool text("решений ревизора нет")
                text(file.decisions.joinToString("\n") { d ->
                    "[${d.id}] ${d.status} ${d.verdict}: ${d.original}" +
                        (if (d.verdict == "fix" && d.suggested.isNotBlank()) " → ${d.suggested}" else "") +
                        (if (d.parts.isEmpty()) "" else " [" + d.parts.joinToString("; ") { "${it.original} → ${it.translation}" } + "]") +
                        (if (d.reason.isBlank()) "" else " — ${d.reason}")
                })
            }

            addTool(
                name = "search_alignment",
                description = "Поиск по выравниванию «оригинал ↔ перевод»: как фраза уже переводилась. scope=translation|source.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val q = argString(it.params.arguments, "query") ?: return@addTool text("нет query")
                val scope = argString(it.params.arguments, "scope") ?: "translation"
                val book = argString(it.params.arguments, "book")
                val hits = services.alignSearch.search(p, book, q, scope)
                text(
                    if (hits.isEmpty()) "не найдено"
                    else hits.joinToString("\n---\n") { h -> "гл ${h.chapter}, блок ${h.block + 1}\nORIG: ${h.src}\nRU: ${h.tr}" },
                )
            }

            addTool(
                name = "read_chapter",
                description = "Текст главы: which=source|draft|edited.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val ch = argString(it.params.arguments, "chapter")?.toIntOrNull() ?: return@addTool text("нет chapter")
                val which = argString(it.params.arguments, "which") ?: "edited"
                val structure = services.projectStore.loadStructure(p, b)
                val meta = structure.chapters.firstOrNull { it.index == ch }
                    ?: return@addTool text("глава не найдена")
                val bookDir = services.projectStore.bookDir(p, b)
                val lines: List<String> = when (which) {
                    "source" -> services.projectStore.readChapterSource(p, b, meta.file).map { bl -> bl.text }
                    "draft" -> {
                        val f = bookDir.resolve("work").resolve("draft").resolve(meta.file)
                        if (Files.isRegularFile(f)) Files.readAllLines(f) else listOf("(не собрано)")
                    }
                    else -> {
                        val f = bookDir.resolve("translation").resolve(meta.file)
                        if (Files.isRegularFile(f)) Files.readAllLines(f) else listOf("(не собрано)")
                    }
                }
                text(lines.joinToString("\n"))
            }

            addTool(
                name = "get_critic_suggestions",
                description = "Предложения корректора по главе (статусы pending/applied/rejected).",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val ch = argString(it.params.arguments, "chapter")?.toIntOrNull() ?: return@addTool text("нет chapter")
                val file = services.suggestionService.loadChapter(p, b, ch)
                    ?: return@addTool text("корректор по главе не запускался")
                val sb = StringBuilder()
                file.suggestions.forEach { s ->
                    sb.appendLine("[${s.id}] ${s.status} (${s.priority}/${s.category}) grep=${s.grepCount}")
                    sb.appendLine("  БЫЛО: ${s.was}")
                    sb.appendLine("  СТАЛО: ${s.became}")
                    sb.appendLine("  ПОЧЕМУ: ${s.why}")
                }
                if (file.doubts.isNotEmpty()) {
                    sb.appendLine("СОМНЕНИЯ (id вида dN-M):")
                    file.doubts.forEach { d ->
                        sb.appendLine("[${d.id}] ${d.status} — ${d.text}")
                    }
                }
                val tDoubts = services.suggestionService.translatorDoubts(p, b, ch)
                if (tDoubts.isNotEmpty()) {
                    sb.appendLine("ПОМЕТКИ ПЕРЕВОДЧИКА ([[??: …]] в тексте; id вида tN-XXXXXXXX):")
                    tDoubts.forEach { d ->
                        sb.appendLine("[${d.id}] ${d.fragmentId}, блок ${d.blockIndex + 1} — ${d.text}")
                    }
                }
                text(sb.toString())
            }

            addTool(
                name = "resolve_doubt",
                description = "Разобрать сомнение: id dN-M — сомнение корректора (отметить разобранным); id tN-… — пометка переводчика [[??: …]] (убрать из текста). note — необязательный комментарий человека.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val id = argString(it.params.arguments, "id") ?: return@addTool text("нет id")
                val note = argString(it.params.arguments, "note") ?: ""
                if (id.startsWith("t")) {
                    val err = services.suggestionService.removeTranslatorMarker(p, b, id)
                    if (err == null) {
                        onMutated.mutated(p, b)
                        text("пометка убрана: $id")
                    } else text(err)
                } else {
                    val updated = services.suggestionService.resolveDoubt(p, b, id, note)
                    if (updated == null) text("сомнение не найдено") else {
                        onMutated.mutated(p, b)
                        text("разобрано: $id")
                    }
                }
            }

            addTool(
                name = "resolve_suggestion",
                description = "Решение по предложению корректора: action=apply|reject; id вида cN-M.",
            ) {
                val p = argString(it.params.arguments, "project") ?: return@addTool text("нет project")
                val b = argString(it.params.arguments, "book") ?: return@addTool text("нет book")
                val id = argString(it.params.arguments, "id") ?: return@addTool text("нет id")
                val action = argString(it.params.arguments, "action") ?: return@addTool text("нет action")
                val file = services.suggestionService.loadChapter(
                    p, b, id.substringAfter("c").substringBefore("-").toIntOrNull() ?: return@addTool text("id некорректен"),
                ) ?: return@addTool text("файл корректора не найден")
                val suggestion = file.suggestions.firstOrNull { it.id == id }
                    ?: return@addTool text("предложение не найдено")
                when (action) {
                    "apply" -> when (
                        val outcome = services.suggestionService.apply(
                            p, b, suggestion, services.verifyFor(p),
                        )
                    ) {
                        is ru.sebyanin.booktranslator.core.glossary.SuggestionService.ApplyOutcome.Applied -> {
                            onMutated.mutated(p, b)
                            text("применено: $id")
                        }
                        is ru.sebyanin.booktranslator.core.glossary.SuggestionService.ApplyOutcome.Failed -> text("не применено: ${outcome.reason}")
                    }
                    "reject" -> {
                        services.suggestionService.reject(p, b, id)
                        onMutated.mutated(p, b)
                        text("отклонено: $id")
                    }
                    else -> text("action должен быть apply|reject")
                }
            }
        }
    }

    companion object {
        /** Для теста/экспорта списка инструментов. */
        fun toolNames(): List<String> = listOf(
            "list_projects", "list_books", "get_book_status", "run_stage",
            "list_fragments", "get_fragment_brief", "submit_translation",
            "get_glossary", "get_notes", "resolve_proposals", "search_alignment",
            "read_chapter", "get_critic_suggestions", "resolve_suggestion", "resolve_doubt",
            "run_glossary_revisor", "get_glossary_revision",
        )
    }
}
