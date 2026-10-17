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

/** Рабочая зона: параллельный просмотр главы (оригинал слева, перевод справа). */
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

    val ch = chapter ?: return
    val meta = structure?.chapters?.firstOrNull { it.index == ch }
    val blocks = remember(ch, version, manifest, book) {
        state.chapterBlocks(ch)
    }
    val src = blocks.src
    val out = blocks.out
    val isFallback = blocks.shownVersion != version
    val previewFs = settings.previewFontSize.coerceIn(PREVIEW_FS_MIN, PREVIEW_FS_MAX)

    // Ручной редактор блока: клик по правой карточке. bt-флаги для скриншотов:
    // -Dbt.blockEdit=<индекс> открывает редактор, -Dbt.blockEditSel="a:b" сеет выделение
    var editingIdx by remember(ch, version) {
        mutableStateOf(System.getProperty("bt.blockEdit")?.toIntOrNull()?.takeIf { it >= 0 })
    }
    var fieldValue by remember(editingIdx) {
        mutableStateOf(TextFieldValue(out.getOrNull(editingIdx ?: -1) ?: ""))
    }
    var noteText by remember(editingIdx) { mutableStateOf("") }
    var wasSaving by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val sel = fieldValue.selection
    val hasSelection = editingIdx != null && sel.min < sel.max
    val selectedText = if (hasSelection) fieldValue.text.substring(sel.min, sel.max) else ""

    // Закрытие редактора по завершении сохранения: saveBlockEdit ставит message = null
    // перед стартом, итог приходит («Блок сохранён…» или «Ошибка: …») — ошибка оставляет
    // редактор открытым, текст не тронут
    val message by state.message.collectAsState()
    LaunchedEffect(message) {
        if (wasSaving && message != null) {
            if (!message!!.startsWith("Ошибка")) editingIdx = null
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
    LaunchedEffect(editingIdx) {
        if (editingIdx != null) {
            System.getProperty("bt.blockEditSel")?.let { raw ->
                val parts = raw.split(":").mapNotNull { it.toIntOrNull() }
                if (parts.size == 2) fieldValue = fieldValue.copy(selection = TextRange(parts[0], parts[1]))
            }
            focusRequester.requestFocus()
        }
    }

    Column(modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // weight(1f) обязателен: без него колонка меряется по самому длинному
            // ребёнку (подсказка ~470px), и «Собрать главу» достаётся ~20px —
            // кнопка сжимается в вертикальную полоску на дефолтном окне (урок)
            Column(Modifier.weight(1f)) {
                Text(
                    "Глава $ch · ${meta?.title ?: ""}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${src.size} блоков · показан: " +
                        when {
                            isFallback && blocks.shownVersion == "draft" -> "черновик (редактуры пока нет)"
                            isFallback && blocks.shownVersion == "edited" -> "редактура (выбран черновик, но его нет)"
                            version == "edited" -> "отредактированный текст"
                            else -> "черновик"
                        },
                    fontSize = 12.sp,
                    color = if (isFallback) BtPalette.AccentText else BtPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Клик по блоку перевода — правка; выделите слова — примечание (вручную или от модели)",
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
                "ПЕРЕВОД (${if (blocks.shownVersion == "edited") "редактура" else "черновик"})",
                Modifier.weight(1f),
                color = BtPalette.AccentText,
            )
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(src) { i, s ->
                val t = out.getOrNull(i)
                val ref = blocks.refs.getOrNull(i)
                val editing = editingIdx == i
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BtCard(
                        modifier = Modifier.weight(1f),
                        border = BtPalette.Border,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            s,
                            fontFamily = BtFonts.Book,
                            fontSize = previewFs.sp,
                            lineHeight = (previewFs * 22f / 14f).sp,
                            color = if (s.startsWith("#")) BtPalette.Text else BtPalette.Text,
                            fontWeight = if (s.startsWith("#")) FontWeight.SemiBold else FontWeight.Normal,
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
                                        onClick = { state.requestNote(s, t ?: "", selectedText) },
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
                                        val r = ref ?: return@BtButton
                                        state.message.value = null
                                        wasSaving = true
                                        state.saveBlockEdit(r, ch, fieldValue.text)
                                    },
                                    enabled = running == null && ref != null,
                                    kind = BtButtonKind.Success,
                                    height = 30.dp,
                                )
                                BtButton("Отмена", onClick = { editingIdx = null }, enabled = running == null, height = 30.dp)
                                Text(
                                    "переносы строк при сохранении станут пробелами; якоря { [img:…] } меняются только через «Заменить изображение»",
                                    fontSize = 11.sp,
                                    color = BtPalette.Muted,
                                )
                            }
                        }
                    } else if (t != null && BlockEditor.IMG_MARKER_RE.containsMatchIn(t)) {
                        // Якорь изображения: ручная правка текста запрещена, картинка
                        // меняется загрузкой файла в images/ проекта
                        BtCard(
                            modifier = Modifier.weight(1f),
                            border = BtPalette.AccentBorderLight,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column {
                                Text(
                                    t,
                                    fontFamily = BtFonts.Book,
                                    fontSize = previewFs.sp,
                                    lineHeight = (previewFs * 22f / 14f).sp,
                                )
                                if (ref != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BtButton(
                                            "Заменить изображение…",
                                            onClick = { state.replaceBlockImage(ref, ch) },
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
                                enabled = running == null && ref != null && !t.isNullOrBlank(),
                            ) { editingIdx = i },
                            border = if (t != null) BtPalette.AccentBorderLight else BtPalette.Border,
                            background = if (t == null) BtPalette.Bg else BtPalette.Card,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                t ?: "— ещё не переведено —",
                                fontFamily = BtFonts.Book,
                                fontSize = previewFs.sp,
                                lineHeight = (previewFs * 22f / 14f).sp,
                                color = if (t == null) BtPalette.Muted else BtPalette.Text,
                                fontWeight = if (t?.startsWith("#") == true) FontWeight.SemiBold else FontWeight.Normal,
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
