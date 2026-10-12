package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.ui.components.BtButton
import ru.sebyanin.booktranslator.ui.components.BtButtonKind
import ru.sebyanin.booktranslator.ui.components.BtCard
import ru.sebyanin.booktranslator.ui.components.BtDropdown
import ru.sebyanin.booktranslator.ui.components.BtHoverCard
import ru.sebyanin.booktranslator.ui.components.BtTextField
import ru.sebyanin.booktranslator.ui.theme.BtPalette
import java.awt.FileDialog
import java.awt.Frame

/** Максимальная ширина контента стартовых экранов — на больших мониторах не растягиваем. */
private val CONTENT_WIDTH = 820.dp

/** Экран проектов: центрированная колонка, список + создание нового. */
@Composable
fun ProjectsScreen(state: AppState) {
    val projects by state.projects.collectAsState()
    var createOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(BtPalette.Bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = CONTENT_WIDTH).fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Проекты", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Проект — серия книг с общим глоссарием, конвенциями и промптами",
                        fontSize = 13.sp,
                        color = BtPalette.TextSecondary,
                    )
                }
                BtButton("Обновить", onClick = { state.refreshProjects() })
                Spacer(Modifier.width(8.dp))
                BtButton("+ Новый проект", kind = BtButtonKind.Primary, onClick = { createOpen = !createOpen })
            }

            if (createOpen) {
                CreateProjectForm(state, onDone = { createOpen = false })
            }

            if (projects.isEmpty() && !createOpen) {
                Text(
                    "Проектов пока нет. Создайте проект — в одном проекте может быть несколько книг серии;\n" +
                        "глоссарий и конвенции общие на весь проект.\n\nДанные хранятся в Documents/BookTranslator.",
                    fontSize = 14.sp,
                    color = BtPalette.TextSecondary,
                    lineHeight = 21.sp,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(projects.size) { i ->
                    val p = projects[i]
                    BtHoverCard(
                        onClick = { state.openProject(p) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${p.sourceLang.uppercase()} → ${p.targetLang.uppercase()} · пресет: ${p.preset}",
                                    fontSize = 13.sp,
                                    color = BtPalette.TextSecondary,
                                )
                            }
                            BtButton("Открыть", kind = BtButtonKind.Primary, onClick = { state.openProject(p) })
                        }
                    }
                }
            }
        }
    }
}

/** Форма создания проекта: на стартовом экране (wide) и в левой панели (compact, узкая колонка). */
@Composable
internal fun CreateProjectForm(
    state: AppState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var name by remember { mutableStateOf("") }
    var srcLang by remember { mutableStateOf("ja") }
    var dstLang by remember { mutableStateOf("ru") }
    var preset by remember { mutableStateOf("ja") }

    BtCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Новый проект", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Column {
                Text("Название", fontSize = 12.sp, color = BtPalette.TextSecondary)
                Spacer(Modifier.height(6.dp))
                BtTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Например: Genkoku")
            }
            if (compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Исходный язык", fontSize = 12.sp, color = BtPalette.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        BtTextField(srcLang, { srcLang = it }, Modifier.fillMaxWidth(), placeholder = "ja")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Целевой язык", fontSize = 12.sp, color = BtPalette.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        BtTextField(dstLang, { dstLang = it }, Modifier.fillMaxWidth(), placeholder = "ru")
                    }
                }
                Column {
                    Text("Пресет проверок", fontSize = 12.sp, color = BtPalette.TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    BtDropdown(
                        options = listOf("ja", "generic"),
                        selected = preset,
                        onSelect = { preset = it ?: "generic" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Исходный язык", fontSize = 12.sp, color = BtPalette.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        BtTextField(srcLang, { srcLang = it }, Modifier.fillMaxWidth(), placeholder = "ja")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Целевой язык", fontSize = 12.sp, color = BtPalette.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        BtTextField(dstLang, { dstLang = it }, Modifier.fillMaxWidth(), placeholder = "ru")
                    }
                    Column {
                        Text("Пресет проверок", fontSize = 12.sp, color = BtPalette.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        BtDropdown(
                            options = listOf("ja", "generic"),
                            selected = preset,
                            onSelect = { preset = it ?: "generic" },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ja — кана/CJK-проверки и конвенции для японского; generic — универсальный.",
                    fontSize = 12.sp,
                    color = BtPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                BtButton(
                    "Создать",
                    kind = BtButtonKind.Primary,
                    enabled = name.isNotBlank(),
                    onClick = {
                        state.createProject(name.trim(), srcLang.trim(), dstLang.trim(), preset)
                        onDone()
                    },
                )
            }
        }
    }
}

fun pickBookFile(state: AppState) {
    val dialog = FileDialog(Frame(), "Выберите книгу (EPUB/FB2)", FileDialog.LOAD)
    dialog.filenameFilter = java.io.FilenameFilter { _, name ->
        name.lowercase().endsWith(".epub") || name.lowercase().endsWith(".fb2") || name.lowercase().endsWith(".zip")
    }
    dialog.isVisible = true
    val fileName = dialog.file ?: return
    val dir = dialog.directory ?: return
    state.importBook(dir + fileName)
}
