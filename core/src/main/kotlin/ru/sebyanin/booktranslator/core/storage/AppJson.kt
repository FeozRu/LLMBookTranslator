package ru.sebyanin.booktranslator.core.storage

import kotlinx.serialization.json.Json

/** Единый JSON-конфиг приложения: человекочитаемый, устойчивый к новым полям. */
val AppJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    // null-поля (tool_calls и пр.) не пишутся в API-запросы: строгие провайдеры
    // отвергают "tool_calls": [] у сообщений без вызовов
    explicitNulls = false
}
