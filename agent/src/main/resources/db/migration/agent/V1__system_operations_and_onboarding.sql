-- Общий журнал системных операций; содержимое запросов/ответов зашифровано ключом из env.
create table outgoing_operations (
    id uuid primary key,
    event_key text not null unique,
    user_id bigint,
    method text not null,
    path text not null,
    request_encrypted text not null,
    response_status integer,
    response_encrypted text,
    created_at timestamptz not null,
    completed_at timestamptz,
    check ((completed_at is null) = (response_encrypted is null))
);
create index outgoing_operations_user_created_idx on outgoing_operations(user_id, created_at);
create index outgoing_operations_pending_idx on outgoing_operations(created_at) where completed_at is null;

create table incoming_events (
    id text primary key,
    user_id bigint,
    chat_id bigint not null,
    payload_encrypted text not null,
    reply_encrypted text,
    created_at timestamptz not null,
    delivered_at timestamptz
);
create index incoming_events_user_created_idx on incoming_events(user_id, created_at);
create index incoming_events_pending_idx on incoming_events(created_at) where delivered_at is null;

create table onboarding_state (
    user_id bigint primary key,
    step text not null check (step in ('AWAIT_NAME','AWAIT_TIMEZONE','AWAIT_TIMEZONE_CONFIRM')),
    data jsonb not null,
    updated_at timestamptz not null
);
