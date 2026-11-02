# Internal REST core

Статус: утверждён владельцем 2026-11-02 (этап 1). Решения и причины — `docs/DECISIONS.md`, раздел 11. Схема данных — `docs/database.md`.

Системные вызовы агента (пользователи, доступ, инвайты, usage, квоты, кнопки уведомлений) и будущие не-ИИ клиенты. Модель сюда **никогда** не ходит: ни один эндпоинт не публикуется как MCP-инструмент. Операции над напоминаниями для модели — в MCP (`docs/api/mcp.md`).

## Общее

- База: `/internal/v1`, внутренний порт core, наружу не публикуется.
- Аутентификация: `Authorization: Bearer <THESEUS_SERVICE_TOKEN>` на всех эндпоинтах, кроме `GET /health`. Нет/неверный → `401`.
- JSON, поля `snake_case`, перечисления — `lower_snake`. Время — ISO-8601 UTC (`2026-11-02T07:00:00Z`).
- `user_id`, `chat_id` здесь — в пути/теле: REST вызывает только код агента, не модель.
- Ошибка:
  ```json
  {"error": {"code": "invite_expired", "message": "Срок действия приглашения истёк"}}
  ```
  `message` — для логов и отладки; пользователю агент показывает свой шаблон по `code`.
- Общие коды: `400 invalid_arguments`, `401 unauthorized`, `403 forbidden`, `404 not_found`, `409 conflict`, `500 internal`.

### `User`

```json
{
  "id": 42,
  "chat_id": 123456789,
  "role": "user",
  "status": "active",
  "name": "Аня",
  "gender": "female",
  "timezone": "Europe/Moscow",
  "created_at": "2026-11-02T07:00:00Z",
  "onboarded_at": "2026-11-02T07:02:00Z"
}
```
`role`: `user` \| `admin`; `status`: `onboarding` \| `active` \| `revoked`; `gender`: `male` \| `female` \| `unspecified`; `name`, `timezone`, `onboarded_at` — `null` до онбординга.

## Служебное

### `GET /health`
Без авторизации. `200 {"status": "ok"}`, если доступна БД; иначе `503`. Для healthcheck Docker.

## Пользователи

### `GET /internal/v1/users/by-chat/{chat_id}`
Вызывается на каждое входящее сообщение **до** LLM: есть ли пользователь и в каком он статусе.
- `200 User` · `404 not_found` — незнакомый chatId (агент отвечает шаблоном или молчит, LLM не вызывается).

### `POST /internal/v1/users/activate`
Активировать инвайт из `/start <код>`.

```json
{"chat_id": 123456789, "invite_code": "7Q2M9XKJ4TB8WZ3N"}
```
- `201 {"user": User, "invite": {"id": 5, "label": "для мамы"}}` — пользователь создан в статусе `onboarding`, род взят из инвайта. Агент шлёт админу уведомление об активации.
- `404 invite_not_found` · `410 invite_expired` · `409 invite_used` · `410 invite_revoked` · `409 already_registered` (chatId уже есть).

### `PATCH /internal/v1/users/{user_id}`
Онбординг и профиль. Любое подмножество полей:
```json
{"name": "Аня", "timezone": "Asia/Yekaterinburg", "gender": "female"}
```
- `timezone` — валидный IANA id, иначе `400 invalid_timezone`; `name` — 1–64 символа после trim.
- Когда у пользователя `onboarding` заполнены `name` и `timezone` — core переводит его в `active` и ставит `onboarded_at`.
- `200 User` · `404` · `409 invalid_state` для `revoked`.

### `GET /internal/v1/users`
Для `/users` админа. Сортировка по `created_at`.
```json
{"users": [{"user": User, "invite_label": "для мамы", "active_reminders": 7, "last_activity_at": "2026-11-02T07:00:00Z"}]}
```
`last_activity_at` — последняя запись `llm_usage`, `null` — не было.

### `POST /internal/v1/users/{user_id}/revoke`
Отозвать доступ (`/revoke`). Активные напоминания пользователя не удаляются, но планировщик не отправляет уведомления пользователям не в `active` (срабатывания пропускаются, не копятся).
- `200 User` · `404` · `409 invalid_state` (уже `revoked` или это админ).

### `POST /internal/v1/users/{user_id}/restore`
Вернуть доступ (`/restore`). Статус — `active`, если онбординг был завершён, иначе `onboarding`. Напоминания продолжают работать со следующего срабатывания после текущего момента, пропущенные не досылаются.
- `200 User` · `404` · `409 invalid_state` (не `revoked`).

## Инвайты

### `POST /internal/v1/invites`
```json
{"gender": "female", "label": "для мамы"}
```
- `gender` обязателен (`male` \| `female` \| `unspecified`); `label` — до 64 символов, необязателен. TTL — из конфига (72 ч).
- Права админа проверяет агент (chatId из конфига) до вызова; core доверяет сервисному токену.
- `201`:
  ```json
  {"id": 5, "code": "7Q2M9XKJ4TB8WZ3N", "gender": "female", "label": "для мамы", "expires_at": "2026-11-05T07:00:00Z"}
  ```
  `code` возвращается **только здесь**: в БД — хэш. Ссылку `t.me/<bot>?start=<code>` собирает агент.

### `GET /internal/v1/invites?state=active`
`state`: `active` \| `used` \| `expired` \| `revoked` \| `all` (по умолчанию `active`).
```json
{"invites": [{"id": 5, "state": "active", "gender": "female", "label": "для мамы", "created_at": "…", "expires_at": "…", "used_by_user_id": null}]}
```

### `POST /internal/v1/invites/{id}/revoke`
`200` инвайт · `404` · `409 invalid_state` (уже использован или отозван).

## Учёт LLM и квоты

### `POST /internal/v1/usage`
Запись о каждом вызове LLM (агент шлёт после ответа OpenRouter).
```json
{
  "user_id": 42,
  "role": "main",
  "model": "anthropic/claude-haiku-5.5",
  "generation_id": "gen-123",
  "tokens_in": 2100,
  "tokens_out": 85,
  "tokens_cached": 1800,
  "cost_usd": "0.00025",
  "latency_ms": 940
}
```
`role`: `main` \| `summary` \| `router`. `cost_usd` — строкой (decimal без потери точности). `201 {"id": 1001}`.

### `GET /internal/v1/usage/stats?from=…&to=…`
Для `/stats` админа. `from`/`to` — ISO-моменты, по умолчанию текущий календарный месяц UTC.
```json
{
  "from": "2026-11-01T00:00:00Z", "to": "2026-12-01T00:00:00Z",
  "total": {"requests": 812, "tokens_in": 1500000, "tokens_out": 60000, "cost_usd": "0.18"},
  "by_user": [{"user_id": 42, "name": "Аня", "requests": 120, "tokens_in": 230000, "tokens_out": 9000, "cost_usd": "0.03"}],
  "by_model": [{"model": "anthropic/claude-haiku-5.5", "requests": 800, "cost_usd": "0.17"}]
}
```

### `GET /internal/v1/users/{user_id}/quota`
Агент спрашивает перед вызовом LLM.
```json
{"enabled": false, "limit_usd": null, "used_usd": "0.03", "period_start": "2026-11-01T00:00:00Z", "exceeded": false}
```
В v1 квоты выключены флагом конфига core (`THESEUS_QUOTAS_ENABLED=false`): `enabled: false`, `exceeded` всегда `false`. Период — календарный месяц UTC.

## Уведомления

### Сообщение-уведомление

core отправляет уведомление сам (`TelegramNotifier`, Bot API `sendMessage`) в `chat_id` пользователя. Текст — шаблон в голосе Сири из ресурсов core, с текстом напоминания и, для досыла, пометкой об опоздании. Под ним inline-кнопки.

**Контракт `callback_data` кнопок** (между core и агентом; Telegram отдаёт нажатия боту, т.е. агенту):

| Кнопка | `callback_data` |
|---|---|
| +15 минут | `snz:<delivery_id>:15m` |
| +1 час | `snz:<delivery_id>:1h` |
| Завтра | `snz:<delivery_id>:tmr` |

Формат `snz:<id>:<вариант>`, не длиннее 64 байт. Новые кнопки — новый префикс; неизвестный префикс агент игнорирует с ответом-заглушкой.

### `POST /internal/v1/deliveries/{delivery_id}/snooze`
Нажатие кнопки snooze — без LLM.
```json
{"user_id": 42, "option": "15m"}
```
- `option`: `15m` — через 15 минут от нажатия; `1h` — через час от нажатия; `tmr` — завтра в то же локальное время, что и исходное срабатывание.
- core проверяет, что доставка принадлежит `user_id` (иначе `404`). Дальше — как MCP `snooze_reminder`.
- `200 {"reminder_id": 17, "snoozed_until": "2026-11-02T07:15:00Z", "snoozed_until_local": "2026-11-02T10:15"}` — агент подтверждает шаблоном и убирает кнопки.
- `409 invalid_state` — напоминание удалено.

### `GET /internal/v1/deliveries/by-message?user_id=…&message_id=…`
Пользователь ответил (reply) на уведомление — агент узнаёт, о каком напоминании речь, и передаёт это модели в контексте.
- `200 {"delivery_id": 901, "reminder_id": 17, "occurrence_at": "2026-11-02T07:00:00Z", "occurrence_local": "2026-11-02T10:00"}`
- `404` — сообщение не уведомление.
