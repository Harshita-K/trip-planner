-- Transactional outbox (D56): Kafka messages are written in the same transaction as the change that
-- causes them, then relayed by OutboxRelay. A crash after commit can no longer lose a message.

create table outbox (
    id           bigserial primary key,          -- relay order: preserves per-key order
    topic        text        not null,
    message_key  text,
    payload      text        not null,           -- JSON, exactly as it goes on the topic
    created_at   timestamptz not null default now(),
    published_at timestamptz,
    attempts     int         not null default 0,
    last_error   text
);
-- The relay only ever looks for unpublished rows, oldest first.
create index outbox_pending_idx on outbox (id) where published_at is null;
create index outbox_published_idx on outbox (published_at) where published_at is not null;
