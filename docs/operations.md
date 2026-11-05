# Эксплуатация

Решения и причины — `docs/DECISIONS.md`, разделы 12 (стенды, CI, деплой) и 13 (этап 2). Здесь — как это поднять и обслуживать.

Статус: этап 2. Бэкап по расписанию, шифрование и проверка восстановления — этап 7 (впереди); сейчас есть только дамп БД стенда перед каждым деплоем.

## Стенды и раскладка

- Два стенда на одном VPS: **prod** и **test**. Каждый — свои core и agent, свой Telegram-бот; различаются только конфигом (`<стенд>/stand.env`). Compose-проекты: `theseus-prod`, `theseus-test`.
- **PostgreSQL один** на оба стенда — отдельный проект `theseus-infra`, контейнер `theseus-postgres` (`postgres:17-alpine`), том `theseus-pgdata`. Базы `theseus_prod`, `theseus_test`; роли `theseus_<стенд>_core` и `theseus_<стенд>_agent` с правом `CONNECT` только к своей базе и своими схемами `core` / `agent`. Порт наружу не публикуется.
- Сеть `theseus-db` (создаёт infra) общая для стендов; core подключён к ней и адресуется агентом по имени контейнера `theseus-<стенд>-core:8080`. Внутри стенда — своя сеть `stand`. Агент на этапе 2 к `theseus-db` не подключён.
- Порты core и PostgreSQL наружу не публикуются.
- Тестовый стенд поднимается по требованию и гасится после проверки (память, см. ниже).

Раскладка `/opt/theseus` на VPS (файлы из `deploy/vps/` копирует workflow при каждом деплое, секреты кладёт владелец):

```
/opt/theseus/
├── deploy.sh                  # из репозитория
├── docker-compose.yml         # стенд (из репозитория)
├── infra/
│   ├── docker-compose.yml     # общий PostgreSQL (из репозитория)
│   ├── initdb/10-theseus.sh   # базы и роли (из репозитория)
│   └── infra.env              # секреты, права 600 (владелец)
├── prod/                      # и аналогично test/
│   ├── stand.env              # секреты и конфиг стенда, права 600 (владелец)
│   ├── image.env              # THESEUS_IMAGE_TAG=<sha> (пишет deploy.sh)
│   └── image.env.prev         # предыдущий тег (для отката)
└── backups/<стенд>/pre-deploy-<UTC-время>-<sha12>.dump
```

## Переменные окружения

Все секреты — только в env-файлах на VPS (права 600), в git лежат шаблоны `deploy/env/stand.env.example`, `deploy/env/infra.env.example` со значениями `TODO`. Некорректный конфиг — core и agent падают при старте и перечисляют все ошибки разом (значения секретов в сообщения не попадают).

### core (`CoreConfig`)

| Переменная | Обязательна | По умолчанию | Назначение |
|---|---|---|---|
| `THESEUS_HTTP_PORT` | нет | `8080` (1–65535) | порт MCP, REST и `/health` внутри контейнера |
| `THESEUS_DB_URL` | да | — | JDBC-URL PostgreSQL, в compose: `jdbc:postgresql://theseus-postgres:5432/theseus_<стенд>` |
| `THESEUS_DB_USER` | да | — | роль БД, в compose: `theseus_<стенд>_core` |
| `THESEUS_DB_PASSWORD` | да | — | пароль роли; в compose берётся из `THESEUS_CORE_DB_PASSWORD` |
| `THESEUS_DB_POOL_SIZE` | нет | `5` (1–50) | размер пула Hikari |
| `THESEUS_SERVICE_TOKEN` | да | — | сервисный Bearer-токен (не короче 32 символов); тот же у agent; свой на каждый стенд |
| `THESEUS_TELEGRAM_BOT_TOKEN` | да | — | токен бота стенда: core отправляет им уведомления |
| `THESEUS_ADMIN_CHAT_ID` | да | — | Telegram chatId владельца (число) |
| `THESEUS_ADMIN_NAME` | да | — | имя админа |
| `THESEUS_ADMIN_GENDER` | нет | `unspecified` | `male` \| `female` \| `unspecified` |
| `THESEUS_ADMIN_TIMEZONE` | да | — | IANA-таймзона админа |
| `THESEUS_DEFAULT_TIME` | нет | `09:00` | время для «завтра» без часа (`HH:MM`) |
| `THESEUS_SCHEDULER_INTERVAL_SECONDS` | нет | `30` (5–300) | период тика планировщика |
| `THESEUS_LATE_THRESHOLD_MINUTES` | нет | `5` (1–1440) | на сколько позже срока доставка помечается «опоздало» |
| `THESEUS_LOG_LEVEL` | нет | `INFO` | корневой уровень логов (`logback.xml`); SQL и внутренние логи ktgbotapi заглушены всегда — в них тексты пользователей |

Админ создаётся при старте core, если пользователя с таким chatId ещё нет; существующая строка не меняется. На этапе 2 он сразу `ACTIVE` (онбординга нет, `docs/DECISIONS.md` §13).

Compose стенда передаёт core только `THESEUS_HTTP_PORT`, `THESEUS_DB_*` (кроме пула), токены, данные админа и `THESEUS_DEFAULT_TIME`. `THESEUS_DB_POOL_SIZE`, `THESEUS_SCHEDULER_INTERVAL_SECONDS`, `THESEUS_LATE_THRESHOLD_MINUTES` работают на значениях по умолчанию; чтобы их менять, добавь строки в `environment` core в `deploy/vps/docker-compose.yml`.

### agent (`AgentConfig`)

| Переменная | Обязательна | По умолчанию | Назначение |
|---|---|---|---|
| `THESEUS_TELEGRAM_BOT_TOKEN` | да | — | токен бота стенда (тот же, что у core) |
| `THESEUS_CORE_URL` | да | — | адрес core; в compose: `http://theseus-<стенд>-core:8080` |
| `THESEUS_SERVICE_TOKEN` | да | — | тот же токен, что у core (не короче 32 символов) |
| `THESEUS_OPENROUTER_API_KEY` | да | — | ключ OpenRouter, один на оба стенда |
| `THESEUS_LLM_MAIN_MODELS` | да | — | модели роли `main` через запятую, от 1 до 3, порядок = приоритет fallback |
| `THESEUS_OPENROUTER_URL` | нет | `https://openrouter.ai/api/v1` | базовый URL OpenRouter |
| `THESEUS_LLM_TIMEOUT_SECONDS` | нет | `60` (5–300) | таймаут запроса к LLM |
| `THESEUS_HEALTH_FILE` | нет | `/tmp/theseus-agent.health` | файл пульса для healthcheck; в compose задан явно |
| `THESEUS_LOG_LEVEL` | нет | `INFO` | корневой уровень логов, как у core |

Неверный токен бота agent обнаруживает при старте (`getMe`) и завершается с ошибкой `UnauthorizedException` в логе; Docker перезапускает его с нарастающей паузой, healthcheck не проходит, деплой падает.

Compose передаёт agent всё, кроме `THESEUS_OPENROUTER_URL` и `THESEUS_LLM_TIMEOUT_SECONDS` (значения по умолчанию). Запросы к OpenRouter уходят с запретом сбора данных провайдерами (`provider.data_collection = deny`).

### Compose и образы (`stand.env`, `image.env`, `infra.env`)

| Переменная | Обязательна | По умолчанию | Назначение |
|---|---|---|---|
| `THESEUS_STAND` | да | — | `prod` или `test`: имена контейнеров, базы, роли |
| `THESEUS_IMAGE_REGISTRY` | да | — | `ghcr.io/<владелец репозитория строчными>` |
| `THESEUS_IMAGE_TAG` | да | — | SHA коммита; пишет `deploy.sh` в `image.env`, руками не задаётся |
| `THESEUS_CORE_HEAP` / `THESEUS_AGENT_HEAP` | нет | `256m` | `-Xmx` JVM (test — `128m`) |
| `THESEUS_CORE_MEM_LIMIT` / `THESEUS_AGENT_MEM_LIMIT` | нет | `400m` | `mem_limit` контейнера (test — `300m`) |
| `THESEUS_JAVA_OPTS_COMMON` | нет | `-XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Xss512k -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC` | общие опции JVM |
| `THESEUS_CORE_DB_PASSWORD` | да | — | пароль роли `theseus_<стенд>_core`; равен `THESEUS_<СТЕНД>_CORE_DB_PASSWORD` из `infra.env` |

`infra/infra.env` (`openssl rand -hex 24`): `POSTGRES_PASSWORD`, `THESEUS_PROD_CORE_DB_PASSWORD`, `THESEUS_PROD_AGENT_DB_PASSWORD`, `THESEUS_TEST_CORE_DB_PASSWORD`, `THESEUS_TEST_AGENT_DB_PASSWORD` — все обязательны. Пароли `*_AGENT_DB_PASSWORD` на этапе 2 нигде не используются (у agent нет БД), но роли создаются.

## Первичная настройка VPS

1. Пользователь `deploy` в группе `docker`, без sudo. Для него — SSH-ключ (публичная часть в `~/.ssh/authorized_keys`).
2. Swap 1–2 ГБ — обязательно (на 2 ГБ RAM нет запаса на дамп и пики).
3. Каталог `/opt/theseus`, владелец `deploy`.
4. Положить файлы деплоя вручную: `scp -r deploy/vps/. deploy@<VPS>:/opt/theseus/`, затем `chmod +x deploy.sh infra/initdb/*.sh`. (Deploy workflow копирует их сам, но `deploy.sh` без поднятого PostgreSQL завершится ошибкой, а `infra.env` нужен до первого запуска.)
5. Создать `/opt/theseus/infra/infra.env` по `deploy/env/infra.env.example`, `chmod 600`.
6. Поднять PostgreSQL (сеть `theseus-db` создаётся этим же проектом):
   ```
   cd /opt/theseus
   docker compose -p theseus-infra -f infra/docker-compose.yml --env-file infra/infra.env up -d
   ```
   При первой инициализации тома `initdb/10-theseus.sh` создаёт базы и роли обоих стендов. Скрипт идемпотентен; повторно (например, после смены паролей в `infra.env` — пароли ролей обновляются):
   `docker exec theseus-postgres bash /docker-entrypoint-initdb.d/10-theseus.sh`
7. Проверить роли и базы:
   ```
   docker exec theseus-postgres psql -U postgres -c '\du'
   docker exec theseus-postgres psql -U postgres -c '\l'
   ```
   Ожидается: роли `theseus_{prod,test}_{core,agent}`, базы `theseus_prod`, `theseus_test`.
8. Создать `/opt/theseus/<стенд>/stand.env` по `deploy/env/stand.env.example` (`chmod 600`): `THESEUS_STAND`, реестр, токены, данные админа, модели. Каталог `<стенд>/` — создать.
9. Первый деплой — через GitHub Actions (ниже).

## GitHub

- Репозиторий приватный; основная ветка `main`, работа в `develop`.
- **Environments** `test` и `prod`. Секреты в каждом: `VPS_HOST`, `VPS_USER` (`deploy`), `VPS_SSH_KEY` (приватный ключ), `VPS_KNOWN_HOSTS` (результат `ssh-keyscan <host>`). Секреты приложения (токены ботов, OpenRouter, сервисный токен) в GitHub **не хранятся**.
- **CI** (`.github/workflows/ci.yml`) — на каждый push и PR: `./gradlew build` (юнит- и интеграционные тесты, ktlint, detekt). Только проверка, не деплой.
- **Deploy** (`.github/workflows/deploy.yml`) — только вручную: Actions → Deploy → Run workflow; параметры `stand` (`test` / `prod`) и `ref` (ветка, тег или SHA, по умолчанию `main`). Не больше одного деплоя на стенд одновременно.
  1. `ref` → SHA. Для `prod` коммит должен входить в `main`, иначе workflow падает.
  2. Если образов `ghcr.io/<владелец>/theseus-{core,agent}:<sha>` нет — сборка, тесты, `docker build`, push. Есть — образ продвигается без пересборки.
  3. На VPS копируются файлы `deploy/vps/`, затем по SSH выполняется `deploy.sh <стенд> <sha>` (логин в ghcr.io — токеном job'а, только на время деплоя).

## `deploy.sh <prod|test> <sha>`

Проверяет аргументы (тег — SHA коммита, 7–40 hex), наличие `<стенд>/stand.env` и здоровье `theseus-postgres`. Затем:

1. Дамп БД стенда: `pg_dump -Fc theseus_<стенд>` → `backups/<стенд>/pre-deploy-….dump`; хранятся 10 последних (`KEEP_DUMPS`).
2. Новый тег в `<стенд>/image.env`; прежний — в `image.env.prev`.
3. `docker compose pull` и `up -d --remove-orphans`. **Миграции Flyway** выполняет core при старте (`classpath:db/migration/core`, схема `core`).
4. Ожидание `healthy` у core и agent до `HEALTH_TIMEOUT_SECONDS` (240 с). Не поднялись — выход с кодом 1 и хвост логов, workflow падает.
5. Очистка образов старше 7 суток, вывод `docker stats` по контейнерам Theseus.

## Healthcheck'и

- **core:** `curl -fsS http://localhost:8080/health` каждые 15 с. `200 {"status":"ok"}` — БД доступна, иначе `503`. Эндпоинт без авторизации.
- **agent:** HTTP-сервера нет. Раз в 30 с агент вызывает Telegram `getMe` и при успехе обновляет файл `THESEUS_HEALTH_FILE`; контейнер нездоров, если файл старше 120 с. Так проверяются и процесс, и связь с Telegram, и валидность токена.
- agent стартует после того, как core стал `healthy`.

## Память

Бюджет 2 ГБ RAM: ОС + Docker ~300 МБ, PostgreSQL ~280, prod (core + agent, heap 256m) ~800, test (heap 128m) ~550. Prod и test вместе — ~1,9 ГБ без запаса, поэтому тест гасится после проверки. Проверка фактического потребления:

```
docker stats --no-stream
free -m
```

Нужен постоянный тест — VPS на 4 ГБ, а не урезание heap.

## Тестовый стенд: выключение

```
cd /opt/theseus
docker compose -p theseus-test -f docker-compose.yml \
  --env-file test/stand.env --env-file test/image.env down
```

База `theseus_test` и данные в ней сохраняются; следующий деплой на test поднимет стенд снова.

## Откат

1. Прежний тег лежит в `<стенд>/image.env.prev`. Откат — запуск Deploy workflow на нужный SHA (образ уже в ghcr.io, пересборки нет) либо вручную `/opt/theseus/deploy.sh <стенд> <прежний sha>`. Каждый такой запуск снова делает дамп.
2. Миграции Flyway — только вперёд и аддитивные (правило проекта), поэтому предыдущая версия кода работает с новой схемой. Если миграция всё же сломала данные — восстановление из дампа (ниже).
3. Восстановление дампа (кратко; процедура на практике ещё не отрабатывалась, проверка восстановления — этап 7): остановить стенд (`down`), затем
   ```
   docker exec -i theseus-postgres pg_restore -U postgres --clean --if-exists -d theseus_<стенд> < backups/<стенд>/<дамп>.dump
   ```
   и запустить deploy нужной версии.

## Обновление без потери данных

Данные живут в томе `theseus-pgdata`, который не затрагивается деплоем (`up -d` пересоздаёт только контейнеры core и agent). Схема меняется только новыми миграциями Flyway при старте core, перед каждым деплоем снимается дамп. Проект `theseus-infra` деплоем не перезапускается; обновление версии PostgreSQL — отдельная ручная процедура.

## Что нужно от владельца

Чек-лист того, что должен предоставить владелец, чтобы стенды заработали:

- [ ] **Telegram-боты:** два бота у BotFather — для prod и для test; токены в `stand.env` соответствующих стендов.
- [ ] **OpenRouter:** один ключ на оба стенда; в настройках аккаунта запрещён сбор данных провайдерами (data collection), задан лимит расходов; ключ — в `THESEUS_OPENROUTER_API_KEY`.
- [ ] **GitHub:** приватный репозиторий; Environments `test` и `prod` с секретами `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS`; разрешены GitHub Actions и запись в ghcr.io (пакеты).
- [ ] **VPS:** пользователь `deploy` (группа `docker`, без sudo), SSH-ключ деплоя (приватный — в GitHub, публичный — на VPS), swap 1–2 ГБ, каталог `/opt/theseus`.
- [ ] **Секреты на VPS:** `infra/infra.env`, `prod/stand.env`, `test/stand.env` (права 600); пароли и сервисные токены генерируются `openssl rand -hex 32` (сервисный токен) и `openssl rand -hex 24` (пароли).
- [ ] **Данные админа:** chatId владельца в Telegram (число), имя, обращение (`male` / `female` / `unspecified`), таймзона IANA.
- [ ] **Реестр образов:** значение `THESEUS_IMAGE_REGISTRY` (`ghcr.io/<владелец строчными>`).
