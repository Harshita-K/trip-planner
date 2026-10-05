-- Wanderly core schema (design doc §9). Flyway owns the schema; Hibernate only maps it.

create table users (
    id            uuid primary key,
    email         text        not null unique,
    password_hash text        not null,
    name          text        not null,
    cognito_sub   text,                          -- reserved for the Cognito migration
    created_at    timestamptz not null default now()
);

create table user_preferences (
    user_id      uuid primary key references users (id) on delete cascade,
    interests    text[]      not null default '{}',   -- e.g. {museum, nature, nightlife}
    budget_level text        not null default 'mid',  -- low | mid | high
    travel_pace  text        not null default 'balanced', -- relaxed | balanced | packed
    updated_at   timestamptz not null default now()
);

create table events (
    id          uuid primary key,
    title       text           not null,
    category    text           not null,
    venue       text           not null,
    lat         double precision not null,
    lng         double precision not null,
    city        text           not null,
    start_time  timestamptz    not null,
    price       numeric(10, 2) not null,
    currency    text           not null default 'INR',
    capacity    int            not null,
    available   int            not null,
    description text,
    constraint events_available_range check (available >= 0 and available <= capacity)
);
create index events_city_start_idx on events (lower(city), start_time);

create table trips (
    id            uuid primary key,
    destination   text           not null,
    lat           double precision not null,
    lng           double precision not null,
    base_price    numeric(10, 2) not null,
    currency      text           not null default 'INR',
    duration_days int            not null,
    description   text
);

create table bookings (
    id         uuid primary key,
    user_id    uuid           not null references users (id),
    item_type  text           not null,          -- event | trip
    item_id    uuid           not null,
    item_title text           not null,          -- snapshot at booking time, like an order line
    quantity  int            not null check (quantity > 0),
    status     text           not null,          -- pending | confirmed | cancelled
    amount     numeric(10, 2) not null,
    currency   text           not null,
    created_at timestamptz    not null default now(),
    updated_at timestamptz    not null default now()
);
create index bookings_user_idx on bookings (user_id, created_at desc);
create index bookings_item_idx on bookings (item_type, item_id);

create table itineraries (
    id          uuid primary key,
    user_id     uuid        not null references users (id),
    destination text        not null,
    start_date  date        not null,
    end_date    date        not null,
    plan        jsonb       not null,
    created_at  timestamptz not null default now()
);
create index itineraries_user_idx on itineraries (user_id, created_at desc);

-- Written by the notification consumer. source_event_id is UNIQUE so a replayed Kafka
-- message can never produce a second notification (idempotent consumer).
create table notifications (
    id              uuid primary key,
    user_id         uuid        not null references users (id),
    type            text        not null,
    channel         text        not null,
    subject         text        not null,
    body            text        not null,
    source_event_id text        not null unique,
    created_at      timestamptz not null default now()
);
create index notifications_user_idx on notifications (user_id, created_at desc);
