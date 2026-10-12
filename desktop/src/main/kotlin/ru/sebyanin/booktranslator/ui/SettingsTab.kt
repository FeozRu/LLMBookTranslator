package ru.sebyanin.booktranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.sebyanin.booktranslator.AppState
import ru.sebyanin.booktranslator.core.model.AgentRole
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.LlmProfile
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

/**
 * Вкладка «Настройки» центральной зоны (после Журнала): глобальные и проектные
 * настройки. Переехала из правой панели — там на 340dp ей было тесно, особенно
 * с прицелом на расширение. Контент ограничен 820dp и центрирован (как стартовые
 * экраны): на узком окне занимает всю ширину центра, на широком не растягивается.
 */
@Composable
fun SettingsTab(state: AppState, modifier: Modifier = Modifier) {
    val services = state.services
    val settings by services.settings.collectAsState()
    val project by state.currentProject.collectAsState()

    Column(modifier.padding(12.dp)) {
        Column(
            modifier = Modifier
                .widthIn(max = 820.dp)
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Внешний вид
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

            // Профили LLM
            SectionCaption("ПРОФИЛИ LLM")
            ProfilesSection(state, services, settings)
            RolesSection(services, settings)

            // Конвейер
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

            // MCP
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

            // Проект: конвенции и промпты
            if (project != null) {
                ProjectKnowledgeSection(state, services, project!!)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfilesSection(state: AppState, services: ru.sebyanin.booktranslator.core.AppServices, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf(mapOf<String, String>()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        settings.profiles.forEachIndexed { index, profile ->
            BtCard {
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        BtButton("Дублировать", height = 26.dp, onClick = {
                            services.updateSettings { s ->
                                s.copy(profiles = s.profiles + profile.copy(name = profile.name + " (копия)"))
                            }
                        })
                        Spacer(Modifier.width(6.dp))
                        BtButton("Удалить", height = 26.dp, onClick = {
                            services.updateSettings { s ->
                                val removed = s.profiles.toMutableList().also { it.removeAt(index) }
                                s.copy(profiles = removed, roleBindings = s.roleBindings.filterValues { name -> removed.any { it.name == name } })
                            }
                        })
                    }
                    FieldRow(
                        listOf(
                            FieldSpec("Название", profile.name, placeholder = "", weight = 1f) { v ->
                                services.updateSettings { s ->
                                    s.copy(profiles = s.profiles.toMutableList().also { it[index] = profile.copy(name = v) })
                                }
                            },
                            FieldSpec("Модель", profile.model, placeholder = "deepseek-flash…", weight = 1f) { v ->
                                services.updateSettings { s ->
                                    s.copy(profiles = s.profiles.toMutableList().also { it[index] = profile.copy(model = v.trim()) })
                                }
                            },
                        ),
                    )
                    FieldAbove("Base URL (до /chat/completions)", profile.baseUrl, Modifier.fillMaxWidth(), placeholder = "https://api.deepseek.com/v1") { v ->
                        services.updateSettings { s ->
                            s.copy(profiles = s.profiles.toMutableList().also { it[index] = profile.copy(baseUrl = v.trim()) })
                        }
                    }
                    FieldAbove("API-ключ", profile.apiKey, Modifier.fillMaxWidth(), placeholder = "sk-…") { v ->
                        services.updateSettings { s ->
                            s.copy(profiles = s.profiles.toMutableList().also { it[index] = profile.copy(apiKey = v.trim()) })
                        }
                    }
                    FieldRow(
                        listOf(
                            FieldSpec("Температура", profile.temperature.toString(), weight = 1f) { v ->
                                v.toDoubleOrNull()?.let { d ->
                                    services.updateSettings { s -> s.copy(profiles = s.profiles.toMutableList().also { it[index] = profile.copy(temperature = d) }) }
                                }
                            },
                            FieldSpec("Max tokens (reasoning ≥ 16384)", profile.maxTokens.toString(), weight = 1.3f) { v ->
                                v.toIntOrNull()?.let { n ->
                                    services.updateSettings { s -> s.copy(profiles = s.profiles.toMutableList().also { it[index] = profile.copy(maxTokens = n) }) }
                                }
                            },
                            FieldSpec("Таймаут, с", profile.requestTimeoutSec.toString(), weight = 1f) { v ->
                                v.toIntOrNull()?.let { n ->
                                    services.updateSettings { s -> s.copy(profiles = s.profiles.toMutableList().also { it[index] = profile.copy(requestTimeoutSec = n) }) }
                                }
                            },
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BtButton(
                            if (testing == profile.name) "Проверяю…" else "Проверить связь",
                            enabled = testing == null && profile.baseUrl.isNotBlank() && profile.model.isNotBlank(),
                            height = 28.dp,
                            onClick = {
                                testing = profile.name
                                scope.launch {
                                    val result = runCatching { services.llm.testConnection(profile) }
                                        .fold({ "OK: ${it.take(60)}" }, { "Ошибка: ${it.message?.take(160)}" })
                                    results = results + (profile.name to result)
                                    testing = null
                                }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        results[profile.name]?.let {
                            Text(it, fontSize = 12.sp, color = if (it.startsWith("OK")) BtPalette.SuccessDark else BtPalette.Warn, modifier = Modifier.weight(1f))
                        } ?: Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        BtButton("+ Добавить профиль", height = 30.dp, onClick = {
            services.updateSettings { s ->
                s.copy(profiles = s.profiles + LlmProfile(name = "Профиль ${s.profiles.size + 1}", baseUrl = ""))
            }
        })
    }
}

@Composable
private fun RolesSection(services: ru.sebyanin.booktranslator.core.AppServices, settings: AppSettings) {
    val profileNames = settings.profiles.map { it.name }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionCaption("РОЛИ → ПРОФИЛЬ")
        AgentRole.entries.forEach { role ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(role.title, fontSize = 13.sp)
                    Text(role.description, fontSize = 11.sp, color = BtPalette.TextSecondary)
                }
                BtDropdown(
                    options = profileNames,
                    selected = settings.roleBindings[role.id],
                    onSelect = { selected ->
                        services.updateSettings { s ->
                            val bindings = s.roleBindings.toMutableMap()
                            if (selected == null) bindings.remove(role.id) else bindings[role.id] = selected
                            s.copy(roleBindings = bindings)
                        }
                    },
                    placeholder = "— первый профиль —",
                    allowEmpty = true,
                )
            }
        }
        if (profileNames.isEmpty()) {
            Text("Добавьте профиль — он подойдёт всем ролям по умолчанию.", fontSize = 12.sp, color = BtPalette.TextSecondary)
        }
    }
}

@Composable
private fun ProjectKnowledgeSection(state: AppState, services: ru.sebyanin.booktranslator.core.AppServices, project: ru.sebyanin.booktranslator.core.model.ProjectMeta) {
    var conventions by remember(project.slug) { mutableStateOf(services.projectStore.readConventions(project.slug)) }
    var role by remember { mutableStateOf(AgentRole.DRAFT) }
    var promptText by remember(project.slug, role) { mutableStateOf(services.promptStore.resolve(project.slug, role)) }
    var showBrief by remember { mutableStateOf(false) }

    SectionCaption("КОНВЕНЦИИ ПРОЕКТА (В КАЖДЫЙ БРИФ)")
    BtTextArea(conventions.replace("\r", ""), { conventions = it }, Modifier.fillMaxWidth(), minHeight = 140.dp)
    Row(Modifier.padding(top = 6.dp)) {
        Spacer(Modifier.weight(1f))
        BtButton("Сохранить конвенции", kind = BtButtonKind.Primary, height = 30.dp, onClick = {
            services.projectStore.writeConventions(project.slug, conventions)
            state.message.value = "Конвенции сохранены"
        })
    }

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
