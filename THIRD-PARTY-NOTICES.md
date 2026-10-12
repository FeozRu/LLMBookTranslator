# Third-party notices

Сторонние зависимости проекта и их лицензии. Все — пермиссивные.

## Основной код

| Зависимость | Версия | Лицензия | Источник |
|---|---|---|---|
| Kotlin (stdlib, Gradle-плагины) | 2.4.21 | Apache 2.0 | https://github.com/JetBrains/kotlin |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.11.0 | Apache 2.0 | https://github.com/Kotlin/kotlinx.serialization |
| org.jetbrains.kotlinx:kotlinx-coroutines-core | 1.11.0 | Apache 2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| io.ktor:* (client core/cio, server core/cio/sse/websockets) | 3.6.0 | Apache 2.0 | https://github.com/ktorio/ktor |
| org.jetbrains.compose (Compose Multiplatform, вкл. material3, skiko, androidx.compose.*) | 1.12.1 | Apache 2.0 | https://github.com/JetBrains/compose-multiplatform |
| org.jsoup:jsoup | 1.23.2 | MIT | https://jsoup.org — Copyright © 2009–2025 Jonathan Hedley |
| io.modelcontextprotocol:kotlin-sdk-server | 0.15.0 | MIT (существующий код) / Apache 2.0 (новые вклады) | https://github.com/modelcontextprotocol/kotlin-sdk |
| org.jspecify:jspecify | 1.0.0 | Apache 2.0 | https://github.com/jspecify/jspecify |
| Gradle / Gradle wrapper | 9.8.1 | Apache 2.0 | https://gradle.org |

## Шрифты в ресурсах приложения (desktop/src/main/resources/fonts)

| Шрифт | Лицензия | Копирайт | Текст лицензии |
|---|---|---|---|
| IBM Plex Sans | SIL OFL 1.1 | © 2017 IBM Corp. (Reserved Font Name «Plex») | `LICENSE-IBMPlexSans.txt` рядом со шрифтами |
| Source Serif 4 | SIL OFL 1.1 | © 2014–2023 Adobe (Reserved Font Name «Source») | `LICENSE-SourceSerif4.txt` рядом со шрифтами |

OFL: https://openfontlicense.org — шрифты распространяются вместе с приложением,
лицензионные тексты приложены рядом с файлами шрифтов.

## Только тесты

| Зависимость | Версия | Лицензия | Источник |
|---|---|---|---|
| org.junit.jupiter (JUnit 5) | 5.11.4 | EPL 2.0 | https://github.com/junit-team/junit5 |

## Тексты лицензий

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- MIT: https://opensource.org/license/mit
- EPL 2.0: https://www.eclipse.org/legal/epl-2.0

Примечание: при сборке устанавливаемых дистрибутивов (MSI/deb/rpm через
`:desktop:packageDistributionForCurrentOS`) этот файл стоит включать в состав
поставки рядом с LICENSE.
