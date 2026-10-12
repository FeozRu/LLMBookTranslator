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
    implementation(compose.material3)
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
            description = "Конвейер перевода книг: черновик → редактура → корректор → сборка EPUB/FB2"
            vendor = "sebyanin"

            windows {
                menu = true
                dirChooser = true
            }
        }
    }
}
