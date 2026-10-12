package ru.sebyanin.booktranslator.core.llm

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.LlmProfile

/** Результат прогона агента: финальный текст + служебная статистика. */
data class AgentResult(
    val text: String,
    val toolCallsTotal: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

/**
 * Прогон агента с tool-loop'ом: отправляет бриф, исполняет tool-вызовы
 * (in-process, read-only), возвращает финальный текст. Лимиты — страховка
 * от зацикливания; каждый вызов инструмента пишется в журнал.
 */
class AgentRunner(private val client: LlmClient) {

    suspend fun run(
        profile: LlmProfile,
        userPrompt: String,
        tools: List<ToolSpec>?,
        executor: (suspend (LlmToolCall) -> String)? = null,
        maxToolCalls: Int = 10,
        sourceLabel: String = "",
    ): AgentResult {
        val useTools = tools != null && executor != null
        val messages = mutableListOf(
            ApiMessage.system(PromptDefaults.SYSTEM),
            ApiMessage.user(userPrompt),
        )
        var toolBudget = if (useTools) maxToolCalls else 0
        var totalCalls = 0
        var promptTokens = 0
        var completionTokens = 0

        while (true) {
            val sendTools = if (useTools && toolBudget > 0) tools else null
            val resp = client.complete(profile, messages, sendTools)
            promptTokens += resp.promptTokens ?: 0
            completionTokens += resp.completionTokens ?: 0

            if (!resp.wantsTools || !useTools) {
                return AgentResult(resp.content, totalCalls, promptTokens, completionTokens)
            }

            messages += ApiMessage(role = "assistant", content = resp.content.ifBlank { null }, tool_calls = resp.rawToolCalls)

            var budgetExhausted = false
            for (call in resp.toolCalls) {
                if (toolBudget <= 0) {
                    // Протокол: у КАЖДОГО tool_call_id обязан быть ответ — иначе провайдер
                    // отклоняет следующий запрос («insufficient tool messages»). Шлём заглушку.
                    budgetExhausted = true
                    messages += ApiMessage.tool(
                        toolCallId = call.id,
                        name = call.name,
                        content = "Инструмент недоступен: лимит вызовов исчерпан.",
                    )
                    continue
                }
                toolBudget--
                totalCalls++
                val result = runCatching { executor!!(call) }
                    .getOrElse {
                        AppLog.warn("tools", "Ошибка инструмента ${call.name}: ${it.message}")
                        "Ошибка инструмента: ${it.message}"
                    }
                AppLog.info("tools", "$sourceLabel → ${call.name}(${call.argumentsJson.take(120)}) → ${result.length} зн.")
                messages += ApiMessage.tool(toolCallId = call.id, name = call.name, content = result)
            }
            if (budgetExhausted) toolBudget = 0

            if (toolBudget <= 0) {
                messages += ApiMessage.user(
                    "Лимит вызовов инструментов исчерпан. Больше не вызывай их — заверши задание с тем, что уже есть.",
                )
                // Спеки инструментов передаём и здесь: Anthropic отклоняет запрос,
                // у которого в истории tool_use/tool_result, а tools не задан (400).
                val final = client.complete(profile, messages, tools)
                promptTokens += final.promptTokens ?: 0
                completionTokens += final.completionTokens ?: 0
                return AgentResult(final.content, totalCalls, promptTokens, completionTokens)
            }
        }
    }
}
