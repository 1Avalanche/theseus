---
name: add-module
description: Добавляет новый Gradle-модуль в Theseus (сервис или библиотеку) по конвенциям проекта — build.gradle.kts на convention-плагине, include в settings, пакет, README и документация. Используй, когда нужен новый модуль или вынос части кода в отдельный модуль.
---

# Добавить модуль

## 0. Сначала реши, нужен ли модуль
- Новый домен в core (заметки, задачи…) — обычно **пакет** внутри `:core`, а не модуль (`.codex/rules/architecture.md`).
- Отдельный модуль оправдан: отдельный процесс (сервис), переиспользуемая библиотека, или нужна жёсткая граница зависимостей (например, `domain` без инфраструктуры).
- Новый модуль меняет архитектуру → предложи владельцу и получи согласие; после — запись в `docs/DECISIONS.md`.

## 1. Имя
- Каталог и Gradle-путь — kebab-case, английский: `notes`, `core-domain` → `:core-domain`.
- Вложенный модуль: каталог `core/domain` → `:core:domain`.
- Пакет: `theseus.<имя без дефисов, через точку>` — `theseus.core.domain`.

## 2. Файлы
`<модуль>/build.gradle.kts`:
- библиотека:
  ```kotlin
  plugins {
      id("theseus.kotlin-jvm")
  }
  ```
- сервис (есть `main`):
  ```kotlin
  plugins {
      id("theseus.application")
  }

  application {
      mainClass = "theseus.<пакет>.MainKt"
  }
  ```
- Зависимости — только из `gradle/libs.versions.toml` (`implementation(libs.…)`), зависимость на модуль — `implementation(project(":core-domain"))`. Новой библиотеки в каталоге нет → согласуй с владельцем, добавь версию в каталог.
- Код: `<модуль>/src/main/kotlin/theseus/<пакет>/`, тесты: `<модуль>/src/test/kotlin/theseus/<пакет>/`.

## 3. Подключение
- `include(":<модуль>")` в корневом `settings.gradle.kts`.

## 4. Документация (на русском)
- `<модуль>/README.md` — одна-две строки и ссылка на `docs/modules/<модуль>.md`.
- `docs/modules/<модуль>.md` — назначение, публичные интерфейсы, зависимости, статус.
- `docs/architecture.md` — строка в таблице сервисов (если сервис) или упоминание модуля.
- `docs/DECISIONS.md` — если модуль — архитектурное решение.

## 5. Проверка
- `./gradlew :<модуль>:build` — зелёная (компиляция, ktlint, detekt).
- `./gradlew build` — весь проект зелёный.
- Если IntelliJ открыт — синхронизируй Gradle-проект (иначе индекс IDE не увидит модуль).

## 6. Коммит
`build: add <модуль> module` (правило `.codex/rules/git-commits.md`).
