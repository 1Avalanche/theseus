-- Карточка индексирует зашифрованный снимок общего журнала; открытый payload не дублируется.
create table pending_actions (
    id uuid primary key references outgoing_operations(id) on delete cascade,
    user_id bigint not null,
    kind text not null check (kind in ('CONFIRM_DELETE', 'CONFIRM_DUPLICATE', 'CONFIRM_SERIES_RESET')),
    operation_id uuid not null unique,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    resolved_at timestamptz,
    resolution text check (resolution in ('CONFIRMED', 'CANCELLED', 'EXPIRED'))
);
create index pending_actions_user_created_idx on pending_actions(user_id, created_at);
