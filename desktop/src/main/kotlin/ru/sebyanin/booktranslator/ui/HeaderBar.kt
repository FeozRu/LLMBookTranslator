package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.ui.components.BtButton
import ru.sebyanin.booktranslator.ui.theme.BtPalette

/**
 * Шапка из двух зон, выровненных по колонкам окна (правая колонка — свой
 * столбец на всю высоту, уведомление конвейера живёт в нём, см. RightPanel):
 *  - левая (280dp, как левая панель) — книга и языковая пара;
 *  - центральная — прогресс, «Папка» и экспорт: шкала забирает всё свободное
 *    место (weight 1f), при сужении окна сжимается она, а не кнопки.
 * Экспорт спрашивает место и имя (нативный «Сохранить как»), «Папка» открывает
 * папку книги в файловом менеджере; QA — этап, его карточка живёт в StagePanel.
 */
@Composable
fun HeaderBar(state: AppState) {
    val project by state.currentProject.collectAsState()
    val book by state.currentBook.collectAsState()
    val running by state.runningStage.collectAsState()

    val (done, total) = state.bookProgress()
    val percent = if (total > 0) done * 100 / total else 0
    val p = project
    val b = book

    Row(
        Modifier.fillMaxWidth().background(BtPalette.Card).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Левая зона — ширина левой колонки, содержимое по её внутреннему отступу
        Column(Modifier.width(LeftPaneWidth).padding(start = 12.dp, end = 16.dp)) {
            Text(
                b?.title ?: "Книга не выбрана",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    p?.let { "${it.sourceLang.uppercase()} → ${it.targetLang.uppercase()}" },
                    p?.name,
                ).joinToString(" · "),
                fontSize = 13.sp,
                color = BtPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Центральная зона: шкала — weight(1f), счётчик и кнопки — постоянной ширины
        Row(
            Modifier.weight(1f).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).height(8.dp)
                    .background(BtPalette.Track, RoundedCornerShape(4.dp)),
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(percent / 100f)
                        .background(BtPalette.Accent, RoundedCornerShape(4.dp)),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("$done/$total", fontSize = 13.sp, color = BtPalette.TextSecondary)
            Spacer(Modifier.width(12.dp))
            BtButton("Папка", onClick = { state.openFolder() }, enabled = p != null, height = 32.dp)
            Spacer(Modifier.width(6.dp))
            BtButton("EPUB", onClick = { state.buildEpub() }, enabled = running == null && b != null, height = 32.dp)
            Spacer(Modifier.width(6.dp))
            BtButton("FB2", onClick = { state.buildFb2() }, enabled = running == null && b != null, height = 32.dp)
        }
    }
}
