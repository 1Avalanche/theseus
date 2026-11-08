# Деплой, образы, CI

- Образ собирается из дистрибутива `./gradlew installDist`; Gradle в образе и на VPS не запускается. Файлы — `COPY --chmod=…` (jar из Gradle-кеша бывают `600`, пользователь контейнера их не прочитает). Нужен BuildKit (`# syntax=docker/dockerfile:1`).
- Переменные контейнерам — явным списком в `environment` compose, не `env_file`: каждый сервис видит только свои секреты.
- Root-owned bind mount с initdb-скриптами PostgreSQL должен читаться контейнерным пользователем `postgres`: каталог `infra/initdb` — `755`, скрипты — `755`. Права `700` на этот каталог ломают старт (`Permission denied`). Родительский `/opt/theseus` остаётся `700`, env-файлы с секретами — `600`. Проверка локального Compose из репозитория не проверяет права установленного серверного каталога.
- В env-файлах комментарии — только отдельными строками (inline `#` парсеры читают по-разному).
- Секреты приложения — только в env-файлах на VPS; в GitHub — только SSH-доступ к VPS. В `run:` workflow не подставлять `${{ inputs.* }}` напрямую — через `env:` (инъекция в shell).
- Деплой — только через root-owned обработчик и разрешённые digest; workflow не копирует привилегированные файлы. Стенд закреплён за SSH-ключом. Все административные изменения серверной конфигурации и разрешений — под `/opt/theseus/deploy.lock`; сам lock-файл не пересоздавать. Проверять изменения обработчика тестами `deploy/handler/tests`, включая SSH/sudo в одноразовом Ubuntu-контейнере без Docker socket хоста.
- Новая переменная окружения → `CoreConfig`/`AgentConfig` + compose + `deploy/env/*.example` + таблица в `docs/operations.md`.
- Изменение compose/Dockerfile/скриптов проверяй локально скиллом `local-stand-check`, а не только чтением.
- Локально (macOS, Docker Desktop): если `docker pull`/`build` виснет на «error getting credentials», запускай с `DOCKER_CONFIG=<tmp>` с `config.json` = `{}` и симлинком `cli-plugins` → `~/.docker/cli-plugins`. В zsh тег пиши `"${m}:local"`: `$m:l…` — модификатор zsh.
