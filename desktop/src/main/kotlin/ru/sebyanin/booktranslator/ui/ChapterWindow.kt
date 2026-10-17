package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.ui.theme.BtPalette

/**
 * Сколько глав окна держится собранным: дальние вытесняются, данные читаются с диска заново.
 */
internal const val CHAPTER_WINDOW_CAP = 8

/**
 * Номер главы из ключа строки непрерывного чтения. Ключи обеих панелей —
 * «буквенный префикс + номер главы» (разделители «d8»/«cd8», блок «b8:3»,
 * карточка корректора «cs8:c8-7», хвосты «t8»/«ct8»): цифры сразу после префикса.
 * Ключ верен, пока строка показана, — по нему scroll-spy читает главу даже в кадр,
 * когда раскладка ещё не перестроилась под текущие строки.
 */
internal fun chapterOfRowKey(key: Any?): Int? {
    val s = key as? String ?: return null
    var start = 0
    while (start < s.length && !s[start].isDigit()) start++
    var end = start
    while (end < s.length && s[end].isDigit()) end++
    return if (end > start) s.substring(start, end).toIntOrNull() else null
}

/** За сколько строк до края окна догружать соседнюю главу (примерно экран текста). */
internal const val LOAD_AHEAD_ROWS = 14

/**
 * Состояние скользящего окна глав для непрерывного чтения (рабочая зона «Перевод»,
 * вкладка корректора): строки всех глав окна идут одним LazyColumn-списком; следующая
 * глава подгружается у нижнего края, предыдущая у верхнего, дальние вытесняются —
 * память ограничена, данные главы читаются с диска только при входе в окно. Позиция
 * вьюпорта при вставках/вытеснениях сохраняется (requestScrollToItem). Глава первой
 * видимой строки сообщается в onSpyChapter (scroll-spy: шапка, кнопки этапа и левое
 * меню следят за чтением); внешняя навигация (смена selected/книги) пересобирает
 * окно вокруг главы и прыгает к её началу.
 */
@Stable
class ChapterWindowState internal constructor(
    val listState: LazyListState,
    initialChapter: Int,
    initialBookSlug: String?,
) {
    /** Главы окна по порядку книги — по ним строятся строки списка. */
    var window by mutableStateOf(listOf(initialChapter))
        internal set
    internal var spyChapter by mutableStateOf<Int?>(null)
    internal var windowBook by mutableStateOf(initialBookSlug)
    internal var jumpTick by mutableStateOf(0)
}

/** Создаёт состояние окна. Строки списка строятся ПОСЛЕ него — по [ChapterWindowState.window]. */
@Composable
fun rememberChapterWindowState(bookSlug: String?, initialChapter: Int): ChapterWindowState {
    val listState = rememberLazyListState()
    return remember { ChapterWindowState(listState, initialChapter, bookSlug) }
}

/**
 * Эффекты окна: внешняя навигация, прыжок к началу, scroll-spy и догрузка соседей
 * у краёв. [rowChapterAt] — глава строки по индексу текущего списка строк (null за
 * пределами); [chapterRowCount] — сколько строк займёт глава (разделитель + содержимое),
 * нужна ДО входа главы в окно — считается по данным, не по готовым строкам;
 * [chapterOfKey] — глава по ключу строки списка: spy читает её из ключа первой
 * видимой строки, потому что ключ верен, пока строка показана, независимо от раскладки;
 * [rowsVersion] сменяется при пересборке строк и перезапускает эффекты со свежими
 * лямбдами (List-ключ сравнивается по содержимому, класс — по ссылке).
 */
@Composable
fun ChapterWindowEffects(
    state: ChapterWindowState,
    selected: Int?,
    bookSlug: String?,
    chapterOrder: List<Int>,
    rowsVersion: Any?,
    rowChapterAt: (Int) -> Int?,
    chapterRowCount: (Int) -> Int,
    onSpyChapter: (Int) -> Unit,
    chapterOfKey: (Any?) -> Int? = ::chapterOfRowKey,
) {
    val listState = state.listState

    // Внешняя навигация (левое меню, открытие книги): окно пересобирается вокруг главы.
    // spyChapter отличает собственные обновления scroll-spy от внешней навигации
    LaunchedEffect(bookSlug, selected) {
        if (selected == null) return@LaunchedEffect
        val external = selected != state.spyChapter || bookSlug != state.windowBook
        state.windowBook = bookSlug
        if (!external) return@LaunchedEffect
        state.window = listOf(selected)
        state.jumpTick++
    }

    LaunchedEffect(state.jumpTick) {
        if (state.jumpTick > 0) listState.scrollToItem(0)
    }

    fun appendNext(first: Int, offset: Int) {
        val idx = chapterOrder.indexOf(state.window.last())
        val next = chapterOrder.getOrNull(idx + 1) ?: return
        var newWindow = state.window + next
        if (newWindow.size > CHAPTER_WINDOW_CAP) {
            // Вытесняем первую главу окна, только если она целиком выше вьюпорта,
            // и переносим позицию на ту же видимую строку нового списка
            var dropped = 0
            while (rowChapterAt(dropped) == newWindow.first()) dropped++
            if (dropped in 1..first) {
                listState.requestScrollToItem(first - dropped, offset)
                newWindow = newWindow.drop(1)
            }
        }
        state.window = newWindow
    }

    fun prependPrev(first: Int, offset: Int) {
        val idx = chapterOrder.indexOf(state.window.first())
        val prev = chapterOrder.getOrNull(idx - 1) ?: return
        // Вьюпорт остаётся на той же видимой строке
        val shift = chapterRowCount(prev)
        listState.requestScrollToItem(first + shift, offset)
        var newWindow = listOf(prev) + state.window
        if (newWindow.size > CHAPTER_WINDOW_CAP) newWindow = newWindow.dropLast(1)
        state.window = newWindow
    }

    // Scroll-spy: глава первой видимой строки становится текущей. Глава читается
    // из КЛЮЧА строки, а не маппингом индекса по строкам: после смены окна
    // (вставка/вытеснение с requestScrollToItem) эффект перезапускается раньше
    // remeasure и видит старый firstVisibleItemIndex поверх новых строк — индекс
    // указывал на соседнюю главу, и на каждой границе при полном окне шапка и
    // левое меню моргали «на главу вперёд и обратно». Ключ принадлежит реально
    // показанной строке, поэтому верен в любой момент, даже когда раскладка ещё
    // не перестроилась под текущие строки
    LaunchedEffect(rowsVersion) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { chapterOfKey(it.key) }
        }.collect { c ->
            if (c != null && c != state.spyChapter) {
                state.spyChapter = c
                onSpyChapter(c)
            }
        }
    }

    // Догрузка соседей у краёв окна. Одна мутация на эмиссию: после неё строки
    // пересобираются и эффект перезапустится с новым окном
    LaunchedEffect(rowsVersion, chapterOrder) {
        var busy = false
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                listState.layoutInfo.totalItemsCount,
            )
        }.collect { (first, last, total) ->
            if (busy || total == 0 || chapterOrder.isEmpty()) return@collect
            // Раскладка ещё не перестроилась под свежие строки (смена окна из этого
            // же кадра, remeasure с requestScrollToItem впереди): старые first/total
            // поверх новых строк рассчитали бы перенос вьюпорта по чужому индексу —
            // вьюпорт прыгал бы на главу. Ждём remeasure: у показанной первой строки
            // глава из ключа и маппинг индекса обязаны совпадать
            val keyChapter = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let { chapterOfKey(it.key) }
            if (keyChapter == null || keyChapter != rowChapterAt(first)) return@collect
            val offset = listState.firstVisibleItemScrollOffset
            if (last >= total - LOAD_AHEAD_ROWS && state.window.last() != chapterOrder.last()) {
                busy = true
                appendNext(first, offset)
            } else if (first <= LOAD_AHEAD_ROWS && state.window.first() != chapterOrder.first()) {
                busy = true
                prependPrev(first, offset)
            }
        }
    }

    // bt-проверка границы глав при полном окне: -Dbt.wsWalkChapters=<N> — шагами
    // по пол-экрана докрутить до главы N с паузами, чтобы догрузка успевала менять
    // окно: у предела окна каждая граница проходится со вставкой/вытеснением и
    // переносом вьюпорта (requestScrollToItem) — именно там жило «моргание» шапки
    // и левого меню на соседнюю главу. Цель — spy-глава N; шапка/меню после прохода
    // обязаны остаться на ней же
    LaunchedEffect(chapterOrder) {
        val walkTo = System.getProperty("bt.wsWalkChapters")?.toIntOrNull() ?: return@LaunchedEffect
        var steps = 0
        // Шаг может перепрыгнуть короткую главу — цель «дошли или прошли мимо» (>=)
        while ((state.spyChapter ?: 0) < walkTo && steps++ < 400) {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) {
                delay(100)
                continue
            }
            if (state.window.last() == chapterOrder.last() &&
                listState.firstVisibleItemIndex >= info.totalItemsCount - 1
            ) break // дальше идти некуда — цель за пределами книги
            val step = (info.visibleItemsInfo.size / 2).coerceAtLeast(3)
            listState.scrollToItem((listState.firstVisibleItemIndex + step).coerceAtMost(info.totalItemsCount - 1))
            delay(120)
        }
    }
}

/** Разделитель глав в непрерывном чтении: тонкая линия + подпись «ГЛАВА N · название». */
@Composable
internal fun ChapterDivider(chapter: Int, title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(BtPalette.Border))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "ГЛАВА $chapter",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                color = BtPalette.AccentText,
            )
            if (title.isNotBlank()) {
                Text(
                    title,
                    fontSize = 11.sp,
                    color = BtPalette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
