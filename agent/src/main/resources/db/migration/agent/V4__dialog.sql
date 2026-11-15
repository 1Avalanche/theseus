CREATE TABLE dialog_sessions (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL,
    chat_id bigint NOT NULL,
    started_at timestamptz NOT NULL,
    last_activity_at timestamptz NOT NULL,
    closed_at timestamptz,
    summary text,
    summary_until_message_id bigint,
    state jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX dialog_sessions_user_activity ON dialog_sessions(user_id, last_activity_at);
CREATE UNIQUE INDEX dialog_sessions_open_user ON dialog_sessions(user_id) WHERE closed_at IS NULL;
CREATE TABLE dialog_messages (
    id bigserial PRIMARY KEY,
    session_id bigint NOT NULL REFERENCES dialog_sessions(id) ON DELETE CASCADE,
    user_id bigint NOT NULL,
    event_id text NOT NULL,
    role text NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content text NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (user_id, event_id, role)
);
CREATE INDEX dialog_messages_session ON dialog_messages(session_id, id);
CREATE INDEX dialog_messages_user_created ON dialog_messages(user_id, created_at);
