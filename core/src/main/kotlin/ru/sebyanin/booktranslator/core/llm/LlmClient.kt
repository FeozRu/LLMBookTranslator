package ru.sebyanin.booktranslator.core.llm

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.ApiKind
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

open class LlmException(
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
 * HTTP 402 Payment Required — на счету провайдера нет средств. Хуже обычной терминальной:
 * продолжать этап бессмысленно не только ретраем, но и переходом к следующим фрагментам/главам —
 * каждый вызов упадёт так же и провалит весь остаток книги. Поэтому этапы обязаны пробрасывать
 * её наверх целиком (полная остановка прогона), а AppState ставит понятный статус.
 */
class PaymentRequiredException(message: String) : LlmException(message, httpStatus = 402, terminal = true)

/**
 * HTTP-клиент LLM, говорящий обоих протоколов (выбор — по профилю):
 * OpenAI-совместимый chat/completions и Anthropic Messages. Домен (ApiMessage,
 * LlmResponse) общий — этапы и tool-loop протокола не знают. Один экземпляр
 * на приложение; таймаут берётся из профиля (обёртка withTimeout), ретраи делает вызывающий этап.
 */
class LlmClient : Closeable {

    private val http = HttpClient(CIO) {
        expectSuccess = false
        engine {
            // Движковый лимит CIO по умолчанию — 15 с: длинная генерация (30–60 с тишины
            // в сокете) убивалась им до всякого withTimeout из профиля. 0 = без лимита.
            requestTimeout = 0
        }
    }

    suspend fun complete(profile: LlmProfile, messages: List<ApiMessage>, tools: List<ToolSpec>? = null): LlmResponse {
        val started = System.currentTimeMillis()
        val result = try {
            when (profile.api) {
                ApiKind.ANTHROPIC -> completeAnthropic(profile, messages, tools)
                ApiKind.OPENAI -> completeOpenAi(profile, messages, tools)
            }
        } catch (e: TimeoutCancellationException) {
            throw LlmException("Таймаут ${profile.requestTimeoutSec} с при вызове ${profile.name}")
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Сбой вызова ${profile.name} (${e::class.simpleName}: ${e.message})", e)
        }
        AppLog.info(
            "llm",
            "${profile.name}/${profile.model}: ${System.currentTimeMillis() - started} мс, " +
                "токены ${result.promptTokens ?: "-"}/${result.completionTokens ?: "-"}" +
                if (result.wantsTools) ", tool-вызовов: ${result.toolCalls.size}" else "",
        )
        return result
    }

    /** Проверка связи: минимальный запрос, ожидаем короткий ответ модели. Полный текст ошибки — в журнал. */
    suspend fun testConnection(profile: LlmProfile): String {
        try {
            val response = complete(
                profile.copy(maxTokens = minOf(profile.maxTokens, 64)),
                listOf(ApiMessage.user("Это проверка связи. Ответь ровно одним словом: OK")),
            )
            return response.content.trim().ifEmpty { "(пустой ответ)" }
        } catch (e: Exception) {
            AppLog.error("llm", "Проверка связи «${profile.name}»: ${e.message}")
            throw e
        }
    }

    private suspend fun completeOpenAi(
        profile: LlmProfile,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>?,
    ): LlmResponse {
        val request = ChatCompletionRequest(
            model = profile.model,
            messages = messages,
            temperature = profile.temperature,
            maxTokens = profile.maxTokens,
            tools = tools?.map { ApiTool(function = ApiToolFunction(it.name, it.description, it.parametersSchema)) },
            thinking = if (profile.noThinking) ThinkingParam("disabled") else null,
        )
        val body = postForBody(
            profile,
            profile.baseUrl.trim().trimEnd('/') + "/chat/completions",
            buildMap {
                if (profile.apiKey.isNotBlank()) put("Authorization", "Bearer ${profile.apiKey}")
            },
            AppJson.encodeToString(ChatCompletionRequest.serializer(), request),
        )
        val parsed = AppJson.decodeFromString(ChatCompletionResponse.serializer(), body)
        val choice = parsed.choices.firstOrNull()
            ?: throw LlmException("Пустой ответ (нет choices) от ${profile.name}")
        if (choice.finishReason == "length") {
            throw LlmException.terminal(
                "Ответ обрезан лимитом max_tokens=${profile.maxTokens} (finish_reason=length). " +
                    "Поднимите «Max tokens» в модели «${profile.name}» — reasoning-модели тратят квоту на рассуждения.",
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
        return LlmResponse(
            content = msg?.content.orEmpty(),
            toolCalls = toolCalls,
            // Сырые tool_calls обязаны уезжать обратно в сообщении assistant —
            // без них строгие провайдеры отклоняют «tool_calls: []» (урок DeepSeek)
            rawToolCalls = msg?.tool_calls.orEmpty(),
            promptTokens = parsed.usage?.promptTokens,
            completionTokens = parsed.usage?.completionTokens,
        )
    }

    private suspend fun completeAnthropic(
        profile: LlmProfile,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>?,
    ): LlmResponse {
        val request = AnthropicWire.buildRequest(profile, messages, tools)
        val body = postForBody(
            profile,
            AnthropicWire.endpointUrl(profile.baseUrl),
            buildMap {
                put("anthropic-version", AnthropicWire.API_VERSION)
                if (profile.apiKey.isNotBlank()) put("x-api-key", profile.apiKey)
            },
            AppJson.encodeToString(AnthropicRequest.serializer(), request),
        )
        return AnthropicWire.parseResponse(body, profile)
    }

    private suspend fun postForBody(profile: LlmProfile, url: String, headers: Map<String, String>, body: String): String {
        val response = withTimeout(profile.requestTimeoutSec * 1000L) {
            http.post(url) {
                headers.forEach { (name, value) -> header(name, value) }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            val code = response.status.value
            if (code == 402) {
                AppLog.error("llm", "HTTP 402 от ${profile.name}: ${text.take(500)}")
                throw PaymentRequiredException(
                    "у «${profile.name}» закончились средства (HTTP 402 Payment Required)",
                )
            }
            throw LlmException(
                when {
                    code in 400..499 && text.contains("temperature", ignoreCase = true) ->
                        "HTTP $code от ${profile.name}: модель не принимает «Температуру» — очистите это поле " +
                            "в карточке модели (пусто = параметр не отправляется). Ответ: ${text.take(300)}"
                    code in 400..499 && text.contains("thinking", ignoreCase = true) ->
                        "HTTP $code от ${profile.name}: модель не принимает параметр «Рассуждения» — " +
                            "переключите его в «Включены» в карточке модели. Ответ: ${text.take(300)}"
                    else -> "HTTP $code от ${profile.name}: ${text.take(500)}"
                },
                httpStatus = code,
                terminal = code in 400..499,
            )
        }
        return text
    }

    override fun close() = http.close()
}
