package ru.sebyanin.booktranslator.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.ui.components.BtTextField
import ru.sebyanin.booktranslator.ui.theme.BtPalette

data class FieldSpec(
    val label: String,
    val value: String,
    val placeholder: String = "",
    val weight: Float = 1f,
    val onValue: (String) -> Unit,
)

/**
 * Ряд полей с подписями сверху. IntrinsicSize.Min выравнивает колонки по самой
 * высокой подписи — верхи полей всегда на одной линии; длинная подпись сокращается
 * (maxLines=1), а не толкает поле вниз.
 */
@Composable
fun FieldRow(
    fields: List<FieldSpec>,
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
) {
    Row(modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(spacing)) {
        fields.forEach { f ->
            Column(Modifier.weight(f.weight).fillMaxWidth()) {
                Text(
                    f.label,
                    fontSize = 12.sp,
                    color = BtPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                BtTextField(
                    value = f.value,
                    onValueChange = f.onValue,
                    placeholder = f.placeholder,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Одно поле с подписью в одну строку. */
@Composable
fun FieldAbove(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onValue: (String) -> Unit,
) {
    Column(modifier) {
        Text(
            label,
            fontSize = 12.sp,
            color = BtPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        BtTextField(value = value, onValueChange = onValue, placeholder = placeholder, modifier = Modifier.fillMaxWidth())
    }
}

/** Подпись слева (фиксированная ширина), поле справа. */
@Composable
fun RowField(label: String, value: String, placeholder: String = "", onValue: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = BtPalette.TextSecondary, modifier = Modifier.width(200.dp))
        BtTextField(value = value, onValueChange = onValue, placeholder = placeholder, modifier = Modifier.weight(1f))
    }
}

/** Одиночное числовое поле с подписью (в один ряд с FieldRow не смешивать — используйте FieldSpec). */
@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    Column(modifier) {
        Text(
            label,
            fontSize = 12.sp,
            color = BtPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        BtTextField(value = value, onValueChange = onValue, modifier = Modifier.fillMaxWidth())
    }
}
