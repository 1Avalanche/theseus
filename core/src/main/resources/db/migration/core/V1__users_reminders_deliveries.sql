-- Этап 2: пользователи, напоминания, журнал отправок. Схема — docs/database.md.
-- invites и llm_usage — следующими миграциями (этап 3).

create table users (
    id                     bigint generated always as identity primary key,
    telegram_chat_id       bigint      not null unique,
    role                   text        not null check (role in ('USER', 'ADMIN')),
    status                 text        not null check (status in ('ONBOARDING', 'ACTIVE', 'REVOKED')),
    name                   text,
    gender                 text        not null check (gender in ('MALE', 'FEMALE', 'UNSPECIFIED')),
    timezone               text,
    monthly_cost_limit_usd numeric(12, 6),
    created_at             timestamptz not null,
    updated_at             timestamptz not null,
    onboarded_at           timestamptz,
    revoked_at             timestamptz,
    constraint users_active_has_profile check (status <> 'ACTIVE' or (name is not null and timezone is not null))
);

create table reminders (
    id                   bigint generated always as identity primary key,
    user_id              bigint        not null references users (id),
    text                 text          not null check (char_length(text) between 1 and 1000),
    timezone             text          not null,
    start_local          timestamp     not null,
    recurrence_rule      text,
    excluded_occurrences timestamptz[] not null default '{}',
    parent_id            bigint references reminders (id),
    status               text          not null check (status in ('ACTIVE', 'PAUSED', 'DONE', 'DELETED')),
    paused_until         timestamptz,
    snoozed_until        timestamptz,
    next_fire_at         timestamptz,
    last_fired_at        timestamptz,
    created_at           timestamptz   not null,
    updated_at           timestamptz   not null,
    deleted_at           timestamptz,
    constraint reminders_one_time_simple check (
        recurrence_rule is not null or (status <> 'PAUSED' and excluded_occurrences = '{}')
    ),
    constraint reminders_paused_until check (paused_until is null or status = 'PAUSED'),
    constraint reminders_finished_no_next check (status not in ('DONE', 'DELETED') or next_fire_at is null)
);

create index reminders_user_status_idx on reminders (user_id, status);
create index reminders_due_idx on reminders (next_fire_at) where status = 'ACTIVE';
create index reminders_paused_idx on reminders (paused_until) where status = 'PAUSED';

create table reminder_deliveries (
    id                  bigint generated always as identity primary key,
    reminder_id         bigint      not null references reminders (id),
    user_id             bigint      not null references users (id),
    occurrence_at       timestamptz not null,
    kind                text        not null check (kind in ('SCHEDULED', 'SNOOZED')),
    status              text        not null check (status in ('SENT', 'FAILED')),
    late                boolean     not null,
    sent_at             timestamptz,
    telegram_message_id bigint,
    error               text,
    created_at          timestamptz not null,
    constraint reminder_deliveries_occurrence_uq unique (reminder_id, occurrence_at, kind)
);

create index reminder_deliveries_user_created_idx on reminder_deliveries (user_id, created_at);
create unique index reminder_deliveries_user_message_uq on reminder_deliveries (user_id, telegram_message_id);
