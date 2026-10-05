-- Email ownership verification: accounts start unverified and get a one-time code by email.

alter table users add column email_verified boolean not null default false;
alter table users add column email_verified_at timestamptz;

-- Accounts created before verification existed are trusted as-is.
update users set email_verified = true, email_verified_at = now();

-- At most one pending code per user. Only a hash is stored, never the code itself.
create table email_verifications (
    user_id      uuid primary key references users (id) on delete cascade,
    code_hash    text        not null,
    expires_at   timestamptz not null,
    attempts     int         not null default 0,
    last_sent_at timestamptz not null
);
