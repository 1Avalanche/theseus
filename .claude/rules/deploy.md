---
paths:
  - "deploy/**"
  - ".github/**"
  - "**/Dockerfile"
---

# Деплой, образы, CI

- Образ собирается из дистрибутива `./gradlew installDist`; Gradle в образе и на VPS не запускается. Файлы — `COPY --chmod=…` (jar из Gradle-кеша бывают `600`, пользователь контейнера их не прочитает). Нужен BuildKit (`# syntax=docker/dockerfile:1`).
- Переменные контейнерам — явным списком в `environment` compose, не `env_file`: каждый сервис видит только свои секреты.
- В env-файлах комментарии — только отдельными строками (inline `#` парсеры читают по-разному).
- Секреты приложения — только в env-файлах на VPS; в GitHub — только SSH-доступ к VPS. В `run:` workflow не подставлять `${{ inputs.* }}` напрямую — через `env:` (инъекция в shell).
- Новая переменная окружения → `CoreConfig`/`AgentConfig` + compose + `deploy/env/*.example` + таблица в `docs/operations.md`.
- Изменение compose/Dockerfile/скриптов проверяй локально скиллом `local-stand-check`, а не только чтением.
- Локально (macOS, Docker Desktop): если `docker pull`/`build` виснет на «error getting credentials», запускай с `DOCKER_CONFIG=<tmp>` с `config.json` = `{}` и симлинком `cli-plugins` → `~/.docker/cli-plugins`. В zsh тег пиши `"${m}:local"`: `$m:l…` — модификатор zsh.
