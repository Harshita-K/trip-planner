-- Wanderly is a planner, not a booking site (D51): no seats, checkout or refunds.
-- Bookings become "saved events"; trip packages become priceless trip ideas; anyone signed in can list an event (D54).

create table saved_events (
    id         uuid primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    event_id   uuid        not null references events (id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint saved_events_once unique (user_id, event_id)
);
create index saved_events_event_idx on saved_events (event_id);

-- Keep what people had: every live event booking becomes a save.
insert into saved_events (id, user_id, event_id, created_at)
select gen_random_uuid(), b.user_id, b.item_id, min(b.created_at)
from bookings b
join events e on e.id = b.item_id
where b.item_type = 'event' and b.status = 'confirmed'
group by b.user_id, b.item_id;

drop table bookings;

-- Seats only existed to be sold. Price stays as the (indicative) entry fee; 0 = free.
alter table events drop constraint events_available_range;
alter table events drop column capacity;
alter table events drop column available;

-- Events listed by users. Null = part of the seeded catalogue.
alter table events add column created_by uuid references users (id) on delete set null;
alter table events add column created_at timestamptz not null default now();
create index events_created_by_idx on events (created_by);

-- Packages were priced to be sold; as trip ideas they only suggest where and how long.
alter table trips drop column base_price;
alter table trips drop column currency;
