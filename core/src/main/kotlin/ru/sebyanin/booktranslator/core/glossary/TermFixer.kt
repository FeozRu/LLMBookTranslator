package ru.sebyanin.booktranslator.core.glossary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.sebyanin.booktranslator.core.llm.AgentRunner
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.LlmProfile
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files
import java.nio.file.Path

/**
 * Слой 2 замены термина (опциональный LLM-прогон): по фрагментам, где слово
 * встречается в любой форме (цели даёт TermReplacer.scan), модель заменяет
 * грамматические формы старого варианта на формы нового. Каждый фрагмент —
 * отдельный вызов и отдельная атомарная запись: остановка этапа не теряет
 * готовое, сбой одного фрагмента не мешает остальным (можно повторить прогон).
 * Полный перезапуск редактуры не нужен и не делается.
 */
class TermFixer(
    private val store: ProjectStore,
    private val promptStore: PromptStore,
    client: LlmClient,
    private val settingsProvider: () -> AppSettings,
) {
    private val runner = AgentRunner(client)

    data class TermFixSummary(
        val fragments: Int,
        val changed: Int,
        val unchanged: Int,
        val failed: Int,
        val promptTokens: Int,
        val completionTokens: Int,
    )

    suspend fun run(
        projectSlug: String,
        bookSlug: String,
        from: String,
        to: String,
        targetFragmentIds: List<String>,
        verify: VerifyEngine,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): TermFixSummary {
        val profile = settingsProvider().profileFor(AgentRole.TERM_FIX)
            ?: error("Не настроена модель LLM для роли «${AgentRole.TERM_FIX.title}»")
        val template = promptStore.resolve(projectSlug, AgentRole.TERM_FIX)
        val fragsDir = store.bookDir(projectSlug, bookSlug).resolve("work").resolve("fragments")

        var changed = 0
        var unchanged = 0
        var failed = 0
        var promptTokens = 0
        var completionTokens = 0
        var done = 0
        val total = targetFragmentIds.size
        targetFragmentIds.forEach { id ->
            val ff = runCatching {
                AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(fragsDir.resolve("$id.json")))
            }.getOrNull()
            if (ff == null) {
                failed++
                AppLog.warn("termfix", "Фрагмент $id не читается — пропущен")
            } else {
                val inEdited = ff.editedBlocks.isNotEmpty()
                val blocks = if (inEdited) ff.editedBlocks else ff.draftBlocks
                when {
                    blocks.isEmpty() -> unchanged++ // переводить ещё нечего
                    else -> when (val r = applyModel(ff, blocks, inEdited, from, to, template, profile, verify, fragsDir)) {
                        is FragmentResult.Failed -> {
                            failed++
                            AppLog.warn("termfix", "Фрагмент $id не обработан: ${r.reason}")
                        }
                        is FragmentResult.Unchanged -> {
                            unchanged++
                            // Прогон «форм не нашлось» тоже потратил токены — учитываем
                            promptTokens += r.promptTokens
                            completionTokens += r.completionTokens
                        }
                        is FragmentResult.Changed -> {
                            changed++
                            promptTokens += r.promptTokens
                            completionTokens += r.completionTokens
                        }
                    }
                }
            }
            done++
            onProgress(done, total)
        }
        AppLog.info(
            "termfix",
            "Прогон форм «$from» → «$to»: изменено $changed, без изменений $unchanged, ошибок $failed (токены $promptTokens/$completionTokens)",
        )
        return TermFixSummary(total, changed, unchanged, failed, promptTokens, completionTokens)
    }

    /** Итог обработки одного фрагмента. */
    private sealed class FragmentResult {
        data class Failed(val reason: String) : FragmentResult()
        data class Unchanged(val promptTokens: Int = 0, val completionTokens: Int = 0) : FragmentResult()
        data class Changed(val promptTokens: Int, val completionTokens: Int) : FragmentResult()
    }

    /** Вызов модели по одному фрагменту, verify, запись. Никогда не бросает — сбой это [FragmentResult.Failed]. */
    private suspend fun applyModel(
        ff: FragmentFile,
        blocks: List<String>,
        inEdited: Boolean,
        from: String,
        to: String,
        template: String,
        profile: LlmProfile,
        verify: VerifyEngine,
        fragsDir: Path,
    ): FragmentResult {
        val outcome = runCatching {
            val result = runner.run(
                profile = profile,
                userPrompt = buildPrompt(template, from, to, blocks),
                tools = null,
                sourceLabel = "termfix:${ff.id}",
            )
            result to parseBlocks(result.text, blocks.size)
        }.getOrNull() ?: return FragmentResult.Failed("вызов модели не удался")
        val newBlocks = outcome.second
            ?: return FragmentResult.Failed("ответ не разобран (нужен JSON {\"blocks\":[…]})")
        if (newBlocks == blocks) {
            return FragmentResult.Unchanged(outcome.first.promptTokens, outcome.first.completionTokens)
        }
        val vr = verify.verifyTranslation(ff.srcBlocks, newBlocks, VerifyEngine.termReplaceOptions(stripMarkers = !inEdited))
        if (!vr.ok) return FragmentResult.Failed("verify ${vr.summary()}")
        val updated = if (inEdited) ff.copy(editedBlocks = newBlocks, verifyEdited = vr.summary())
        else ff.copy(draftBlocks = newBlocks, verifyDraft = vr.summary())
        ProjectStore.atomicWrite(fragsDir.resolve("${ff.id}.json"), AppJson.encodeToString(FragmentFile.serializer(), updated))
        return FragmentResult.Changed(outcome.first.promptTokens, outcome.first.completionTokens)
    }

    /** Промпт прогона: шаблон роли + FROM/TO/SOURCE (нумерованные блоки фрагмента). */
    internal fun buildPrompt(template: String, from: String, to: String, blocks: List<String>): String = template
        .replace("{{FROM}}", from.trim())
        .replace("{{TO}}", to.trim())
        .replace("{{SOURCE}}", blocks.mapIndexed { i, b -> "${i + 1}. ${b.replace("\n", " ")}" }.joinToString("\n"))

    companion object {
        private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

        /**
         * Разбор ответа: {"blocks": ["…", …]} — устойчив к ```-заборам и тексту
         * вокруг; фолбэк — голый массив строк. Число блоков обязано совпасть,
         * иначе null (фрагмент не записывается).
         */
        fun parseBlocks(raw: String, expectedSize: Int): List<String>? {
            // Только СТРОКОВЫЕ примитивы: contentOrNull отсеивает JsonNull, а не числа —
            // без isString массив [1, 2] притворился бы блоками
            fun strings(el: JsonElement?): List<String>? = when (el) {
                is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                else -> null
            }
            val objStart = raw.indexOf('{')
            val objEnd = raw.lastIndexOf('}')
            if (objStart in 0 until objEnd) {
                runCatching {
                    val o = lenientJson.parseToJsonElement(raw.substring(objStart, objEnd + 1))
                    if (o is JsonObject) strings(o["blocks"]) else null
                }.getOrNull()?.let { if (it.size == expectedSize) return it }
            }
            val arrStart = raw.indexOf('[')
            val arrEnd = raw.lastIndexOf(']')
            if (arrStart in 0 until arrEnd) {
                runCatching {
                    strings(lenientJson.parseToJsonElement(raw.substring(arrStart, arrEnd + 1)))
                }.getOrNull()?.let { if (it.size == expectedSize) return it }
            }
            return null
        }
    }
}
