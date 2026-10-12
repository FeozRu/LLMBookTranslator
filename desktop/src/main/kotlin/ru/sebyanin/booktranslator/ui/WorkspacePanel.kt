package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.ui.components.BtButton
import ru.sebyanin.booktranslator.ui.components.BtButtonKind
import ru.sebyanin.booktranslator.ui.components.BtCard
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

    Column(modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Глава $ch · ${meta?.title ?: ""}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
                )
            }
            Spacer(Modifier.weight(1f))

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
                    BtCard(
                        modifier = Modifier.weight(1f),
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
