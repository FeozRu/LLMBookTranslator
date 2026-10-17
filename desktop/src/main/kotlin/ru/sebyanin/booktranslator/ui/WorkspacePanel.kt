package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.core.pipeline.BlockEditor
import ru.sebyanin.booktranslator.ui.components.BtButton
import ru.sebyanin.booktranslator.ui.components.BtButtonKind
import ru.sebyanin.booktranslator.ui.components.BtCard
import ru.sebyanin.booktranslator.ui.components.BtTextField
import ru.sebyanin.booktranslator.ui.components.SectionCaption
import ru.sebyanin.booktranslator.ui.theme.BtFonts
import ru.sebyanin.booktranslator.ui.theme.BtPalette

/** Границы размера шрифта предпросмотра (sp) — степпер в шапке рабочей зоны. */
const val PREVIEW_FS_MIN = 10
const val PREVIEW_FS_MAX = 24

/** Строка рабочей зоны: разделитель главы или пара «оригинал | перевод». */
private data class WorkspaceRow(
    val chapter: Int,
    val title: String,
    val divider: Boolean,
    val blockIdx: Int = -1,
    val src: String = "",
    /** Перевод блока; null — заполнитель «ещё не переведено». */
    val out: String? = null,
    val ref: BlockEditor.BlockRef? = null,
)

/**
 * Рабочая зона: параллельный просмотр (оригинал слева, перевод справа) в режиме
 * непрерывного чтения — следующая глава подгружается при прокрутке вниз, предыдущая
 * при прокрутке вверх, позиция вьюпорта сохраняется (requestScrollToItem до смены
 * окна). В памяти — окно из нескольких глав ([CHAPTER_WINDOW_CAP]); данные главы
 * читаются с диска только когда глава входит в окно. Глава первой видимой строки
 * становится текущей (scroll-spy, как навигация в настройках): за ней следят шапка,
 * «Собрать главу» и подсветка в левом меню; клик по главе в меню, наоборот,
 * пересобирает окно вокруг неё и прыгает к её началу.
 */
@Composable
fun WorkspacePanel(state: AppState, modifier: Modifier = Modifier) {
    val chapter by state.selectedChapter.collectAsState()
    val version by state.viewVersion.collectAsState()
    val running by state.runningStage.collectAsState()
    val structure by state.structure.collectAsState()
    // Именно collectAsState: чтение StateFlow.value в ключах remember не подписывает
    // на изменения — панель не узнаёт о завершении фрагментов этапа
    val manifest by state.manifest.collectAsState()
    val book by state.currentBook.collectAsState()
    val settings by state.services.settings.collectAsState()
    // Тексты глав живут в файлах вне манифеста (правка блока, сборка, корректор):
    // refreshBook дёргает счётчик после любого этапа — строки окна пересобираются
    val criticVersion by state.criticVersion.collectAsState()

    val ch = chapter ?: return
    val previewFs = settings.previewFontSize.coerceIn(PREVIEW_FS_MIN, PREVIEW_FS_MAX)

    // ---- скользящее окно глав: непрерывное чтение (механика общая с корректором) ----
    val chapterOrder = structure?.chapters?.map { it.index } ?: emptyList()
    val cw = rememberChapterWindowState(book?.slug, ch)

    // Строки окна. Ключи манифест/criticVersion/book — данные на диске меняет любой
    // этап и правка блока; version — переключение черновик/редактура
    val (rows, shownByChapter) = remember(cw.window, version, manifest, book, criticVersion, structure) {
        buildWorkspaceContent(state, cw.window)
    }

    ChapterWindowEffects(
        state = cw,
        selected = chapter,
        bookSlug = book?.slug,
        chapterOrder = chapterOrder,
        rowsVersion = rows,
        rowChapterAt = { i -> rows.getOrNull(i)?.chapter },
        chapterRowCount = { c -> state.chapterBlocks(c).src.size + 1 }, // разделитель + блоки
        onSpyChapter = { c -> if (state.selectedChapter.value != c) state.selectedChapter.value = c },
    )

    // bt-проверка бесшовности: -Dbt.wsScrollIntoChapter=<N> — открыть главу N и встать
    // на 3-й блок: окно должно догрузить соседей, шапка и левое меню — следить за позицией
    var btScrolledTo by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(rows) {
        val into = System.getProperty("bt.wsScrollIntoChapter")?.toIntOrNull() ?: return@LaunchedEffect
        if (btScrolledTo == into) return@LaunchedEffect
        if (rows.none { it.chapter == into }) {
            state.selectedChapter.value = into
            return@LaunchedEffect
        }
        btScrolledTo = into
        val first = rows.indexOfFirst { it.chapter == into }
        val last = rows.indexOfLast { it.chapter == into }
        cw.listState.scrollToItem((first + 3).coerceAtMost(last))
    }

    // Ручной редактор блока: клик по правой карточке. bt-флаги для скриншотов:
    // -Dbt.blockEdit=<индекс> открывает редактор, -Dbt.blockEditSel="a:b" сеет выделение
    var editingAt by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (глава, блок)
    LaunchedEffect(ch, version) {
        editingAt = null
        System.getProperty("bt.blockEdit")?.toIntOrNull()?.takeIf { it >= 0 }?.let { editingAt = ch to it }
    }
    var fieldValue by remember(editingAt) {
        val t = editingAt
        val initial = if (t == null) "" else {
            rows.firstOrNull { !it.divider && it.chapter == t.first && it.blockIdx == t.second }?.out.orEmpty()
        }
        mutableStateOf(TextFieldValue(initial))
    }
    var noteText by remember(editingAt) { mutableStateOf("") }
    var wasSaving by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val sel = fieldValue.selection
    val hasSelection = editingAt != null && sel.min < sel.max
    val selectedText = if (hasSelection) fieldValue.text.substring(sel.min, sel.max) else ""

    // Закрытие редактора по завершении сохранения: saveBlockEdit ставит message = null
    // перед стартом, итог приходит («Блок сохранён…» или «Ошибка: …») — ошибка оставляет
    // редактор открытым, текст не тронут
    val message by state.message.collectAsState()
    LaunchedEffect(message) {
        if (wasSaving && message != null) {
            if (!message!!.startsWith("Ошибка")) editingAt = null
            wasSaving = false
        }
    }
    // Готовое примечание от модели — в поле панели примечаний
    val noteDraft by state.noteDraft.collectAsState()
    LaunchedEffect(noteDraft) {
        if (noteDraft != null) {
            noteText = noteDraft!!
            state.noteDraft.value = null
        }
    }
    LaunchedEffect(editingAt) {
        if (editingAt != null) {
            System.getProperty("bt.blockEditSel")?.let { raw ->
                val parts = raw.split(":").mapNotNull { it.toIntOrNull() }
                if (parts.size == 2) fieldValue = fieldValue.copy(selection = TextRange(parts[0], parts[1]))
            }
            focusRequester.requestFocus()
        }
    }

    val shownVersion = shownByChapter[ch] ?: version
    val isFallback = shownVersion != version
    val chapterTitle = structure?.chapters?.firstOrNull { it.index == ch }?.title.orEmpty()
    val chBlockCount = rows.count { !it.divider && it.chapter == ch }

    Column(modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // weight(1f) обязателен: без него колонка меряется по самому длинному
            // ребёнку (подсказка ~470px), и «Собрать главу» достаётся ~20px —
            // кнопка сжимается в вертикальную полоску на дефолтном окне (урок)
            Column(Modifier.weight(1f)) {
                Text(
                    "Глава $ch · $chapterTitle",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$chBlockCount блоков · показан: " +
                        when {
                            isFallback && shownVersion == "draft" -> "черновик (редактуры пока нет)"
                            isFallback && shownVersion == "edited" -> "редактура (выбран черновик, но его нет)"
                            version == "edited" -> "отредактированный текст"
                            else -> "черновик"
                        },
                    fontSize = 12.sp,
                    color = if (isFallback) BtPalette.AccentText else BtPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Главы листаются сплошным потоком; клик по блоку перевода — правка, выделение слов — примечание",
                    fontSize = 11.sp,
                    color = BtPalette.Muted,
                )
            }
            Spacer(Modifier.width(10.dp))

            // Размер шрифта предпросмотра: сохраняется в settings.json
            Row(verticalAlignment = Alignment.CenterVertically) {
                BtButton(
                    "A−",
                    onClick = { state.services.updateSettings { it.copy(previewFontSize = it.previewFontSize - 1) } },
                    enabled = previewFs > PREVIEW_FS_MIN,
                    height = 32.dp,
                    compact = true,
                )
                Text(
                    "$previewFs",
                    fontSize = 12.sp,
                    color = BtPalette.TextSecondary,
                    modifier = Modifier.width(26.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                BtButton(
                    "A+",
                    onClick = { state.services.updateSettings { it.copy(previewFontSize = it.previewFontSize + 1) } },
                    enabled = previewFs < PREVIEW_FS_MAX,
                    height = 32.dp,
                    compact = true,
                )
            }
            Spacer(Modifier.width(8.dp))

            BtButton(
                if (version == "edited") "Показать черновик" else "Показать редактуру",
                onClick = { state.viewVersion.value = if (version == "edited") "draft" else "edited" },
                height = 32.dp,
            )
            Spacer(Modifier.width(8.dp))
            BtButton(
                "Собрать главу",
                onClick = { state.assembleCurrentChapter() },
                enabled = running == null,
                kind = BtButtonKind.Success,
                height = 32.dp,
            )
        }

        // Шапка пары
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCaption("ОРИГИНАЛ", Modifier.weight(1f))
            SectionCaption(
                "ПЕРЕВОД (${if (shownVersion == "edited") "редактура" else "черновик"})",
                Modifier.weight(1f),
                color = BtPalette.AccentText,
            )
        }

        LazyColumn(state = cw.listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(rows, key = { _, r -> if (r.divider) "d${r.chapter}" else "b${r.chapter}:${r.blockIdx}" }) { _, row ->
                if (row.divider) {
                    ChapterDivider(row.chapter, row.title)
                    return@itemsIndexed
                }
                val editing = editingAt == (row.chapter to row.blockIdx)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BtCard(
                        modifier = Modifier.weight(1f),
                        border = BtPalette.Border,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            row.src,
                            fontFamily = BtFonts.Book,
                            fontSize = previewFs.sp,
                            lineHeight = (previewFs * 22f / 14f).sp,
                            color = BtPalette.Text,
                            fontWeight = if (row.src.startsWith("#")) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    if (editing) {
                        // Редактор блока: выделение мышью открывает панель примечаний
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BasicTextField(
                                value = fieldValue,
                                onValueChange = { fieldValue = it },
                                textStyle = TextStyle(
                                    fontFamily = BtFonts.Book,
                                    fontSize = previewFs.sp,
                                    lineHeight = (previewFs * 22f / 14f).sp,
                                    color = BtPalette.Text,
                                ),
                                cursorBrush = SolidColor(BtPalette.Accent),
                                modifier = Modifier.fillMaxWidth()
                                    .background(BtPalette.Bg, RoundedCornerShape(8.dp))
                                    .heightIn(min = 90.dp)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .focusRequester(focusRequester),
                            )
                            if (hasSelection) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BtTextField(
                                        value = noteText,
                                        onValueChange = { noteText = it },
                                        placeholder = "Примечание к «${selectedText.take(40)}» — впишите или спросите модель",
                                        modifier = Modifier.weight(1f),
                                    )
                                    BtButton(
                                        "Спросить LLM",
                                        onClick = { state.requestNote(row.src, row.out ?: "", selectedText) },
                                        enabled = running == null,
                                        height = 30.dp,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    BtButton(
                                        "Вставить примечание",
                                        onClick = {
                                            val new = BlockEditor.insertNoteMarker(fieldValue.text, sel.min, sel.max, noteText)
                                            fieldValue = TextFieldValue(
                                                new,
                                                selection = TextRange(sel.max + (new.length - fieldValue.text.length)),
                                            )
                                            noteText = ""
                                        },
                                        enabled = noteText.isNotBlank() && running == null,
                                        kind = BtButtonKind.Primary,
                                        height = 30.dp,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BtButton(
                                    "Сохранить",
                                    onClick = {
                                        val r = row.ref ?: return@BtButton
                                        state.message.value = null
                                        wasSaving = true
                                        state.saveBlockEdit(r, row.chapter, fieldValue.text)
                                    },
                                    enabled = running == null && row.ref != null,
                                    kind = BtButtonKind.Success,
                                    height = 30.dp,
                                )
                                BtButton("Отмена", onClick = { editingAt = null }, enabled = running == null, height = 30.dp)
                                Text(
                                    "переносы строк при сохранении станут пробелами; якоря { [img:…] } меняются только через «Заменить изображение»",
                                    fontSize = 11.sp,
                                    color = BtPalette.Muted,
                                )
                            }
                        }
                    } else if (row.out != null && BlockEditor.IMG_MARKER_RE.containsMatchIn(row.out)) {
                        // Якорь изображения: ручная правка текста запрещена, картинка
                        // меняется загрузкой файла в images/ проекта
                        BtCard(
                            modifier = Modifier.weight(1f),
                            border = BtPalette.AccentBorderLight,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column {
                                Text(
                                    row.out,
                                    fontFamily = BtFonts.Book,
                                    fontSize = previewFs.sp,
                                    lineHeight = (previewFs * 22f / 14f).sp,
                                )
                                if (row.ref != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BtButton(
                                            "Заменить изображение…",
                                            onClick = { state.replaceBlockImage(row.ref, row.chapter) },
                                            enabled = running == null,
                                            height = 30.dp,
                                        )
                                        Text(
                                            "файл копируется в images/ проекта; маркер руками не правится",
                                            fontSize = 11.sp,
                                            color = BtPalette.Muted,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        BtCard(
                            modifier = Modifier.weight(1f).clickable(
                                enabled = running == null && row.ref != null && !row.out.isNullOrBlank(),
                            ) { editingAt = row.chapter to row.blockIdx },
                            border = if (row.out != null) BtPalette.AccentBorderLight else BtPalette.Border,
                            background = if (row.out == null) BtPalette.Bg else BtPalette.Card,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                row.out ?: "— ещё не переведено —",
                                fontFamily = BtFonts.Book,
                                fontSize = previewFs.sp,
                                lineHeight = (previewFs * 22f / 14f).sp,
                                color = if (row.out == null) BtPalette.Muted else BtPalette.Text,
                                fontWeight = if (row.out?.startsWith("#") == true) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        // Конкорданс: как фраза переводилась ранее
        ConcordanceBar(state)
    }
}

/** Строки окна: разделитель + пары блоков каждой главы; карта «глава → какая версия показана». */
private fun buildWorkspaceContent(state: AppState, window: List<Int>): Pair<List<WorkspaceRow>, Map<Int, String>> {
    val rows = mutableListOf<WorkspaceRow>()
    val shown = mutableMapOf<Int, String>()
    val chapters = state.structure.value?.chapters
    for (c in window) {
        val title = chapters?.firstOrNull { it.index == c }?.title.orEmpty()
        rows += WorkspaceRow(c, title, divider = true)
        val view = state.chapterBlocks(c)
        shown[c] = view.shownVersion
        view.src.forEachIndexed { i, s ->
            rows += WorkspaceRow(
                chapter = c,
                title = title,
                divider = false,
                blockIdx = i,
                src = s,
                out = view.out.getOrNull(i),
                ref = view.refs.getOrNull(i),
            )
        }
    }
    return rows to shown
}

/** Поиск по выравниванию: «воспоминание персонажа» — сверка прежних переводов. */
@Composable
private fun ConcordanceBar(state: AppState) {
    var query by remember { mutableStateOf("") }
    val project by state.currentProject.collectAsState()
    val book by state.currentBook.collectAsState()
    var hits by remember { mutableStateOf<List<ru.sebyanin.booktranslator.core.align.AlignHit>>(emptyList()) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionCaption("КОНКОРДАНС", Modifier.width(120.dp))
            ru.sebyanin.booktranslator.ui.components.BtTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Как эта фраза уже переводилась? (поиск по оригиналу и переводу)",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            BtButton(
                "Найти",
                enabled = query.trim().length >= 2 && project != null,
                onClick = {
                    hits = state.services.alignSearch.search(
                        projectSlug = project!!.slug,
                        bookSlug = book?.slug,
                        query = query.trim(),
                    )
                },
                height = 32.dp,
            )
        }
        if (hits.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth()
                    .background(BtPalette.AccentBgSoft, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                hits.take(5).forEach { hit ->
                    Column {
                        Text(
                            "Книга ${hit.bookSlug} · глава ${hit.chapter} · блок ${hit.block + 1}",
                            fontSize = 11.sp,
                            color = BtPalette.AccentText,
                        )
                        Text("ОРИГИНАЛ: ${hit.src}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("ПЕРЕВОД: ${hit.tr}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
