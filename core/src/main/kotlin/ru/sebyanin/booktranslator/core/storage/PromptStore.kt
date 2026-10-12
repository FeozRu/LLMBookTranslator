package ru.sebyanin.booktranslator.core.storage

import ru.sebyanin.booktranslator.core.llm.PromptDefaults
import ru.sebyanin.booktranslator.core.model.AgentRole
import java.nio.file.Files
import java.nio.file.Path

/**
 * Промпты: глобальные дефолты в [globalDir] (md-файлы по ролям), переопределение —
 * в проекте, папка prompts. Резолюция: проект → глобальный → вшитый дефолт.
 */
class PromptStore(private val dataRootProvider: () -> Path, private val projectDirProvider: (String) -> Path) {

    fun globalDir(): Path = dataRootProvider().resolve("prompts")

    fun ensureGlobalDefaults() {
        AgentRole.entries.forEach { role ->
            val f = globalDir().resolve(fileName(role))
            if (!Files.isRegularFile(f)) {
                Files.createDirectories(f.parent)
                Files.writeString(f, PromptDefaults.forRole(role))
            }
        }
    }

    fun readGlobal(role: AgentRole): String =
        globalDir().resolve(fileName(role)).takeIf { Files.isRegularFile(it) }
            ?.let { Files.readString(it) }
            ?: PromptDefaults.forRole(role)

    fun writeGlobal(role: AgentRole, text: String) {
        val f = globalDir().resolve(fileName(role))
        Files.createDirectories(f.parent)
        ProjectStore.atomicWrite(f, text)
    }

    fun resetGlobal(role: AgentRole) {
        val f = globalDir().resolve(fileName(role))
        Files.deleteIfExists(f)
        ensureGlobalDefaults()
    }

    /** Промпт проекта, если файл существует (пустая строка = нет переопределения). */
    fun readProjectOverride(projectSlug: String, role: AgentRole): String? {
        val f = projectDirProvider(projectSlug).resolve("prompts").resolve(fileName(role))
        return if (Files.isRegularFile(f)) Files.readString(f) else null
    }

    fun writeProjectOverride(projectSlug: String, role: AgentRole, text: String) {
        val f = projectDirProvider(projectSlug).resolve("prompts").resolve(fileName(role))
        ProjectStore.atomicWrite(f, text)
    }

    fun deleteProjectOverride(projectSlug: String, role: AgentRole) {
        projectDirProvider(projectSlug).resolve("prompts").resolve(fileName(role))
            .let { Files.deleteIfExists(it) }
    }

    /** Итоговый промпт: проект → глобальный → дефолт. */
    fun resolve(projectSlug: String, role: AgentRole): String =
        readProjectOverride(projectSlug, role) ?: readGlobal(role)

    /** Копирует глобальные промпты в проект (при создании проекта). */
    fun seedProjectFromGlobal(projectSlug: String) {
        val dir = projectDirProvider(projectSlug).resolve("prompts")
        Files.createDirectories(dir)
        AgentRole.entries.forEach { role ->
            val f = dir.resolve(fileName(role))
            if (!Files.isRegularFile(f)) Files.writeString(f, readGlobal(role))
        }
    }

    companion object {
        fun fileName(role: AgentRole): String = "${role.id}.md"
    }
}
