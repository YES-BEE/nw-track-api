create table user_station_assignments (
  user_id uuid primary key references users(id) on delete cascade,
  station_id uuid not null references stations(id),
  active_on_shift boolean not null default false,
  assigned_at timestamptz not null default now()
);

create table refresh_tokens (
  id uuid primary key, user_id uuid not null references users(id) on delete cascade,
  token_hash varchar(128) not null unique, expires_at timestamptz not null,
  revoked_at timestamptz, created_at timestamptz not null default now()
);

create table notifications (
  id uuid primary key, recipient_user_id uuid not null references users(id) on delete cascade,
  event_type varchar(80) not null, payload jsonb not null, created_at timestamptz not null default now(),
  delivered_at timestamptz
);
create index ix_notifications_recipient on notifications(recipient_user_id,created_at desc);

alter table nfc_tags add column version bigint not null default 0;
alter table storage_locations add column version bigint not null default 0;

