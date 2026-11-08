# Theseus — платформа личного секретаря «Сири» в Telegram

Theseus — платформа (репозиторий, сервисы, пакеты), Сири — персона агента. Многопользовательский (в первом запуск - до 30) Telegram-агент-секретарь. v1 — напоминания (разовые и повторяющиеся). Функционал будет расширяться новыми доменами.

## Язык
- **Всё, кроме кода, — на русском:** документация, KDoc, правила Codex (`.codex/rules/`), скиллы (`.agents/skills/`), промпты LLM, карточка персоны, тексты для пользователя, описания PR.
- Идентификаторы в коде, имена файлов/модулей/пакетов — английский.
- **Сообщения коммитов — на английском**, формат `<scope>: <summary>` (см. `.codex/rules/git-commits.md`).

План работ по этапам и текущий статус: `docs/roadmap.md` — обновляй статус, когда этап или пункт завершён.

Полный журнал архитектурных решений: `docs/DECISIONS.md`. Читай его перед любым изменением архитектуры, схемы БД или контрактов. Не принимай решений, противоречащих ему, без явного согласия владельца — предложи и спроси.

## Архитектура (кратко)
- **core** — домен, PostgreSQL, планировщик, уведомления (`Notifier` → Telegram). Наружу: **MCP** (только инструменты для LLM) и **internal REST** (системные вызовы агента и не-ИИ клиенты). Оба — тонкие адаптеры над application-сервисами.
- **agent** — Telegram-бот (long polling) + LLM-пайплайн через OpenRouter. Своя схема БД для истории диалога.
- Docker Compose на VPS. Миграции — Flyway.

## Главный принцип
**Всё, что можно сделать детерминированно, делается кодом.** LLM — только понимание языка, извлечение сущностей, уточнения, семантическое сопоставление, диалог. Даты, таймзоны, RRULE, валидация, дубли-кандидаты, рендер списков, онбординг, кнопки, права — код.

## Стек
- Kotlin, JVM 21, Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`), convention plugins в `build-logic/`.
- Ktor (сервер и клиент), kotlinx.serialization, Exposed DSL + Flyway + HikariCP, MCP Kotlin SDK, ktgbotapi, lib-recur.
- DI — ручной, через конструкторы. Тесты — JUnit 6 + MockK + Testcontainers. Качество — detekt + ktlint.
- Модули: `:core`, `:agent`; пакеты `theseus.<модуль>`. Устройство сборки — `docs/architecture.md`.
- Обоснования — `docs/DECISIONS.md`, раздел 1. Новые зависимости — только по согласованию, версии — только в `gradle/libs.versions.toml`.

## Команды
- Сборка + тесты + ktlint + detekt: `./gradlew build`
- Юнит-тесты модуля: `./gradlew :<module>:test`
- Интеграционные тесты core (Testcontainers, нужен Docker): `./gradlew :core:integrationTest`
- Автоисправление стиля: `./gradlew ktlintFormat`
- Тесты хуков Codex: `python3 -m unittest discover -s .codex/hooks/tests`
- Локальный стенд Docker Compose: скилл `.agents/skills/local-stand-check/SKILL.md`.


## Правила работы
- Перед работой прочитай общие правила из таблицы ниже; тематические — до изменения соответствующих файлов. Codex не загружает `.codex/rules/*.md` автоматически и не применяет Claude-фронтматтер `paths:`.
- Поиск по коду — сначала MCP-сервер индекса IDE (см. `.codex/rules/ide-index.md`).
- Документация — в `docs/`, обновляется вместе с кодом (см. `.codex/rules/documentation.md`). При доступных субагентах делегируй сверку `docs-writer`: инструкции и модель в `.codex/agents/docs-writer.toml`; без поддержки именованной роли передай файл обычному субагенту и явно задай модель из TOML в параметрах запуска. Без субагентов выполни процедуру сам.
- Новое правило или повторяемая процедура, выявленные в работе, — сразу оформляются в rules/skills (см. `.codex/rules/self-improvement.md`).
- Каждое изменение логики — с юнит-тестами. Задача не завершена, пока `./gradlew build` не зелёный.
- Не добавляй зависимости без согласования.
- Не меняй применённые миграции — только новые, аддитивные.
- Секреты — только из env. Никогда не коммить `.env`, токены, ключи.
- Тексты для пользователя — в голосе Сири, из ресурсов (не хардкод).

## Применимость правил Codex

| Когда читать | Файлы в `.codex/rules/` |
|---|---|
| Всегда перед работой | `architecture.md`, `security.md`, `testing.md`, `documentation.md`, `git-commits.md`, `ide-index.md`, `self-improvement.md`, `codex.md` |
| `agent/**` | `agent-llm.md`, `persona.md` |
| Любые `**/resources/**` | `persona.md` |
| `core/**`, `agent/**/storage/**`, любые `**/db/migration/**` | `database.md` |
| `deploy/**`, `.github/**`, любой `Dockerfile` | `deploy.md` |

Навыки Codex: `.agents/skills/add-module/` и `.agents/skills/local-stand-check/`. Хуки: `.codex/hooks.json`; подключение, проверка доверия и отличия от Claude — `docs/codex.md`.
