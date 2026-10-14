package ru.sebyanin.booktranslator.core

import com.sun.net.httpserver.HttpServer
import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.llm.ApiMessage
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.llm.PaymentRequiredException
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.BookStructure
import ru.sebyanin.booktranslator.core.model.ChapterMeta
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.LlmModel
import ru.sebyanin.booktranslator.core.model.LlmProvider
import ru.sebyanin.booktranslator.core.pipeline.CriticStage
import ru.sebyanin.booktranslator.core.pipeline.DraftStage
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.BlockKind
import ru.sebyanin.booktranslator.core.storage.ChapterBlock
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * HTTP 402 (нет средств у провайдера) — не рядовой провал фрагмента: каждый следующий
 * вызов упадёт так же. Клиент отображает его в PaymentRequiredException, а этапы
 * (черновик здесь, корректор, редактура — тот же шаблон) обязаны остановиться ЦЕЛИКОМ.
 * Провайдер — локальный HTTP-сервер, всегда отвечающий 402.
 */
class PaymentStopTest {

    private fun start402Server(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = "{\"error\":{\"message\":\"Insufficient Balance\"}}".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(402, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun settings(baseUrl: String) = AppSettings(
        providers = listOf(LlmProvider(name = "local402", baseUrl = baseUrl)),
        models = listOf(
            LlmModel(name = "test", provider = "local402", model = "m", requestTimeoutSec = 10),
        ),
    )

    @Test
    fun `402 от провайдера превращается в PaymentRequiredException`() {
        val server = start402Server()
        try {
            val profile = settings("http://127.0.0.1:${server.address.port}/v1")
                .profileFor(AgentRole.DRAFT)!!
            val e = assertThrows<PaymentRequiredException> {
                runBlocking {
                    LlmClient().use { it.complete(profile, listOf(ApiMessage.user("тест"))) }
                }
            }
            assertEquals(402, e.httpStatus)
            assertTrue(e.terminal)
            assertTrue(e.message!!.contains("закончились средства"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `этап при 402 останавливается целиком, не помечая фрагменты failed`() {
        val server = start402Server()
        try {
            val tmp = Files.createTempDirectory("bt-402")
            val store = ProjectStore { tmp }
            val project = store.createProject("Сороковой2", "ja", "ru", "ja")
            val blocks = listOf(
                ChapterBlock(BlockKind.TEXT, "Сома открыл глаза. Под потолком кружилась паутина."),
                ChapterBlock(BlockKind.TEXT, "— Реплика, — сказал он, глядя на тонкую нить."),
            )
            val meta = ChapterMeta(
                index = 1,
                title = "Паутина",
                file = "chapter-0001.md",
                blockCount = blocks.size,
                charCount = ru.sebyanin.booktranslator.core.storage.BlockText.charCount(blocks),
            )
            val book = BookMeta(slug = "b", title = "Книга", author = "Автор", format = "epub")
            store.writeChapterSource(project.slug, book.slug, meta, blocks)
            store.saveBookMeta(project.slug, book)
            store.saveStructure(project.slug, book.slug, BookStructure(listOf(meta)))
            SplitStage(store).split(project.slug, book.slug, partMaxChars = 5000, fragmentTargetChars = 1700)
            val promptStore = PromptStore({ tmp }) { slug -> store.projectDir(slug) }
            val knowledge = KnowledgeService(store)
            val appSettings = settings("http://127.0.0.1:${server.address.port}/v1")

            runBlocking {
                // Черновик: единственный текстовый фрагмент должен уйти в LLM и получить 402
                LlmClient().use { client ->
                    val e = assertThrows<PaymentRequiredException> {
                        DraftStage(store, promptStore, knowledge, client, { appSettings }, { VerifyEngine("ja") })
                            .run(project.slug, book.slug)
                    }
                    assertEquals(402, e.httpStatus)

                    val bookDir = store.bookDir(project.slug, book.slug)
                    val frag = SplitStage.loadManifest(bookDir).allFragments().first()
                    assertFalse(frag.draft.isFailed, "фрагмент не должен быть помечен failed — остановка, а не провал")

                    // Корректор: вписываем черновик и убеждаемся, что его прогон тоже останавливается
                    val fragId = frag.id
                    val fpath = bookDir.resolve("work").resolve("fragments").resolve("$fragId.json")
                    val file = AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(fpath))
                    Files.writeString(
                        fpath,
                        AppJson.encodeToString(FragmentFile.serializer(), file.copy(draftBlocks = file.srcBlocks)),
                    )

                    val e2 = assertThrows<PaymentRequiredException> {
                        CriticStage(store, promptStore, knowledge, client, { appSettings })
                            .run(project.slug, book.slug)
                    }
                    assertEquals(402, e2.httpStatus)
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
