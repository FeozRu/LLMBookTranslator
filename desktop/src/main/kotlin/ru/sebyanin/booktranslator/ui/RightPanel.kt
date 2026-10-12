package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.ui.components.BtButton
import ru.sebyanin.booktranslator.ui.components.BtButtonKind
import ru.sebyanin.booktranslator.ui.components.BtCard
import ru.sebyanin.booktranslator.ui.theme.BtPalette

/**
 * Правая колонка на всю высоту окна: сверху — статусная карточка в стиле
 * карточек этапов (маскот-малютка слева сверху, лицо — по статусу конвейера;
 * текст и кнопки справа), видна всегда; ниже — [Этапы]: статусы и запуск.
 * Настройки переехали во вкладку центра [SettingsTab] (после Журнала) —
 * в 340dp панели им было тесно.
 */
@Composable
fun RightPanel(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier.background(BtPalette.Card)) {
        Box(Modifier.fillMaxWidth().padding(12.dp)) {
            MascotStatusCard(state)
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            StagePanel(state, Modifier.fillMaxSize())
        }
    }
}

// ---------------- Статусная карточка с маскотом ----------------

/**
 * Статусная карточка (вариант «А» со стенда -Dbt.mockups): маскот-малютка
 * слева сверху, текст уведомления справа от него; кнопки — ОТДЕЛЬНОЙ полосой
 * под разделителем на всю ширину карточки, прижаты вправо (по наброску
 * пользователя: иначе кнопки сливаются с текстом). В простое кнопок нет —
 * разделитель тоже не рисуется. Единственная поверхность статуса: во время
 * этапа «Выполняется: …» с прогрессом и «Остановить»; в простое — последнее
 * сообщение («Ошибка: …» подсвечивается, кнопка «Журнал» открывает полный
 * копируемый текст); если сообщений ещё не было — «ожидаю команд». Карточка
 * видна всегда.
 */
@Composable
private fun MascotStatusCard(state: AppState) {
    val running by state.runningStage.collectAsState()
    val progressText by state.stageProgress.collectAsState()
    val message by state.message.collectAsState()

    val r = running
    val m = message
    val mood = when {
        r != null -> MascotMood.WORKING
        m?.startsWith("Ошибка") == true -> MascotMood.ERROR
        m != null -> MascotMood.DONE
        else -> MascotMood.IDLE
    }

    BtCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                BookMascot(mood, Modifier.height(95.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        r != null -> {
                            Text(
                                "Выполняется: ${stageTitle(r)}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                // Цвет индикации маскота — жёлтая лампочка антенны
                                color = MascotLamp.Working,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (progressText.isNotBlank()) {
                                Text(
                                    progressText,
                                    fontSize = 11.sp,
                                    color = BtPalette.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        m != null -> {
                            val isError = m.startsWith("Ошибка")
                            Text(
                                m,
                                fontSize = 13.sp,
                                // Ошибка — в красный лампочки/искры маскота
                                color = if (isError) MascotLamp.Error else BtPalette.Text,
                                lineHeight = 17.sp,
                                // Сообщение в карточке — для опознания: полный текст
                                // копируемый в журнале, не тащим его сюда целиком
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else -> Text(
                            "ожидаю команд",
                            fontSize = 13.sp,
                            // Тон в цвет белой лампочки простоя маскота
                            color = MascotLamp.Idle,
                        )
                    }
                }
            }
            // Полоса кнопок под разделителем — только когда есть что нажимать
            if (r != null || m != null) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(BtPalette.Border))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    when {
                        r != null ->
                            BtButton("Остановить", onClick = { state.stopStage() }, compact = true, height = 28.dp)
                        m != null -> {
                            if (m.startsWith("Ошибка")) {
                                BtButton(
                                    "Журнал",
                                    kind = BtButtonKind.Primary,
                                    compact = true,
                                    height = 28.dp,
                                    onClick = { state.requestedTab.value = "log" },
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            BtButton("Скрыть", compact = true, height = 28.dp, onClick = { state.message.value = null })
                        }
                    }
                }
            }
        }
    }
}

private fun stageTitle(stage: String): String = when (stage) {
    "split" -> "нарезка"
    "draft" -> "черновик"
    "edit" -> "редактура"
    "critic" -> "корректор"
    "revisor" -> "ревизор"
    "assemble" -> "сборка главы"
    "import" -> "импорт"
    "qa" -> "QA"
    else -> stage
}

// ---------------- Этапы ----------------

@Composable
private fun StagePanel(state: AppState, modifier: Modifier = Modifier) {
    val manifest by state.manifest.collectAsState()
    val structure by state.structure.collectAsState()
    val book by state.currentBook.collectAsState()
    val running by state.runningStage.collectAsState()
    val glossary by state.glossary.collectAsState()
    val notes by state.notes.collectAsState()

    val frags = manifest?.allFragments().orEmpty()
    val draftDone = frags.count { it.draft.isDone }
    val draftFailed = frags.count { it.draft.isFailed }
    val editDone = frags.count { it.edited.isDone }
    val editFailed = frags.count { it.edited.isFailed }

    val p = state.currentProject.value
    val b = book
    val chaptersTotal = structure?.chapters?.size ?: 0
    // criticVersion в ключах: применение/отклонение правок меняет только
    // work/critic/*.json, манифест структурно равен прежнему — без этого
    // сигнала «к ревью: N» замерло бы на стартовом значении
    val criticVersion by state.criticVersion.collectAsState()
    val criticPassed = remember(manifest, book, criticVersion) {
        if (p == null || b == null) 0
        else structure?.chapters?.count { state.services.suggestionService.loadChapter(p.slug, b.slug, it.index) != null } ?: 0
    }
    val pendingSuggestions = remember(manifest, book, criticVersion) {
        if (p == null || b == null) 0
        else structure?.chapters?.sumOf { state.services.suggestionService.pending(p.slug, b.slug, it.index).size } ?: 0
    }
    val proposed = glossary.count { it.status == "proposed" } + notes.count { it.status == "proposed" }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Карточка «Выполняется» и статус-сообщение живут в правой зоне шапки (HeaderBar)

        // Нарезка
        StageCard(
            title = "Нарезка",
            status = when {
                running == "split" -> "работает" to BtPalette.AccentText
                frags.isEmpty() -> "ожидает" to BtPalette.TextSecondary
                else -> "готово" to BtPalette.SuccessDark
            },
            description = if (frags.isEmpty()) "Главы не нарезаны на фрагменты" else "${frags.size} фрагментов готовы к переводу",
            progress = if (frags.isEmpty()) null else 1f,
            action = "Нарезать",
            actionEnabled = running == null && b != null,
            onAction = { state.runSplit() },
        )

        // Черновик
        StageCard(
            title = "Черновик (Переводчик)",
            status = when {
                running == "draft" -> "работает" to BtPalette.AccentText
                frags.isEmpty() -> "после нарезки" to BtPalette.TextSecondary
                draftFailed > 0 -> "ошибки: $draftFailed" to BtPalette.Warn
                draftDone == frags.size -> "готово" to BtPalette.SuccessDark
                draftDone == 0 -> "ожидает" to BtPalette.TextSecondary
                else -> "в процессе" to BtPalette.AccentText
            },
            description = when {
                frags.isEmpty() -> "—"
                draftFailed > 0 -> "$draftDone из ${frags.size} готово; повтор доберёт только ошибки"
                draftDone == 0 -> "Дословный черновик с тегами тона"
                else -> "$draftDone из ${frags.size} фрагментов переведено"
            },
            progress = if (frags.isEmpty()) null else draftDone.toFloat() / frags.size,
            action = if (draftDone > 0 && draftDone < frags.size) "Продолжить" else "Запустить",
            actionEnabled = running == null && frags.isNotEmpty(),
            onAction = { state.runDraft() },
        )

        // Редактура
        StageCard(
            title = "Редактура (Редактор)",
            status = when {
                running == "edit" -> "работает" to BtPalette.AccentText
                frags.isEmpty() || draftDone == 0 -> "после черновика" to BtPalette.TextSecondary
                editFailed > 0 -> "ошибки: $editFailed" to BtPalette.Warn
                editDone == frags.size -> "готово" to BtPalette.SuccessDark
                editDone == 0 -> "ожидает" to BtPalette.TextSecondary
                else -> "в процессе" to BtPalette.AccentText
            },
            description = when {
                frags.isEmpty() || draftDone == 0 -> "—"
                editFailed > 0 -> "$editDone из ${frags.size}; повтор доберёт ошибки"
                else -> "$editDone из ${frags.size} фрагментов отредактировано"
            },
            progress = if (frags.isEmpty() || draftDone == 0) null else editDone.toFloat() / frags.size,
            action = if (editDone > 0 && editDone < frags.size) "Продолжить" else "Запустить",
            actionEnabled = running == null && draftDone > 0,
            onAction = { state.runEdit() },
        )

        // Корректор
        StageCard(
            title = "Корректор",
            status = when {
                running == "critic" -> "работает" to BtPalette.AccentText
                chaptersTotal == 0 || editDone == 0 -> "после редактуры" to BtPalette.TextSecondary
                pendingSuggestions > 0 -> "к ревью: $pendingSuggestions" to BtPalette.Warn
                criticPassed == chaptersTotal -> "готово" to BtPalette.SuccessDark
                else -> "частично" to BtPalette.AccentText
            },
            description = when {
                chaptersTotal == 0 || editDone == 0 -> "—"
                else -> "глав пройдено $criticPassed из $chaptersTotal" +
                    (if (pendingSuggestions > 0) ", предложений к ревью: $pendingSuggestions" else "")
            },
            progress = if (chaptersTotal == 0) null else criticPassed.toFloat() / chaptersTotal,
            action = if (pendingSuggestions > 0 || criticPassed > 0) "Повторить" else "Запустить",
            actionEnabled = running == null && editDone > 0,
            onAction = { state.runCritic() },
            extraAction = if (pendingSuggestions > 0) {
                "К ревью" to { state.requestedTab.value = "critic" }
            } else null,
        )

        // Терминолог
        BtCard(contentPadding = PaddingValues(12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Терминолог", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        if (proposed > 0) "предложений: $proposed" else "канон чист",
                        fontSize = 13.sp,
                        color = if (proposed > 0) BtPalette.Warn else BtPalette.TextSecondary,
                    )
                }
                if (proposed > 0) {
                    Text("Предложения ждут утверждения в глоссарии", fontSize = 12.sp, color = BtPalette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    BtButton("К глоссарию", onClick = { state.requestedTab.value = "glossary" }, height = 30.dp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ревизор — LLM-прогон: качество вариантов, дубли, расхождения транскрипции",
                    fontSize = 12.sp,
                    color = BtPalette.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    BtButton(
                        "Прогнать ревизора",
                        kind = BtButtonKind.Primary,
                        height = 30.dp,
                        enabled = running == null && b != null,
                        onClick = { state.runGlossaryRevisor("queue") },
                    )
                    Spacer(Modifier.width(6.dp))
                    BtButton(
                        "По канону",
                        height = 30.dp,
                        enabled = running == null && b != null,
                        onClick = { state.runGlossaryRevisor("canon") },
                    )
                }
            }
        }
    }
}

@Composable
private fun StageCard(
    title: String,
    status: Pair<String, androidx.compose.ui.graphics.Color>,
    description: String,
    progress: Float?,
    action: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    extraAction: Pair<String, () -> Unit>? = null,
) {
    BtCard(contentPadding = PaddingValues(12.dp)) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(status.first, fontSize = 13.sp, color = status.second)
            }
            if (description != "—") {
                Text(description, fontSize = 12.sp, color = BtPalette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
            progress?.let {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(6.dp)
                        .background(BtPalette.AccentBorderLight, RoundedCornerShape(3.dp)),
                ) {
                    Row(
                        Modifier.fillMaxWidth(it.coerceIn(0f, 1f)).height(6.dp)
                            .background(BtPalette.Accent, RoundedCornerShape(3.dp)),
                    ) {}
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                BtButton(action, onClick = onAction, enabled = actionEnabled, kind = BtButtonKind.Primary, height = 30.dp)
                extraAction?.let { (label, onClick) ->
                    Spacer(Modifier.width(6.dp))
                    BtButton(label, onClick = onClick, height = 30.dp)
                }
            }
        }
    }
}

