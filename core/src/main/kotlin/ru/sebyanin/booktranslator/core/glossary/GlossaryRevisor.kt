package ru.sebyanin.booktranslator.core.glossary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import ru.sebyanin.booktranslator.core.glossary.GlossaryRevision.findRedundant
import ru.sebyanin.booktranslator.core.glossary.GlossaryRevision.findRedundantApproved
import ru.sebyanin.booktranslator.core.llm.AgentRunner
import ru.sebyanin.booktranslator.core.llm.LlmClient
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.FragmentFile
import ru.sebyanin.booktranslator.core.model.GlossaryEntry
import ru.sebyanin.booktranslator.core.model.GlossaryRevisionDecision
import ru.sebyanin.booktranslator.core.model.GlossaryRevisionFile
import ru.sebyanin.booktranslator.core.model.GlossaryRevisionPart
import ru.sebyanin.booktranslator.core.pipeline.BriefBuilder
import ru.sebyanin.booktranslator.core.pipeline.DraftStage
import ru.sebyanin.booktranslator.core.pipeline.SplitStage
import ru.sebyanin.booktranslator.core.storage.AppJson
import ru.sebyanin.booktranslator.core.storage.PromptStore
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * LLM-ревизор глоссария: прогоняет записи по тексту книги пакетами (~30 записей)
 * и предлагает чистку, которую не видит механическая ревизия (бредовые варианты,
 * расхождения транскрипции, мусорные термины, дубли написаний). Два скоупа:
 * очередь предложений (proposed) и утверждённый канон (approved). Вердикты
 * keep/drop/fix/split складываются в <projectDir>/glossary-revision.json и
 * НИКОГДА не применяются сами — срабатывает только кнопка человека.
 */
class GlossaryRevisor(
    private val store: ProjectStore,
    private val promptStore: PromptStore,
    private val knowledge: KnowledgeService,
    private val client: LlmClient,
    private val settingsProvider: () -> AppSettings,
) {
    private val runner = AgentRunner(client)

    data class RevisorSummary(
        val reviewed: Int,
        val batches: Int,
        val drop: Int,
        val fix: Int,
        val keep: Int,
    )

    /** Вердикт модели по одной записи очереди (после нормализации вердикта). */
    data class RawVerdict(
        val original: String,
        val verdict: String,
        val reason: String,
        val suggested: String,
        val parts: List<GlossaryRevisionPart> = emptyList(),
    )

    data class ParsedVerdicts(
        val valid: List<RawVerdict>,
        /** Оригиналы отброшенных вердиктов: нет в очереди или verdict не из списка. */
        val ignoredOriginals: List<String>,
    )

    suspend fun run(
        projectSlug: String,
        bookSlug: String,
        scope: String = SCOPE_QUEUE,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): RevisorSummary {
        require(scope == SCOPE_QUEUE || scope == SCOPE_CANON) { "неизвестный скоуп ревизора: $scope" }
        val settings = settingsProvider()
        val profile = settings.profileFor(AgentRole.REVISOR)
            ?: error("Не настроена модель LLM для роли «${AgentRole.REVISOR.title}»")
        val glossary = knowledge.glossary(projectSlug)
        // Прогон по очереди предложений (proposed) или по утверждённому канону (approved)
        val targets = glossary.filter { if (scope == SCOPE_CANON) it.isApproved else it.status == "proposed" }

        // Зависшие решения: запись больше не ни в очереди, ни в каноне — pending не действует
        var decisions = store.loadGlossaryRevision(projectSlug).decisions
        val validOriginals = glossary.filter { it.status == "proposed" || it.isApproved }.map { it.original }.toSet()
        val targetOriginals = targets.map { it.original }.toSet()
        val stale = decisions.filter { it.status == "pending" && it.original !in validOriginals }
        if (stale.isNotEmpty()) {
            decisions = dropStalePending(decisions, validOriginals)
            store.saveGlossaryRevision(projectSlug, GlossaryRevisionFile(decisions = decisions))
            AppLog.info("revisor", "Зависших решений снято: ${stale.size} (записей больше нет в очереди и каноне)")
        }
        if (targets.isEmpty()) return RevisorSummary(0, 0, 0, 0, 0)

        val conventions = store.readConventions(projectSlug)
        val template = promptStore.resolve(projectSlug, AgentRole.REVISOR)
        val canon = glossary.filter { it.isApproved }
        val found = mechanicalFindings(glossary)
        val index = runCatching { FragmentIndex.load(store.bookDir(projectSlug, bookSlug)) }
            .getOrElse { FragmentIndex.empty() }
        val batches = chunk(targets)

        var drop = 0
        var fix = 0
        var keep = 0
        var done = 0
        batches.forEachIndexed { bi, batch ->
            val src = batch.mapIndexed { i, e -> entryBlock(e, i, index.locate(e)) }
            val brief = BriefBuilder.build(
                BriefBuilder.Input(
                    template = template
                        .replace("{{FOUND}}", found)
                        .replace("{{SCOPE}}", scopeLine(scope)),
                    srcBlocks = src,
                    dictionary = canon,
                    conventions = conventions,
                ),
            )
            val result = runner.run(
                profile = profile,
                userPrompt = brief,
                tools = null,
                sourceLabel = "revisor:$scope:${bi + 1}/${batches.size}",
            )
            val parsed = parseVerdicts(result.text, targetOriginals)
            parsed.ignoredOriginals.forEach {
                AppLog.warn("revisor", "Вердикт по «$it» проигнорирован (нет в пакете или вердикт неизвестен)")
            }
            drop += parsed.valid.count { it.verdict == "drop" }
            fix += parsed.valid.count { it.verdict == "fix" }
            keep += parsed.valid.count { it.verdict == "keep" }
            decisions = mergeDecisions(decisions, parsed.valid, scope)
            // Сохраняем после каждого пакета: остановка этапа не теряет разобранное
            store.saveGlossaryRevision(projectSlug, GlossaryRevisionFile(decisions = decisions))
            done += batch.size
            onProgress(done, targets.size)
            AppLog.info(
                "revisor",
                "Пакет $scope ${bi + 1}/${batches.size}: drop ${parsed.valid.count { it.verdict == "drop" }}, " +
                    "fix ${parsed.valid.count { it.verdict == "fix" }}, keep ${parsed.valid.count { it.verdict == "keep" }}, " +
                    "проигнорировано ${parsed.ignoredOriginals.size}",
            )
        }
        return RevisorSummary(targets.size, batches.size, drop, fix, keep)
    }

    // ---------- пакет и выдержки ----------

    /** Блок одной записи для {{SOURCE}}: оригинал → перевод, категория, пояснение, выдержка места. */
    internal fun entryBlock(e: GlossaryEntry, index: Int, excerpt: Pair<String, String>?): String = buildString {
        append("${index + 1}. ${e.original} → ${e.translation}")
        append(" | категория: ${e.category}")
        if (e.alternatives.isNotEmpty()) append(" | варианты: ${e.alternatives.joinToString(", ")}")
        if (e.reason.isNotBlank()) append("\n   пояснение: ${e.reason}")
        if (excerpt != null) {
            append("\n   оригинал места: «${excerpt.first}»")
            append("\n   перевод места: «${excerpt.second}»")
        } else {
            append("\n   (место в книге не найдено)")
        }
    }

    /** Находки механической ревизии — ревизору на вход, чтобы не дублировать карточки. */
    internal fun mechanicalFindings(entries: List<GlossaryEntry>): String {
        val queue = findRedundant(entries)
        val canon = findRedundantApproved(entries)
        if (queue.isEmpty() && canon.isEmpty()) return "(нет)"
        return buildString {
            queue.forEach { r ->
                appendLine("— ${r.composite.original} → ${r.composite.translation} (части уже в глоссарии: " +
                    r.parts.joinToString("; ") { "${it.original} → ${it.translation}" } + ")")
            }
            canon.forEach { r ->
                appendLine("— ${r.composite.original} → ${r.composite.translation} (части уже утверждены: " +
                    r.parts.joinToString("; ") { "${it.original} → ${it.translation}" } + ")")
            }
        }.trim()
    }

    /**
     * Индекс «fragmentId → FragmentFile» книги: 119 файлов читаются ОДИН раз на прогон,
     * а не на каждую запись. Поиск места — по образцу SuggestionService.locateTerm:
     * сначала точное вхождение оригинала в блоках исходника, фолбэк — транскрипция
     * в переводе; фрагмент из entry.context приоритетен.
     */
    internal class FragmentIndex private constructor(
        private val order: List<String>,
        private val byId: Map<String, FragmentFile>,
    ) {
        companion object {
            fun load(bookDir: Path): FragmentIndex {
                val manifest = SplitStage.loadManifest(bookDir)
                val ids = manifest.parts.asSequence()
                    .flatMap { it.fragments }
                    .map { it.id }
                    .distinct()
                    .toList()
                val dir = bookDir.resolve("work").resolve("fragments")
                val map = ids.mapNotNull { id ->
                    runCatching {
                        AppJson.decodeFromString(FragmentFile.serializer(), Files.readString(dir.resolve("$id.json")))
                    }.getOrNull()
                }.associateBy { it.id }
                return FragmentIndex(ids, map)
            }

            fun empty(): FragmentIndex = FragmentIndex(emptyList(), emptyMap())
        }

        /** Выдержка (оригинал, перевод) блока с термином или null, если не найдено. */
        fun locate(entry: GlossaryEntry): Pair<String, String>? {
            val original = entry.original.trim()
            if (original.isEmpty()) return null
            val hint = entry.context.substringAfter("фрагмент", "").trim()
            val trNeedles = (listOf(entry.translation) + entry.alternatives)
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinct()
            val ids = if (hint.isNotEmpty()) order.sortedBy { it != hint } else order

            for (id in ids) {
                val ff = byId[id] ?: continue
                val tr = ff.editedBlocks.ifEmpty { ff.draftBlocks }.map { displayTranslation(it) }
                ff.srcBlocks.forEachIndexed { i, s ->
                    if (s.contains(original)) {
                        return excerptOf(s, original) to excerptOf(tr.getOrNull(i) ?: "", null)
                    }
                }
            }
            if (trNeedles.isNotEmpty()) {
                for (id in ids) {
                    val ff = byId[id] ?: continue
                    val tr = ff.editedBlocks.ifEmpty { ff.draftBlocks }.map { displayTranslation(it) }
                    tr.forEachIndexed { i, t ->
                        trNeedles.firstOrNull { t.contains(it) }?.let { needle ->
                            return excerptOf(ff.srcBlocks.getOrNull(i) ?: "", null) to excerptOf(t, needle)
                        }
                    }
                }
            }
            return null
        }

        private fun excerptOf(text: String, needle: String?): String = excerpt(text, needle)
    }

    companion object {
        /** Размер пакета: константа по ТЗ, новых настроек нет. */
        const val BATCH_SIZE = 30
        const val EXCERPT_MAX_CHARS = 300

        /** Скоупы прогона: очередь предложений / утверждённый канон. */
        const val SCOPE_QUEUE = "queue"
        const val SCOPE_CANON = "canon"

        /** Строка задачи для {{SCOPE}}: модель должна знать, что перед ней — очередь или канон. */
        fun scopeLine(scope: String): String = if (scope == SCOPE_CANON) {
            "Пакет — записи, УЖЕ УТВЕРЖДЁННЫЕ человеком в канон. drop = убрать термин из канона, " +
                "fix = заменить канонический перевод — предлагай только при уверенности: это отмена принятого решения."
        } else {
            "Пакет — записи из очереди на утверждение (каноном ещё не стали)."
        }

        /** Pending-решения по записям, которых больше нет ни в очереди, ни в каноне, снимаются. */
        fun dropStalePending(
            decisions: List<GlossaryRevisionDecision>,
            validOriginals: Set<String>,
        ): List<GlossaryRevisionDecision> =
            decisions.filter { it.status != "pending" || it.original in validOriginals }

        private const val TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"

        /** Пакетирование очереди: записи по [size] штук, вызовы LLM последовательные. */
        fun chunk(entries: List<GlossaryEntry>, size: Int = BATCH_SIZE): List<List<GlossaryEntry>> =
            if (entries.isEmpty()) emptyList() else entries.chunked(size.coerceAtLeast(1))

        /** Выдержка ≤maxChars знаков: окно вокруг первого вхождения needle (или с начала). */
        fun excerpt(text: String, needle: String?, maxChars: Int = EXCERPT_MAX_CHARS): String {
            val clean = text.replace("\r", "").replace("\n", " ").trim()
            if (clean.length <= maxChars) return clean
            val center = needle?.takeIf { it.isNotEmpty() }?.let { clean.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            val start = (center - maxChars / 2).coerceAtLeast(0)
            val end = (start + maxChars).coerceAtMost(clean.length)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < clean.length) "…" else ""
            return prefix + clean.substring(start, end) + suffix
        }

        /** Черновик может держать маркер [[имя?: X → Y | …]] — для выдержки оставляем только вариант. */
        private fun displayTranslation(block: String): String =
            if (!block.contains("[[имя?:")) block
            else DraftStage.NAME_MARKER.replace(block) { m -> m.groupValues[2].trim() }

        private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

        private val KNOWN_VERDICTS = setOf("keep", "drop", "fix", "split")

        /**
         * Разбор ответа ревизора: JSON-массив вердиктов (устойчив к ```-заборам и тексту
         * вокруг; при сломанном массиве — фолбэк на отдельные объекты {...}). Записи с
         * original вне [knownOriginals] или с вердиктом не из keep/drop/fix/split — в
         * ignoredOriginals; split без двух и более частей игнорируется.
         */
        fun parseVerdicts(raw: String, knownOriginals: Set<String>): ParsedVerdicts {
            val elements = extractJsonObjects(raw)
            val valid = mutableListOf<RawVerdict>()
            val ignored = mutableListOf<String>()
            elements.forEach { o ->
                val original = o["original"]?.let { el ->
                    (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }?.trim().orEmpty()
                val verdictRaw = o["verdict"]?.let { el ->
                    (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }?.trim().orEmpty()
                val verdict = verdictRaw.lowercase()
                val reason = o["reason"]?.let { el ->
                    (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }.orEmpty().trim()
                val suggested = o["suggested"]?.let { el ->
                    (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }.orEmpty().trim()
                val parts = (o["parts"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { p ->
                    val po = p as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    fun str(key: String): String = po[key]?.let { el ->
                        (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    }.orEmpty().trim()
                    val po0 = str("original")
                    val pt = str("translation")
                    if (po0.isNotBlank() && pt.isNotBlank()) GlossaryRevisionPart(po0, pt) else null
                }.orEmpty()
                when {
                    original.isEmpty() -> Unit // нечего даже логировать по имени
                    verdict !in KNOWN_VERDICTS -> {
                        AppLog.warn("revisor", "Вердикт «$verdictRaw» по «$original» не из keep/drop/fix/split")
                        ignored += original
                    }
                    verdict == "split" && parts.size < 2 -> {
                        AppLog.warn("revisor", "split по «$original» без частей — игнорируется")
                        ignored += original
                    }
                    original !in knownOriginals -> {
                        ignored += original
                    }
                    else -> valid += RawVerdict(
                        original, verdict, reason, suggested,
                        parts = if (verdict == "split") parts else emptyList(),
                    )
                }
            }
            return ParsedVerdicts(valid, ignored)
        }

        /** JSON-объекты ответа: сначала массив целиком, при неудаче — каждый {...} отдельно. */
        private fun extractJsonObjects(raw: String): List<kotlinx.serialization.json.JsonObject> {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start in 0 until end) {
                runCatching {
                    return lenientJson.parseToJsonElement(raw.substring(start, end + 1))
                        .jsonArray.mapNotNull { el -> runCatching { el.jsonObject }.getOrNull() }
                }
            }
            return Regex("\\{[^{}]*}", RegexOption.DOT_MATCHES_ALL).findAll(raw).mapNotNull { m ->
                runCatching { lenientJson.parseToJsonElement(m.value).jsonObject }.getOrNull()
            }.toList()
        }

        /**
         * Слияние свежих вердиктов с решениями файла: pending по тому же original
         * заменяются свежими (id сохраняется), applied/dismissed не трогаются —
         * повторный прогон не затирает решения человека. Исключение: у разобранной
         * записи свежий вердикт ДРУГОГО типа (например, после утверждения из очереди
         * канонный прогон предложил fix) — добавляется новым решением, иначе канонный
         * прогон молчал бы. keep по разобранной записи — шум, пропускается. Дубли
         * внутри свежих (модель повторила original) берутся один раз.
         */
        fun mergeDecisions(
            existing: List<GlossaryRevisionDecision>,
            fresh: List<RawVerdict>,
            scope: String = SCOPE_QUEUE,
            now: () -> String = { LocalDateTime.now().format(DateTimeFormatter.ofPattern(TIME_FORMAT)) },
        ): List<GlossaryRevisionDecision> {
            val result = existing.toMutableList()
            var nextId = (existing.mapNotNull { it.id.removePrefix("r").toIntOrNull() }.maxOrNull() ?: 0) + 1
            val seen = HashSet<String>()
            fresh.forEach { v ->
                if (v.original in seen) return@forEach
                seen += v.original
                val idx = result.indexOfFirst { it.original == v.original }
                if (idx >= 0) {
                    val old = result[idx]
                    when {
                        old.status == "pending" -> result[idx] = old.copy(
                            verdict = v.verdict,
                            suggested = v.suggested,
                            reason = v.reason,
                            parts = v.parts,
                            scope = scope,
                        )
                        v.verdict == "keep" || old.verdict == v.verdict -> Unit
                        else -> result += GlossaryRevisionDecision(
                            id = "r${nextId++}",
                            original = v.original,
                            verdict = v.verdict,
                            suggested = v.suggested,
                            parts = v.parts,
                            reason = v.reason,
                            status = "pending",
                            scope = scope,
                            createdAt = now(),
                        )
                    }
                } else {
                    result += GlossaryRevisionDecision(
                        id = "r${nextId++}",
                        original = v.original,
                        verdict = v.verdict,
                        suggested = v.suggested,
                        parts = v.parts,
                        reason = v.reason,
                        status = "pending",
                        scope = scope,
                        createdAt = now(),
                    )
                }
            }
            return result
        }
    }
}
