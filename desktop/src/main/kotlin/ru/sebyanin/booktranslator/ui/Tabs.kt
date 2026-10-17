package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.core.glossary.GlossaryRevision
import ru.sebyanin.booktranslator.core.glossary.SuggestionService
import ru.sebyanin.booktranslator.core.glossary.TermReplacer
import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.log.LogLevel
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.CriticChapterFile
import ru.sebyanin.booktranslator.core.model.CriticSuggestion
import ru.sebyanin.booktranslator.core.model.GlossaryEntry
import ru.sebyanin.booktranslator.core.model.GlossaryRevisionDecision
import ru.sebyanin.booktranslator.core.model.KnowledgeNote
import ru.sebyanin.booktranslator.core.pipeline.TranslatorDoubt
import ru.sebyanin.booktranslator.core.pipeline.TranslatorDoubts
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.sebyanin.booktranslator.ui.components.BtButton
import ru.sebyanin.booktranslator.ui.components.BtButtonKind
import ru.sebyanin.booktranslator.ui.components.BtCard
import ru.sebyanin.booktranslator.ui.components.BtChip
import ru.sebyanin.booktranslator.ui.components.BtDropdown
import ru.sebyanin.booktranslator.ui.components.BtTab
import ru.sebyanin.booktranslator.ui.components.BtTabs
import ru.sebyanin.booktranslator.ui.components.BtTextField
import ru.sebyanin.booktranslator.ui.components.SectionCaption
import ru.sebyanin.booktranslator.ui.theme.BtFonts
import ru.sebyanin.booktranslator.ui.theme.BtPalette

/**
 * Центральная зона: два яруса вкладок. Верхний — скоуп (ГЛАВА | ПРОЕКТ | ОБЩЕЕ),
 * нижний — вкладки выбранного скоупа: глава → Перевод | Корректор (текущая глава из
 * левого меню), проект → Глоссарий, общее → Журнал | Настройки.
 */
@Composable
fun CenterTabs(state: AppState, modifier: Modifier = Modifier) {
    var scope by remember { mutableStateOf(0) }
    var inner by remember { mutableStateOf(0) }
    val requested by state.requestedTab.collectAsState()
    androidx.compose.runtime.LaunchedEffect(requested) {
        requested?.let {
            scope = when (it) {
                "glossary" -> 1
                "log", "settings" -> 2
                else -> 0
            }
            inner = if (it == "critic" || it == "settings") 1 else 0
            state.requestedTab.value = null
        }
    }

    val glossary by state.glossary.collectAsState()
    val notes by state.notes.collectAsState()
    // Пересчёт счётчика при изменении правок на диске
    val criticVersion by state.criticVersion.collectAsState()
    val pending = remember(criticVersion) { countPendingSuggestions(state) }

    // Последняя вкладка скоупа «ГЛАВА»: при выборе другой главы возвращаемся
    // в этот скоуп на то же место — даже если сейчас открыты ПРОЕКТ/ОБЩЕЕ
    var lastChapterInner by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(scope, inner) {
        if (scope == 0) lastChapterInner = inner
    }

    // Первый запуск пропускаем: восстановленная сессия приходит на композицию уже
    // с главой — иначе bt-сиды вкладок (glossary/log/settings) сразу вытесняло бы в «ГЛАВУ»
    var chapterSeen by remember { mutableStateOf(false) }
    var prevChapter by remember { mutableStateOf<Int?>(null) }
    val chapter by state.selectedChapter.collectAsState()
    androidx.compose.runtime.LaunchedEffect(chapter) {
        if (!chapterSeen) {
            chapterSeen = true
        } else if (chapter != null && chapter != prevChapter && scope != 0) {
            scope = 0
            inner = lastChapterInner
        }
        prevChapter = chapter
    }

    Column(modifier) {
        BtTabs(
            tabs = listOf(BtTab("ГЛАВА"), BtTab("ПРОЕКТ"), BtTab("ОБЩЕЕ")),
            selected = scope,
            onSelect = {
                scope = it
                inner = 0
            },
            compact = true,
            showDivider = false,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        BtTabs(
            tabs = when (scope) {
                0 -> listOf(
                    BtTab("Перевод"),
                    BtTab("Корректор", (pending.takeIf { it > 0 })?.toString()),
                )
                1 -> listOf(
                    BtTab(
                        "Глоссарий",
                        pendingSuffix(glossary.count { it.status == "proposed" } + notes.count { it.status == "proposed" }),
                    ),
                )
                else -> listOf(BtTab("Журнал"), BtTab("Настройки"))
            },
            selected = inner,
            onSelect = { inner = it },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        when (scope) {
            0 -> when (inner) {
                1 -> CriticTab(state, Modifier.fillMaxSize())
                else -> WorkspacePanel(state, Modifier.fillMaxSize())
            }
            1 -> GlossaryTab(state, Modifier.fillMaxSize())
            else -> when (inner) {
                1 -> SettingsTab(state, Modifier.fillMaxSize())
                else -> LogTab(state, Modifier.fillMaxSize())
            }
        }
    }
}

/** Счётчик предложений в очереди: «+4» или null, если очередь пуста. */
private fun pendingSuffix(count: Int): String? = if (count > 0) "+$count" else null

private fun countPendingSuggestions(state: AppState): Int {
    val p = state.currentProject.value ?: return 0
    val b = state.currentBook.value ?: return 0
    return state.structure.value?.chapters?.sumOf {
        state.services.suggestionService.pending(p.slug, b.slug, it.index).size
    } ?: 0
}

// ---------------- Корректор: ревью с контекстом ----------------

@Composable
fun CriticTab(state: AppState, modifier: Modifier = Modifier) {
    val project by state.currentProject.collectAsState()
    val book by state.currentBook.collectAsState()
    val structure by state.structure.collectAsState()
    // Версия критики: статусы правок живут вне манифеста — подписка на этот сигнал
    val criticVersion by state.criticVersion.collectAsState()
    val p = project ?: return
    val b = book ?: return

    // Глава общая с рабочей зоной: выбор в левом меню переключает и корректора.
    // Отдельный дропдаун здесь не нужен — он дублировал левое меню
    val chapter by state.selectedChapter.collectAsState()
    val ch = chapter ?: 1
    // bt-проверки: предвыбранная карточка для скриншота контекста (-Dbt.criticSelect=id)
    val btSelect = remember { System.getProperty("bt.criticSelect") }
    var selectedId by remember { mutableStateOf<String?>(btSelect) }

    // Глава сменилась (левое меню) — выделенная карточка больше не актуальна
    LaunchedEffect(chapter) { if (btSelect == null) selectedId = null }

    // Живой статус этапа: кнопки отправки сомнений и перезапуска гаснут во время работы
    val running by state.runningStage.collectAsState()
    // Сомнение, отправленное корректору на разбор (пока этап не завершится)
    var fixingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(running) { if (running == null) fixingId = null }

    // В ключах и slugs книги: без них смена книги при открытой вкладке оставляла
    // чужие предложения, если номер первой главы совпадал
    val file = remember(p.slug, b.slug, ch, criticVersion) {
        state.services.suggestionService.loadChapter(p.slug, b.slug, ch)
    }
    // Сомнения переводчика: пометки [[??: …]], оставшиеся в текущем тексте главы.
    // Выводятся из фрагментов — разрешённые редактором/правками исчезают сами
    val tDoubts = remember(p.slug, b.slug, ch, criticVersion) {
        state.services.suggestionService.translatorDoubts(p.slug, b.slug, ch)
    }
    // Сомнения, которые редактура закрыла сама (маркера в тексте уже нет) — на проверку
    val tResolved = remember(p.slug, b.slug, ch, criticVersion) {
        state.services.suggestionService.translatorDoubtsResolvedByEditor(p.slug, b.slug, ch)
    }
    val verify = remember(p.slug) { state.services.verifyFor(p.slug) }

    // Действия над сомнениями общие для обеих веток: пометки переводчика видны
    // и до первого прогона корректора
    val onResolveDoubt: (String) -> Unit = { id ->
        state.services.suggestionService.resolveDoubt(p.slug, b.slug, id)
        state.refreshBook()
    }
    val onResolveTranslator: (String) -> Unit = { id ->
        state.services.suggestionService.removeTranslatorMarker(p.slug, b.slug, id)?.let { AppLog.warn("doubts", it) }
        state.refreshBook()
    }
    // Согласие с сомнением: корректор ищет места и возвращает правки в очередь
    // ревью; сомнение помечается разобранным самим разбором
    val onFixDoubt: (String, String) -> Unit = { id, note ->
        fixingId = id
        state.runDoubtFix(id, note)
    }

    Column(modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Глава $ch · ${structure?.chapters?.firstOrNull { it.index == ch }?.title ?: ""}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            BtButton(
                // Файл может существовать без прогона корректора (создан разбором
                // пометки переводчика) — тогда это ещё первый запуск
                if (file != null && file.model.isNotBlank()) "Перепроверить главу" else "Запустить корректора по главе",
                kind = BtButtonKind.Primary,
                height = 30.dp,
                enabled = running == null,
                onClick = { state.runCritic(setOf(ch), forceRedo = true) },
            )
        }
        Spacer(Modifier.height(10.dp))

        if (file == null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                TranslatorDoubtsBlock(tDoubts, running != null, fixingId, onResolveTranslator, onFixDoubt)
                ResolvedByEditorBlock(tResolved, running != null, fixingId, onFixDoubt)
                BtCard {
                    Text(
                        "Корректор по этой главе ещё не запускался.",
                        fontSize = 13.sp,
                        color = BtPalette.TextSecondary,
                    )
                }
            }
        } else {
            val onResolved: (String, Boolean) -> Unit = { resolvedId, wasOpen ->
                // Авто-переход к следующему pending: разрешённая карточка исчезает сама
                // (фильтр + refreshBook); состояние предпросмотра переносится как было
                val pendingList = file.suggestions.filter { it.status == "pending" }
                val idx = pendingList.indexOfFirst { it.id == resolvedId }
                val next = pendingList.getOrNull(idx + 1) ?: pendingList.firstOrNull()
                selectedId = if (wasOpen) next?.takeIf { it.id != resolvedId }?.id else null
            }
            val visible = file.suggestions.filter { it.status != "rejected" && it.status != "applied" }
            if (visible.isEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    if (file.suggestions.isNotEmpty()) {
                        Text(
                            "Применённых: ${file.suggestions.count { it.status == "applied" }} · " +
                                "отклонённых: ${file.suggestions.count { it.status == "rejected" }}",
                            fontSize = 12.sp,
                            color = BtPalette.TextSecondary,
                        )
                    }
                    CriticDoubtsBlock(file, running != null, fixingId, onResolveDoubt, onFixDoubt)
                    TranslatorDoubtsBlock(tDoubts, running != null, fixingId, onResolveTranslator, onFixDoubt)
                    ResolvedByEditorBlock(tResolved, running != null, fixingId, onFixDoubt)
                    if (file.suggestions.isEmpty() && file.doubts.none { it.status != "resolved" } && tDoubts.isEmpty()) {
                        Text("Предложений нет.", fontSize = 13.sp, color = BtPalette.TextSecondary)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visible) { s ->
                        SuggestionCardWithContext(
                            state, s, verify,
                            isSelected = selectedId == s.id,
                            onToggle = { selectedId = if (selectedId == s.id) null else s.id },
                            onResolved = onResolved,
                        )
                    }
                    items(listOf(Unit)) { } // разделитель
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Применённых: ${file.suggestions.count { it.status == "applied" }} · " +
                                    "отклонённых: ${file.suggestions.count { it.status == "rejected" }}",
                                fontSize = 12.sp,
                                color = BtPalette.TextSecondary,
                            )
                            CriticDoubtsBlock(file, running != null, fixingId, onResolveDoubt, onFixDoubt)
                            TranslatorDoubtsBlock(tDoubts, running != null, fixingId, onResolveTranslator, onFixDoubt)
                            ResolvedByEditorBlock(tResolved, running != null, fixingId, onFixDoubt)
                        }
                    }
                }
            }
        }
    }
}

/** Строка сомнения в обобщённой карточке: id действия, текст, необязательное место в книге. */
private data class DoubtRow(val id: String, val text: String, val location: String? = null)

/** Сомнения корректора из файла главы (d-ids): «Снять» = разобрано без правки. */
@Composable
private fun CriticDoubtsBlock(
    file: CriticChapterFile,
    busy: Boolean,
    fixingId: String?,
    onResolve: (String) -> Unit,
    onFix: (String, String) -> Unit,
) = DoubtsCard(
    "СОМНЕНИЯ (до вашего решения):",
    file.doubts.filter { it.status != "resolved" }.map { DoubtRow(it.id, it.text) },
    busy, fixingId,
    resolvedCount = file.doubts.count { it.status == "resolved" },
    onResolve = onResolve,
    onFix = onFix,
)

/** Пометки переводчика (t-ids, [[??: …]] в тексте): «Убрать пометку» вычищает маркер из текста. */
@Composable
private fun TranslatorDoubtsBlock(
    tDoubts: List<TranslatorDoubt>,
    busy: Boolean,
    fixingId: String?,
    onResolve: (String) -> Unit,
    onFix: (String, String) -> Unit,
) = DoubtsCard(
    "СОМНЕНИЯ ПЕРЕВОДЧИКА (пометки [[??: …]] в тексте):",
    tDoubts.map { DoubtRow(it.id, it.text, "фрагмент ${it.fragmentId} · блок ${it.blockIndex + 1}") },
    busy, fixingId,
    resolveLabel = "Убрать пометку",
    resolveAllLabel = "Убрать все",
    onResolve = onResolve,
    onFix = onFix,
)

/**
 * Сомнения, которые редактура закрыла САМА (маркера в тексте уже нет): текст сомнения
 * из черновика + блок, которым редактор его закрыл. Свёрнуто по умолчанию; не согласен
 * с решением — «Исправить…» корректором по текущему тексту.
 */
@Composable
private fun ResolvedByEditorBlock(
    resolved: List<TranslatorDoubts.ResolvedByEditor>,
    busy: Boolean,
    fixingId: String?,
    onFix: (String, String) -> Unit,
) {
    if (resolved.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    BtCard(contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "РЕДАКТУРА РАЗРЕШИЛА САМА — проверьте: ${resolved.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                BtButton(if (expanded) "Скрыть" else "Показать", height = 26.dp, active = expanded, onClick = { expanded = !expanded })
            }
            Text(
                "Пометки [[??: …]] из черновика, которые редактор закрыл по своему усмотрению. Не согласен с решением — «Исправить…»: правки лягут в очередь ревью.",
                fontSize = 12.sp,
                color = BtPalette.TextSecondary,
            )
            if (expanded) {
                resolved.forEach { r ->
                    Column {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(r.doubt.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            if (r.doubt.id == fixingId) {
                                Text(
                                    "корректор готовит правки…",
                                    fontSize = 12.sp,
                                    color = BtPalette.AccentText,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            } else {
                                BtButton(
                                    "Исправить…",
                                    height = 26.dp,
                                    enabled = !busy,
                                    onClick = { editId = if (editId == r.doubt.id) null else r.doubt.id },
                                )
                            }
                        }
                        Text(
                            "фрагмент ${r.doubt.fragmentId} · блок ${r.doubt.blockIndex + 1}",
                            fontSize = 11.sp,
                            color = BtPalette.Muted,
                        )
                        Text(
                            "РЕДАКТУРА НАПИСАЛА: ${r.currentBlock}",
                            fontSize = 12.sp,
                            color = BtPalette.TextSecondary,
                        )
                        if (editId == r.doubt.id && r.doubt.id != fixingId) {
                            var note by remember(r.doubt.id) { mutableStateOf("") }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                BtTextField(
                                    value = note,
                                    onValueChange = { note = it },
                                    placeholder = "Сообщение корректору — необязательно: что поправить, какой вариант предпочесть",
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                BtButton(
                                    "Отправить",
                                    kind = BtButtonKind.Primary,
                                    height = 28.dp,
                                    enabled = !busy,
                                    onClick = {
                                        onFix(r.doubt.id, note)
                                        editId = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Блок сомнений. «Исправить…» — человек согласен (+ опциональное сообщение), корректор
 * вернёт точечные правки в очередь ревью; вторая кнопка — «Снять» (сомнение корректора,
 * разобрано без правки) или «Убрать пометку» (пометка переводчика вычищается из текста).
 */
@Composable
private fun DoubtsCard(
    title: String,
    rows: List<DoubtRow>,
    busy: Boolean,
    fixingId: String?,
    resolvedCount: Int = 0,
    resolveLabel: String = "Снять",
    resolveAllLabel: String = "Снять все",
    onResolve: (String) -> Unit,
    onFix: (String, String) -> Unit,
) {
    if (rows.isEmpty()) return
    var editId by remember { mutableStateOf<String?>(null) }
    BtCard(border = BtPalette.WarnBorder, background = BtPalette.WarnBg, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (rows.size > 1) {
                    BtButton(resolveAllLabel, height = 26.dp, onClick = { rows.forEach { onResolve(it.id) } })
                }
            }
            if (resolvedCount > 0) {
                Text("разобрано: $resolvedCount", fontSize = 12.sp, color = BtPalette.TextSecondary)
            }
            rows.forEach { d ->
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(d.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        if (d.id == fixingId) {
                            Text(
                                "корректор готовит правки…",
                                fontSize = 12.sp,
                                color = BtPalette.AccentText,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        } else {
                            BtButton(
                                "Исправить…",
                                height = 26.dp,
                                enabled = !busy,
                                onClick = { editId = if (editId == d.id) null else d.id },
                            )
                            Spacer(Modifier.width(6.dp))
                            BtButton(resolveLabel, height = 26.dp, enabled = !busy, onClick = { onResolve(d.id) })
                        }
                    }
                    if (d.location != null) {
                        Text(d.location, fontSize = 11.sp, color = BtPalette.Muted)
                    }
                    if (editId == d.id && d.id != fixingId) {
                        var note by remember(d.id) { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            BtTextField(
                                value = note,
                                onValueChange = { note = it },
                                placeholder = "Сообщение корректору — необязательно: что поправить, какой вариант предпочесть",
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            BtButton(
                                "Отправить",
                                kind = BtButtonKind.Primary,
                                height = 28.dp,
                                enabled = !busy,
                                onClick = {
                                    onFix(d.id, note)
                                    editId = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCardWithContext(
    state: AppState,
    s: CriticSuggestion,
    verify: ru.sebyanin.booktranslator.core.verify.VerifyEngine,
    isSelected: Boolean,
    onToggle: () -> Unit,
    /** Вызывается после успешного применения/отклонения: (id, был ли открыт контекст). */
    onResolved: (String, Boolean) -> Unit,
) {
    val p = state.currentProject.value ?: return
    val b = state.currentBook.value ?: return
    var status by remember(s.id, s.status) { mutableStateOf(s.status) }
    var errorText by remember(s.id) { mutableStateOf<String?>(null) }
    // Отказ «встречается N раз, но правка одиночная» — не тупик: показываем override
    // прямо под ошибкой (для карточек с устаревшим grepCount основная кнопка — «Применить»).
    // Хранится пара (число вхождений, цель правки) — у вариантов «стало» свои цели
    var forceOffer by remember(s.id) { mutableStateOf<Pair<Int, CriticSuggestion>?>(null) }

    // Живые места многократной цитаты: grepCount снимался при прогоне корректора
    // и мог устареть после других правок; для кнопки «всё равно» и списка мест
    // нужна текущая картина. Одиночные (grepCount <= 1) не сканируем — карточек много.
    val multiLocations = remember(s.id, status) {
        if (s.grepCount > 1) state.services.suggestionService.locateAll(p.slug, b.slug, s) else null
    }
    val liveCount = multiLocations?.size ?: 0

    // Общий путь применения: force = ручное «везде всё равно» (основная кнопка или
    // инлайн-offer после отказа); отказ с вхождениями>1 показывает override
    val applySuggestion: (CriticSuggestion, Boolean) -> Unit = { target, force ->
        val wasOpen = isSelected
        when (val outcome = state.services.suggestionService.apply(p.slug, b.slug, target, verify, forceEverywhere = force)) {
            is SuggestionService.ApplyOutcome.Applied -> {
                status = "applied"
                forceOffer = null
                state.refreshBook()
                onResolved(s.id, wasOpen)
            }
            is SuggestionService.ApplyOutcome.Failed -> {
                errorText = outcome.reason
                val n = outcome.forceEverywhereCount ?: 0
                forceOffer = if (n > 0) n to target else null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BtCard(
            border = if (isSelected) BtPalette.Accent else BtPalette.WarnBorder,
            background = BtPalette.WarnBg,
            contentPadding = PaddingValues(14.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BtChip(s.priority, BtPalette.WarnHighlight, BtPalette.Warn)
                    Spacer(Modifier.width(6.dp))
                    BtChip(s.category, BtPalette.Bg, BtPalette.TextSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            s.grepCount == 1 -> "цитата найдена"
                            s.grepCount > 1 && s.replaceAll -> "вхождений: ${s.grepCount} (замена везде)"
                            s.grepCount > 1 -> "цитата встречается ${s.grepCount} раз"
                            else -> "цитата не найдена"
                        },
                        fontSize = 12.sp,
                        color = if (s.grepCount == 1 || (s.replaceAll && s.grepCount > 1)) BtPalette.SuccessDark else BtPalette.Warn,
                    )
                    Spacer(Modifier.weight(1f))
                    BtButton(
                        "Контекст",
                        height = 28.dp,
                        active = isSelected,
                        onClick = onToggle,
                    )
                    Spacer(Modifier.width(6.dp))
                    // Кнопка «Применить» одна только когда вариантов «стало» нет;
                    // с вариантами у каждого варианта своя кнопка (см. ниже)
                    if (s.alternatives.isEmpty()) {
                        BtButton(
                            when {
                                s.replaceAll && s.grepCount > 1 -> "Применить везде (${s.grepCount})"
                                liveCount > 1 -> "Применить везде ($liveCount) всё равно"
                                else -> "Применить"
                            },
                            kind = BtButtonKind.Warning,
                            height = 28.dp,
                            enabled = status == "pending",
                            onClick = { applySuggestion(s, liveCount > 1 && !s.replaceAll) },
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    BtButton(
                        "Отклонить",
                        height = 28.dp,
                        enabled = status == "pending",
                        onClick = {
                            val wasOpen = isSelected
                            state.services.suggestionService.reject(p.slug, b.slug, s.id)
                            status = "rejected"
                            // Обновить список (статусы правок живут вне манифеста)
                            state.refreshBook()
                            onResolved(s.id, wasOpen)
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("БЫЛО:  ${s.was}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)

                if (s.alternatives.isEmpty()) {
                    Text("СТАЛО: ${s.became}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                } else {
                    // Варианты: у каждого своя кнопка «Применить»
                    val variants = listOf(s.became) + s.alternatives
                    variants.forEachIndexed { vi, variant ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                (if (variants.size > 1) "СТАЛО-${'а' + vi}: " else "СТАЛО: ") + variant,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            BtButton(
                                when {
                                    s.replaceAll && s.grepCount > 1 -> "Везде (${s.grepCount})"
                                    liveCount > 1 -> "Везде всё равно ($liveCount)"
                                    else -> "Применить"
                                },
                                kind = BtButtonKind.Warning,
                                height = 26.dp,
                                enabled = status == "pending",
                                onClick = { applySuggestion(s.copy(became = variant), liveCount > 1 && !s.replaceAll) },
                            )
                        }
                    }
                }
                if (s.why.isNotBlank()) {
                    Text(s.why, fontSize = 12.sp, color = BtPalette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                errorText?.let {
                    Text("Не применено: $it", fontSize = 12.sp, color = BtPalette.Warn, modifier = Modifier.padding(top = 4.dp))
                }
                forceOffer?.let { (n, target) ->
                    if (status == "pending") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                "Цитата встречается $n раз — можно заменить во всех местах:",
                                fontSize = 12.sp,
                                color = BtPalette.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            BtButton(
                                "Применить везде ($n) всё равно",
                                kind = BtButtonKind.Warning,
                                height = 26.dp,
                                onClick = { applySuggestion(target, true) },
                            )
                        }
                    }
                }
            }
        }

        // Контекст: где это написано. Одно место — пара «было/стало»;
        // несколько (пачечная правка или «всё равно») — список всех мест замены
        if (isSelected) {
            val locations = multiLocations ?: remember(s.id, status) {
                state.services.suggestionService.locateAll(p.slug, b.slug, s)
            }
            val location = locations.singleOrNull()
            if (locations.isEmpty()) {
                BtCard { Text("Место не найдено: цитаты нет в текущем переводе главы.", fontSize = 12.sp, color = BtPalette.TextSecondary) }
            } else if (location != null) {
                BtCard(border = BtPalette.AccentBorderLight, contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Фрагмент ${location.fragmentId}, блок ${location.blockIndex + 1}",
                            fontSize = 11.sp,
                            color = BtPalette.AccentText,
                        )
                        // Пара «было / стало» — обе стороны от ПЕРЕВОДА.
                        // До применения: слева текущий текст с цитатой, справа превью замены.
                        // После применения: цитата «было» в тексте уже заменена — слева
                        // восстанавливаем «было» обратной подстановкой, справа актуальное.
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                SectionCaption("БЫЛО · ПЕРЕВОД ДО ПРАВКИ")
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (status == "applied") {
                                        replacedView(location.trBlock, from = s.became, to = s.was, bg = BtPalette.WarnHighlight, fg = BtPalette.Text)
                                    } else {
                                        highlightNeedle(location.trBlock, s.was, BtPalette.WarnHighlight)
                                    },
                                    fontSize = 14.sp,
                                    fontFamily = BtFonts.Book,
                                    lineHeight = 21.sp,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                SectionCaption("СТАЛО · ПОСЛЕ ПРАВКИ", color = BtPalette.SuccessDark)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    replacedView(
                                        location.trBlock,
                                        from = s.was,
                                        to = s.became,
                                        bg = BtPalette.AccentBg,
                                        fg = BtPalette.AccentText,
                                    ),
                                    fontSize = 14.sp,
                                    fontFamily = BtFonts.Book,
                                    lineHeight = 21.sp,
                                )
                            }
                        }
                        // Японский оригинал — справочной строкой
                        if (location.srcBlock.isNotBlank()) {
                            Text(
                                "Оригинал: ${location.srcBlock}",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = BtPalette.TextSecondary,
                                maxLines = 4,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                BtCard(border = BtPalette.AccentBorderLight, contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Встречается ${locations.size} раз — «Применить везде» меняет каждое:",
                            fontSize = 12.sp,
                            color = BtPalette.AccentText,
                        )
                        locations.forEach { loc ->
                            Column {
                                Text(
                                    "Фрагмент ${loc.fragmentId}, блок ${loc.blockIndex + 1}",
                                    fontSize = 11.sp,
                                    color = BtPalette.AccentText,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (status == "applied") highlightNeedle(loc.trBlock, s.became, BtPalette.AccentBg)
                                    else highlightNeedle(loc.trBlock, s.was, BtPalette.WarnHighlight),
                                    fontSize = 14.sp,
                                    fontFamily = BtFonts.Book,
                                    lineHeight = 21.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Текст блока с подстановкой: каждое вхождение [from] визуально заменено на [to]
 * (подсвечено). Для «стало»: from=было, to=стало; для ретроспективы «было»
 * после применения: from=стало, to=было.
 */
private fun replacedView(block: String, from: String, to: String, bg: Color, fg: Color): AnnotatedString {
    val needles = listOf(from.trim(), from.trim('«', '»', '"', ' ').trim())
        .filter { it.length >= 3 }
        .distinct()
    val needle = needles.firstOrNull { block.contains(it) } ?: return AnnotatedString(block)
    val replacement = to.trim().removePrefix("«").removeSuffix("»")
    return buildAnnotatedString {
        var start = 0
        var idx = block.indexOf(needle, start)
        while (idx >= 0) {
            append(block.substring(start, idx))
            withStyle(SpanStyle(background = bg, color = fg)) { append(replacement) }
            start = idx + needle.length
            idx = block.indexOf(needle, start)
        }
        append(block.substring(start))
    }
}

private fun highlightNeedle(block: String, was: String, bg: Color, minLen: Int = 3): AnnotatedString {
    val needles = listOf(was.trim(), was.trim('«', '»', '"', ' ').trim())
        .filter { it.length >= minLen }
        .distinct()
    val needle = needles.firstOrNull { block.contains(it) } ?: return AnnotatedString(block)
    return buildAnnotatedString {
        var start = 0
        var idx = block.indexOf(needle, start)
        while (idx >= 0) {
            append(block.substring(start, idx))
            withStyle(SpanStyle(background = bg)) { append(needle) }
            start = idx + needle.length
            idx = block.indexOf(needle, start)
        }
        append(block.substring(start))
    }
}

/**
 * Подсветка транскрипции в переводе: по границам слова (иначе короткое «оти»
 * поймалось бы внутри «потихоньку»). Берётся первый найденный вариант перевода.
 */
private fun highlightTranscription(block: String, candidates: List<String>, bg: Color): AnnotatedString {
    val needle = candidates.asSequence()
        .map { it.trim() }
        .firstOrNull { c -> c.length >= 2 && transcriptionRegex(c).containsMatchIn(block) }
        ?: return AnnotatedString(block)
    val regex = transcriptionRegex(needle)
    return buildAnnotatedString {
        var last = 0
        regex.findAll(block).forEach { m ->
            append(block.substring(last, m.range.first))
            withStyle(SpanStyle(background = bg)) { append(m.value) }
            last = m.range.last + 1
        }
        append(block.substring(last))
    }
}

private fun transcriptionRegex(needle: String): Regex =
    Regex("(?<![\\p{L}\\p{Nd}])" + Regex.escape(needle) + "(?![\\p{L}\\p{Nd}])", RegexOption.IGNORE_CASE)

// ---------------- Глоссарий ----------------

@Composable
fun GlossaryTab(state: AppState, modifier: Modifier = Modifier) {
    val glossary by state.glossary.collectAsState()
    val notes by state.notes.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    Column(modifier.padding(12.dp)) {
        // Оффер «канонический перевод термина изменился — перенести в текст?»
        // (утверждение альтернативы, правка в «Терминах», «Поправить на» ревизора)
        TermReplaceBanner(state)
        val proposedCount = glossary.count { it.status == "proposed" } + notes.count { it.status == "proposed" }
        BtTabs(
            tabs = listOf(
                BtTab("К утверждению", (proposedCount.takeIf { it > 0 })?.toString()),
                BtTab("Термины"),
                BtTab("Заметки"),
            ),
            selected = tab,
            onSelect = { tab = it },
        )
        Spacer(Modifier.height(8.dp))
        BtTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Поиск: термин, перевод, пояснение…")
        if (query.isNotBlank()) {
            val found = when (tab) {
                0 -> glossary.count { it.status == "proposed" && glossaryMatches(it, query) } +
                    notes.count { it.status == "proposed" && noteMatches(it, query) }
                1 -> glossary.count { it.isApproved && glossaryMatches(it, query) }
                else -> notes.count { it.status == "approved" && noteMatches(it, query) }
            }
            Text("Найдено: $found", fontSize = 12.sp, color = BtPalette.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            0 -> ProposalsList(state, glossary, notes, query)
            1 -> TermsList(state, glossary, query)
            2 -> NotesList(state, notes, query)
        }
    }
}

/**
 * Банер «Замена термина в переводе»: слой 1 — точная замена старого варианта
 * везде (бесплатно, транзакционно), слой 2 — прогон модели по фрагментам,
 * где слово встречается в любой форме (склонения). Скан книги — на IO;
 * criticVersion в ключах: после замены/прогона данные на диске обновились,
 * пересканируем (оба нуля — оффер снимается сам: менять нечего).
 */
@Composable
private fun TermReplaceBanner(state: AppState) {
    val offer by state.termReplaceOffer.collectAsState()
    val o = offer ?: return
    val p = state.currentProject.value ?: return
    val b = state.currentBook.value ?: return
    val cv by state.criticVersion.collectAsState()
    val running by state.runningStage.collectAsState()
    var scan by remember(p.slug, b.slug, o.from, o.to, cv) {
        mutableStateOf<TermReplacer.Scan?>(null)
    }
    var result by remember(o.from, o.to) { mutableStateOf<String?>(null) }
    // Что будет заменено: немного мест — показать сразу, много — за кнопкой
    var showAllPlaces by remember(o.from, o.to) { mutableStateOf(false) }
    LaunchedEffect(p.slug, b.slug, o.from, o.to, cv) {
        val s = withContext(Dispatchers.IO) { state.services.termReplacer.scan(p.slug, b.slug, o.from) }
        scan = s
        if (s.occurrences == 0 && s.stemFragmentIds.isEmpty()) state.termReplaceOffer.value = null
    }
    val s = scan ?: return
    val profile = remember { state.services.settings.value.profileFor(AgentRole.TERM_FIX) }

    BtCard(border = BtPalette.AccentBorderLight, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionCaption("ЗАМЕНА ТЕРМИНА В ПЕРЕВОДЕ", Modifier.weight(1f))
                BtButton("Скрыть", height = 26.dp, onClick = { state.termReplaceOffer.value = null })
            }
            Text("«${o.from}» → «${o.to}»", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (s.occurrences > 0) {
                Text(
                    "Точных вхождений: ${s.occurrences} в ${s.fragmentIds.size} фрагментах — точная замена бесплатна и проверяется автоматически.",
                    fontSize = 12.sp,
                    color = BtPalette.TextSecondary,
                )
            } else {
                Text("Точных вхождений не осталось.", fontSize = 12.sp, color = BtPalette.TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.occurrences > 0) {
                    BtButton(
                        "Заменить везде (${s.occurrences})",
                        kind = BtButtonKind.Primary,
                        height = 28.dp,
                        onClick = {
                            state.replaceTermEverywhere(o.from, o.to) { outcome ->
                                result = when (outcome) {
                                    is TermReplacer.ReplaceOutcome.Done ->
                                        "Заменено: ${outcome.replaced} вхождений в ${outcome.fragments.size} фрагментах"
                                    is TermReplacer.ReplaceOutcome.Failed ->
                                        "Не заменено: ${outcome.reason}"
                                }
                            }
                        },
                    )
                }
                if (s.stemFragmentIds.isNotEmpty()) {
                    BtButton(
                        "Прогнать модель по формам (${s.stemFragmentIds.size} фрагм.)",
                        kind = if (s.occurrences > 0) BtButtonKind.Ghost else BtButtonKind.Primary,
                        height = 28.dp,
                        enabled = running == null,
                        onClick = { state.runTermFix(o.from, o.to, s.stemFragmentIds) },
                    )
                }
            }
            if (s.stemFragmentIds.isNotEmpty()) {
                Text(
                    "Модель меняет склонения («Карла»/«Карлы»/«Карле») только в ${s.stemFragmentIds.size} затронутых фрагментах" +
                        (profile?.let { "; модель роли «Замена термина»: ${it.name}" }
                            ?: "; модели нет — настройте роль в Настройках"),
                    fontSize = 11.sp,
                    color = BtPalette.Muted,
                )
            }
            // Что будет заменено: каждое точное вхождение, как у корректора в «ВЕЗДЕ»
            if (s.occurrences > 0) {
                val fewPlaces = s.exact.size <= 8
                val expanded = fewPlaces || showAllPlaces
                if (!fewPlaces) {
                    BtButton(
                        if (showAllPlaces) "Скрыть места (${s.occurrences})" else "Показать все места (${s.occurrences})",
                        height = 26.dp,
                        active = showAllPlaces,
                        onClick = { showAllPlaces = !showAllPlaces },
                    )
                }
                if (expanded) {
                    Text(
                        "«Заменить везде» меняет каждое вхождение:",
                        fontSize = 12.sp,
                        color = BtPalette.AccentText,
                    )
                    s.exact.forEach { hit ->
                        Column {
                            Text(
                                "Фрагмент ${hit.fragmentId}, блок ${hit.blockIndex + 1}" +
                                    (if (hit.count > 1) " · вхождений: ${hit.count}" else ""),
                                fontSize = 11.sp,
                                color = BtPalette.AccentText,
                            )
                            Text(
                                highlightNeedle(hit.text, o.from, BtPalette.WarnHighlight, minLen = 2),
                                fontSize = 14.sp,
                                fontFamily = BtFonts.Book,
                                lineHeight = 21.sp,
                            )
                        }
                    }
                }
            }
            result?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (it.startsWith("Не заменено")) BtPalette.Warn else BtPalette.SuccessDark,
                )
            }
        }
    }
}

/** Фильтр поиска по записи глоссария: оригинал, перевод, варианты, пояснения. */
private fun glossaryMatches(e: GlossaryEntry, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return e.original.lowercase().contains(needle) ||
        e.translation.lowercase().contains(needle) ||
        e.alternatives.any { it.lowercase().contains(needle) } ||
        e.reason.lowercase().contains(needle) ||
        e.context.lowercase().contains(needle) ||
        e.note.lowercase().contains(needle)
}

/** Фильтр поиска по заметке: сущность, ситуация, текст. */
private fun noteMatches(n: KnowledgeNote, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return n.entity.lowercase().contains(needle) ||
        n.situation.lowercase().contains(needle) ||
        n.text.lowercase().contains(needle)
}

@Composable
private fun ProposalsList(state: AppState, glossary: List<GlossaryEntry>, notes: List<KnowledgeNote>, query: String) {
    val proposedTerms = glossary.filter { it.status == "proposed" && glossaryMatches(it, query) }
    val proposedNotes = notes.filter { it.status == "proposed" && noteMatches(it, query) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Ревизия — инструмент разбора очереди; в режиме поиска не мешает
        if (query.isBlank()) {
            GlossaryRevisionBlock(state, glossary)
            // Группа «РЕВИЗОР» — между механической ревизией и очередью
            GlossaryRevisorBlock(state, glossary)
        }
        if (proposedTerms.isEmpty() && proposedNotes.isEmpty()) {
            BtCard {
                Text(
                    if (query.isNotBlank()) "По запросу «${query.trim()}» ничего не найдено."
                    else "Очередь пуста. Предложения появляются из маркеров [[имя?: …]] в черновиках.",
                    fontSize = 13.sp,
                    color = BtPalette.TextSecondary,
                )
            }
        }
        proposedTerms.forEach { e ->
            GlossaryProposalCard(state, e)
        }
        proposedNotes.forEach { n ->
            BtCard {
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(n.entity, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                if (n.situation.isNotBlank()) Text(" [${n.situation}]", fontSize = 13.sp, color = BtPalette.TextSecondary)
                            }
                            Text(n.text, fontSize = 14.sp)
                        }
                        BtButton("Утвердить", kind = BtButtonKind.Success, height = 30.dp, onClick = { state.approveNote(n) })
                        Spacer(Modifier.width(6.dp))
                        BtButton("Отклонить", height = 30.dp, onClick = { state.rejectNote(n) })
                    }
                    CustomApproveRow(placeholder = "Свой текст заметки") { custom -> state.approveNote(n, textOverride = custom) }
                }
            }
        }
    }
}

/**
 * Ревизия очереди: составные имена (через «・»), все части которых уже предложены
 * или утверждены отдельными записями. Такое составное имя — дубль: перевод собирается
 * из частей. Ничего не удаляется без явного нажатия; расхождение перевода составной
 * записи со склейкой частей показывается предупреждением.
 */
@Composable
private fun GlossaryRevisionBlock(state: AppState, glossary: List<GlossaryEntry>) {
    val redundant = remember(glossary) { GlossaryRevision.findRedundant(glossary) }
    val redundantCanon = remember(glossary) { GlossaryRevision.findRedundantApproved(glossary) }
    // Упакованные записи: несколько терминов в одном предложении («Карла; カリル → Карл»)
    val packed = remember(glossary) {
        GlossaryRevision.findPacked(glossary).filter { it.entry.status == "proposed" || it.entry.status == "approved" }
    }
    val packedQueue = packed.filter { it.entry.status == "proposed" }
    val packedCanon = packed.filter { it.entry.status == "approved" }
    if (redundant.isEmpty() && redundantCanon.isEmpty() && packedQueue.isEmpty() && packedCanon.isEmpty()) return
    var open by remember { mutableStateOf(false) }

    fun noteFor(r: GlossaryRevision.Redundant, inCanon: Boolean): String {
        val head = (if (inCanon) "убрано ревизией канона: части уже утверждены (" else "убрано ревизией: части уже в глоссарии (") +
            r.parts.joinToString(", ") { it.original } + ")"
        return if (r.consistent) head
        else "$head; составной вариант «${r.composite.translation}» расходился с частями («${r.composed}»)"
    }

    BtCard {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionCaption("РЕВИЗИЯ ОЧЕРЕДИ")
                    Text(
                        buildString {
                            if (redundant.isNotEmpty() || redundantCanon.isNotEmpty()) {
                                append("Покрытых своими частями: ${redundant.size} в очереди")
                                if (redundantCanon.isNotEmpty()) append(" · ${redundantCanon.size} в каноне")
                            }
                            if (packedQueue.isNotEmpty() || packedCanon.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append("упакованных записей: ${packedQueue.size} в очереди")
                                if (packedCanon.isNotEmpty()) append(" · ${packedCanon.size} в каноне")
                            }
                        },
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                BtButton(
                    if (open) "Скрыть" else "Показать",
                    height = 28.dp,
                    active = open,
                    onClick = { open = !open },
                )
                if (redundant.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    BtButton(
                        "Убрать все (${redundant.size})",
                        kind = BtButtonKind.Primary,
                        height = 28.dp,
                        onClick = { redundant.forEach { state.rejectTermDuplicate(it.composite, noteFor(it, inCanon = false)) } },
                    )
                }
            }
            if (open) {
                if (redundant.isNotEmpty()) {
                    if (redundantCanon.isNotEmpty()) RevisionGroupCaption("В ОЧЕРЕДИ")
                    redundant.forEach { r ->
                        RevisionRow(r, partsLabel = "части уже в глоссарии") {
                            state.rejectTermDuplicate(r.composite, noteFor(r, inCanon = false))
                        }
                    }
                }
                if (redundantCanon.isNotEmpty()) {
                    RevisionGroupCaption("УЖЕ В КАНОНЕ")
                    redundantCanon.forEach { r ->
                        RevisionRow(r, partsLabel = "части уже утверждены") {
                            state.rejectTermDuplicate(r.composite, noteFor(r, inCanon = true))
                        }
                    }
                }
                if (packedQueue.isNotEmpty()) {
                    RevisionGroupCaption("УПАКОВАННЫЕ · НЕСКОЛЬКО ТЕРМИНОВ В ОДНОЙ ЗАПИСИ (В ОЧЕРЕДИ)")
                    packedQueue.forEach { pk ->
                        PackedRow(pk) { state.splitPackedEntry(pk.entry) }
                    }
                }
                if (packedCanon.isNotEmpty()) {
                    RevisionGroupCaption("УПАКОВАННЫЕ · В КАНОНЕ")
                    packedCanon.forEach { pk ->
                        PackedRow(pk) { state.splitPackedEntry(pk.entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RevisionGroupCaption(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        color = BtPalette.TextSecondary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun RevisionRow(r: GlossaryRevision.Redundant, partsLabel: String, onRemove: () -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${r.composite.original} → ${r.composite.translation}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            BtButton("Убрать", height = 26.dp, onClick = onRemove)
        }
        Text(
            "$partsLabel: " + r.parts.joinToString("; ") { "${it.original} → ${it.translation}" },
            fontSize = 12.sp,
            color = BtPalette.TextSecondary,
        )
        if (!r.consistent) {
            Text(
                "составной вариант «${r.composite.translation}» расходится со склейкой частей («${r.composed}») — каноном останутся части",
                fontSize = 12.sp,
                color = BtPalette.Warn,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Строка упакованной записи: первая пара перепишет запись, остальные уйдут в очередь отдельными предложениями. */
@Composable
private fun PackedRow(pk: GlossaryRevision.Packed, onSplit: () -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${pk.entry.original} → ${pk.entry.translation}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            BtButton("Разделить (${pk.parts.size})", height = 26.dp, onClick = onSplit)
        }
        Text(
            "в записи несколько терминов: " + pk.parts.joinToString("; ") { "${it.first} → ${it.second}" },
            fontSize = 12.sp,
            color = BtPalette.TextSecondary,
        )
    }
}

/**
 * Группа «РЕВИЗОР» (LLM-прогон очереди глоссария): карточки только для pending
 * вердиктов drop/fix. Решения — только по кнопкам человека («Убрать все» нет);
 * keep карточек не создаёт. Данные — файл вне манифеста, поэтому подписка на
 * StateFlow + ключ glossaryRevisionVersion у перезагрузки в AppState.
 */
@Composable
private fun GlossaryRevisorBlock(state: AppState, glossary: List<GlossaryEntry>) {
    val decisions by state.glossaryRevision.collectAsState()
    val pending = decisions.filter { it.isActionable }
    if (pending.isEmpty()) return
    val confirmed = decisions.count { it.status == "pending" && it.verdict == "keep" }
    val resolved = decisions.count { it.status != "pending" }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCaption("РЕВИЗОР (LLM)")
        Text(
            buildString {
                append("Замечаний к решению: ${pending.size}")
                if (confirmed > 0) append(" · подтверждено ревизором: $confirmed")
                if (resolved > 0) append(" · разобрано: $resolved")
            },
            fontSize = 13.sp,
        )
        pending.forEach { d ->
            GlossaryRevisorCard(state, d, glossary.firstOrNull { it.original == d.original })
        }
    }
}

@Composable
private fun GlossaryRevisorCard(state: AppState, d: GlossaryRevisionDecision, entry: GlossaryEntry?) {
    BtCard(border = BtPalette.WarnBorder, background = BtPalette.WarnBg, contentPadding = PaddingValues(14.dp)) {
        // ВНИМАНИЕ: BtCard — это Box; несколько детей — только внутри Column
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BtChip(
                    when (d.verdict) {
                        "drop" -> "убрать"
                        "fix" -> "исправить"
                        else -> "разделить"
                    },
                    BtPalette.WarnHighlight,
                    BtPalette.Warn,
                )
                if (d.scope == "canon") {
                    Spacer(Modifier.width(6.dp))
                    BtChip("канон", BtPalette.Bg, BtPalette.TextSecondary)
                }
                Spacer(Modifier.weight(1f))
                BtButton("Оставить", height = 28.dp, onClick = { state.dismissRevision(d) })
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${d.original} → ${entry?.translation ?: "(уже не в очереди)"}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (d.verdict == "fix" && d.suggested.isNotBlank()) {
                Text(
                    "предлагает: ${d.suggested}",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (d.verdict == "split" && d.parts.isNotEmpty()) {
                Text(
                    "разделить на записи:",
                    fontSize = 12.sp,
                    color = BtPalette.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                d.parts.forEach { part ->
                    Text(
                        "${part.original} → ${part.translation}",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (d.reason.isNotBlank()) {
                Text(d.reason, fontSize = 12.sp, color = BtPalette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row {
                when (d.verdict) {
                    "fix" -> BtButton(
                        "Поправить на «${d.suggested}»",
                        kind = BtButtonKind.Primary,
                        height = 28.dp,
                        onClick = { state.applyRevisionFix(d) },
                    )
                    "split" -> BtButton(
                        "Разделить (${d.parts.size})",
                        kind = BtButtonKind.Primary,
                        height = 28.dp,
                        onClick = { state.applyRevisionSplit(d) },
                    )
                    else -> BtButton(
                        "Убрать",
                        kind = BtButtonKind.Warning,
                        height = 28.dp,
                        onClick = { state.applyRevisionDrop(d) },
                    )
                }
            }
        }
    }
}

/**
 * Карточка предложения термина в очереди: термин → вариант, пояснение
 * (почему попало в очередь — из пометки переводчика) и контекст места в книге.
 */
@Composable
private fun GlossaryProposalCard(state: AppState, e: GlossaryEntry) {
    var open by remember(e.original) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BtCard {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(e.original, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(" → ${e.translation}", fontSize = 15.sp)
                        }
                        if (e.context.isNotBlank()) Text(e.context, fontSize = 12.sp, color = BtPalette.TextSecondary)
                    }
                    BtButton("Контекст", height = 28.dp, active = open, onClick = { open = !open })
                    Spacer(Modifier.width(6.dp))
                    BtButton("Утвердить", kind = BtButtonKind.Success, height = 30.dp, onClick = { state.approveTerm(e, e.translation) })
                    Spacer(Modifier.width(6.dp))
                    BtButton("Отклонить", height = 30.dp, onClick = { state.rejectTerm(e) })
                }
                // Почему в очереди: предложение приходит только из пометки переводчика в черновике
                Text(
                    "Слова не было в словаре — переводчик передал его транскрипцией и пометил ([[имя?: …]]) на утверждение.",
                    fontSize = 12.sp,
                    color = BtPalette.TextSecondary,
                )
                if (e.reason.isNotBlank()) {
                    Text("Пояснение переводчика: ${e.reason}", fontSize = 13.sp)
                }
                // Альтернативные варианты от Терминолога — утвердить можно любой
                e.alternatives.forEach { alt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "вариант: $alt",
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        BtButton(
                            "Утвердить",
                            kind = BtButtonKind.Success,
                            height = 28.dp,
                            onClick = { state.approveTerm(e, alt) },
                        )
                    }
                }
                CustomApproveRow { custom -> state.approveTerm(e, custom) }
            }
        }

        // Контекст: где термин написан (пара ОРИГИНАЛ/ПЕРЕВОД, как у корректора)
        if (open) {
            GlossaryContextCard(state, e)
        }
    }
}

@Composable
private fun GlossaryContextCard(state: AppState, e: GlossaryEntry) {
    val p = state.currentProject.value ?: return
    val b = state.currentBook.value
    if (b == null) {
        BtCard { Text("Книга не выбрана: контекст недоступен.", fontSize = 12.sp, color = BtPalette.TextSecondary) }
        return
    }
    val location = remember(p.slug, b.slug, e.original, e.context) {
        state.services.suggestionService.locateTerm(p.slug, b.slug, e)
    }
    if (location == null) {
        BtCard {
            Text(
                "Место не найдено: «${e.original}» нет в блоках оригинала этой книги (возможно, термин предложен в другой книге).",
                fontSize = 12.sp,
                color = BtPalette.TextSecondary,
            )
        }
        return
    }
    BtCard(border = BtPalette.AccentBorderLight, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Фрагмент ${location.fragmentId}, блок ${location.blockIndex + 1}",
                fontSize = 11.sp,
                color = BtPalette.AccentText,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    SectionCaption("ОРИГИНАЛ")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        highlightNeedle(location.srcBlock, e.original, BtPalette.WarnHighlight, minLen = 1),
                        fontSize = 14.sp,
                        fontFamily = BtFonts.Book,
                        lineHeight = 21.sp,
                    )
                }
                Column(Modifier.weight(1f)) {
                    SectionCaption("ПЕРЕВОД")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        highlightTranscription(location.trBlock, listOf(e.translation) + e.alternatives, BtPalette.WarnHighlight),
                        fontSize = 14.sp,
                        fontFamily = BtFonts.Book,
                        lineHeight = 21.sp,
                    )
                }
            }
        }
    }
}

/** Строка «Свой вариант»: кнопка раскрывает поле, подтверждение утвердит с этим текстом. */
@Composable
private fun CustomApproveRow(placeholder: String = "Свой вариант перевода", onApprove: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    if (!open) {
        Row {
            BtButton("Свой вариант", height = 26.dp, onClick = { open = true })
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BtTextField(value, { value = it }, Modifier.weight(1f), placeholder = placeholder, singleLine = false)
            BtButton(
                "Утвердить вариант",
                kind = BtButtonKind.Success,
                height = 28.dp,
                enabled = value.isNotBlank(),
                onClick = {
                    onApprove(value.trim())
                    open = false
                },
            )
            BtButton("Отмена", height = 28.dp, onClick = { open = false; value = "" })
        }
    }
}

@Composable
private fun TermsList(state: AppState, glossary: List<GlossaryEntry>, query: String) {
    var editing by remember { mutableStateOf<String?>(null) }
    var editValue by remember { mutableStateOf("") }
    var addOpen by remember { mutableStateOf(false) }
    val project = state.currentProject.value
    val visible = glossary.filter { it.isApproved && glossaryMatches(it, query) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionCaption("УТВЕРЖДЁННЫЕ ТЕРМИНЫ (${visible.size})", Modifier.weight(1f))
            BtButton(if (addOpen) "Скрыть форму" else "+ Добавить", height = 28.dp, active = addOpen, onClick = { addOpen = !addOpen })
        }

        if (addOpen && project != null) {
            var original by remember { mutableStateOf("") }
            var translation by remember { mutableStateOf("") }
            var category by remember { mutableStateOf("term") }
            BtCard {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BtTextField(original, { original = it }, Modifier.weight(1f), placeholder = "Оригинал (например, 御釈迦様)")
                        BtTextField(translation, { translation = it }, Modifier.weight(1f), placeholder = "Перевод (например, Будда)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BtDropdown(
                            options = listOf("term", "person", "place", "other"),
                            selected = category,
                            onSelect = { category = it ?: "term" },
                        )
                        Spacer(Modifier.weight(1f))
                        BtButton(
                            "Добавить в канон",
                            kind = BtButtonKind.Success,
                            height = 30.dp,
                            enabled = original.isNotBlank() && translation.isNotBlank(),
                            onClick = {
                                state.services.knowledge.addManualTerm(project.slug, original, translation, category)
                                state.refreshKnowledge()
                                original = ""; translation = ""
                                addOpen = false
                            },
                        )
                    }
                }
            }
        }

        visible.forEach { e ->
            BtCard {
                // ВНИМАНИЕ: BtCard — это Box; строка и поле редактирования — только
                // внутри Column, иначе непрозрачное поле рисуется ПОВЕРХ строки и
                // накрывает «Сохр.» (клики уходят в поле — сохранить невозможно)
                Column(Modifier.padding(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.original, fontSize = 14.sp, modifier = Modifier.weight(0.4f))
                        Text(e.translation, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.4f))
                        BtChip(e.category, BtPalette.Bg, BtPalette.TextSecondary)
                        Spacer(Modifier.width(8.dp))
                        if (editing == e.original) {
                            BtButton("Сохр.", height = 26.dp, onClick = {
                                state.approveTerm(e, editValue.trim().ifBlank { e.translation })
                                editing = null
                            })
                        } else {
                            BtButton("Править", height = 26.dp, onClick = {
                                editing = e.original
                                editValue = e.translation
                            })
                        }
                        Spacer(Modifier.width(6.dp))
                        BtButton("Удалить", height = 26.dp, onClick = { state.removeTerm(e) })
                    }
                    if (editing == e.original) {
                        Spacer(Modifier.height(6.dp))
                        BtTextField(editValue, { editValue = it }, Modifier.fillMaxWidth(), placeholder = "Новый перевод термина")
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            BtCard {
                Text(
                    if (query.isNotBlank()) "По запросу «${query.trim()}» ничего не найдено."
                    else "Утверждённых терминов нет.",
                    fontSize = 13.sp,
                    color = BtPalette.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun NotesList(state: AppState, notes: List<KnowledgeNote>, query: String) {
    var addOpen by remember { mutableStateOf(false) }
    val project = state.currentProject.value
    val visible = notes.filter { it.status == "approved" && noteMatches(it, query) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionCaption("УТВЕРЖДЁННЫЕ ЗАМЕТКИ (${visible.size})", Modifier.weight(1f))
            BtButton(if (addOpen) "Скрыть форму" else "+ Добавить", height = 28.dp, active = addOpen, onClick = { addOpen = !addOpen })
        }

        if (addOpen && project != null) {
            var entity by remember { mutableStateOf("") }
            var situation by remember { mutableStateOf("") }
            var text by remember { mutableStateOf("") }
            BtCard {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BtTextField(entity, { entity = it }, Modifier.fillMaxWidth(), placeholder = "Сущность (персонаж, место, понятие)")
                    BtTextField(situation, { situation = it }, Modifier.fillMaxWidth(), placeholder = "Ситуация (например: при испуге) — необязательно")
                    BtTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = "Текст заметки: голос, манера, обращение…")
                    Row {
                        Text(
                            "Попадает в брифы, когда сущность встречается во фрагменте.",
                            fontSize = 12.sp,
                            color = BtPalette.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        BtButton(
                            "Добавить в канон",
                            kind = BtButtonKind.Success,
                            height = 30.dp,
                            enabled = entity.isNotBlank() && text.isNotBlank(),
                            onClick = {
                                state.services.knowledge.addManualNote(project.slug, entity, situation, text)
                                state.refreshKnowledge()
                                entity = ""; situation = ""; text = ""
                                addOpen = false
                            },
                        )
                    }
                }
            }
        }

        visible.forEach { n ->
            BtCard {
                Column(Modifier.padding(4.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(n.entity, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (n.situation.isNotBlank()) Text(" [${n.situation}]", fontSize = 12.sp, color = BtPalette.TextSecondary)
                            }
                            Text(n.text, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        BtButton("Удалить", height = 26.dp, onClick = { state.removeNote(n) })
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            BtCard {
                Text(
                    if (query.isNotBlank()) "По запросу «${query.trim()}» ничего не найдено."
                    else "Утверждённых заметок нет. Заметки — голоса персонажей, обращения, манеры.",
                    fontSize = 13.sp,
                    color = BtPalette.TextSecondary,
                )
            }
        }
    }
}

// ---------------- Журнал ----------------

// ClipEntry(native) на десктопе помечен ExperimentalComposeUiApi, но это единственный
// документированный способ собрать буфер из текста (замена deprecated LocalClipboardManager)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LogTab(state: AppState, modifier: Modifier = Modifier) {
    val events by state.logEvents.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copyNote by remember { mutableStateOf<String?>(null) }

    Column(modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Событий: ${events.size}",
                fontSize = 13.sp,
                color = BtPalette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            copyNote?.let { Text(it, fontSize = 12.sp, color = BtPalette.SuccessDark, modifier = Modifier.padding(end = 12.dp)) }
            BtButton(
                "Копировать всё",
                height = 28.dp,
                onClick = {
                    // Новый API буфера: setClipEntry suspend-ный, на десктопе нативная
                    // обёртка — AWT Transferable (StringSelection кладёт plain text)
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(StringSelection(AppLog.plainText())))
                        copyNote = "скопировано"
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
            BtButton("Очистить", height = 28.dp, onClick = { AppLog.clear() })
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(events.reversed()) { e ->
                    val lineColor = when (e.level) {
                        LogLevel.ERROR -> BtPalette.Warn
                        LogLevel.WARN -> BtPalette.AccentText
                        else -> BtPalette.Text
                    }
                    Text(
                        "${e.formattedTime()} [${e.level}] ${e.source}: ${e.message}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = lineColor,
                    )
                }
            }
        }
    }
}
