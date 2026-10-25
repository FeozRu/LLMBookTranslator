package ru.sebyanin.booktranslator.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.sebyanin.booktranslator.core.mcp.McpServer
import ru.sebyanin.booktranslator.core.mcp.MutationNotifier
import ru.sebyanin.booktranslator.core.mcp.StageLauncher
import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.BookStructure
import ru.sebyanin.booktranslator.core.model.ChapterMeta
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * Живой прогон MCP-потока «агент переводит сам» через Streamable HTTP:
 * list_fragments → get_fragment_brief → submit_translation (многострочный text
 * с JSON-экранированием) — против настоящего сервера на localhost.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpHttpTest {

    private lateinit var services: AppServices
    private lateinit var server: McpServer
    private var port = 0
    private val http = HttpClient.newHttpClient()

    private val mutations = mutableListOf<Pair<String, String?>>()

    @BeforeAll
    fun start() {
        val dataRoot = Files.createTempDirectory("bt-mcp")
        // AppServices.create читает корень из системного свойства
        val prevRoot = System.getProperty("bt.dataRoot")
        System.setProperty("bt.dataRoot", dataRoot.toAbsolutePath().toString())
        services = AppServices.create()
        if (prevRoot == null) System.clearProperty("bt.dataRoot") else System.setProperty("bt.dataRoot", prevRoot)

        // проект + книга из двух текстовых блоков → нарезка
        val store = services.projectStore
        val project = store.createProject("Кукла", "ja", "ru", "ja")
        val blocks = listOf(
            ChapterBlock(BlockKind.TEXT, "古い人形が棚の上に静かに座っていた。"),
            ChapterBlock(BlockKind.TEXT, "彼は小さな店でそれを買った。"),
        )
        val meta = ChapterMeta(index = 1, title = "Глава", file = "chapter-0001.md", blockCount = 2, charCount = BlockText.charCount(blocks))
        val book = BookMeta(slug = "b", title = "Книга", author = "Автор", format = "epub")
        store.writeChapterSource(project.slug, book.slug, meta, blocks)
        store.saveBookMeta(project.slug, book)
        store.saveStructure(project.slug, book.slug, BookStructure(listOf(meta)))
        SplitStage(store).split(project.slug, book.slug, partMaxChars = 5000, fragmentTargetChars = 1700)

        server = McpServer(
            services,
            StageLauncher { _, _, _, _ -> Result.success("test") },
            MutationNotifier { p, b -> mutations += p to b },
        )
        port = ServerSocket(0).use { it.localPort }
        server.start(port, "")
    }

    @AfterAll
    fun stop() {
        server.stop()
    }

    private fun callTool(name: String, args: String): String {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "HTTP-статус tools/call $name: ${response.body().take(400)}")
        return response.body()
    }

    @Test
    fun `полный цикл - очередь бриф приём`() {
        val slug = services.projectStore.listProjects().first().slug

        val list = callTool("list_fragments", """{"project":"$slug","book":"b","stage":"draft"}""")
        assertTrue(list.contains("pending"), "очередь работы: $list")

        val fragId = SplitStage.loadManifest(services.projectStore.bookDir(slug, "b"))
            .allFragments().first().id
        val brief = callTool("get_fragment_brief", """{"project":"$slug","book":"b","fragment":"$fragId"}""")
        assertTrue(brief.contains("ИСХОДНИК"), "бриф с заданием: ${brief.take(200)}")
        assertTrue(brief.contains("古い人形"))

        // Многострочный text с переносами и кавычками — честный JSON-разбор аргументов
        val line1 = "Старая кукла тихо сидела на полке. «как живая»"
        val line2 = "Он купил её в маленькой лавке."
        val submit = callTool(
            "submit_translation",
            """{"project":"$slug","book":"b","fragment":"$fragId","text":"$line1\n$line2","source":"test-agent"}""",
        )
        assertTrue(submit.contains("принято"), "сабмит с многострочным текстом: $submit")

        // Мутация дошла до колбэка (UI-сигнал)
        assertEquals(listOf(slug to "b"), mutations)

        // Повторный list_fragments — очередь пуста
        val listAfter = callTool("list_fragments", """{"project":"$slug","book":"b","stage":"draft"}""")
        assertTrue(listAfter.contains("готово"), "очередь опустела: $listAfter")
    }
}
