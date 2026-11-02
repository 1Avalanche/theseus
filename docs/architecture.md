# Архитектура Theseus

Решения и их причины — `docs/DECISIONS.md`. Здесь — как система устроена сейчас.

## Сервисы

| Сервис | Gradle-модуль | Назначение | Статус |
|---|---|---|---|
| core | `:core` | домен, PostgreSQL, планировщик, уведомления; наружу MCP (инструменты LLM) и internal REST | заглушка |
| agent | `:agent` | Telegram-бот, LLM-пайплайн через OpenRouter, история диалога | заглушка, есть ресурсы промптов |

Описание модулей — `docs/modules/<модуль>.md`.

## Контракты и хранение

Этап 1 roadmap зафиксировал контракты (утверждены владельцем); код по ним ещё не написан.

- **БД:** один PostgreSQL, две схемы — `core` и `agent`; каждый сервис мигрирует только свою, agent схему `core` не читает. Таблицы и правила — `docs/database.md`.
- **MCP** (только инструменты для LLM, напоминания) — `docs/api/mcp.md`.
- **Internal REST** (пользователи, инвайты, usage, квоты, кнопки уведомлений, поиск по reply) — `docs/api/rest.md`.
- **Eval-набор LLM** (`agent/eval/`, не входит в `./gradlew build`) — `docs/eval.md`.

## Сборка

- Gradle Kotlin DSL, JVM 21 (toolchain; JDK скачивается через foojay при отсутствии).
- Версии всех зависимостей и плагинов — только в `gradle/libs.versions.toml`.
- Общие настройки модулей — convention-плагины в `build-logic/` (included build):

| Плагин | Что даёт |
|---|---|
| `theseus.kotlin-jvm` | Kotlin JVM 21, JUnit (BOM) + MockK для тестов, ktlint, detekt |
| `theseus.application` | `theseus.kotlin-jvm` + плагин `application` (запускаемый сервис) |

- Пакеты: `theseus.<модуль>…`, group `theseus`.
- Включены Gradle build cache, configuration cache и параллельная сборка (`gradle.properties`).

### Новый модуль
1. Каталог `<модуль>/` с `build.gradle.kts`, применяющим `theseus.kotlin-jvm` или `theseus.application`.
2. `include(":<модуль>")` в `settings.gradle.kts`.
3. `README.md` модуля и `docs/modules/<модуль>.md`.

Пошаговая процедура для Claude — скилл `.claude/skills/add-module/`.

## Качество кода

- **ktlint** (плагин `org.jlleitschuh.gradle.ktlint`) — стиль; входит в `check`. Автоисправление: `./gradlew ktlintFormat`.
- **detekt** `2.0.0-alpha` (плагин `dev.detekt`) — статический анализ, конфиг по умолчанию; входит в `check`. Стабильная ветка 1.23 не поддерживает Kotlin 2.4 — перейти на стабильный 2.x, когда выйдет.
