package ru.sebyanin.booktranslator.core.storage

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.AppSettings
import ru.sebyanin.booktranslator.core.model.migratedFromProfiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Загрузка/сохранение глобальных настроек: <dataRoot>/settings.json (атомарная запись). */
class SettingsStore(private val dataRootPath: Path) {

    val settingsFile: Path = dataRootPath.resolve("settings.json")

    fun load(): AppSettings {
        if (!Files.isRegularFile(settingsFile)) return AppSettings()
        val decoded = runCatching {
            AppJson.decodeFromString<AppSettings>(Files.readString(settingsFile))
        }.getOrElse {
            // Повреждённые настройки не должны блокировать запуск; бэкапим и начинаем заново.
            runCatching {
                Files.move(
                    settingsFile,
                    settingsFile.resolveSibling("settings.corrupt-${System.currentTimeMillis()}.json"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            return AppSettings()
        }
        // Миграция schema 1 (монопрофили) → провайдеры + модели, детерминированная
        // по legacy-профилям: повторный прогон после отката файла ничего не теряет.
        val migrated = decoded.migratedFromProfiles() ?: return decoded
        runCatching { save(migrated) }.onFailure {
            AppLog.warn("settings", "Не удалось сохранить мигрированные настройки: ${it.message}")
        }
        AppLog.info(
            "settings",
            "Профили мигрировали в провайдеры+модели: " +
                "${migrated.providers.size} провайдер(ов), ${migrated.models.size} модель(ей)",
        )
        return migrated
    }

    fun save(settings: AppSettings) {
        Files.createDirectories(dataRootPath)
        val tmp = settingsFile.resolveSibling("settings.json.tmp")
        Files.writeString(tmp, AppJson.encodeToString(AppSettings.serializer(), settings))
        Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
