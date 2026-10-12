package ru.sebyanin.booktranslator.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogLevel { INFO, WARN, ERROR }

data class LogEvent(
    val time: Instant = Instant.now(),
    val level: LogLevel = LogLevel.INFO,
    val source: String,
    val message: String,
) {
    fun formattedTime(): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(time)
}

/**
 * Журнал приложения: единая шина событий конвейера и LLM-вызовов.
 * UI подписывается на [events]; кольцевой буфер ограничен.
 */
object AppLog {
    private const val MAX_EVENTS = 5000

    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events

    fun info(source: String, message: String) = post(LogLevel.INFO, source, message)
    fun warn(source: String, message: String) = post(LogLevel.WARN, source, message)
    fun error(source: String, message: String) = post(LogLevel.ERROR, source, message)

    fun clear() {
        _events.value = emptyList()
    }

    /** Все события одной строкой — для копирования в буфер/файл. */
    fun plainText(): String =
        _events.value.joinToString("\n") { e ->
            "${e.formattedTime()} [${e.level}] ${e.source}: ${e.message}"
        }

    private fun post(level: LogLevel, source: String, message: String) {
        _events.update { old ->
            val next = old + LogEvent(level = level, source = source, message = message)
            if (next.size > MAX_EVENTS) next.subList(next.size - MAX_EVENTS, next.size) else next
        }
    }
}
