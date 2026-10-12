package ru.sebyanin.booktranslator

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ru.sebyanin.booktranslator.core.AppServices
import ru.sebyanin.booktranslator.core.mcp.McpServer
import ru.sebyanin.booktranslator.core.mcp.StageLauncher
import ru.sebyanin.booktranslator.ui.AppShell
import ru.sebyanin.booktranslator.ui.MascotMockupsScreen
import ru.sebyanin.booktranslator.ui.theme.BtTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/** Иконка окна и таскбара из ресурсов класспаса (работает и из запакованного jar). */
private fun loadAppIcon(): Painter? {
    val url = ClassLoader.getSystemResource("icon.png") ?: return null
    val awt: BufferedImage = try {
        ImageIO.read(url)
    } catch (_: Exception) {
        return null
    } ?: return null
    return AppIconPainter(awt.toComposeImageBitmap())
}

private class AppIconPainter(private val bitmap: ImageBitmap) : Painter() {
    override val intrinsicSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())

    override fun DrawScope.onDraw() {
        drawImage(bitmap, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
}

/**
 * Отладочный автоскриншот — работает ТОЛЬКО при явном -Dbt.shot=<путь.png>
 * (см. проброс -Dbt.* в desktop/build.gradle.kts). Приложение само снимает
 * СВОЁ окно в PNG; внешние скрипты, снимающие экран или трогающие чужие окна,
 * для проверки UI не используются. bt.shotDelayMs — задержка перед снимком
 * (по умолчанию 6 с — дождаться старта и восстановления сессии; окно,
 * появившееся позже, дополнительно ждётся поллингом до 30 с),
 * bt.shotExit=true — закрыть приложение сразу после снимка, ГАРАНТИРОВАННО —
 * даже если окно так и не нашлось (иначе тестовый экземпляр повисает).
 */
private fun startAutoShot() {
    val path = System.getProperty("bt.shot") ?: return
    val delayMs = System.getProperty("bt.shotDelayMs", "6000")?.toLongOrNull() ?: 6000L
    val exitAfter = System.getProperty("bt.shotExit") == "true"
    Thread({
        try {
            Thread.sleep(delayMs)
            val win = pollShowingWindow(30_000L)
            if (win == null) {
                println("AUTOSHOT: showing window not found, снимок не сделан")
            } else {
                // Голый toFront() из фонового процесса Windows игнорирует (запрет
                // кражи фокуса) и снимок ловит чужое окно поверх. Поднимаем только
                // СВОЁ окно — alwaysOnTop не требует фокуса, чужие окна не трогаем.
                win.isAlwaysOnTop = true
                win.toFront()
                Thread.sleep(700)
                val loc = win.locationOnScreen
                val image = java.awt.Robot()
                    .createScreenCapture(java.awt.Rectangle(loc.x, loc.y, win.width, win.height))
                win.isAlwaysOnTop = false
                ImageIO.write(image, "png", File(path))
                println("AUTOSHOT saved: $path")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (exitAfter) exitProcess(0)
    }, "bt-autoshot").apply {
        isDaemon = true
        start()
    }
}

/** Ждёт появления видимого окна не дольше timeoutMillis, опрашивая раз в 250 мс. */
private fun pollShowingWindow(timeoutMillis: Long): java.awt.Window? {
    val giveUpAt = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < giveUpAt) {
        java.awt.Window.getWindows().firstOrNull { it.isShowing && it.width > 0 }
            ?.let { return it }
        Thread.sleep(250)
    }
    return null
}

fun main() = application {
    startAutoShot()
    val services = remember { AppServices.create() }
    val scope = rememberCoroutineScope()
    val state = remember { AppState(services, scope) }
    // -Dbt.tab=settings|log|critic|glossary — открыть вкладку при старте
    // (проверка раскладки скриншотом, как -Dbt.theme для тем)
    System.getProperty("bt.tab")?.let { state.requestedTab.value = it }

    // MCP-сервер: живёт по настройкам (вкл/выкл, порт, токен)
    val mcpServer = remember {
        McpServer(services, StageLauncher(state::launchStage))
    }
    val settings by services.settings.collectAsState()
    LaunchedEffect(settings.mcp.enabled, settings.mcp.port) {
        mcpServer.stop()
        if (settings.mcp.enabled) {
            mcpServer.start(settings.mcp.port, settings.mcp.token)
        }
    }
    DisposableEffect(Unit) {
        onDispose { mcpServer.stop() }
    }

    // -Dbt.mockups=true — временный стенд вариантов дизайна статуса (маскот)
    val mockups = System.getProperty("bt.mockups") == "true"

    Window(
        onCloseRequest = ::exitApplication,
        title = "Переводчик книг",
        icon = remember { loadAppIcon() },
        // rememberWindowState: состояние (вкл. максимизацию) переживает рекомпозиции
        state = rememberWindowState(width = if (mockups) 1180.dp else 1440.dp, height = 860.dp),
    ) {
        // -Dbt.theme=light|dark — отладочный оверрайд (проверка тем без правки settings.json)
        val themeOverride = System.getProperty("bt.theme")
        val dark = when (themeOverride ?: settings.themeMode) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme()
        }
        BtTheme(dark = dark) {
            if (mockups) MascotMockupsScreen() else AppShell(state = state, services = services)
        }
    }
}
