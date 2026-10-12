package ru.sebyanin.booktranslator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as platformFont
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Набор визуальных токенов одной темы (из макета design_by_claude, Main.dc.html).
 * Источник истины один: токены, для которых в Material 3 есть семантические
 * слоты, проецируются в ColorScheme (см. LightMaterialScheme/DarkMaterialScheme),
 * остальные — в BtExtendedColors (см. LightExtended/DarkExtended).
 */
private data class BtColors(
    val Bg: Color,
    val Card: Color,
    val Border: Color,
    val BorderLight: Color,
    val FieldBorder: Color,
    val Hover: Color,
    val Text: Color,
    val TextSecondary: Color,
    val Accent: Color,
    val AccentText: Color,
    val AccentBg: Color,
    val AccentBgSoft: Color,
    val AccentBorderLight: Color,
    val Success: Color,
    val SuccessDark: Color,
    val Warn: Color,
    val WarnBg: Color,
    val WarnHighlight: Color,
    val WarnBorder: Color,
    val Muted: Color,
    val Dark: Color,
    val Track: Color,
    val AccentHover: Color,
    val SuccessHover: Color,
    val DarkHover: Color,
    /** Цвет текста на сплошной заливке (акцентная/зелёная/тёмная кнопки). */
    val OnSolid: Color,
    /** Цвет текста warning-кнопки. */
    val WarnText: Color,
)

/** Светлая тема — исходные токены макета design_by_claude (Main.dc.html). */
private val LightColors = BtColors(
    Bg = Color(0xFFF4F5F7),
    Card = Color(0xFFFFFFFF),
    Border = Color(0xFFDDE0E8),
    BorderLight = Color(0xFFEDEFF4),
    FieldBorder = Color(0xFFC9CEDA),
    Hover = Color(0xFFE8EAF0),
    Text = Color(0xFF14171F),
    TextSecondary = Color(0xFF4A5160),
    Accent = Color(0xFF3B4BD8),
    AccentText = Color(0xFF2B38A8),
    AccentBg = Color(0xFFE6E9FB),
    AccentBgSoft = Color(0xFFF3F4FE),
    AccentBorderLight = Color(0xFFD5D9F7),
    Success = Color(0xFF1F8A5B),
    SuccessDark = Color(0xFF1F6F4A),
    Warn = Color(0xFF9A5B00),
    WarnBg = Color(0xFFFFF6DD),
    WarnHighlight = Color(0xFFFCEBC2),
    WarnBorder = Color(0xFFE8C97A),
    Muted = Color(0xFF9AA1B2),
    Dark = Color(0xFF14171F),
    Track = Color(0xFFE3E6EE),
    AccentHover = Color(0xFF3240C7),
    SuccessHover = Color(0xFF1B6344),
    DarkHover = Color(0xFF333A4D),
    OnSolid = Color(0xFFFFFFFF),
    WarnText = Color(0xFF6E4100),
)

/** Тёмная тема: тот же макет, осветлённые акценты, инвертированные сплошные кнопки. */
private val DarkColors = BtColors(
    Bg = Color(0xFF12141A),
    Card = Color(0xFF1B1E27),
    Border = Color(0xFF2A2F3C),
    BorderLight = Color(0xFF232733),
    FieldBorder = Color(0xFF3D4354),
    Hover = Color(0xFF262B37),
    Text = Color(0xFFE6E9F0),
    TextSecondary = Color(0xFFA9B0C0),
    Accent = Color(0xFF7C89F2),
    AccentText = Color(0xFFAAB3FA),
    AccentBg = Color(0xFF2B3158),
    AccentBgSoft = Color(0xFF1F2338),
    AccentBorderLight = Color(0xFF353C6E),
    Success = Color(0xFF4CBF8A),
    SuccessDark = Color(0xFF3AA977),
    Warn = Color(0xFFE0A54E),
    WarnBg = Color(0xFF382F17),
    WarnHighlight = Color(0xFF4A3C1C),
    WarnBorder = Color(0xFF8A7337),
    Muted = Color(0xFF70788C),
    Dark = Color(0xFFE6E9F0),
    Track = Color(0xFF252A36),
    AccentHover = Color(0xFF8D99F5),
    SuccessHover = Color(0xFF4BB986),
    DarkHover = Color(0xFFCFD5E3),
    OnSolid = Color(0xFF12141A),
    WarnText = Color(0xFFFFD98F),
)

/** Роли, для которых в схеме Material 3 нет слотов: статусы, warning-семейство, hover'ы. */
class BtExtendedColors(
    val BorderLight: Color,
    val Hover: Color,
    val AccentBgSoft: Color,
    val AccentBorderLight: Color,
    val AccentHover: Color,
    val Success: Color,
    val SuccessDark: Color,
    val SuccessHover: Color,
    val Warn: Color,
    val WarnBg: Color,
    val WarnHighlight: Color,
    val WarnBorder: Color,
    val WarnText: Color,
    val Muted: Color,
    val Dark: Color,
    val DarkHover: Color,
    val Track: Color,
)

private fun BtColors.extended() = BtExtendedColors(
    BorderLight = BorderLight,
    Hover = Hover,
    AccentBgSoft = AccentBgSoft,
    AccentBorderLight = AccentBorderLight,
    AccentHover = AccentHover,
    Success = Success,
    SuccessDark = SuccessDark,
    SuccessHover = SuccessHover,
    Warn = Warn,
    WarnBg = WarnBg,
    WarnHighlight = WarnHighlight,
    WarnBorder = WarnBorder,
    WarnText = WarnText,
    Muted = Muted,
    Dark = Dark,
    DarkHover = DarkHover,
    Track = Track,
)

/** Расширенные цвета темы — паттерн material3 для ролей вне ColorScheme. */
val LocalBtColors = staticCompositionLocalOf { LightColors.extended() }

/** Флаг темной темы — для арта с собственными парами палитр (маскот), не сводимого к токенам. */
val LocalBtDark = staticCompositionLocalOf { false }

@Composable
fun btColors(): BtExtendedColors = LocalBtColors.current

private val LightMaterialScheme = lightColorScheme(
    primary = LightColors.Accent,
    onPrimary = LightColors.OnSolid,
    primaryContainer = LightColors.AccentBg,
    onPrimaryContainer = LightColors.AccentText,
    background = LightColors.Bg,
    onBackground = LightColors.Text,
    surface = LightColors.Card,
    onSurface = LightColors.Text,
    onSurfaceVariant = LightColors.TextSecondary,
    outline = LightColors.FieldBorder,
    outlineVariant = LightColors.Border,
    surfaceContainer = LightColors.Card,
    surfaceContainerHigh = LightColors.Hover,
)

private val DarkMaterialScheme = darkColorScheme(
    primary = DarkColors.Accent,
    onPrimary = DarkColors.OnSolid,
    primaryContainer = DarkColors.AccentBg,
    onPrimaryContainer = DarkColors.AccentText,
    background = DarkColors.Bg,
    onBackground = DarkColors.Text,
    surface = DarkColors.Card,
    onSurface = DarkColors.Text,
    onSurfaceVariant = DarkColors.TextSecondary,
    outline = DarkColors.FieldBorder,
    outlineVariant = DarkColors.Border,
    surfaceContainer = DarkColors.Card,
    surfaceContainerHigh = DarkColors.Hover,
)

/**
 * Фасад токенов над MaterialTheme — привязывает собственные компоненты
 * (BtButton, BtCard, BtTextField…) к теме: семантические роли читаются из
 * colorScheme, роли вне схемы — из LocalBtColors. Смена темы едет через
 * стандартные CompositionLocal, глобального состояния здесь больше нет.
 * Новому коду можно читать MaterialTheme.colorScheme и btColors() напрямую.
 */
object BtPalette {
    val Bg: Color @Composable get() = MaterialTheme.colorScheme.background
    val Card: Color @Composable get() = MaterialTheme.colorScheme.surface
    val Border: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
    val BorderLight: Color @Composable get() = btColors().BorderLight
    val FieldBorder: Color @Composable get() = MaterialTheme.colorScheme.outline
    val Hover: Color @Composable get() = btColors().Hover

    val Text: Color @Composable get() = MaterialTheme.colorScheme.onBackground
    val TextSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
    val AccentText: Color @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
    val AccentBg: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val AccentBgSoft: Color @Composable get() = btColors().AccentBgSoft
    val AccentBorderLight: Color @Composable get() = btColors().AccentBorderLight

    val Success: Color @Composable get() = btColors().Success
    val SuccessDark: Color @Composable get() = btColors().SuccessDark

    val Warn: Color @Composable get() = btColors().Warn
    val WarnBg: Color @Composable get() = btColors().WarnBg
    val WarnHighlight: Color @Composable get() = btColors().WarnHighlight
    val WarnBorder: Color @Composable get() = btColors().WarnBorder
    val WarnText: Color @Composable get() = btColors().WarnText

    val Muted: Color @Composable get() = btColors().Muted
    val Dark: Color @Composable get() = btColors().Dark
    val Track: Color @Composable get() = btColors().Track

    // Hover-состояния (наведение мыши)
    val AccentHover: Color @Composable get() = btColors().AccentHover
    val SuccessHover: Color @Composable get() = btColors().SuccessHover
    val DarkHover: Color @Composable get() = btColors().DarkHover

    // Текст на сплошной заливке (кнопки Primary/Success/Dark)
    val OnSolid: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
}

/** Шрифты из ресурсов (fallback — системные, чтобы приложение запускалось всегда). */
object BtFonts {
    private fun load(resource: String, weight: FontWeight): Font? = runCatching {
        val url = BtFonts::class.java.classLoader.getResource("fonts/$resource") ?: return null
        platformFont(File(url.toURI()), weight)
    }.getOrNull()

    private fun family(vararg pairs: Pair<String, FontWeight>): FontFamily {
        val fonts = pairs.mapNotNull { (name, w) -> load(name, w) }
        return if (fonts.isEmpty()) FontFamily.SansSerif else FontFamily(fonts)
    }

    val Ui: FontFamily by lazy {
        family(
            "IBMPlexSans-Regular.ttf" to FontWeight.Normal,
            "IBMPlexSans-Medium.ttf" to FontWeight.Medium,
            "IBMPlexSans-SemiBold.ttf" to FontWeight.SemiBold,
        )
    }

    val Book: FontFamily by lazy {
        val fonts = listOfNotNull(load("SourceSerif4-Regular.ttf", FontWeight.Normal), load("SourceSerif4-SemiBold.ttf", FontWeight.SemiBold))
        if (fonts.isEmpty()) FontFamily.Serif else FontFamily(fonts)
    }
}

private val BtTypography = Typography(
    bodyLarge = TextStyle(fontFamily = BtFonts.Ui, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = BtFonts.Ui, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = BtFonts.Ui, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = BtFonts.Ui, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = BtFonts.Ui, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = BtFonts.Ui, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = BtFonts.Ui, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = BtFonts.Ui, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = BtFonts.Ui, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun BtTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(
        colorScheme = if (dark) DarkMaterialScheme else LightMaterialScheme,
        typography = BtTypography,
    ) {
        // LocalContentColor: Text без явного цвета иначе берёт дефолт material3 —
        // ЧИСТЫЙ ЧЁРНЫЙ (в тёмной теме заголовки рисовались чёрным по тёмному).
        CompositionLocalProvider(
            LocalContentColor provides colors.Text,
            LocalBtColors provides colors.extended(),
            LocalBtDark provides dark,
        ) {
            content()
        }
    }
}
