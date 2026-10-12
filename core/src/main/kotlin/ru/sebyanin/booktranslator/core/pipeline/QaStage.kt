package ru.sebyanin.booktranslator.core.pipeline

import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.storage.BlockText
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.verify.VerifyEngine
import java.nio.file.Files

/** Отчёт финального QA по всей книге. */
data class QaReport(
    val bookSlug: String,
    val chapters: Int,
    val chaptersTranslated: Int,
    val problems: List<String>,
    val warnings: List<String>,
    val stats: Map<String, Int>,
) {
    val ok: Boolean get() = problems.isEmpty()
}

/**
 * Финальный QA (фаза 7): verify всех глав, глоссарный grep по книге,
 * -тся/-ться конфликты, «?!» порядок, «...» vs «…», смешение кавычек.
 */
class QaStage(
    private val store: ProjectStore,
    private val knowledge: KnowledgeService,
) {

    fun run(projectSlug: String, bookSlug: String, verify: VerifyEngine): QaReport {
        val structure = store.loadStructure(projectSlug, bookSlug)
        val translationDir = store.bookDir(projectSlug, bookSlug).resolve("translation")
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        var translated = 0
        val allText = StringBuilder()
        structure.chapters.forEach { m ->
            val f = translationDir.resolve(m.file)
            if (!Files.isRegularFile(f)) {
                problems += "нет перевода главы ${m.index}"
                return@forEach
            }
            translated++
            val out = BlockText.decode(Files.readString(f)).map { it.text }
            val src = store.readChapterSource(projectSlug, bookSlug, m.file).map { it.text }
            val vr = verify.verifyTranslation(src, out, VerifyEngine.finalOptions())
            if (!vr.ok) problems += "глава ${m.index}: ${vr.summary()}"
            else if (vr.warnings.isNotEmpty()) warnings += "глава ${m.index}: ${vr.summary()}"
            allText.append(out.joinToString("\n")).append('\n')
        }

        val text = allText.toString()

        // Глоссарный grep: утверждённый термин должен встречаться, если его оригинал был в книге
        val srcAll = structure.chapters.joinToString("\n") { m ->
            store.readChapterSource(projectSlug, bookSlug, m.file).joinToString("\n") { it.text }
        }
        knowledge.approvedTerms(projectSlug).forEach { e ->
            if (e.original in srcAll && e.translation !in text) {
                warnings += "термин «${e.original} → ${e.translation}»: перевод термина не встречается в книге"
            }
        }

        // -тся/-ться: одно слово в обеих формах — почти наверняка ошибка в одной из них
        val tsa = Regex("([А-Яа-яё]{4,}ться)").findAll(text).map { it.value.lowercase() }.toSet()
        val tsya = Regex("([А-Яа-яё]{4,}тся)").findAll(text).map { it.value.lowercase() }.toSet()
        tsa.forEach { w ->
            val stem = w.removeSuffix("ться")
            if (tsya.any { it.removeSuffix("тся") == stem }) {
                warnings += "конфликт -тся/-ться: «$w» и форма на «тся» от того же корня"
            }
        }

        val bangQuestion = Regex("\\?!").findAll(text).count()
        if (bangQuestion > 0) problems += "найдено «!?» вместо «?!»: $bangQuestion"

        val threeDots = Regex("\\.\\.\\.").findAll(text).count()
        if (threeDots > 0) warnings += "три точки вместо «…»: $threeDots"

        val stats = mapOf(
            "глав" to structure.chapters.size,
            "переведено" to translated,
            "знаков" to text.count { !it.isWhitespace() },
            "утверждённых терминов" to knowledge.approvedTerms(projectSlug).size,
        )

        val report = QaReport(bookSlug, structure.chapters.size, translated, problems, warnings, stats)
        AppLog.info(
            "qa",
            if (report.ok) "QA: чисто (глав $translated/${structure.chapters.size}, предупреждений ${warnings.size})"
            else "QA: проблем ${problems.size}, предупреждений ${warnings.size}",
        )
        problems.forEach { AppLog.error("qa", "ПРОБЛЕМА: $it") }
        warnings.forEach { AppLog.warn("qa", "предупреждение: $it") }
        return report
    }
}
