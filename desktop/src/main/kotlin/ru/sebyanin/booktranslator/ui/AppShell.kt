package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.core.AppServices
import ru.sebyanin.booktranslator.ui.theme.BtPalette

/** Ширина левой колонки; та же ширина задана левой зоне шапки. */
val LeftPaneWidth = 280.dp

/** Ширина правой колонки (маскот + статус + этапы). */
val RightPaneWidth = 340.dp

/**
 * Корень: стартовый экран проектов → единое рабочее окно.
 * Геометрия — «таблица 2×2 + столбец»: левая и центральная колонки имеют
 * шапку в первой строке (HeaderBar) и контент во второй, правая колонка —
 * единый столбец на всю высоту окна (маскот, статус конвейера, этапы).
 */
@Composable
fun AppShell(state: AppState, services: AppServices) {
    val project by state.currentProject.collectAsState()

    if (project == null) {
        ProjectsScreen(state)
        return
    }

    Row(Modifier.fillMaxSize().background(BtPalette.Bg)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            HeaderBar(state)
            Row(Modifier.fillMaxSize()) {
                LeftPanel(state, modifier = Modifier.width(LeftPaneWidth).fillMaxHeight())
                CenterTabs(state, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
        RightPanel(state, modifier = Modifier.width(RightPaneWidth).fillMaxHeight())
    }
}
