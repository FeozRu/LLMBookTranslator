package ru.sebyanin.booktranslator.core.storage

/**
 * Кодек «блоки главы ↔ markdown-файл». Один блок = одна строка; бланковые строки
 * игнорируются при чтении. Формат человекочитаем и grep-пригоден (как в прошлом опыте):
 *
 *   # Заголовок           → HEADING (## / ### — уровни)
 *   ◇　◇　◇               → SEPARATOR (разделитель сцен)
 *   { [img:name.jpg] }    → IMAGE (якорь позиции иллюстрации)
 *   <!-- ... -->          → COMMENT (шапка-комментарий части)
 *   прочее                → TEXT
 */
enum class BlockKind { TEXT, HEADING, SEPARATOR, IMAGE, COMMENT }

data class ChapterBlock(val kind: BlockKind, val text: String) {
    /** Уровень заголовка для HEADING (1..3), иначе null. */
    val headingLevel: Int? get() = if (kind == BlockKind.HEADING) text.count { it == '#' } - text.indexOfFirst { it != '#' } + 1 else null
}

object BlockText {

    const val SEPARATOR_LINE = "◇　◇　◇"
    private val SEP_RE = Regex("^◇(　◇)*$")
    private val IMG_RE = Regex("^\\{ \\[img:[^]]+] }$")

    fun encode(blocks: List<ChapterBlock>): String =
        blocks.joinToString("\n") { b ->
            when (b.kind) {
                BlockKind.HEADING -> b.text // уже с решётками: "# Тема"
                else -> b.text
            }
        }

    fun decode(text: String): List<ChapterBlock> =
        text.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { line ->
                when {
                    line.startsWith("<!--") -> ChapterBlock(BlockKind.COMMENT, line)
                    SEP_RE.matches(line) -> ChapterBlock(BlockKind.SEPARATOR, line)
                    IMG_RE.matches(line) -> ChapterBlock(BlockKind.IMAGE, line)
                    line.startsWith("### ") -> ChapterBlock(BlockKind.HEADING, line)
                    line.startsWith("## ") -> ChapterBlock(BlockKind.HEADING, line)
                    line.startsWith("# ") -> ChapterBlock(BlockKind.HEADING, line)
                    else -> ChapterBlock(BlockKind.TEXT, line)
                }
            }
            .toList()

    fun heading(text: String, level: Int = 1): ChapterBlock =
        ChapterBlock(BlockKind.HEADING, "${"#".repeat(level.coerceIn(1, 3))} $text")

    fun imageMarker(fileName: String): ChapterBlock =
        ChapterBlock(BlockKind.IMAGE, "{ [img:$fileName] }")

    fun separator(): ChapterBlock = ChapterBlock(BlockKind.SEPARATOR, SEPARATOR_LINE)

    fun imageFileOf(block: ChapterBlock): String? =
        IMG_RE.find(block.text)?.groupValues?.get(1)

    /** Строка-якорь: позиционная метка (иллюстрация/разделитель/комментарий), не текст для перевода. */
    fun isAnchorLine(line: String): Boolean =
        line.startsWith("<!--") || SEP_RE.matches(line) || IMG_RE.matches(line)

    /** Все блоки — якоря: фрагмент проводится через конвейер копией исходника, без LLM. */
    fun allAnchors(blocks: List<String>): Boolean =
        blocks.isNotEmpty() && blocks.all { isAnchorLine(it) }

    /** Знаки без пробелов (метрика verify и нарезки). */
    fun charCount(blocks: List<ChapterBlock>): Int =
        blocks.sumOf { b -> b.text.count { !it.isWhitespace() } }
}
