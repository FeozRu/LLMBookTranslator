package ru.sebyanin.booktranslator.core.model

/**
 * Роли конвейера. Идентификаторы стабильны — используются в настройках,
 * привязках «роль → профиль LLM», промптах и MCP.
 */
enum class AgentRole(val id: String, val title: String, val description: String) {
    DRAFT("draft", "Переводчик", "Черновой дословный перевод с тегами тона и пометками контекста"),
    EDITOR("editor", "Редактор", "Литературная отделка черновика без оригинала"),
    CRITIC("critic", "Корректор", "Вычитка по паре «перевод + оригинал», предложения правок"),
    REVISOR("revisor", "Ревизор глоссария", "Прогон очереди терминов по тексту: чистка мусора, дублей и расхождений транскрипции"),
    FALLBACK("fallback", "Одиночный перевод", "Одноагентный фоллбэк: перевод+отделка одним проходом"),
    ;

    companion object {
        fun byId(id: String): AgentRole? = entries.firstOrNull { it.id == id }
    }
}
