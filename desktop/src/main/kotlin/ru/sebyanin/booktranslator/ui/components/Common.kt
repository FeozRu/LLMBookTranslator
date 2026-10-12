package ru.sebyanin.booktranslator.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.ui.theme.BtPalette

enum class BtButtonKind { Primary, Ghost, Success, Warning, Dark }

private data class ButtonColors(val bg: Color, val hoverBg: Color, val content: Color, val border: Color?, val hoverBorder: Color?)

@Composable
fun BtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtButtonKind = BtButtonKind.Ghost,
    enabled: Boolean = true,
    /** Переключатель в нажатом состоянии (например, «Предпросмотр брифа» открыт). */
    active: Boolean = false,
    /** Узкий вариант для степперов/иконок в плотных рядах (меньше поля и шрифт). */
    compact: Boolean = false,
    height: Dp = 36.dp,
) {
    val shape = RoundedCornerShape(8.dp)
    val hPadding = if (compact) 10.dp else 16.dp
    val fontSp = if (compact) 13.sp else 14.sp
    val colors = when (kind) {
        // Текст сплошных кнопок — OnSolid, а не Card: в тёмной теме карточки
        // тёмные, белый текст акцентной кнопки задавался бы цветом карточки
        BtButtonKind.Primary -> ButtonColors(BtPalette.Accent, BtPalette.AccentHover, BtPalette.OnSolid, null, null)
        BtButtonKind.Ghost -> ButtonColors(BtPalette.Card, BtPalette.Hover, BtPalette.Text, BtPalette.FieldBorder, BtPalette.Accent)
        BtButtonKind.Success -> ButtonColors(BtPalette.SuccessDark, BtPalette.SuccessHover, BtPalette.OnSolid, null, null)
        BtButtonKind.Warning -> ButtonColors(BtPalette.Card, BtPalette.WarnBg, BtPalette.WarnText, BtPalette.Warn, BtPalette.Warn)
        BtButtonKind.Dark -> ButtonColors(BtPalette.Dark, BtPalette.DarkHover, BtPalette.OnSolid, null, null)
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        !enabled -> BtPalette.Track
        active -> BtPalette.AccentBg
        hovered && enabled -> colors.hoverBg
        else -> colors.bg
    }
    val borderColor = when {
        !enabled -> null
        active -> BtPalette.Accent
        hovered -> colors.hoverBorder ?: colors.border
        else -> colors.border
    }
    val contentColor = when {
        !enabled -> BtPalette.TextSecondary
        active -> BtPalette.AccentText
        else -> colors.content
    }
    Box(
        modifier = modifier
            .heightIn(min = height)
            .background(bg, shape)
            .then(
                if (borderColor != null) Modifier.border(BorderStroke(1.dp, borderColor), shape) else Modifier,
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = hPadding, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = contentColor,
            fontSize = fontSp,
            fontWeight = if (kind == BtButtonKind.Ghost && !active) FontWeight.Normal else FontWeight.Medium,
        )
    }
}

/** Элемент вкладки: подпись + необязательный счётчик («46», «+4»). */
data class BtTab(val label: String, val badge: String? = null)

/**
 * Полоса вкладок: текстовые пункты с подчёркиванием активной вкладки и
 * разделителем под всей строкой — вкладки читаются как вкладки, а не как кнопки.
 */
@Composable
fun BtTabs(
    tabs: List<BtTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // fillMaxWidth внутри wrap-content вкладки растянул бы её на всю строку —
    // подчёркивание рисуем по фактической ширине вкладки
    val underlineColor = BtPalette.Accent
    Column(modifier.fillMaxWidth()) {
        Row {
            tabs.forEachIndexed { i, tab ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val active = i == selected
                Column(
                    modifier = Modifier
                        .clickable(interactionSource = interaction, indication = null) { onSelect(i) }
                        .drawBehind {
                            if (active) {
                                val h = 2.dp.toPx()
                                drawRect(
                                    color = underlineColor,
                                    topLeft = Offset(0f, size.height - h),
                                    size = Size(size.width, h),
                                )
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier.height(34.dp).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tab.label,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active || hovered) BtPalette.Text else BtPalette.TextSecondary,
                        )
                        tab.badge?.let { badge ->
                            Spacer(Modifier.width(5.dp))
                            Text(
                                badge,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (active) BtPalette.AccentText else BtPalette.Muted,
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BtPalette.Border))
    }
}

/** Точка-статус главы: закрашенная (цвет) или полая (в очереди). */
@Composable
fun StatusDot(color: Color?, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val shape = CircleShape
    Box(
        modifier
            .then(if (color != null) Modifier.background(color, shape) else Modifier)
            .then(if (color == null) Modifier.border(BorderStroke(2.dp, BtPalette.Muted), shape) else Modifier)
            .size(size),
    )
}

/** Простая карточка: белый фон, рамка 1px, радиус 10-12. */
@Composable
fun BtCard(
    modifier: Modifier = Modifier,
    border: Color = BtPalette.Border,
    background: Color = BtPalette.Card,
    radius: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(background, RoundedCornerShape(radius))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(radius))
            .padding(contentPadding),
    ) { content() }
}

/** Подпись-заголовок секции («ГЛАВЫ», «АГЕНТЫ», …): 12sp, letter-spacing, серый. */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier, color: Color = BtPalette.TextSecondary) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    )
}

/** Поле ввода в стиле макета: белое, рамка, радиус 8, высота 44. */
@Composable
fun BtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .background(BtPalette.Card, shape)
            .border(BorderStroke(1.dp, BtPalette.FieldBorder), shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = BtPalette.Muted, fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            // fillMaxWidth: без него клик «в поле» работает только по узкой зоне текста
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            singleLine = singleLine,
            textStyle = TextStyle(color = BtPalette.Text, fontSize = 14.sp, lineHeight = 20.sp),
        )
    }
}

/** Многострочное поле (редактор промптов/конвенций). */
@Composable
fun BtTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 200.dp,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .background(BtPalette.Card, shape)
            .border(BorderStroke(1.dp, BtPalette.FieldBorder), shape)
            .padding(12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
            textStyle = TextStyle(color = BtPalette.Text, fontSize = 13.sp, lineHeight = 19.sp),
        )
    }
}

/** Цветной чип статуса. */
@Composable
fun BtChip(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = fg,
        fontSize = 12.sp,
        modifier = modifier
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Кликабельная карточка с hover-подсветкой (списки проектов/книг/глав). */
@Composable
fun BtHoverCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    border: Color = BtPalette.Border,
    hoverBorder: Color = BtPalette.Accent,
    background: Color = BtPalette.Card,
    hoverBackground: Color = BtPalette.Hover,
    radius: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .background(if (hovered) hoverBackground else background, shape)
            .border(BorderStroke(1.dp, if (hovered) hoverBorder else border), shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(contentPadding),
    ) { content() }
}

/** Выпадающий список в стиле поля ввода. */
@Composable
fun BtDropdown(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "— не выбрано —",
    allowEmpty: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .background(if (hovered) BtPalette.Hover else BtPalette.Card, shape)
            .border(BorderStroke(1.dp, if (hovered) BtPalette.Accent else BtPalette.FieldBorder), shape)
            .clickable(interactionSource = interaction, indication = null) { open = true }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                selected ?: placeholder,
                color = if (selected == null) BtPalette.Muted else BtPalette.Text,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
            Text("▾", color = BtPalette.TextSecondary, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowEmpty) {
                DropdownMenuItem(text = { Text(placeholder) }, onClick = { onSelect(null); open = false })
            }
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}
