package ru.sebyanin.booktranslator.core.verify

/**
 * Автопроверки после каждого шага — перенос verify_translation.py:
 * блоки 1:1, якоря (img/разделители/комментарии) 1:1 и на местах,
 * остаточные знаки исходника (кана/CJK для пресета ja), коридор длины.
 */
data class VerifyResult(
    val ok: Boolean,
    val problems: List<String>,
    val warnings: List<String>,
) {
    fun summary(): String =
        if (ok && warnings.isEmpty()) "чисто" else buildString {
            if (problems.isNotEmpty()) append("проблемы: ${problems.joinToString("; ")}")
            if (warnings.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append("предупреждения: ${warnings.joinToString("; ")}")
            }
        }

    companion object {
        val CLEAN = VerifyResult(true, emptyList(), emptyList())
    }
}

class VerifyEngine(private val preset: String) {

    data class Options(
        /** Проверять остаточные знаки исходника (кана/CJK). */
        val residualCheck: Boolean = true,
        /** Коридор отношения длин перевод/оригинал; null = не проверять. */
        val ratioCorridor: ClosedFloatingPointRange<Double>? = null,
        val ratioAsWarning: Boolean = false,
        /** Вырезать [[…]]-пометки перед проверкой остатков (для черновика). */
        val stripMarkers: Boolean = false,
    )

    fun verifyTranslation(src: List<String>, out: List<String>, options: Options): VerifyResult {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (src.size != out.size) {
            problems += "блоков ${src.size} → ${out.size} (ожидалось 1:1)"
        }

        // Якоря: последовательности маркеров должны совпадать
        val srcAnchors = src.mapNotNull { anchorKind(it) }
        val outAnchors = out.mapNotNull { anchorKind(it) }
        if (srcAnchors != outAnchors) {
            val byKind = { k: String ->
                "src=${srcAnchors.count { it == k }} tr=${outAnchors.count { it == k }}"
            }
            problems += "якоря расходятся [img: ${byKind("img")}; sep: ${byKind("sep")}; comment: ${byKind("comment")}]"
        }

        if (options.residualCheck) {
            val residual = residualSourceChars(out, options.stripMarkers)
            if (residual > 0) {
                problems += "остаточные знаки исходника: $residual"
            }
        }

        options.ratioCorridor?.let { corridor ->
            val srcLen = src.joinToString("").count { !it.isWhitespace() }
            val outLen = out.joinToString("").count { !it.isWhitespace() }
            if (srcLen > 0) {
                val ratio = outLen.toDouble() / srcLen
                if (ratio !in corridor) {
                    val msg = "отношение длин %.2f вне коридора %.1f–%.1f".format(ratio, corridor.start, corridor.endInclusive)
                    if (options.ratioAsWarning) warnings += msg else problems += msg
                }
            }
        }

        // Пометки черновика, которые редактор обязан снять
        if (!options.stripMarkers) {
            if (out.any { MARKER_RE.containsMatchIn(it) }) {
                warnings += "остались [[…]]-пометки (для редактуры — норма, для финала — нет)"
            }
            if (out.any { TONE_TAG_RE.containsMatchIn(it) }) {
                warnings += "остались теги тона […]"
            }
        }

        return if (problems.isEmpty()) VerifyResult(true, emptyList(), warnings) else VerifyResult(false, problems, warnings)
    }

    private fun anchorKind(line: String): String? = when {
        Regex("^\\{ \\[img:[^]]+] }$").matches(line) -> "img"
        Regex("^◇(　◇)*$").matches(line) -> "sep"
        line.startsWith("<!--") -> "comment"
        else -> null
    }

    /** Кана + CJK-иероглифы вне [[…]]-пометок. Работает по пресету ja; generic не проверяет. */
    private fun residualSourceChars(out: List<String>, stripMarkers: Boolean): Int {
        if (preset != "ja") return 0
        return out.sumOf { line ->
            // Черновик: все [[…]]-пометки содержат исходник — норма. После редактуры
            // промпт разрешает редактору оставить неразрешённое сомнение [[??: …]] на
            // месте (его разбор — за человеком в блоке сомнений), поэтому кану внутри
            // таких пометок остатком не считаем; содержимое незнакомых [[…]] — считаем.
            val text = if (stripMarkers) MARKER_RE.replace(line, "") else DOUBT_MARKER_RE.replace(line, "")
            val commentStripped = COMMENT_RE.replace(text, "")
            commentStripped.count { it in KANA_RANGE || CJK_START <= it && it <= CJK_END }
        }
    }

    companion object {
        private val KANA_RANGE = '\u3040'..'\u30FF' // хирагана + катакана (+ полуширинные до 30FF)
        private const val CJK_START = '\u4E00'
        private const val CJK_END = '\u9FFF'
        private val MARKER_RE = Regex("\\[\\[.*?]]")
        private val COMMENT_RE = Regex("<!--.*?-->")
        private val TONE_TAG_RE = Regex("\\[(вежл|грубо|высокомерно|по-детски|нараспев|иронично)]")

        /**
         * Сомнение переводчика из черновика: [[??: что непонятно]] (промпт DRAFT, правило 7).
         * Группа 1 — текст сомнения. Допускаем один и два «?» — модели пишут оба варианта.
         */
        val DOUBT_MARKER_RE = Regex("\\[\\[\\?+:\\s*([^]]+?)\\s*]]")

        /** Настройки проверок по этапам для пресета ja. */
        fun draftOptions(): Options = Options(
            residualCheck = true,
            stripMarkers = true, // в черновике [[имя?: оригинал → вар]] содержит исходник — это норма
            ratioCorridor = 0.8..3.2,
            ratioAsWarning = true,
        )

        fun editedOptions(ratioCorridor: ClosedFloatingPointRange<Double>? = 1.1..3.0): Options = Options(
            residualCheck = true,
            stripMarkers = false,
            ratioCorridor = ratioCorridor,
        )

        fun finalOptions(): Options = Options(
            residualCheck = true,
            stripMarkers = false,
            ratioCorridor = 1.1..3.0,
        )
    }
}
