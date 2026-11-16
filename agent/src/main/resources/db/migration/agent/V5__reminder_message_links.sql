CREATE TABLE reminder_message_links (
    user_id bigint NOT NULL,
    telegram_message_id bigint NOT NULL CHECK (telegram_message_id > 0),
    reminder_id bigint NOT NULL CHECK (reminder_id > 0),
    reminder_kind text NOT NULL CHECK (reminder_kind IN ('one_time', 'recurring')),
    timezone text NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, telegram_message_id)
);
CREATE INDEX reminder_message_links_created ON reminder_message_links(created_at);
