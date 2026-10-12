package ru.sebyanin.booktranslator.core.llm

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.LlmProfile
import ru.sebyanin.booktranslator.core.storage.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.Closeable

class LlmException(
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
    /** Терминальная ошибка: повтор с теми же параметрами даст тот же результат (4xx, обрезание по max_tokens). */
    val terminal: Boolean = false,
) : Exception(message, cause) {
    /** Клиентские ошибки (4xx) не имеют смысла ретраить — повтор даёт тот же ответ. */
    val isClientError: Boolean get() = httpStatus != null && httpStatus in 400..499

    companion object {
        fun terminal(message: String) = LlmException(message, terminal = true)
    }
}

/**
 * Клиент OpenAI-совместимого протокола. Один экземпляр на приложение.
 * Таймаут берётся из профиля (обёртка withTimeout), ретраи делает вызывающий этап.
 */
class OpenAiCompatClient : Closeable {

    private val http = HttpClient(CIO) {
        expectSuccess = false
        engine {
            // Движковый лимит CIO по умолчанию — 15 с: длинная генерация (30–60 с тишины
            // в сокете) убивалась им до всякого withTimeout из профиля. 0 = без лимита.
            requestTimeout = 0
        }
    }

    suspend fun complete(profile: LlmProfile, messages: List<ApiMessage>, tools: List<ToolSpec>? = null): LlmResponse {
        val url = endpointUrl(profile.baseUrl)
        val request = ChatCompletionRequest(
            model = profile.model,
            messages = messages,
            temperature = profile.temperature,
            maxTokens = profile.maxTokens,
            tools = tools?.map { ApiTool(function = ApiToolFunction(it.name, it.description, it.parametersSchema)) },
        )
        val started = System.currentTimeMillis()
        try {
            val response = withTimeout(profile.requestTimeoutSec * 1000L) {
                http.post(url) {
                    header("Authorization", "Bearer ${profile.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(AppJson.encodeToString(ChatCompletionRequest.serializer(), request))
                }
            }
            val body = response.bodyAsText()
            if (response.status != HttpStatusCode.OK) {
                throw LlmException(
                    "HTTP ${response.status.value} от ${profile.name}: ${body.take(500)}",
                    httpStatus = response.status.value,
                    terminal = response.status.value in 400..499,
                )
            }
            val parsed = AppJson.decodeFromString(ChatCompletionResponse.serializer(), body)
            val choice = parsed.choices.firstOrNull()
                ?: throw LlmException("Пустой ответ (нет choices) от ${profile.name}")
            if (choice.finishReason == "length") {
                throw LlmException.terminal(
                    "Ответ обрезан лимитом max_tokens=${profile.maxTokens} (finish_reason=length). " +
                        "Поднимите «Max tokens» в профиле «${profile.name}» — reasoning-модели тратят квоту на рассуждения.",
                )
            }
            val msg = choice.message
            val toolCalls = msg?.tool_calls.orEmpty().map {
                LlmToolCall(
                    id = it.id,
                    name = it.function.name ?: "",
                    argumentsJson = it.function.arguments.ifBlank { "{}" },
                )
            }
            val result = LlmResponse(
                content = msg?.content.orEmpty(),
                toolCalls = toolCalls,
                // Сырые tool_calls обязаны уезжать обратно в сообщении assistant —
                // без них строгие провайдеры отклоняют «tool_calls: []» (урок DeepSeek)
                rawToolCalls = msg?.tool_calls.orEmpty(),
                promptTokens = parsed.usage?.promptTokens,
                completionTokens = parsed.usage?.completionTokens,
            )
            AppLog.info(
                "llm",
                "${profile.name}/${profile.model}: ${System.currentTimeMillis() - started} мс, " +
                    "токены ${result.promptTokens ?: "-"}/${result.completionTokens ?: "-"}" +
                    if (result.wantsTools) ", tool-вызовов: ${result.toolCalls.size}" else "",
            )
            return result
        } catch (e: TimeoutCancellationException) {
            throw LlmException("Таймаут ${profile.requestTimeoutSec} с при вызове ${profile.name}")
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Сбой вызова ${profile.name} (${e::class.simpleName}: ${e.message})", e)
        }
    }

    /** Проверка связи: минимальный запрос, ожидаем короткий ответ модели. */
    suspend fun testConnection(profile: LlmProfile): String {
        val response = complete(
            profile.copy(temperature = 0.0, maxTokens = minOf(profile.maxTokens, 64)),
            listOf(ApiMessage.user("Это проверка связи. Ответь ровно одним словом: OK")),
        )
        return response.content.trim().ifEmpty { "(пустой ответ)" }
    }

    private fun endpointUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/') + "/chat/completions"

    override fun close() = http.close()
}
