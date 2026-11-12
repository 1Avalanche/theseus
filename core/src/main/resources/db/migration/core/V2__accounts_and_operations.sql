-- Этап 3: инвайты, расходы и атомарные результаты системных операций.
create table invites (
    id bigint generated always as identity primary key,
    code_hash text not null unique,
    gender text not null check (gender in ('MALE', 'FEMALE', 'UNSPECIFIED')),
    label text check (char_length(label) <= 64),
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    used_by_user_id bigint references users(id),
    revoked_at timestamptz,
    check ((used_at is null) = (used_by_user_id is null))
);
create index invites_used_by_user_idx on invites(used_by_user_id);
create index invites_expires_idx on invites(expires_at);

create table llm_usage (
    id bigint generated always as identity primary key,
    user_id bigint not null references users(id),
    role text not null check (role in ('MAIN', 'SUMMARY', 'ROUTER')),
    model text not null,
    generation_id text,
    tokens_in integer not null check (tokens_in >= 0),
    tokens_out integer not null check (tokens_out >= 0),
    tokens_cached integer not null check (tokens_cached between 0 and tokens_in),
    cost_usd numeric(12,8) not null check (cost_usd >= 0),
    latency_ms integer check (latency_ms >= 0),
    created_at timestamptz not null
);
create index llm_usage_user_created_idx on llm_usage(user_id, created_at);

create table operation_results (
    id uuid primary key,
    user_id bigint references users(id),
    subject text not null,
    channel text not null check (channel in ('REST', 'MCP')),
    operation text not null,
    request_hash text not null,
    result jsonb not null,
    created_at timestamptz not null
);
create index operation_results_user_created_idx on operation_results(user_id, created_at);
