package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.ApiKind
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.LlmModel
import ru.sebyanin.booktranslator.core.model.LlmProvider
import ru.sebyanin.booktranslator.ui.components.BtButton
import ru.sebyanin.booktranslator.ui.components.BtButtonKind
import ru.sebyanin.booktranslator.ui.components.BtCard
import ru.sebyanin.booktranslator.ui.components.BtDropdown
import ru.sebyanin.booktranslator.ui.components.BtTextArea
import ru.sebyanin.booktranslator.ui.components.SectionCaption
import ru.sebyanin.booktranslator.ui.settings.FieldAbove
import ru.sebyanin.booktranslator.ui.settings.FieldRow
import ru.sebyanin.booktranslator.ui.settings.FieldSpec
import ru.sebyanin.booktranslator.ui.theme.BtPalette
import java.nio.file.Files
import kotlin.math.roundToInt

/** Ширина колонки навигации по разделам. */
private val SettingsNavWidth = 200.dp

/**
 * Раздел настроек: короткая подпись для навигации + контент. Контент первым
 * ребёнком рисует свой SectionCaption (у «Промптов проекта» он с текущей ролью).
 */
private data class SettingsSection(val navLabel: String, val content: @Composable () -> Unit)

/**
 * Вкладка «Настройки»: слева — навигация по разделам, справа — контент.
 * Контент занимает всю доступную ширину (на фуллскрине растягивается, в окне
 * сужается сам). Разделы в контенте разделены линиями-разделителями; разделитель,
 * последним пересёкший верхний край области контента, определяет активный пункт
 * навигации; клик по пункту приводит разделитель раздела к верхнему краю
 * (последние разделы — насколько позволяет прокрутка, т.е. к низу страницы).
 */
@Composable
fun SettingsTab(state: AppState, modifier: Modifier = Modifier) {
    val services = state.services
    val settings by services.settings.collectAsState()
    val project = state.currentProject.collectAsState().value

    val sections = listOf(
        SettingsSection("Внешний вид") {
            SectionCaption("ВНЕШНИЙ ВИД")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Тема", fontSize = 13.sp, modifier = Modifier.weight(1f))
                BtDropdown(
                    options = listOf("Системная", "Светлая", "Тёмная"),
                    selected = when (settings.themeMode) {
                        "light" -> "Светлая"
                        "dark" -> "Тёмная"
                        else -> "Системная"
                    },
                    onSelect = { sel ->
                        services.updateSettings { s ->
                            s.copy(
                                themeMode = when (sel) {
                                    "Светлая" -> "light"
                                    "Тёмная" -> "dark"
                                    else -> "system"
                                },
                            )
                        }
                    },
                )
            }
        },
        SettingsSection("Провайдеры LLM") {
            SectionCaption("ПРОВАЙДЕРЫ LLM")
            ProvidersSection(state, services, settings)
        },
        SettingsSection("Модели LLM") {
            SectionCaption("МОДЕЛИ LLM")
            ModelsSection(services, settings)
        },
        SettingsSection("Роли → модель") {
            SectionCaption("РОЛИ → МОДЕЛЬ")
            RolesSection(services, settings)
        },
        SettingsSection("Конвейер") {
            SectionCaption("КОНВЕЙЕР")
            FieldRow(
                listOf(
                    FieldSpec("Параллельных вызовов", settings.concurrency.toString()) { v ->
                        v.toIntOrNull()?.let { n -> services.updateSettings { s -> s.copy(concurrency = n.coerceIn(1, 8)) } }
                    },
                ),
            )
            FieldRow(
                listOf(
                    FieldSpec("Часть ≤, знаков", settings.partMaxChars.toString()) { v ->
                        v.toIntOrNull()?.let { n -> services.updateSettings { s -> s.copy(partMaxChars = n) } }
                    },
                    FieldSpec("Фрагмент ≈, знаков", settings.fragmentTargetChars.toString()) { v ->
                        v.toIntOrNull()?.let { n -> services.updateSettings { s -> s.copy(fragmentTargetChars = n) } }
                    },
                    FieldSpec("Корректор ≤, знаков", settings.criticChunkMaxChars.toString()) { v ->
                        v.toIntOrNull()?.let { n -> services.updateSettings { s -> s.copy(criticChunkMaxChars = n) } }
                    },
                ),
            )
            Text(
                "Инструменты агентов: ${if (settings.agentTools.enabledByDefault) "включены" else "выключены"}; " +
                    "лимит ${settings.agentTools.maxToolCallsPerFragment} вызовов/фрагмент, ответ ≤ ${settings.agentTools.maxToolResultChars} зн.",
                fontSize = 12.sp,
                color = BtPalette.TextSecondary,
            )
        },
        SettingsSection("MCP") {
            SectionCaption("MCP (ВНЕШНЕЕ УПРАВЛЕНИЕ, НАПРИМЕР ZCODE)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сервер", fontSize = 13.sp, modifier = Modifier.weight(1f))
                BtDropdown(
                    options = listOf("выключен", "включён"),
                    selected = if (settings.mcp.enabled) "включён" else "выключен",
                    onSelect = { sel -> services.updateSettings { it.copy(mcp = it.mcp.copy(enabled = sel == "включён")) } },
                )
            }
            FieldRow(
                listOf(
                    FieldSpec("Порт", settings.mcp.port.toString()) { v ->
                        v.toIntOrNull()?.let { n -> services.updateSettings { s -> s.copy(mcp = s.mcp.copy(port = n)) } }
                    },
                ),
            )
            FieldRow(
                listOf(
                    FieldSpec("Токен (Bearer, необязательно)", settings.mcp.token) { v ->
                        services.updateSettings { s -> s.copy(mcp = s.mcp.copy(token = v.trim())) }
                    },
                ),
            )
            Text(
                "Адрес: http://127.0.0.1:${settings.mcp.port}/mcp — инструкция в docs/MCP.md",
                fontSize = 12.sp,
                color = BtPalette.TextSecondary,
            )
        },
    ) + if (project != null) {
        listOf(
            SettingsSection("Конвенции проекта") {
                ProjectConventionsSection(state, services, project)
            },
            SettingsSection("Промпты проекта") {
                ProjectPromptsSection(state, services, project)
            },
        )
    } else {
        emptyList()
    }

    val scrollState = rememberScrollState()
    // Отладка для bt.shot: -Dbt.settingsScroll=<px> — стартовая прокрутка контента настроек
    System.getProperty("bt.settingsScroll")?.toIntOrNull()?.let { seed ->
        LaunchedEffect(Unit) { scrollState.scrollTo(seed) }
    }
    val scope = rememberCoroutineScope()
    // Верх контентной области в оконных координатах (не меняется при прокрутке)
    val contentTop = remember { mutableFloatStateOf(0f) }
    // Верх каждого раздела в оконных координатах (меняется при прокрутке)
    val sectionTops = remember { mutableStateMapOf<Int, Float>() }
    val activeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val sectionCount = sections.size

    // Активен раздел, чей разделитель последним пересёк верхний край контента
    // (с небольшим допуском); в самом низу страницы активен последний раздел.
    val activeSection by remember(sectionCount) {
        derivedStateOf {
            var active = 0
            for (i in 0 until sectionCount) {
                val top = sectionTops[i] ?: continue
                if (top - contentTop.floatValue <= activeThresholdPx) active = i
            }
            if (scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue) sectionCount - 1 else active
        }
    }

    fun scrollToSection(index: Int) {
        scope.launch {
            val top = sectionTops[index] ?: return@launch
            val target = scrollState.value + (top - contentTop.floatValue).roundToInt()
            scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue))
        }
    }

    Row(modifier) {
        Column(
            Modifier
                .width(SettingsNavWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SectionCaption("РАЗДЕЛЫ", Modifier.padding(start = 10.dp, bottom = 4.dp), color = BtPalette.Muted)
            sections.forEachIndexed { index, section ->
                SettingsNavItem(
                    section.navLabel,
                    active = index == activeSection,
                    onClick = { scrollToSection(index) },
                )
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(BtPalette.Border))
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onGloballyPositioned { contentTop.floatValue = it.positionInWindow().y }
                .verticalScroll(scrollState)
                .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            sections.forEachIndexed { index, section ->
                // Обёртка секции: её верх = разделитель (для второго и далее) —
                // эта точка и есть «место раздела» и для навигации, и для прыжка
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { sectionTops[index] = it.positionInWindow().y },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(BtPalette.Border))
                    section.content()
                }
            }
        }
    }
}

@Composable
private fun SettingsNavItem(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .background(
                when {
                    active -> BtPalette.AccentBgSoft
                    hovered -> BtPalette.Hover
                    else -> Color.Transparent
                },
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            maxLines = 1,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                active -> BtPalette.AccentText
                hovered -> BtPalette.Text
                else -> BtPalette.TextSecondary
            },
        )
    }
}

@Composable
private fun ProvidersSection(state: AppState, services: ru.sebyanin.booktranslator.core.AppServices, settings: AppSettings) {
    Text(
        "Провайдер — адрес, ключ и протокол API. Модели ссылаются на провайдера: токен вводится один раз на любое число моделей.",
        fontSize = 12.sp,
        color = BtPalette.TextSecondary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        settings.providers.forEachIndexed { index, provider ->
            BtCard {
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(provider.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        BtButton("Дублировать", height = 26.dp, onClick = {
                            services.updateSettings { s ->
                                s.copy(providers = s.providers + provider.copy(name = provider.name + " (копия)"))
                            }
                        })
                        Spacer(Modifier.width(6.dp))
                        BtButton("Удалить", height = 26.dp, onClick = {
                            val usedBy = settings.models.filter { it.provider == provider.name }.map { it.name }
                            if (usedBy.isNotEmpty()) {
                                state.message.value =
                                    "Провайдер «${provider.name}» используют модели: ${usedBy.joinToString(", ")}. Сначала переназначьте или удалите их."
                            } else {
                                services.updateSettings { s ->
                                    s.copy(providers = s.providers.filterIndexed { i, _ -> i != index })
                                }
                            }
                        })
                    }
                    FieldRow(
                        listOf(
                            FieldSpec("Название", provider.name, placeholder = "", weight = 1f) { v ->
                                services.updateSettings { s ->
                                    s.copy(providers = s.providers.toMutableList().also { it[index] = provider.copy(name = v) })
                                }
                            },
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Протокол", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        BtDropdown(
                            options = listOf("OpenAI-совместимый", "Anthropic (Messages)"),
                            selected = if (provider.api == ApiKind.ANTHROPIC) "Anthropic (Messages)" else "OpenAI-совместимый",
                            onSelect = { sel ->
                                services.updateSettings { s ->
                                    s.copy(
                                        providers = s.providers.toMutableList().also {
                                            it[index] = provider.copy(api = if ((sel ?: "").startsWith("Anthropic")) ApiKind.ANTHROPIC else ApiKind.OPENAI)
                                        },
                                    )
                                }
                            },
                        )
                    }
                    FieldAbove(
                        "Base URL (префикс до эндпоинта вызова)",
                        provider.baseUrl,
                        Modifier.fillMaxWidth(),
                        placeholder = if (provider.api == ApiKind.ANTHROPIC) "https://api.anthropic.com/v1" else "https://api.deepseek.com/v1",
                    ) { v ->
                        services.updateSettings { s ->
                            s.copy(providers = s.providers.toMutableList().also { it[index] = provider.copy(baseUrl = v.trim()) })
                        }
                    }
                    FieldAbove("API-ключ", provider.apiKey, Modifier.fillMaxWidth(), placeholder = "sk-…") { v ->
                        services.updateSettings { s ->
                            s.copy(providers = s.providers.toMutableList().also { it[index] = provider.copy(apiKey = v.trim()) })
                        }
                    }
                    if (provider.baseUrl.isNotBlank()) {
                        val endpoint = provider.baseUrl.trim().trimEnd('/') +
                            if (provider.api == ApiKind.ANTHROPIC) "/messages" else "/chat/completions"
                        Text("Вызовы: $endpoint", fontSize = 11.sp, color = BtPalette.Muted)
                    }
                }
            }
        }
        BtButton("+ Добавить провайдера", height = 30.dp, onClick = {
            services.updateSettings { s ->
                s.copy(providers = s.providers + LlmProvider(name = "Провайдер ${s.providers.size + 1}", baseUrl = ""))
            }
        })
    }
}

@Composable
private fun ModelsSection(services: ru.sebyanin.booktranslator.core.AppServices, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf(mapOf<String, String>()) }
    val providerNames = settings.providers.map { it.name }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        settings.models.forEachIndexed { index, model ->
            val providerCfg = settings.providers.firstOrNull { it.name == model.provider }
            BtCard {
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        BtButton("Дублировать", height = 26.dp, onClick = {
                            services.updateSettings { s ->
                                s.copy(models = s.models + model.copy(name = model.name + " (копия)"))
                            }
                        })
                        Spacer(Modifier.width(6.dp))
                        BtButton("Удалить", height = 26.dp, onClick = {
                            services.updateSettings { s ->
                                val removed = s.models.toMutableList().also { it.removeAt(index) }
                                s.copy(models = removed, roleBindings = s.roleBindings.filterValues { name -> removed.any { it.name == name } })
                            }
                        })
                    }
                    FieldRow(
                        listOf(
                            FieldSpec("Название", model.name, placeholder = "", weight = 1f) { v ->
                                services.updateSettings { s ->
                                    s.copy(models = s.models.toMutableList().also { it[index] = model.copy(name = v) })
                                }
                            },
                            FieldSpec("Модель (id в API)", model.model, placeholder = "claude-sonnet-5-5…", weight = 1f) { v ->
                                services.updateSettings { s ->
                                    s.copy(models = s.models.toMutableList().also { it[index] = model.copy(model = v.trim()) })
                                }
                            },
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Провайдер", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        BtDropdown(
                            options = providerNames,
                            selected = model.provider,
                            onSelect = { selected ->
                                services.updateSettings { s ->
                                    s.copy(models = s.models.toMutableList().also { it[index] = model.copy(provider = selected.orEmpty()) })
                                }
                            },
                            placeholder = "— не выбран —",
                            allowEmpty = true,
                        )
                    }
                    FieldRow(
                        listOf(
                            FieldSpec("Температура", model.temperature?.toString() ?: "", placeholder = "по умолчанию", weight = 1f) { v ->
                                val d = v.trim().toDoubleOrNull()
                                if (v.isBlank() || d != null) {
                                    services.updateSettings { s -> s.copy(models = s.models.toMutableList().also { it[index] = model.copy(temperature = d) }) }
                                }
                            },
                            FieldSpec("Max tokens (reasoning ≥ 16384)", model.maxTokens.toString(), weight = 1.3f) { v ->
                                v.toIntOrNull()?.let { n ->
                                    services.updateSettings { s -> s.copy(models = s.models.toMutableList().also { it[index] = model.copy(maxTokens = n) }) }
                                }
                            },
                            FieldSpec("Таймаут, с", model.requestTimeoutSec.toString(), weight = 1f) { v ->
                                v.toIntOrNull()?.let { n ->
                                    services.updateSettings { s -> s.copy(models = s.models.toMutableList().also { it[index] = model.copy(requestTimeoutSec = n) }) }
                                }
                            },
                        ),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // УЧИТЫВАЙ ПРИ ПРАВКАХ: BtCard — это Box, несколько детей должны
                        // лежать в Column/Row (урок наложения — см. память проекта)
                        modifier = Modifier.clickable {
                            services.updateSettings { s ->
                                s.copy(models = s.models.toMutableList().also {
                                    it[index] = model.copy(noThinking = !model.noThinking)
                                })
                            }
                        },
                    ) {
                        Checkbox(
                            checked = model.noThinking,
                            onCheckedChange = null, // клик обрабатывает вся строка
                        )
                        Text("Принудительно отключить рассуждения", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                    Text(
                        "Шлёт thinking {type:disabled}: гибридные модели (DeepSeek/GLM) рассуждают по умолчанию " +
                            "и биллят рассуждения как выходные токены — на черновике это львиная доля счёта. " +
                            "Без галки параметр не отправляется: у DeepSeek рассуждения включены «с завода», " +
                            "у Клода выключены (на него галка не действует).",
                        fontSize = 12.sp,
                        color = BtPalette.TextSecondary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BtButton(
                            if (testing == model.name) "Проверяю…" else "Проверить связь",
                            enabled = testing == null && !providerCfg?.baseUrl.isNullOrBlank() && model.model.isNotBlank(),
                            height = 28.dp,
                            onClick = {
                                testing = model.name
                                scope.launch {
                                    val result = runCatching { services.llm.testConnection(model.resolve(settings.providers)) }
                                        .fold({ "OK: ${it.take(60)}" }, { "Ошибка: ${it.message?.take(160)}" })
                                    results = results + (model.name to result)
                                    testing = null
                                }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        results[model.name]?.let {
                            Text(it, fontSize = 12.sp, color = if (it.startsWith("OK")) BtPalette.SuccessDark else BtPalette.Warn, modifier = Modifier.weight(1f))
                        } ?: Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (providerNames.isEmpty()) {
            Text("Сначала добавьте провайдера — модели ссылаются на него.", fontSize = 12.sp, color = BtPalette.TextSecondary)
        }
        BtButton("+ Добавить модель", height = 30.dp, onClick = {
            services.updateSettings { s ->
                s.copy(models = s.models + LlmModel(name = "Модель ${s.models.size + 1}", provider = s.providers.firstOrNull()?.name.orEmpty()))
            }
        })
    }
}

@Composable
private fun RolesSection(services: ru.sebyanin.booktranslator.core.AppServices, settings: AppSettings) {
    val modelNames = settings.models.map { it.name }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentRole.entries.forEach { role ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(role.title, fontSize = 13.sp)
                    Text(role.description, fontSize = 11.sp, color = BtPalette.TextSecondary)
                }
                BtDropdown(
                    options = modelNames,
                    selected = settings.roleBindings[role.id],
                    onSelect = { selected ->
                        services.updateSettings { s ->
                            val bindings = s.roleBindings.toMutableMap()
                            if (selected == null) bindings.remove(role.id) else bindings[role.id] = selected
                            s.copy(roleBindings = bindings)
                        }
                    },
                    placeholder = "— первая модель —",
                    allowEmpty = true,
                )
            }
        }
        if (modelNames.isEmpty()) {
            Text("Добавьте модель — она подойдёт всем ролям по умолчанию.", fontSize = 12.sp, color = BtPalette.TextSecondary)
        }
    }
}

@Composable
private fun ProjectConventionsSection(
    state: AppState,
    services: ru.sebyanin.booktranslator.core.AppServices,
    project: ru.sebyanin.booktranslator.core.model.ProjectMeta,
) {
    var conventions by remember(project.slug) { mutableStateOf(services.projectStore.readConventions(project.slug)) }

    SectionCaption("КОНВЕНЦИИ ПРОЕКТА (В КАЖДЫЙ БРИФ)")
    BtTextArea(conventions.replace("\r", ""), { conventions = it }, Modifier.fillMaxWidth(), minHeight = 140.dp)
    Row(Modifier.padding(top = 6.dp)) {
        Spacer(Modifier.weight(1f))
        BtButton("Сохранить конвенции", kind = BtButtonKind.Primary, height = 30.dp, onClick = {
            services.projectStore.writeConventions(project.slug, conventions)
            state.message.value = "Конвенции сохранены"
        })
    }
}

@Composable
private fun ProjectPromptsSection(
    state: AppState,
    services: ru.sebyanin.booktranslator.core.AppServices,
    project: ru.sebyanin.booktranslator.core.model.ProjectMeta,
) {
    var role by remember { mutableStateOf(AgentRole.DRAFT) }
    var promptText by remember(project.slug, role) { mutableStateOf(services.promptStore.resolve(project.slug, role)) }
    var showBrief by remember { mutableStateOf(false) }

    SectionCaption("ПРОМПТЫ ПРОЕКТА (РОЛЬ: ${role.title.uppercase()})")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        BtDropdown(
            options = AgentRole.entries.map { it.title },
            selected = role.title,
            onSelect = { title -> AgentRole.entries.firstOrNull { it.title == title }?.let { role = it } },
        )
        Spacer(Modifier.weight(1f))
        BtButton("Сбросить к глобальному", height = 28.dp, onClick = {
            services.promptStore.deleteProjectOverride(project.slug, role)
            promptText = services.promptStore.resolve(project.slug, role)
        })
    }
    BtTextArea(promptText.replace("\r", ""), { promptText = it }, Modifier.fillMaxWidth().padding(top = 6.dp), minHeight = 200.dp)
    Row(Modifier.padding(top = 6.dp)) {
        Text(
            "{{SOURCE}} {{DICTIONARY}} {{NOTES}} {{CONVENTIONS}} {{INSTRUCTIONS}} {{TRANSLATION}} {{TOOLS_DOC}}",
            fontSize = 11.sp,
            color = BtPalette.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        BtButton("Сохранить", kind = BtButtonKind.Primary, height = 30.dp, onClick = {
            services.promptStore.writeProjectOverride(project.slug, role, promptText)
            state.message.value = "Промпт «${role.title}» сохранён для проекта"
        })
    }

    BtButton(
        "Предпросмотр брифа",
        height = 28.dp,
        active = showBrief,
        onClick = { showBrief = !showBrief },
        modifier = Modifier.padding(top = 4.dp),
    )
    if (showBrief) {
        val book = state.currentBook.value
        val manifest = state.manifest.value
        val frag = manifest?.allFragments()?.firstOrNull { !it.draft.isDone } ?: manifest?.allFragments()?.firstOrNull()
        if (book == null || frag == null) {
            BtCard { Text("Нужна книга с нарезанными фрагментами.", fontSize = 12.sp, color = BtPalette.TextSecondary) }
        } else {
            val brief = runCatching {
                val bookDir = services.projectStore.bookDir(project.slug, book.slug)
                val ff = ru.sebyanin.booktranslator.core.storage.AppJson.decodeFromString(
                    ru.sebyanin.booktranslator.core.model.FragmentFile.serializer(),
                    Files.readString(bookDir.resolve("work").resolve("fragments").resolve("${frag.id}.json")),
                )
                val selected = services.knowledge.selectForFragment(project.slug, ff.srcBlocks.joinToString("\n"))
                ru.sebyanin.booktranslator.core.pipeline.BriefBuilder.build(
                    ru.sebyanin.booktranslator.core.pipeline.BriefBuilder.Input(
                        template = services.promptStore.resolve(project.slug, AgentRole.DRAFT),
                        srcBlocks = ff.srcBlocks,
                        dictionary = selected.dictionary,
                        notes = selected.notes,
                        conventions = services.projectStore.readConventions(project.slug),
                        toolsDoc = ru.sebyanin.booktranslator.core.tools.ToolFacade.docsText(),
                    ),
                )
            }.getOrElse { "Ошибка сборки брифа: ${it.message}" }
            BtCard(contentPadding = PaddingValues(12.dp)) {
                // ВНИМАНИЕ: BtCard — это Box; несколько детей рисуются ДРУГ НА ДРУГЕ.
                // Всё содержимое — только внутри Column.
                Column {
                    Text("БРИФ: ${frag.id} (ПЕРЕВОДЧИК)", fontSize = 11.sp, color = BtPalette.AccentText)
                    Spacer(Modifier.height(6.dp))
                    // Read-only BasicTextField вместо Text: Text в Compose Desktop
                    // рисует наложение строк на сложном тексте, BasicTextField — нет
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        androidx.compose.foundation.text.BasicTextField(
                            value = brief.replace("\r", ""),
                            onValueChange = {},
                            readOnly = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = BtPalette.Text,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
