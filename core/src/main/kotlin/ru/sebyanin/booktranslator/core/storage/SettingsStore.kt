package ru.sebyanin.booktranslator.core.storage

import ru.sebyanin.booktranslator.core.model.AppSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Загрузка/сохранение глобальных настроек: <dataRoot>/settings.json (атомарная запись). */
class SettingsStore(private val dataRootPath: Path) {

    val settingsFile: Path = dataRootPath.resolve("settings.json")

    fun load(): AppSettings {
        if (!Files.isRegularFile(settingsFile)) return AppSettings()
        return runCatching {
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
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        Files.createDirectories(dataRootPath)
        val tmp = settingsFile.resolveSibling("settings.json.tmp")
        Files.writeString(tmp, AppJson.encodeToString(AppSettings.serializer(), settings))
        Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
