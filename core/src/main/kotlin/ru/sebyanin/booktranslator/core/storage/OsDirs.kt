package ru.sebyanin.booktranslator.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Определение пользовательских папок без привязки к ОС.
 *
 * Windows: Documents читается из реестра (User Shell Folders → Personal),
 * чтобы учесть перенос папки пользователем; фолбэк — %USERPROFILE%\Documents.
 * Linux: XDG_DOCUMENTS_DIR из ~/.config/user-dirs.dirs; фолбэк — ~/Documents, затем ~.
 */
object OsDirs {

    fun documentsDir(): Path {
        val os = System.getProperty("os.name").lowercase()
        val resolved = when {
            os.contains("win") -> windowsDocuments()
            else -> xdgDocuments()
        }
        return resolved ?: homeFallback()
    }

    fun homeFallback(): Path =
        Paths.get(System.getProperty("user.home") ?: System.getProperty("user.dir"))

    private fun windowsDocuments(): Path? {
        // reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" /v Personal
        return runCatching {
            val proc = ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
                "/v", "Personal",
            ).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val m = Regex("Personal\\s+REG_(EXPAND_)?SZ\\s+(.+)").find(out) ?: return null
            val raw = m.groupValues[2].trim()
            val expanded = raw.replace("%USERPROFILE%", System.getProperty("user.home", ""))
            val p = Paths.get(expanded)
            if (Files.isDirectory(p)) p else null
        }.getOrNull() ?: run {
            val p = Paths.get(System.getProperty("user.home", ""), "Documents")
            if (Files.isDirectory(p)) p else null
        }
    }

    private fun xdgDocuments(): Path? {
        val home = Paths.get(System.getProperty("user.home") ?: return null)
        // Из переменной окружения
        System.getenv("XDG_DOCUMENTS_DIR")?.let { raw ->
            val p = expandXdg(raw, home)
            if (Files.isDirectory(p)) return p
        }
        // Из ~/.config/user-dirs.dirs
        val userDirs = home.resolve(".config/user-dirs.dirs")
        if (Files.isRegularFile(userDirs)) {
            runCatching {
                val line = Files.readAllLines(userDirs)
                    .firstOrNull { it.trimStart().startsWith("XDG_DOCUMENTS_DIR") } ?: return null
                val raw = line.substringAfter('=', "").trim().trim('"')
                val p = expandXdg(raw, home)
                if (Files.isDirectory(p)) return p
            }
        }
        val p = home.resolve("Documents")
        return if (Files.isDirectory(p)) p else null
    }

    private fun expandXdg(raw: String, home: Path): Path {
        val prefix = "\$HOME"
        return if (raw.startsWith(prefix)) home.resolve(raw.removePrefix(prefix).removePrefix("/"))
        else Paths.get(raw)
    }
}
