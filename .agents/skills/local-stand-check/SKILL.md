---
name: local-stand-check
description: Локальная проверка стенда Theseus в Docker перед деплоем — infra Postgres, образы core/agent из installDist, compose стенда, health, REST, MCP, планировщик, память, отсутствие текстов пользователей в логах. Используй после изменений Dockerfile, compose, deploy.sh, initdb, конфига или старта сервисов.
---

# Локальная проверка стенда

Без реальных токенов: Telegram отвечает 401, это ожидаемо. Временные файлы — в `$CLAUDE_JOB_DIR/tmp` (или другом tmp), не в репозитории.

## 1. Подготовка
- Docker запущен (`docker info`). Зависает на credentials — см. `.Codex/rules/deploy.md`.
- `./gradlew build installDist` — зелёный.
- tmp-файлы через Write (хук запрещает редиректы в файлы):
  - `infra.env` — 5 паролей из `deploy/env/infra.env.example` любыми значениями;
  - `stand/test/stand.env` — по `deploy/env/stand.env.example`: `THESEUS_STAND=test`, `THESEUS_IMAGE_REGISTRY=local`, heap 128m, лимиты 300m, `THESEUS_CORE_DB_PASSWORD` = test core из infra.env, токен ≥ 32 символов, фиктивный бот-токен `123456:fake`;
  - `stand/test/image.env` — `THESEUS_IMAGE_TAG=abc1234`.

## 2. Infra
```
docker compose -p theseus-infra -f deploy/vps/infra/docker-compose.yml --env-file <tmp>/infra.env up -d
```
Ждать `healthy` у `theseus-postgres`; в логах — «стенд prod/test готов». Повторный запуск `10-theseus.sh` через `docker exec` — без ошибок (идемпотентность). Не создавай объекты в схеме `core` руками: Flyway откажется мигрировать непустую схему.

## 3. Образы
```
for m in core agent; do docker buildx build --load -f deploy/Dockerfile --build-arg MODULE=$m -t "local/theseus-${m}:abc1234" .; done
```

## 4. Стенд
```
docker compose -p theseus-test -f deploy/vps/docker-compose.yml --env-file <tmp>/stand/test/stand.env --env-file <tmp>/stand/test/image.env up -d
```
Проверить:
- `theseus-test-core` → `healthy`; `docker exec theseus-test-core curl -s localhost:8080/health`.
- REST без токена → 401; с `Authorization: Bearer <токен>` → `/internal/v1/users/by-chat/<THESEUS_ADMIN_CHAT_ID>` = админ `active`.
- MCP: `POST /mcp` (`Accept: application/json, text/event-stream`) с `tools/call` `create_reminder`, `after.minutes=1`, `_meta.theseus/user_id` = id админа → `"status":"created"`.
- Через ~1 мин в логах core — «не доставлено… 401» (планировщик сработал).
- agent с фиктивным токеном падает на старте с `UnauthorizedException` и перезапускается — это ожидаемо.
- `docker logs theseus-test-core theseus-test-agent 2>&1 | grep -c '<текст напоминания>'` = 0.
- `docker stats --no-stream` — RSS в пределах `mem_limit` с запасом.

## 5. Уборка
```
docker compose -p theseus-test -f deploy/vps/docker-compose.yml --env-file … --env-file … down
docker compose -p theseus-infra -f deploy/vps/infra/docker-compose.yml --env-file <tmp>/infra.env down
```
Том `theseus-pgdata` и образы `local/theseus-*` удаляет владелец (`docker volume rm`, `docker rmi` — блокирует хук).
