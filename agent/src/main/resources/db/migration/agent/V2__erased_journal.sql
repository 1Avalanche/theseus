-- Хэши ключей удалённых событий и операций: без chatId, userId и содержимого.
create table erased_journal (
    kind text not null check (kind in ('EVENT','OPERATION')),
    key_hash text not null,
    primary key (kind, key_hash)
);
