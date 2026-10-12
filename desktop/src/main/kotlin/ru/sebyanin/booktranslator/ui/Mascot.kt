package ru.sebyanin.booktranslator.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import ru.sebyanin.booktranslator.ui.theme.LocalBtDark
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Маскот «нейрокнижка» — Canvas-порт SVG-арта Клода (design_by_claude/mascot/,
 * 4 состояния × 2 темы). Координаты рисования — родная система viewBox
 * оригинала (134×176, x от -4, y от -16), равномерно масштабируемая под
 * отведённый размер; штрихи и скругления при этом ведут себя как в SVG.
 *
 * Состояния зеркалят статус конвейера (маппинг — в правой панели):
 * IDLE «ожидаю команд», WORKING «Выполняется…», DONE — успешное сообщение,
 * ERROR — сообщение об ошибке. Базовые цвета книжки берутся из пары
 * light/dark-файлов, «эмоции» (лицо, сеть, бейдж) одинаковы в обеих темах.
 *
 * Живые анимации (зацикленные, каждая — своя фаза 0..1):
 * IDLE — «zz» медленно плывут вверх-вправо и растворяются;
 * WORKING — малютка слегка дрожит всем телом (качание вокруг ножек);
 * ERROR — глаза-крестики мигают. DONE остался статичным.
 */
enum class MascotMood { IDLE, WORKING, DONE, ERROR }

private const val MASCOT_W = 134f
private const val MASCOT_H = 176f

/** Базовые цвета книжки по темам — точные значения из SVG-файлов Клода. */
private class MascotPalette(
    val legs: Color,
    val backFill: Color,
    val backStroke: Color,
    val cover: Color,
    val spine: Color,
    val screen: Color,
    val screenStroke: Color?,
    val netIdle: Color,
)

private val LightMascot = MascotPalette(
    legs = Color(0xFF14171F),
    backFill = Color(0xFFFFFFFF),
    backStroke = Color(0xFFC9CEDA),
    cover = Color(0xFF3B4BD8),
    spine = Color(0xFF2B38A8),
    screen = Color(0xFF0E1220),
    screenStroke = null,
    netIdle = Color(0xFF9AA5F5),
)

private val DarkMascot = MascotPalette(
    legs = Color(0xFFC9CEDA),
    backFill = Color(0xFF232A45),
    backStroke = Color(0xFF3A4266),
    cover = Color(0xFF5766F0),
    spine = Color(0xFF4150DB),
    screen = Color(0xFF070A14),
    screenStroke = Color(0xFF2A3150),
    netIdle = Color(0xFFD0D5FC),
)

// «Эмоции» от темы не зависят — общие для обеих палитр
private val NetWork = Color(0xFFFFC94D)
private val NetDone = Color(0xFF7FE0B0)
private val NetErrorLine = Color(0xFFFF8A8A)
private val NodeError = Color(0xFFFF6B6B)
private val FaceIdle = Color(0xFF7F8CF5)
private val FaceTeal = Color(0xFF6EE7C8)
private val ZzColor = Color(0xFFFFC94D)
private val BadgeWork = Color(0xFFFFC94D)
private val BadgeDone = Color(0xFF1F8A5B)
private val BadgeError = Color(0xFFE5484D)
private val Sweat = Color(0xFF7FD3FF)
private val White = Color(0xFFFFFFFF)
private val ArrowDark = Color(0xFF14171F)

/** Узлы сети: A(48,95) B(48,111) C(67,88) D(67,103) E(67,118) F(86,95) G(86,111). */
private val NetNodes = listOf(
    48f to 95f, 48f to 111f, 67f to 88f, 67f to 103f, 67f to 118f, 86f to 95f, 86f to 111f,
)

private val NetEdges = listOf(
    floatArrayOf(48f, 95f, 67f, 88f), floatArrayOf(48f, 95f, 67f, 103f), floatArrayOf(48f, 95f, 67f, 118f),
    floatArrayOf(48f, 111f, 67f, 88f), floatArrayOf(48f, 111f, 67f, 103f), floatArrayOf(48f, 111f, 67f, 118f),
    floatArrayOf(67f, 88f, 86f, 95f), floatArrayOf(67f, 88f, 86f, 111f),
    floatArrayOf(67f, 103f, 86f, 95f), floatArrayOf(67f, 103f, 86f, 111f),
    floatArrayOf(67f, 118f, 86f, 95f), floatArrayOf(67f, 118f, 86f, 111f),
)

/** Ошибка: часть рёбер «оборвана», узел D пустой. */
private val NetEdgesBroken = listOf(
    floatArrayOf(48f, 95f, 67f, 88f), floatArrayOf(48f, 95f, 67f, 118f),
    floatArrayOf(48f, 111f, 67f, 103f),
    floatArrayOf(67f, 88f, 86f, 95f),
    floatArrayOf(67f, 118f, 86f, 111f),
)

/**
 * Цвета индикации маскота для текста статуса — та же «лампочка» на антенне,
 * которую рисует сам маскот: в простое бело-лавандовая, работает — жёлтая,
 * ошибка — красная. В тёмной теме это точные цвета арта; в светлой жёлтый и
 * красный затемнены до читаемых на белой карточке (те же тона), «ожидаю» —
 * лампочка из палитры темы (в светлои она лавандовая).
 */
object MascotLamp {
    /** «Ожидаю»: лампочка в палитре текущей темы. */
    val Idle: Color @Composable get() = if (LocalBtDark.current) DarkMascot.netIdle else LightMascot.netIdle

    /** «Выполняется»: жёлтая лампочка арта; в светлой теме — затемнённый тон того же цвета. */
    val Working: Color @Composable get() = if (LocalBtDark.current) NetWork else Color(0xFF9A6700)

    /** «Ошибка»: красная лампочка арта; в светлой теме — красный бейджа арта (читаемее на белом). */
    val Error: Color @Composable get() = if (LocalBtDark.current) NodeError else Color(0xFFE5484D)
}

/**
 * Бесконечная фаза 0..1 для одной анимации маскота: пока активна — линейно
 * течёт с периодом durationMillis; неактивна — константа (кадры не тикают).
 * Значение читается ТОЛЬКО внутри отрисовки Canvas — обновление фазы вызывает
 * перерисовку без рекомпозиции.
 */
@Composable
private fun phaseAnimation(durationMillis: Int, active: Boolean): State<Float> {
    return if (!active) {
        remember { mutableStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "mascot").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
            label = "phase",
        )
    }
}

/** Синус полного цикла фазы 0..1 — базовое колебание анимаций. */
private fun sinTau(t: Float): Float = sin((t * 2.0 * PI).toFloat())

/** Дробная часть — нормирует сдвинутые фазы в 0..1. */
private fun fract(t: Float): Float = t - floor(t)

@Composable
fun BookMascot(mood: MascotMood, modifier: Modifier = Modifier) {
    val dark = LocalBtDark.current
    val tShake = phaseAnimation(320, active = mood == MascotMood.WORKING)
    val tFloat = phaseAnimation(2800, active = mood == MascotMood.IDLE)
    val tBlink = phaseAnimation(800, active = mood == MascotMood.ERROR)
    Canvas(modifier.aspectRatio(MASCOT_W / MASCOT_H)) {
        val s = size.width / MASCOT_W
        withTransform({
            scale(s, s, pivot = Offset.Zero)
            // Дрожь в родных координатах viewBox — вокруг середины ножек
            if (mood == MascotMood.WORKING) {
                rotate(2f * sinTau(tShake.value), pivot = Offset(67f, 148f))
            }
        }) {
            drawMascot(mood, if (dark) DarkMascot else LightMascot, tFloat.value, tBlink.value)
        }
    }
}

private fun DrawScope.drawMascot(mood: MascotMood, p: MascotPalette, tFloat: Float, tBlink: Float) {
    // Ножки
    fillRoundRect(38f, 130f, 16f, 18f, 6f, p.legs)
    fillRoundRect(76f, 130f, 16f, 18f, 6f, p.legs)
    // Задняя обложка (тень)
    fillRoundRect(26f, 22f, 80f, 118f, 14f, p.backFill)
    strokeRoundRect(26f, 22f, 80f, 118f, 14f, p.backStroke, 1f)
    // Обложка и корешок
    fillRoundRect(18f, 16f, 80f, 120f, 16f, p.cover)
    pathFill(p.spine) {
        moveTo(18f, 32f)
        quadraticTo(18f, 16f, 34f, 16f)
        lineTo(36f, 16f)
        lineTo(36f, 136f)
        lineTo(34f, 136f)
        quadraticTo(18f, 136f, 18f, 120f)
        close()
    }
    // Антенна (у ошибки сломана — наклонена вправо)
    if (mood == MascotMood.ERROR) {
        line(67f, 16f, 73f, 8f, p.spine, 3f)
    } else {
        line(67f, 16f, 67f, 7f, p.spine, 3f)
    }
    // Экран лица
    fillRoundRect(42f, 30f, 50f, 38f, 11f, p.screen)
    p.screenStroke?.let { strokeRoundRect(42f, 30f, 50f, 38f, 11f, it, 1f) }
    drawFace(mood, p, tBlink)
    drawNet(mood, p)
    drawBadgeAndExtras(mood, p, tFloat)
}

private fun DrawScope.drawFace(mood: MascotMood, p: MascotPalette, tBlink: Float) {
    when (mood) {
        MascotMood.IDLE -> {
            // Мечтательно прикрытые глаза и ровный рот
            pathStroke(FaceIdle, 3f) { moveTo(52f, 50f); quadraticTo(57f, 55f, 62f, 50f) }
            pathStroke(FaceIdle, 3f) { moveTo(72f, 50f); quadraticTo(77f, 55f, 82f, 50f) }
            pathStroke(FaceIdle, 2f) { moveTo(62f, 60f); lineTo(72f, 60f) }
        }
        MascotMood.WORKING -> {
            // Круглые глаза с бликами и рот из трёх точек
            circle(57f, 50f, 6f, FaceTeal)
            circle(77f, 50f, 6f, FaceTeal)
            circle(58.5f, 48.5f, 1.8f, White)
            circle(78.5f, 48.5f, 1.8f, White)
            circle(60f, 60f, 1.6f, FaceTeal)
            circle(67f, 60f, 1.6f, FaceTeal)
            circle(74f, 60f, 1.6f, FaceTeal)
        }
        MascotMood.DONE -> {
            // Прищур счастья и широкая улыбка
            pathStroke(FaceTeal, 3f) { moveTo(52f, 52f); quadraticTo(57f, 45f, 62f, 52f) }
            pathStroke(FaceTeal, 3f) { moveTo(72f, 52f); quadraticTo(77f, 45f, 82f, 52f) }
            pathStroke(FaceTeal, 3f) { moveTo(60f, 58f); quadraticTo(67f, 66f, 74f, 58f) }
        }
        MascotMood.ERROR -> {
            // Крестики вместо глаз мигают, поджатые губы статичны
            val glow = 0.3f + 0.35f * (1f + sinTau(tBlink))
            pathStroke(NodeError.copy(alpha = glow), 3f) { moveTo(52f, 45f); lineTo(62f, 55f); moveTo(62f, 45f); lineTo(52f, 55f) }
            pathStroke(NodeError.copy(alpha = glow), 3f) { moveTo(72f, 45f); lineTo(82f, 55f); moveTo(82f, 45f); lineTo(72f, 55f) }
            pathStroke(NodeError, 2.5f) { moveTo(60f, 63f); quadraticTo(67f, 57f, 74f, 63f) }
        }
    }
}

private fun DrawScope.drawNet(mood: MascotMood, p: MascotPalette) {
    when (mood) {
        MascotMood.IDLE -> {
            val c = p.netIdle.copy(alpha = 0.7f)
            NetEdges.forEach { line(it[0], it[1], it[2], it[3], c, 1.3f) }
            NetNodes.forEach { (x, y) -> circle(x, y, 3.2f, p.netIdle) }
            circle(67f, 3f, 5f, p.netIdle)
        }
        MascotMood.WORKING -> {
            val c = NetWork.copy(alpha = 0.95f)
            NetEdges.forEach { line(it[0], it[1], it[2], it[3], c, 1.3f) }
            NetNodes.forEach { (x, y) -> circle(x, y, 3.2f, NetWork) }
            // Мяч антенны светится
            circle(67f, 3f, 14f, NetWork.copy(alpha = 0.2f))
            circle(67f, 3f, 9f, NetWork.copy(alpha = 0.35f))
            circle(67f, 3f, 5f, NetWork)
        }
        MascotMood.DONE -> {
            val c = NetDone.copy(alpha = 0.85f)
            NetEdges.forEach { line(it[0], it[1], it[2], it[3], c, 1.3f) }
            NetNodes.forEach { (x, y) -> circle(x, y, 3.2f, NetDone) }
            circle(67f, 3f, 5f, BadgeDone)
        }
        MascotMood.ERROR -> {
            val c = NetErrorLine.copy(alpha = 0.8f)
            val dashed = PathEffect.dashPathEffect(floatArrayOf(2.5f, 2.5f))
            NetEdgesBroken.forEach { line(it[0], it[1], it[2], it[3], c, 1.3f, effect = dashed) }
            NetNodes.forEach { (x, y) ->
                if (x == 67f && y == 103f) ring(x, y, 3.2f, p.netIdle, 1.2f) else circle(x, y, 3.2f, NodeError)
            }
        }
    }
}

private fun DrawScope.drawBadgeAndExtras(mood: MascotMood, p: MascotPalette, tFloat: Float) {
    when (mood) {
        MascotMood.IDLE -> {
            // «zz» плывут от головы вверх-вправо: медленный подъём с лёгким
            // покачиванием и небольшим ростом; фазы циклов разнесены, на концах
            // буквы растворяются, поэтому перезапуск цикла не заметен
            floatingZ(cx = 106f, cy = 8.5f, w = 8f, h = 9f, phase = tFloat, travel = 11f, driftX = 3.5f, sway = 1.1f, stroke = 2.4f)
            floatingZ(cx = 115.75f, cy = -1.75f, w = 5.5f, h = 6.5f, phase = tFloat + 0.5f, travel = 9f, driftX = 3f, sway = 0.9f, stroke = 1.9f)
        }
        MascotMood.WORKING -> {
            circle(104f, 22f, 15f, BadgeWork)
            pathStroke(ArrowDark, 2.5f) { moveTo(108f, 14f); lineTo(113f, 18f); lineTo(108f, 22f) }
            line(95f, 18f, 112f, 18f, ArrowDark, 2.5f)
            pathStroke(ArrowDark, 2.5f) { moveTo(100f, 22f); lineTo(95f, 26f); lineTo(100f, 30f) }
            line(113f, 26f, 96f, 26f, ArrowDark, 2.5f)
        }
        MascotMood.DONE -> {
            circle(104f, 22f, 15f, BadgeDone)
            pathStroke(White, 3f) { moveTo(96f, 22f); lineTo(102f, 28f); lineTo(113f, 16f) }
        }
        MascotMood.ERROR -> {
            // Искры на сломанной антенне и капля пота
            circle(75f, 4f, 5f, NodeError)
            line(82f, -2f, 86f, -6f, NodeError, 2f)
            line(84f, 4f, 89f, 4f, NodeError, 2f)
            pathFill(Sweat) {
                moveTo(97f, 46f)
                quadraticTo(101f, 52f, 97f, 56f)
                quadraticTo(93f, 52f, 97f, 46f)
                close()
            }
            circle(104f, 22f, 15f, BadgeError)
            line(104f, 14f, 104f, 23f, White, 3.5f)
            circle(104f, 29.5f, 2.2f, White)
        }
    }
}

// ---------- примитивы в координатах viewBox ----------

/**
 * Одна плывущая «z» (штриховой зигзаг как в SVG-арте): центр (cx, cy) в фазе 0,
 * за цикл поднимается на travel и уходит вправо на driftX с покачиванием sway;
 * появление/растворение — первые/последние доли цикла.
 */
private fun DrawScope.floatingZ(
    cx: Float, cy: Float, w: Float, h: Float,
    phase: Float, travel: Float, driftX: Float, sway: Float, stroke: Float,
) {
    val t = fract(phase)
    val alpha = (t / 0.22f).coerceAtMost(1f) * ((1f - t) / 0.28f).coerceAtMost(1f)
    if (alpha <= 0.02f) return
    val y = cy - t * travel
    val x = cx + t * driftX + sinTau(t) * sway
    val grow = 0.92f + 0.22f * t
    val hw = w * grow / 2f
    val hh = h * grow / 2f
    pathStroke(ZzColor.copy(alpha = alpha), stroke) {
        moveTo(x - hw, y - hh)
        lineTo(x + hw, y - hh)
        lineTo(x - hw, y + hh)
        lineTo(x + hw, y + hh)
    }
}

private fun DrawScope.fillRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) {
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r, r))
}

private fun DrawScope.strokeRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, width: Float) {
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r, r), style = Stroke(width))
}

private fun DrawScope.circle(cx: Float, cy: Float, r: Float, color: Color) {
    drawCircle(color, r, Offset(cx, cy))
}

private fun DrawScope.ring(cx: Float, cy: Float, r: Float, color: Color, width: Float) {
    drawCircle(color, r, Offset(cx, cy), style = Stroke(width))
}

private fun DrawScope.line(
    x1: Float, y1: Float, x2: Float, y2: Float,
    color: Color, width: Float, effect: PathEffect? = null,
) {
    drawLine(color, Offset(x1, y1), Offset(x2, y2), width, cap = StrokeCap.Round, pathEffect = effect)
}

private fun DrawScope.pathStroke(color: Color, width: Float, effect: PathEffect? = null, build: Path.() -> Unit) {
    drawPath(Path().apply(build), color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private fun DrawScope.pathFill(color: Color, build: Path.() -> Unit) {
    drawPath(Path().apply(build), color)
}
