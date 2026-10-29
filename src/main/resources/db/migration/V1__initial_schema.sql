create extension if not exists pgcrypto;

create table roles (
  id uuid primary key default gen_random_uuid(),
  code varchar(40) not null unique,
  description varchar(200)
);
insert into roles(code,description) values
 ('SUPER_ADMIN','Factory and system configuration'),('ADMIN','Style, Lot and Station 0 operations'),
 ('OPERATOR','Assigned Station execution'),('SUPERVISOR','Monitoring and permitted overrides'),('DISPLAY_DEVICE','Restricted display session');

create table users (
  id uuid primary key,
  username varchar(100) not null,
  display_name varchar(150) not null,
  password_hash varchar(255) not null,
  enabled boolean not null default true,
  must_change_password boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index uq_users_username_ci on users(lower(username));
create table user_roles (user_id uuid not null references users(id), role_id uuid not null references roles(id), primary key(user_id,role_id));

create table stations (
  id uuid primary key default gen_random_uuid(), code varchar(50) not null unique, name varchar(150) not null,
  station_type varchar(30) not null default 'PRODUCTION', active boolean not null default true,
  is_station_zero boolean not null default false, is_qc boolean not null default false,
  created_at timestamptz not null default now(), version bigint not null default 0
);
create unique index uq_single_station_zero on stations(is_station_zero) where is_station_zero;
create unique index uq_single_qc_station on stations(is_qc) where is_qc;

create table processes (
  id uuid primary key default gen_random_uuid(), code varchar(50) not null unique, name varchar(150) not null,
  station_id uuid not null references stations(id), target_duration_seconds integer,
  active boolean not null default true, created_at timestamptz not null default now(), version bigint not null default 0
);

create table styles (
  id uuid primary key default gen_random_uuid(), style_number varchar(80) not null unique, description varchar(500),
  status varchar(30) not null check(status in ('DRAFT','RELEASED','IN_PRODUCTION','COMPLETED')),
  released_at timestamptz, created_by uuid references users(id), created_at timestamptz not null default now(), version bigint not null default 0
);
create table style_processes (
  id uuid primary key default gen_random_uuid(), style_id uuid not null references styles(id) on delete cascade,
  process_id uuid not null references processes(id), sequence_no integer not null check(sequence_no>0),
  unique(style_id,sequence_no), unique(style_id,process_id)
);
create table style_route_entries (
  id uuid primary key default gen_random_uuid(), style_id uuid not null references styles(id) on delete cascade,
  station_id uuid not null references stations(id), sequence_no integer not null check(sequence_no>0),
  unique(style_id,sequence_no), unique(style_id,station_id)
);
create table style_route_processes (
  route_entry_id uuid not null references style_route_entries(id) on delete cascade,
  style_process_id uuid not null references style_processes(id), sequence_no integer not null,
  primary key(route_entry_id,style_process_id), unique(route_entry_id,sequence_no)
);

create table lots (
  id uuid primary key default gen_random_uuid(), style_id uuid not null references styles(id), lot_number varchar(100) not null unique,
  quantity integer not null check(quantity>0), status varchar(30) not null,
  current_route_sequence integer, current_location_type varchar(30), current_location_id uuid,
  created_by uuid references users(id), created_at timestamptz not null default now(), completed_at timestamptz,
  cancelled_at timestamptz, cancellation_reason varchar(500), version bigint not null default 0
);
create index ix_lots_style_status on lots(style_id,status);
create table lot_route_entries (
  id uuid primary key default gen_random_uuid(), lot_id uuid not null references lots(id) on delete cascade,
  station_id uuid not null references stations(id), sequence_no integer not null, status varchar(30) not null default 'EXPECTED',
  unique(lot_id,sequence_no), unique(lot_id,station_id)
);
create table station_visits (
  id uuid primary key default gen_random_uuid(), lot_id uuid not null references lots(id), lot_route_entry_id uuid not null references lot_route_entries(id),
  station_id uuid not null references stations(id), status varchar(30) not null,
  dispatched_at timestamptz, received_at timestamptz, received_by uuid references users(id), completed_at timestamptz,
  version bigint not null default 0, unique(lot_id,lot_route_entry_id)
);
create table process_executions (
  id uuid primary key default gen_random_uuid(), station_visit_id uuid not null references station_visits(id) on delete cascade,
  process_id uuid not null references processes(id), sequence_no integer not null,
  status varchar(30) not null check(status in ('PENDING','IN_PROGRESS','PAUSED','BLOCKED','SKIPPED','COMPLETED')),
  started_at timestamptz, completed_at timestamptz, operator_id uuid references users(id), reason varchar(500), version bigint not null default 0,
  unique(station_visit_id,sequence_no)
);
create unique index uq_one_active_process_per_visit on process_executions(station_visit_id) where status='IN_PROGRESS';
create table process_state_events (
  id uuid primary key default gen_random_uuid(), process_execution_id uuid not null references process_executions(id),
  from_status varchar(30), to_status varchar(30) not null, actor_id uuid references users(id), reason varchar(500), occurred_at timestamptz not null default now()
);

create table trolleys (
  id uuid primary key default gen_random_uuid(), code varchar(80) not null unique,
  status varchar(30) not null default 'AVAILABLE', active boolean not null default true, version bigint not null default 0
);
create table storage_locations (
  id uuid primary key default gen_random_uuid(), station_id uuid not null references stations(id), code varchar(80) not null,
  name varchar(150) not null, active boolean not null default true, unique(station_id,code)
);
create table nfc_tags (
  id uuid primary key default gen_random_uuid(), uid varchar(128) not null unique,
  asset_type varchar(30) not null check(asset_type in ('TROLLEY','STORAGE_LOCATION')),
  trolley_id uuid references trolleys(id), storage_location_id uuid references storage_locations(id), active boolean not null default true,
  check((asset_type='TROLLEY' and trolley_id is not null and storage_location_id is null) or
        (asset_type='STORAGE_LOCATION' and storage_location_id is not null and trolley_id is null))
);
create table lot_trolley_assignments (
  id uuid primary key default gen_random_uuid(), lot_id uuid not null references lots(id), trolley_id uuid not null references trolleys(id),
  linked_by uuid references users(id), linked_at timestamptz not null default now(), released_by uuid references users(id), released_at timestamptz
);
create unique index uq_active_trolley_assignment on lot_trolley_assignments(trolley_id) where released_at is null;
create unique index uq_active_lot_trolley on lot_trolley_assignments(lot_id) where released_at is null;
create table storage_occupancies (
  id uuid primary key default gen_random_uuid(), lot_id uuid not null references lots(id), station_id uuid not null references stations(id),
  storage_location_id uuid not null references storage_locations(id), token_number varchar(50), normalized_token_number varchar(50),
  stored_by uuid references users(id), stored_at timestamptz not null, retrieved_by uuid references users(id), retrieved_at timestamptz,
  check((token_number is null and normalized_token_number is null) or (token_number is not null and normalized_token_number is not null))
);
create unique index uq_active_storage_lot on storage_occupancies(lot_id) where retrieved_at is null;
create unique index uq_active_station_token on storage_occupancies(station_id,normalized_token_number)
  where retrieved_at is null and normalized_token_number is not null;
create index ix_storage_location_active on storage_occupancies(storage_location_id,stored_at) where retrieved_at is null;
create table lot_location_events (
  id uuid primary key default gen_random_uuid(), lot_id uuid not null references lots(id), location_type varchar(30) not null,
  location_id uuid, actor_id uuid references users(id), occurred_at timestamptz not null default now(), detail jsonb not null default '{}'::jsonb
);

create table qc_inspections (
  id uuid primary key default gen_random_uuid(), lot_id uuid not null unique references lots(id), outcome varchar(30) not null,
  inspected_quantity integer not null, good_quantity integer not null, rejected_quantity integer not null,
  reason varchar(500), operator_id uuid references users(id), completed_at timestamptz,
  check(inspected_quantity=good_quantity+rejected_quantity)
);

create table transport_locations (
  id uuid primary key default gen_random_uuid(), station_id uuid references stations(id), code varchar(80) not null unique,
  location_type varchar(30) not null, active boolean not null default true
);
create table transport_requests (
  id uuid primary key, lot_id uuid not null references lots(id), trolley_id uuid references trolleys(id),
  source_location_id uuid references transport_locations(id), destination_location_id uuid references transport_locations(id),
  provider_code varchar(60) not null, provider_mission_id varchar(150), status varchar(30) not null,
  priority integer not null default 0, correlation_id uuid not null unique, created_at timestamptz not null default now(), updated_at timestamptz not null default now(), version bigint not null default 0
);
create table transport_request_events (
  id uuid primary key default gen_random_uuid(), transport_request_id uuid not null references transport_requests(id),
  status varchar(30) not null, provider_event_id varchar(150), detail jsonb not null default '{}'::jsonb, occurred_at timestamptz not null default now()
);
create unique index uq_transport_provider_event on transport_request_events(provider_event_id) where provider_event_id is not null;

create table sync_events (
  id uuid primary key, device_id varchar(150) not null, actor_id uuid references users(id), aggregate_type varchar(80) not null,
  aggregate_id uuid not null, event_type varchar(80) not null, payload_hash varchar(128) not null, outcome varchar(30) not null,
  client_occurred_at timestamptz, received_at timestamptz not null default now(), detail jsonb not null default '{}'::jsonb
);
create table audit_log (
  id uuid primary key default gen_random_uuid(), actor_id uuid references users(id), action varchar(100) not null,
  object_type varchar(80) not null, object_id uuid, outcome varchar(30) not null, reason varchar(500),
  correlation_id uuid, occurred_at timestamptz not null default now(), detail jsonb not null default '{}'::jsonb
);
create index ix_audit_object on audit_log(object_type,object_id,occurred_at);
create table outbox_events (
  id uuid primary key, aggregate_type varchar(80) not null, aggregate_id uuid not null, aggregate_version bigint not null,
  event_type varchar(100) not null, schema_version integer not null default 1, payload jsonb not null,
  created_at timestamptz not null default now(), published_at timestamptz
);
create index ix_outbox_pending on outbox_events(created_at) where published_at is null;
