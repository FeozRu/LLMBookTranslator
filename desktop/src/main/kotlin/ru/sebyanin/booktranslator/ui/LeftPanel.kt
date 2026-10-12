package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.core.model.BookMeta
import ru.sebyanin.booktranslator.core.model.ProjectMeta
import ru.sebyanin.booktranslator.ui.components.BtButton
import ru.sebyanin.booktranslator.ui.components.StatusDot
import ru.sebyanin.booktranslator.ui.theme.BtPalette

@Composable
private fun statusColor(status: String): Color? = when (status) {
    "done" -> BtPalette.Success
    "editing", "drafted" -> BtPalette.Accent
    "failed" -> BtPalette.Warn
    else -> null // полая точка = в очереди
}

/** Отступ содержимого зоны: выравнивание по названию секции (правее шеврона). */
private val CONTENT_INDENT = 20.dp

/** Какая зона навигатора раскрыта: её список занимает середину панели, остальные уровни — строки у краёв. */
private enum class PanelSelector { PROJECT, BOOK, CHAPTERS }

/** Зона после смены контекста: есть книга — главы, иначе выбор книги. */
private fun zoneAfterContext(state: AppState): PanelSelector =
    if (state.currentBook.value != null) PanelSelector.CHAPTERS else PanelSelector.BOOK

/** Левая панель: навигатор «Проект / Книга / Главы» — статус-строки, раскрытый уровень в середине. */
@Composable
fun LeftPanel(state: AppState, modifier: Modifier = Modifier) {
    val project by state.currentProject.collectAsState()
    val book by state.currentBook.collectAsState()
    val books by state.books.collectAsState()
    val projects by state.projects.collectAsState()
    val running by state.runningStage.collectAsState()

    var openSelector by remember {
        mutableStateOf(
            when {
                state.currentBook.value != null -> PanelSelector.CHAPTERS
                state.currentProject.value != null -> PanelSelector.BOOK
                else -> PanelSelector.PROJECT
            },
        )
    }
    var createOpen by remember { mutableStateOf(false) }

    // Импорт книги открывает её асинхронно — возвращаемся к главам, как только книга появилась
    LaunchedEffect(book) {
        if (book != null && openSelector == PanelSelector.BOOK) openSelector = PanelSelector.CHAPTERS
    }

    Column(
        modifier.background(BtPalette.Card).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Проект: строка сверху; книги и главы при раскрытии прижимаются к низу панели
        SelectorRow(
            label = "Проект",
            value = project?.name,
            expanded = openSelector == PanelSelector.PROJECT,
            onClick = { openSelector = PanelSelector.PROJECT },
        )
        if (openSelector == PanelSelector.PROJECT) {
            // Зона держит вес, чтобы строки ниже прижимались к нижнему краю; контент — компактной группой сверху
            Column(Modifier.weight(1f)) {
                BtButton(
                    if (createOpen) "Скрыть форму" else "+ Новый проект",
                    onClick = { createOpen = !createOpen },
                    active = createOpen,
                    modifier = Modifier.fillMaxWidth().padding(start = CONTENT_INDENT),
                    height = 32.dp,
                )
                if (createOpen) {
                    CreateProjectForm(
                        state,
                        compact = true,
                        onDone = {
                            createOpen = false
                            openSelector = zoneAfterContext(state)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = CONTENT_INDENT),
                    )
                }
                ProjectList(
                    projects = projects,
                    current = project,
                    onPick = {
                        // Остаёмся в списке проектов: переключение туда-сюда не должно закрывать список
                        state.openProject(it)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 2.dp, start = CONTENT_INDENT),
                )
            }
        }

        // Книга: проект остаётся строкой сверху, главы уходят вниз
        SelectorRow(
            label = "Книга",
            value = book?.title,
            placeholder = "не выбрана",
            expanded = openSelector == PanelSelector.BOOK,
            enabled = project != null,
            onClick = { openSelector = PanelSelector.BOOK },
        )
        if (openSelector == PanelSelector.BOOK) {
            Column(Modifier.weight(1f)) {
                BookList(
                    books = books,
                    current = book,
                    onPick = {
                        state.openBook(it)
                        openSelector = PanelSelector.CHAPTERS
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(start = CONTENT_INDENT),
                )
                BtButton(
                    "Импорт EPUB/FB2",
                    enabled = running == null,
                    onClick = { pickBookFile(state) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = CONTENT_INDENT),
                    height = 32.dp,
                )
            }
        }

        // Главы: проекты и книги уходят вверх, список глав занимает остальное (рабочий вид)
        SelectorRow(
            label = "Главы",
            value = null,
            placeholder = null,
            expanded = openSelector == PanelSelector.CHAPTERS,
            enabled = book != null,
            onClick = { openSelector = PanelSelector.CHAPTERS },
        )
        if (openSelector == PanelSelector.CHAPTERS) {
            ChaptersList(state, Modifier.weight(1f))
        }
    }
}

/**
 * Строка-заголовок уровня в стиле секций VS Code: без рамки и постоянной заливки,
 * шеврон слева (▾ раскрыто / ▸ свернуто), hover — лёгкая подсветка на всю ширину.
 */
@Composable
private fun SelectorRow(
    label: String,
    value: String?,
    expanded: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    placeholder: String? = "не выбран",
) {
    val shape = RoundedCornerShape(6.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val labelColor = if (enabled) BtPalette.TextSecondary else BtPalette.Muted
    val valueColor = if (enabled) BtPalette.Text else BtPalette.Muted
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered && enabled) BtPalette.Hover else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "▾" else "▸", color = labelColor, fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            label.uppercase(),
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
        )
        val shown = value ?: placeholder
        if (shown != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                shown,
                color = valueColor,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Строка выбора в раскрытом списке: hover-подсветка, у текущего — мягкая акцентная заливка. */
@Composable
private fun PickRow(
    isCurrent: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    isCurrent -> BtPalette.AccentBgSoft
                    hovered -> BtPalette.Hover
                    else -> BtPalette.Card
                },
                RoundedCornerShape(6.dp),
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Список проектов раскрытой зоны: выбор по объекту ProjectMeta (не по строке имени). */
@Composable
private fun ProjectList(
    projects: List<ProjectMeta>,
    current: ProjectMeta?,
    onPick: (ProjectMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        items(projects, key = { it.slug }) { p ->
            val isCurrent = p.slug == current?.slug
            PickRow(isCurrent = isCurrent, onClick = { onPick(p) }) {
                Column(Modifier.weight(1f)) {
                    Text(
                        p.name,
                        fontSize = 13.sp,
                        color = BtPalette.Text,
                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${p.sourceLang.uppercase()} → ${p.targetLang.uppercase()} · пресет: ${p.preset}",
                        fontSize = 11.sp,
                        color = BtPalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isCurrent) {
                    Text("✓", color = BtPalette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Список книг раскрытой зоны: название + автор/формат, выбор по объекту BookMeta. */
@Composable
private fun BookList(
    books: List<BookMeta>,
    current: BookMeta?,
    onPick: (BookMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        items(books, key = { it.slug }) { b ->
            val isCurrent = b.slug == current?.slug
            PickRow(isCurrent = isCurrent, onClick = { onPick(b) }) {
                Column(Modifier.weight(1f)) {
                    Text(
                        b.title,
                        fontSize = 13.sp,
                        color = BtPalette.Text,
                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2,
                    )
                    val subtitle = listOfNotNull(
                        b.author.takeIf { it.isNotBlank() },
                        b.format.takeIf { it.isNotBlank() }?.uppercase(),
                    ).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, fontSize = 11.sp, color = BtPalette.TextSecondary, maxLines = 1)
                    }
                }
                if (isCurrent) {
                    Text("✓", color = BtPalette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ChaptersList(state: AppState, modifier: Modifier = Modifier) {
    val selected by state.selectedChapter.collectAsState()
    val manifest by state.manifest.collectAsState()
    // glossary/notes читаются в сводке ниже — здесь их чтение и подписка
    val glossary by state.glossary.collectAsState()
    val notes by state.notes.collectAsState()
    // Применение/отклонение правок меняет только work/critic/*.json (манифест
    // структурно равен прежнему) — подписка на criticVersion обязательна.
    // Ключи remember — это и есть чтения state: «мёртвый» by-делегат без
    // обращения не подписывает композицию.
    val criticVersion by state.criticVersion.collectAsState()

    val chapters = remember(criticVersion, manifest) { state.chapterUiList() }

    Column(modifier) {
        LazyColumn(Modifier.weight(1f).padding(start = CONTENT_INDENT)) {
        items(chapters) { ch ->
            val isSelected = ch.index == selected
            // «N к ревью» — тот же янтарный, что у «к ревью: N» в правой панели:
            // это ожидание действия, а не зелёное «готово»
            val labelColor = when {
                ch.questions > 0 -> BtPalette.Warn
                ch.status == "failed" -> BtPalette.Warn
                ch.status == "editing" || ch.status == "drafted" -> BtPalette.Accent
                ch.status == "done" -> BtPalette.SuccessDark
                else -> BtPalette.TextSecondary
            }
            val label = if (ch.questions > 0) "${ch.questions} к ревью" else ch.label
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        when {
                            isSelected -> BtPalette.AccentBg
                            hovered -> BtPalette.Hover
                            else -> BtPalette.Card
                        },
                    )
                    .clickable(interactionSource = interaction, indication = null) {
                        state.selectedChapter.value = ch.index
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color = statusColor(ch.status))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${ch.index}. ${ch.title}", fontSize = 13.sp, color = BtPalette.Text, maxLines = 2)
                }
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 11.sp, color = labelColor)
            }
        }
        }

        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp, start = CONTENT_INDENT).background(BtPalette.Bg).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val approved = glossary.count { it.isApproved }
            val proposed = glossary.count { it.status == "proposed" }
            val approvedNotes = notes.count { it.status == "approved" }
            val proposedNotes = notes.count { it.status == "proposed" }
            Text(
                buildString {
                    append("Глоссарий: $approved")
                    if (proposed > 0) append(" (+$proposed)")
                    append(" · заметки: $approvedNotes")
                    if (proposedNotes > 0) append(" (+$proposedNotes)")
                    append("\nФрагментов: ${manifest?.allFragments()?.size ?: 0}")
                },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = BtPalette.TextSecondary,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}
