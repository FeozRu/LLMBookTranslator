import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Версия — из gradle.properties (единственный источник; -Pversion=N.N.N переопределяет с CLI)
version = providers.gradleProperty("version").getOrElse("0.0.0")

// Версия внутри приложения: version.properties собирается с подстановкой appVersion,
// читается в Main.kt (заголовок окна)
tasks.processResources {
    filesMatching("version.properties") {
        expand("appVersion" to version.toString())
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    // Алиас compose.material3 депрекейтнут (удалён в будущих CMP) — прямые координаты,
    // версия зафиксирована в libs.versions.toml и совпадает с резолвом алиаса
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
}

// Отладочные флаги приложения: -Dbt.* с командной строки gradlew пробрасываются
// в fork JVM (:desktop:run -Dbt.shot=... — см. startAutoShot в Main.kt)
tasks.withType<JavaExec>().configureEach {
    System.getProperties()
        .filterKeys { it is String && it.startsWith("bt.") }
        .forEach { (k, v) -> systemProperty(k as String, v.toString()) }
}

compose.desktop {
    application {
        mainClass = "ru.sebyanin.booktranslator.MainKt"

        buildTypes.release.proguard {
            isEnabled = false
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "BookTranslator"
            // Версия из gradle.properties (строго N.N.N — ограничение jpackage для MSI)
            packageVersion = version.toString()
            // ТОЛЬКО ASCII: метаданные MSI пишутся в системной кодовой странице
            // (на CI-раннерах cp1252, в России cp1251) — кириллица и особенно стрелки
            // «→» не представимы там и роняют WiX light.exe (exit 311). Проверено:
            // русское описание ломает packageMsi и локально, и на CI.
            description = "Book translation pipeline: draft, edit, critic, EPUB/FB2 assembly"
            vendor = "sebyanin"

            windows {
                menu = true
                dirChooser = true
                // Иначе jpackage кладёт дефолтную котлиновскую иконку в EXE/ярлыки.
                // ICO обязателен (PNG не примет), кадры 16–256 в desktop/icon.ico
                iconFile = file("icon.ico")
            }

            linux {
                iconFile = file("src/main/resources/icon.png")
            }
        }
    }
}
