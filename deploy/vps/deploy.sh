#!/usr/bin/env bash
# Деплой стенда на VPS (вызывается GitHub Actions по SSH, можно и вручную):
#   /opt/theseus/deploy.sh <prod|test> <тег образа = SHA коммита>
# Шаги: дамп БД стенда → pull образов → up -d (Flyway мигрирует при старте core) → ожидание healthy.
# Не поднялся за отведённое время — код выхода 1 и хвост логов.
set -euo pipefail

STAND="${1:?стенд: prod | test}"
TAG="${2:?тег образа}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-240}"
KEEP_DUMPS="${KEEP_DUMPS:-10}"

case "$STAND" in prod | test) ;; *) echo "неизвестный стенд: $STAND" >&2; exit 2 ;; esac
[[ "$TAG" =~ ^[0-9a-f]{7,40}$ ]] || { echo "тег должен быть SHA коммита: $TAG" >&2; exit 2; }
[[ -f "$ROOT/$STAND/stand.env" ]] || { echo "нет $ROOT/$STAND/stand.env (см. docs/operations.md)" >&2; exit 2; }

if [[ "$(docker inspect -f '{{.State.Health.Status}}' theseus-postgres 2>/dev/null)" != "healthy" ]]; then
    echo "theseus-postgres не запущен или нездоров: подними theseus-infra (docs/operations.md)" >&2
    exit 1
fi

compose() {
    docker compose -p "theseus-$STAND" -f "$ROOT/docker-compose.yml" \
        --env-file "$ROOT/$STAND/stand.env" --env-file "$ROOT/$STAND/image.env" "$@"
}

# 1. Дамп БД стенда перед миграциями.
dumps="$ROOT/backups/$STAND"
mkdir -p "$dumps"
dump="$dumps/pre-deploy-$(date -u +%Y%m%dT%H%M%SZ)-${TAG:0:12}.dump"
docker exec theseus-postgres pg_dump -U postgres -Fc "theseus_$STAND" > "$dump"
echo "дамп: $dump ($(du -h "$dump" | cut -f1))"
ls -1t "$dumps"/pre-deploy-*.dump | tail -n +"$((KEEP_DUMPS + 1))" | xargs -r rm --

# 2. Новый тег образа; прежний — в image.env.prev для отката.
[[ -f "$ROOT/$STAND/image.env" ]] && cp "$ROOT/$STAND/image.env" "$ROOT/$STAND/image.env.prev"
printf 'THESEUS_IMAGE_TAG=%s\n' "$TAG" > "$ROOT/$STAND/image.env"

# 3. Образы и запуск.
compose pull
compose up -d --remove-orphans

# 4. Ожидание healthcheck обоих сервисов.
deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
for service in core agent; do
    container="theseus-$STAND-$service"
    while true; do
        status="$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || echo missing)"
        [[ "$status" == "healthy" ]] && { echo "$container: healthy"; break; }
        if (( SECONDS >= deadline )); then
            echo "$container: $status — не поднялся за ${HEALTH_TIMEOUT_SECONDS} с" >&2
            docker logs --tail 80 "$container" >&2 || true
            exit 1
        fi
        sleep 5
    done
done

docker image prune -f --filter "until=168h" > /dev/null
docker stats --no-stream --format '{{.Name}}: {{.MemUsage}}' | grep -E "theseus-" || true
echo "стенд $STAND: развёрнут $TAG"
