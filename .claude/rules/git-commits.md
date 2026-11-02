# Сообщения коммитов

Сообщения коммитов пишутся **только на английском** (исключение из общего правила «всё на русском»). Описания PR — на русском.

## Формат

```
<scope>: <summary>

<body — необязательно>
```

- **scope** — изменённый модуль или функционал, в нижнем регистре:
  `core`, `agent`, `mcp`, `rest`, `scheduler`, `storage`, `db` (миграции), `reminders`, `persona`, `build`, `deploy` (Docker/Compose), `docs`, `claude` (`.claude/`: правила, скиллы, хуки).
  Новый модуль или домен — новый scope с тем же именем.
- Два модуля — через запятую: `core, agent: …`. Больше двух — скорее всего, коммит надо разбить.
- **summary** — повелительное наклонение (`add`, `fix`, `improve`, `remove`), с маленькой буквы, без точки в конце, до ~60 символов. Что сделано, а не «как»: без имён файлов и классов, если они не суть изменения.
- **body** — через пустую строку, перенос строк около 72 символов. Подробнее: что изменилось и **почему**, побочные эффекты, миграции, ломающие изменения.
- Трейлеры (`Co-Authored-By: …`) — в самом конце, после body.

## Примеры

Хорошо:
```
core: add connectors to DB
agent: add clarifying questions
mcp: improve tool descriptions
db: add reminders table
claude: add bash guard hook
```

С body:
```
scheduler: deliver overdue reminders on startup

Reminders whose due time passed while the service was down are now
sent right after start, marked as late. Prevents silent loss of
reminders after VPS reboot.
```

Плохо:
- `fix` — нет scope и смысла;
- `agent: add asking` — неясно, что именно; лучше `agent: add clarifying questions`;
- `core: Added new stuff.` — прошедшее время, заглавная, точка, нет смысла;
- `core: исправить дубли` — не на английском.

## Прочее
- Один коммит = одно логическое изменение. Код, тесты и документация к нему — в одном коммите.
- Коммить только когда владелец просит.
