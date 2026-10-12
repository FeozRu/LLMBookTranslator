package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.ui.components.BtButton
import ru.sebyanin.booktranslator.ui.components.BtButtonKind
import ru.sebyanin.booktranslator.ui.components.BtCard
import ru.sebyanin.booktranslator.ui.components.BtTab
import ru.sebyanin.booktranslator.ui.components.BtTabs
import ru.sebyanin.booktranslator.ui.components.SectionCaption
import ru.sebyanin.booktranslator.ui.theme.BtPalette

/**
 * ВРЕМЕННЫЙ СТЕНД ДИЗАЙНА СТАТУСА (запуск: `:desktop:run -Dbt.mockups=true`,
 * снимок — штатным bt.shot). Три колонки 340dp — как правая колонка окна:
 *  А — статусная карточка в стиле карточек этапов, маскот-малютка слева сверху,
 *      текст справа от него;
 *  Б — «облако комикса»: реплика с хвостиком, идущая от малютки;
 *  В — текущая крупная версия (маскот во всю высоту зоны) — для сравнения.
 * После выбора варианта стенд убирается, победитель переезжает в RightPanel.
 */
@Composable
fun MascotMockupsScreen() {
    Row(
        Modifier.fillMaxSize().background(BtPalette.Bg).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MockColumn("ВАРИАНТ А · МАЛЮТКА В КАРТОЧКЕ") {
            SAMPLES.forEach { s ->
                VariantCardA(s)
                Spacer(Modifier.height(10.dp))
            }
        }
        MockColumn("ВАРИАНТ Б · ОБЛАКО КОМИКСА") {
            SAMPLES.forEach { s ->
                VariantBubble(s)
                Spacer(Modifier.height(10.dp))
            }
        }
        MockColumn("ТЕКУЩАЯ ВЕРСИЯ · ДЛЯ СРАВНЕНИЯ") {
            VariantBigCurrent(SAMPLES[0])
            Spacer(Modifier.height(10.dp))
            VariantBigCurrent(SAMPLES[3])
        }
    }
}

// ---------------- сценарии-образцы ----------------

private data class Sample(
    val mood: MascotMood,
    val headline: String,
    val sub: String? = null,
    val isError: Boolean = false,
    val buttons: List<Pair<String, BtButtonKind>> = emptyList(),
)

private val SAMPLES = listOf(
    Sample(MascotMood.IDLE, "ожидаю команд"),
    Sample(
        MascotMood.WORKING,
        "Выполняется: черновик",
        "фрагмент 12 из 119",
        buttons = listOf("Остановить" to BtButtonKind.Ghost),
    ),
    Sample(
        MascotMood.DONE,
        "Черновик: готово 12, ошибок 0",
        buttons = listOf("Скрыть" to BtButtonKind.Ghost),
    ),
    Sample(
        MascotMood.ERROR,
        "Ошибка: не удалось связаться с LLM (таймаут после 3 попыток). Полный текст — в журнале.",
        isError = true,
        buttons = listOf("Журнал" to BtButtonKind.Primary, "Скрыть" to BtButtonKind.Ghost),
    ),
)

@Composable
private fun headlineColor(s: Sample) = when {
    s.isError -> MascotLamp.Error
    s.mood == MascotMood.WORKING -> MascotLamp.Working
    s.mood == MascotMood.IDLE -> MascotLamp.Idle
    else -> BtPalette.Text
}

@Composable
private fun SampleButtons(s: Sample) {
    if (s.buttons.isEmpty()) return
    Row(Modifier.padding(top = 8.dp)) {
        Spacer(Modifier.weight(1f))
        s.buttons.forEachIndexed { i, (label, kind) ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            BtButton(label, onClick = {}, kind = kind, compact = true, height = 28.dp)
        }
    }
}

// ---------------- вариант А: карточка с малюткой ----------------

@Composable
private fun VariantCardA(s: Sample) {
    BtCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            BookMascot(s.mood, Modifier.height(95.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.headline,
                    fontSize = 13.sp,
                    fontWeight = if (s.mood == MascotMood.WORKING) FontWeight.Medium else FontWeight.Normal,
                    color = headlineColor(s),
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                s.sub?.let {
                    Text(it, fontSize = 11.sp, color = BtPalette.TextSecondary, modifier = Modifier.padding(top = 2.dp))
                }
                SampleButtons(s)
            }
        }
    }
}

// ---------------- вариант Б: облако комикса ----------------

@Composable
private fun VariantBubble(s: Sample) {
    Row(verticalAlignment = Alignment.Top) {
        BookMascot(s.mood, Modifier.height(88.dp))
        Spacer(Modifier.width(16.dp))
        Bubble(Modifier.weight(1f)) {
            Text(
                s.headline,
                fontSize = 13.sp,
                fontWeight = if (s.mood == MascotMood.WORKING) FontWeight.Medium else FontWeight.Normal,
                color = headlineColor(s),
                lineHeight = 17.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            s.sub?.let {
                Text(it, fontSize = 11.sp, color = BtPalette.TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            SampleButtons(s)
        }
    }
}

/** Реплика-облако: скруглённый прямоугольник с хвостиком влево, к морде маскота. */
@Composable
private fun Bubble(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val bg = BtPalette.Bg
    val line = BtPalette.Border
    Box(
        modifier
            .background(bg, RoundedCornerShape(12.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, line), RoundedCornerShape(12.dp))
            .drawBehind {
                // Хвостик: рамочный треугольник чуть больше, поверх — заливка,
                // чтобы сегмент рамки облака под хвостом был перекрыт
                val r = 9.dp.toPx()
                val y = size.height * 0.42f
                val inset = 1.dp.toPx()
                drawPath(
                    Path().apply {
                        moveTo(inset, y - r - inset)
                        lineTo(-r - inset, y)
                        lineTo(inset, y + r + inset)
                        close()
                    },
                    line,
                )
                drawPath(
                    Path().apply {
                        moveTo(inset, y - r)
                        lineTo(-r, y)
                        lineTo(inset, y + r)
                        close()
                    },
                    bg,
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column { content() }
    }
}

// ---------------- вариант В: текущая крупная версия ----------------

@Composable
private fun VariantBigCurrent(s: Sample) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.BottomCenter) {
            BookMascot(s.mood, Modifier.height(200.dp))
        }
        Text(
            s.headline,
            fontSize = 12.sp,
            color = headlineColor(s),
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        s.sub?.let {
            Text(it, fontSize = 11.sp, color = BtPalette.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        SampleButtons(s)
    }
}

// ---------------- каркас колонки стенда ----------------

@Composable
private fun MockColumn(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(340.dp)
            .background(BtPalette.Card)
            .border(androidx.compose.foundation.BorderStroke(1.dp, BtPalette.Border))
            .padding(12.dp),
    ) {
        SectionCaption(title)
        Spacer(Modifier.height(10.dp))
        content()
        Spacer(Modifier.height(12.dp))
        BtTabs(
            tabs = listOf(BtTab("Этапы"), BtTab("Настройки")),
            selected = 0,
            onSelect = {},
        )
        Spacer(Modifier.height(12.dp))
        FakeStageCard()
    }
}

/** Условная карточка этапа — для ощущения контекста под статусной зоной. */
@Composable
private fun FakeStageCard() {
    BtCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                Text("Нарезка", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("готово", fontSize = 13.sp, color = BtPalette.SuccessDark)
            }
            Text("119 фрагментов готовы к переводу", fontSize = 12.sp, color = BtPalette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().height(6.dp)
                    .background(BtPalette.AccentBorderLight, RoundedCornerShape(3.dp)),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(6.dp)
                        .background(BtPalette.Accent, RoundedCornerShape(3.dp)),
                ) {}
            }
            Spacer(Modifier.height(8.dp))
            BtButton("Нарезать", onClick = {}, kind = BtButtonKind.Primary, height = 30.dp)
        }
    }
}
